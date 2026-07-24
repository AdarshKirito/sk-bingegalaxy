# Specialist 06 — Current-tree payment, refund, dispute, and tenant-isolation verification

**Inspection date:** 2026-07-16  
**Tree:** `main` at `e3edbc1b5b11c11b7826ae18e2f8246aec076a9f`, including the uncommitted remediation working tree  
**Method:** read-only static trace across controllers, services, repositories, gateway clients, schedulers, Kafka listeners, DTOs, and frontend callers. No provider request was sent and no database state was changed.

This report supersedes the current-state conclusions in `specialist-05-payment-refund.md`. That earlier report remains useful historical evidence for the pre-remediation tree. The real Razorpay refund call, callback HMAC ordering, and provider abstraction are now implemented; PAY-002, PAY-003, and PAY-004 are not current defects.

## Executive result

The remediation made refunds real, but exposed several more serious distributed-transaction and authorization defects. The current tree is **not safe for production payment traffic** until the P0 items below are fixed and exercised against a sandbox provider.

| Finding | Result | Confidence |
|---|---|---|
| Real Razorpay refund call | Present (`RazorpayGatewayClient#createRefund`) | Confirmed |
| Callback signature before late-capture handling | Present | Confirmed |
| Cross-Binge approval scoping | Missing | Confirmed |
| Booking/customer/Binge binding before creating or recording payments | Incomplete | Confirmed |
| Refund provider/DB atomicity and ambiguous outcome handling | Unsafe | Confirmed from code; failure injection not run |
| Stale-payment reconciliation on provider outage | Unsafe (`null` becomes `FAILED`) | Confirmed |
| Webhook dedup atomicity | Unsafe (`REQUIRES_NEW` marker before outer commit) | Confirmed |
| Dispute accounting and out-of-order delivery | Incomplete | Confirmed |
| Booking cancellation to money refund | Disconnected | Confirmed |
| Revenue/refund dashboard arithmetic | Incorrect after settled refunds | Confirmed |

## SEC-010 — approval workflow is globally addressable across Binges

- `AdminApprovalController.java:31-132` checks only `ADMIN`/`SUPER_ADMIN`; unlike `PaymentController`, it does not invoke `PaymentBingeScopeService`.
- `AdminApprovalService.java:107-230` approves, rejects, cancels, lists, and reads with global `findById` / global status queries. The persisted `bingeId` is returned but never used as an authorization predicate.
- `PaymentService.java:1015-1044` executes an approved refund retry by global approval ID and global refund ID without `ensurePaymentInCurrentBinge`.

An admin for Binge A can list/read and, with a second admin, approve or execute Binge B's refund-retry approval. Four-eyes separation is present, but it is not a tenant boundary. Required target: every approval read/mutation must be scoped to the caller's managed Binge; native platform super-admin access must be explicit and audited.

## SEC-011 — payment writes are not bound to the authoritative booking owner and Binge

- `PaymentController.java:66-78` supplies the authenticated customer ID, but `PaymentService.java:143-270` validates a booking amount/status snapshot that contains neither the booking owner nor the booking Binge. The new payment row takes `customerId` from the caller and `bingeId` from current request context.
- `InternalBookingController.java:69-94` and `BookingAmountClient.java:48-89` exchange amount/status/currency data but omit `customerId` and `bingeId`.
- `PaymentService.java:790-903` (`recordCashPayment`, `addPayment`) accepts a booking reference and customer/amount inputs without resolving that booking in the selected Binge. `addPayment` trusts an optional client `bookingTotalAmount`; `recordCashPayment` has no authoritative remaining-balance check.
- `PaymentEventListener.java:41-168` applies payment events by globally resolved `bookingRef` and calls `addToCollectedAmount`; it does not compare the event Binge with the booking Binge.
- `PaymentController.java:170-175` exposes a customer refund timeline without forwarding `X-User-Id`; `PaymentService.java:921-927` scopes only by booking reference and Binge. Any customer in that Binge who knows a reference can read refund amount, reason, provider ID, failure reason, and initiator fields from `RefundDto.java:15-30`.

Consequences include cross-Binge financial mutation by an admin, a customer initiating payment for a known booking they do not own, overcollection, and same-Binge refund-history disclosure. Required target: a fail-closed booking snapshot containing immutable owner/Binge/currency/remaining balance; compare it before provider order creation and again under a booking-scoped lock before every manual or callback mutation.

## PAY-006 — provider refund can succeed while the durable transaction fails

- `PaymentService#initiateRefund` is transactional and calls `executeRefundAttempt`.
- `PaymentService.java:632-698` generates a new random receipt, performs the external Razorpay refund, and only then persists the Refund row/outbox/audit state.
- The enclosing idempotency record is also committed with the same database transaction. A crash, timeout-after-provider-acceptance, or later database/outbox failure can therefore leave money moved with no durable result.
- A retry uses a new receipt and has no provider lookup by a stable operation key, so it can issue a second refund.

Razorpay's documented normal-refund operation is `POST /v1/payments/:id/refund`, with the amount in the currency's smallest unit: <https://razorpay.com/docs/api/refunds/create-normal/?preferred-country=IN>.

Required target: persist a unique refund intent and stable provider receipt before the network call; use an outbox/worker; classify timeouts as `UNKNOWN`; reconcile by stable receipt/provider ID; never blindly retry an ambiguous result.

## PAY-007 — provider-unreachable is converted to FAILED and enables a second charge

- `RazorpayGatewayClient.java:253-280` returns `null` for configuration absence, empty response, or any exception.
- `PaymentReconciliationScheduler.java:43-100` treats every status other than `paid`, including `null`, as safe to mark `FAILED`.
- `PaymentService` permits a new order after `FAILED`; a delayed valid callback for the first order can still transition that payment to success.

Provider outage sequence: first order is accepted; status lookup fails; local row becomes `FAILED`; customer creates/captures a second order; the delayed first callback also succeeds. Required target: `UNKNOWN`/`RECONCILING`, bounded retry with alerting, and a booking-scoped callback guard that detects and automatically reverses a duplicate capture.

## PAY-008 — webhook dedup marker can commit before the business result

`WebhookDedupService#recordNew` uses `REQUIRES_NEW`. Payment and dispute handlers run their business mutation in an outer transaction and record the marker before that transaction commits. If the outer transaction rolls back, the marker remains; a provider redelivery is then treated as duplicate and the missing mutation is never retried. The same split is present in `DisputeWebhookService`.

Required target: atomically store the inbox event and durable business result, or use an inbox lifecycle (`RECEIVED` → `PROCESSING` → `COMPLETED`/retryable failure) with lease/recovery. A committed dedup record must never imply an uncommitted effect.

## PAY-009 — dispute lifecycle does not maintain complete accounting

- `DisputeWebhookService` claims a lost dispute creates a synthetic Refund, but no `RefundRepository` is injected and no Refund row is written.
- Lost/accepted events set the parent payment to `REFUNDED` without publishing the refund event that updates booking collected amount and refund totals.
- Terminal events call an update-only dispute helper. If a terminal event arrives before `dispute.created`, no dispute row is created; the terminal event is nevertheless deduplicated. A later create can leave the payment open/disputed.
- A won event unconditionally writes `SUCCESS`, losing a prior partial-refund money state.

Required target: an upserted, monotonic provider state machine; accounting entries for chargeback principal/fees; idempotent booking-balance propagation; and permutation tests for created/updated/won/lost/accepted delivery order.

## BOOK-004 — cancellation policy is disconnected from captured-money settlement

- `BookingService#cancelBookingByCustomer` permits only `PENDING`, although `evaluateCustomerCancellation` contains tiers for paid/confirmed bookings and the frontend offers cancellation for `CONFIRMED` bookings.
- The private cancellation path calculates policy output but subtracts the full local `collectedAmount`; it does not call payment-service with the authorized refund amount.
- `BookingEvent` carries no refund amount/percentage. `BookingCancelledEventListener` only fails `INITIATED` payment attempts and never refunds a successful capture.

Thus a confirmed customer's advertised cancellation fails, while an admin cancellation can mark booking/loyalty/accounting state without returning captured money. Required target: a cancellation saga with an explicit refund quote/intent, durable correlation, provider settlement states, and booking finalization only after the financial result is known (or a clearly represented pending-refund state).

## PAY-010 — refund status transitions break revenue reporting

- `PaymentRepository.java:49-53` defines gross revenue as rows whose current parent status is only `SUCCESS`.
- `PaymentService.java:740-748` changes a settled partially/full-refunded parent to `PARTIALLY_REFUNDED`/`REFUNDED`, removing its original charge from that gross sum.
- `PaymentService.java:1164-1181` then subtracts completed refund rows again.

Example: a 100 charge followed by a 30 refund reports gross 0, refunded 30, net -30; economic net is 70. Required target: immutable gross captured amount (include all captured money states), refunds as a separate ledger, and reconciliation tests for partial/full/multiple refunds and chargebacks.

## Reliability note — reconciliation holds transactions across provider calls

`PaymentReconciliationScheduler` wraps the stale-payment loop in a 240-second transaction and the daily-settlement loop in an 1,100-second transaction while performing one provider request per row. This holds a database connection and any mutated entity state for minutes, makes the batch all-or-nothing, and has no page bound. Process bounded pages and commit each row/result separately; keep network I/O outside database transactions.

## Required regression suite

1. Cross-Binge tests for every approval endpoint, execute path, manual payment, initiation, refund-timeline read, and payment event.
2. Provider fault injection at before-send, after-provider-accept/before-response, after-response/before-save, and before-commit boundaries.
3. Two-order scenario proving an unknown first capture cannot be silently reopened and double-charged.
4. Webhook crash-after-marker and crash-before-marker redelivery tests.
5. All dispute event-order permutations and duplicate deliveries.
6. Customer/admin cancellation across unpaid, paid, partial-refund, provider-failure, and delayed-settlement states.
7. Revenue invariants: gross captured − settled refunds − chargeback losses = net, independent of presentation status.

## Limitations

- Static current-tree verification only; Razorpay sandbox credentials were not used.
- No failure injection, concurrent callback, or database rollback harness was run in this pass.
- The issues are confirmed by reachable code structure; provider acceptance timing and production configuration remain runtime-dependent.
