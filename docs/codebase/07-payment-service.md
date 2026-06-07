# 07 — `payment-service` (:8084, `payment_db`)

Money movement. Razorpay order creation, callback/webhook verification (HMAC-SHA256), refunds
(with over-refund protection), chargeback/dispute handling, cash payments, a sensitive-action
admin-approval workflow, reconciliation, and its own transactional outbox. The
`handleCallback` + HMAC + `initiateRefund` paths are traced line-by-line in
[ARCHITECTURE.md §8.3/§8.5](../../ARCHITECTURE.md); this doc walks every file.

Source root: `backend/payment-service/src/main/java/com/skbingegalaxy/payment/`

---

## entity/

### `Payment.java`
`payments` table, `@Version` (optimistic lock). `bookingRef`, `customerId`, `bingeId`, unique
`transactionId`, gateway ids (`gatewayOrderId`/`gatewayPaymentId`), `amount` + `gatewayFee` +
`tax`, `paymentMethod`, `status`, `currency`, customer contact fields, `failureReason`,
`gatewayResponse`, `paidAt`, timestamps. The unique `gatewayOrderId` is what `...ForUpdate`
locks during callback handling.

### `Refund.java`
`refunds` table. FK `payment`, `amount`, `reason`, `gatewayRefundId`, `status` (a `PaymentStatus`),
plus a **dedicated `refundStatus`** (`RefundStatus`, default `SUCCEEDED`) and retry tracking
(`retryOfId`, `retryCount`) — so a failed refund can be retried and chained. `initiatedBy`,
`gatewayResponse`, `failureReason`, `refundedAt`.

### `RefundStatus.java`
A *separate* enum from `PaymentStatus` (the header explains why: refund-only states like
`PENDING/PROCESSING/SUCCEEDED/FAILED` shouldn't pollute the payment lifecycle enum). Drives the
failed-refund recovery queue.

### `PaymentDispute.java`
Chargeback record. `gatewayDisputeId`, `bingeId`, `bookingRef`, `amount`, `currency`, `status`,
`reasonCode`/`reasonDescription`, `respondBy` (deadline), `gatewayCreatedAt`, `rawPayload` (the
full webhook for audit), `opsNotes`. Created on Razorpay `dispute.created`.

### `PaymentStatusHistory.java`
Append-only `payment_status_history`: `paymentId`, `bookingRef`, `fromStatus`, `toStatus`,
`reason`, `createdAt`. Every status change writes a row (via `recordStatusChange`).

### `AdminApprovalRequest.java`
The maker-checker workflow row. Inner `Status` enum `{PENDING, APPROVED, REJECTED, EXECUTED,
CANCELLED, EXPIRED}`. `actionType`, `resourceType`/`resourceId`, JSON `payload`, `amount`/
`currency`, `bingeId`, `requestedBy`/`requestedById`/`requestedAt`/`requestReason`, plus
reviewer + TTL fields. Used to gate high-value operations (e.g. large refund retries) behind a
second approver.

### `AuditLog.java`
`audit_log`: `actor`, `action`, `resourceType`/`resourceId`, `amount`/`currency`, `bingeId`,
free-text `metadata`, `createdAt`. Every money action (refund issued/auto, etc.) is recorded.

### `IdempotencyKey.java`
Request-dedup table with a composite PK (`idempotencyKey`+`httpMethod`+`requestPath`+`userId`).
Stores `requestHash`, the cached `responseStatus`/`responseBody`, and `expiresAt`. A replayed
request with the same key returns the cached response; **a different `requestHash` for the same
key → `IDEMPOTENCY_MISMATCH`** (never retryable).

### `OutboxEvent.java`
Same shape as the booking outbox: `topic`, `aggregateKey`, `payload`, `sent`/`sentAt`,
`attempts`/`lastAttemptAt`/`lastError`, `failedPermanent`. Polled by the payment `OutboxPublisher`.

### `ProcessedWebhookEvent.java`
Webhook dedup table (composite PK `eventId`+`provider`), `payloadHash`, `receivedAt`. The
gateway-assigned event id short-circuits duplicate/replayed deliveries.

---

## provider/ — the gateway abstraction

### `PaymentProvider.java`
Strategy interface abstracting any gateway (Razorpay/Stripe/Adyen/PayPal). Methods: `name()`,
`supportedCurrencies()`, `supportsCurrency(iso)` (default), `createOrder(CreateOrderRequest)`,
`verifyCallback(params)`, `refund(RefundRequest)`. Carries the request/response **records**
(`CreateOrderRequest/Response`, `CallbackVerificationResult`, `RefundRequest/Response`) and an
`UnsupportedCurrencyException`.

### `RazorpayPaymentProvider.java`
The concrete Razorpay impl (supported set INR/USD/EUR/GBP/AED/SGD/AUD/CAD). `createOrder`
delegates to `RazorpayGatewayClient`; `verifyCallback`/`refund` are minimal adapters today since
`PaymentService` still owns those paths directly (documented in
[ARCHITECTURE.md §8.3](../../ARCHITECTURE.md)).

### `PaymentProviderRegistry.java`
Discovers every `PaymentProvider` bean, exposes `getDefault()`, `resolve(name)`, and
`resolveForCurrency(preferred, currency)` (picks a provider that supports the currency). This is
the seam that lets a second gateway be added without touching `PaymentService`.

---

## service/

### `PaymentService.java` (1,224 lines) — the core
`initiatePayment`/`saveInitiatedPayment` (create a gateway order + INITIATED payment),
`handleCallback` (dedup → lock → terminal-idempotency → late-capture auto-refund → stale-reject →
HMAC verify → apply outcome → dedup marker), `verifySignature` (HmacSHA256, constant-time
`MessageDigest.isEqual`), `simulatePayment` (non-prod), `cancelPayment`, getters
(`getPaymentByTransactionId`/`...ByBookingRef`/`getCustomerPayments`/paginated), `initiateRefund`
(pessimistic lock + DB-SUM over-refund guard), `recordCashPayment`, `addPayment`,
`recordStatusChange`, and the Kafka publishers. The hot paths are in
[ARCHITECTURE.md §8.3/§8.5](../../ARCHITECTURE.md).

### `AdminApprovalService.java`
The maker-checker engine. `approve`/`reject`/`cancel` (with `sameActor` guard so the requester
can't approve their own request), `markExecuted`, `list`, `get`, `expireStale` (the scheduler
hook), DTO mapping, `serialize`. Backs the high-value-refund-retry approval flow.

### `DisputeWebhookService.java`
Handles Razorpay dispute webhooks. **`verifyWebhookSignature(rawBody, signature)`** —
HmacSHA256 over the **raw body** with `razorpayWebhookSecret`, hex-compared. `handleDisputeEvent`
parses the event (`dispute.created/won/lost`), upserts a `PaymentDispute`, flips the payment to
`DISPUTED` on creation (but **does not cancel the booking** — per the `PaymentStatus.DISPUTED`
contract), and dedups via the webhook table. Helpers extract dispute/order fields and hex-encode.

### `DisputeAdminService.java`
Admin read/notes API: `getOpenDisputes`/`getAllDisputes` (paged), `countOpenDisputes`,
`updateNotes` (ops annotations), all binge-scoped (`requireBingeId`).

### `IdempotencyService.java`
Request idempotency: stores/looks up cached responses keyed by the composite key + request hash,
returns the cached response on replay, raises mismatch on a changed payload, and `purgeExpired`
(`@Scheduled`) sweeps stale rows.

### `WebhookDedupService.java`
`razorpayEventId(orderId, paymentId, status)` builds the stable dedup key; `isDuplicate`,
`recordNew`, and a `@Scheduled` purge. The mechanism behind callback/webhook idempotency.

### `AuditLogService.java`
Writes `audit_log` with action constants (`ACTION_REFUND_ISSUED`, `ACTION_REFUND_AUTO`, …).
`record(...)` + metadata serialization; never blocks the money path.

### `PaymentBingeScopeService.java`
Tenancy: `ensurePaymentInCurrentBinge` and binge-id resolution so an admin can't touch another
venue's payments.

### `PaymentMetrics.java`
Micrometer counters for the payments on-call dashboard: `signatureFailure`, `webhookDuplicate`/
`Stale`/`Unsigned`/`InvalidSignature`/`Fresh`, `refundIssued`/`refundAutoLate`, etc. — the
"big-company checklist" of signals a payments team watches.

---

## client/

### `RazorpayGatewayClient.java`
The actual HTTP integration with Razorpay (create order, etc.), using the configured key id/
secret. Simulation-aware (`PAYMENT_SIMULATION_ENABLED`) so local/dev runs without a live gateway.

### `BookingAmountClient.java`
Calls booking-service's `/internal` API (with the shared `X-Internal-Secret`) to read the
authoritative booking amount — used to validate payment amounts against the booking total.

---

## listener/

### `BookingCancelledEventListener.java`
`@KafkaListener(BOOKING_CANCELLED, group=payment-group)` — on a cancelled booking, triggers the
auto-refund path so money already collected is returned.

### `CashPaymentEventListener.java`
`@KafkaListener(BOOKING_CASH_PAYMENT, group=payment-group)` — records an offline cash payment
against the booking when an admin marks one.

---

## event/

### `PaymentKafkaPublisher.java`
Writes `PaymentKafkaEvent`s to the **outbox table within the enclosing transaction** (so a
payment outcome can't be published unless it commits); the `OutboxPublisher` then ships them.

### `PaymentKafkaEvent.java`
The internal event record carried to the outbox before being serialized onto the
`payment.success/failed/refunded` topics.

---

## scheduler/

### `OutboxPublisher.java`
`@Scheduled(fixedDelay=2000)` ShedLock-guarded poller — same reliable-publish pattern as
booking's (retry/permanent-fail classification), shipping payment events to Kafka.

### `AdminApprovalExpiryScheduler.java`
`@Scheduled` every 5 min (ShedLock) — flips PENDING approval requests past their TTL to EXPIRED
via `AdminApprovalService.expireStale`.

### `PaymentReconciliationScheduler.java`
Two jobs: a 5-min `@Scheduled` reconciliation of stuck/in-flight payments, and a nightly cron
(`app.payment.settlement-reconciliation-cron`, default 03:00) settlement reconciliation against
the gateway — catching any drift between local state and Razorpay.

---

## config/

### `SecurityConfig.java`
Stateless chain. `/admin/**` → ADMIN/SUPER_ADMIN; `/callback` and `/webhooks/**` → **permitAll**
(authenticated by HMAC, not JWT — the whole point of §8.3); actuator health permitAll, rest
SYSTEM; swagger ADMIN/SUPER_ADMIN; else authenticated. Plus `InternalApiAuthFilter` +
`GatewayHeaderAuthFilter`.

### `KafkaConfig.java`, `ShedLockConfig.java`, `OpenApiConfig.java`, `BingeContextFilter.java`
Kafka producer/consumer + DLQ wiring (imports `common-lib`'s `KafkaDlqErrorHandlerConfig`),
JDBC ShedLock provider, springdoc, and the per-request binge-context filter.

---

## controller/

### `PaymentController.java`
`/api/v1/payments`. `initiate`, `callback`, `webhooks/razorpay`, `transaction/{id}`,
`booking/{ref}`, `my`, `admin/refund`, `admin/refunds/{paymentId}`, `booking/{ref}/refunds`,
`admin/refunds/failed`, `admin/refunds/{id}/retry`, `cancel/{txnId}`, `admin/stats`,
`admin/disputes` (+ all/count/notes), `admin/simulate/{txnId}` (non-prod), `admin/record-cash`,
`admin/add-payment`.

### `AdminApprovalController.java`
`/api/v1/payments/admin/approvals`. `GET /` (list), `GET /{id}`, `POST /{id}/approve|reject|
cancel`, `POST /{id}/execute-refund-retry`. `ensureAdmin(role)` guard on each.

---

## health/
`DatabaseHealthIndicator` — actuator datasource health.

## dto/
`InitiatePaymentRequest`, `PaymentCallbackRequest`, `PaymentDto`, `RefundRequest`/`RefundDto`,
`AddPaymentRequest`, `RecordCashPaymentRequest`, `AdminApprovalRequestDto`,
`DisputeWebhookRequest`/`PaymentDisputeDto`, `BookingBingeDto`.

## Tests (`src/test/`)
`PaymentServiceTest` (callback/HMAC/refund/over-refund), `PaymentControllerAuthzTest`,
`OutboxPublisherTest`, `PaymentBingeScopeServiceTest`.
