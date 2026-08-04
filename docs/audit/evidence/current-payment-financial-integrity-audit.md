# Current Payment & Financial Integrity Audit — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · static trace; provider sandbox proof outstanding (PR-PAY-01)

## Payment lifecycle (Razorpay reference trace)

1. `POST /payments/initiate` → **durable PaymentIntent persisted before provider call** (crash-safe)
2. Provider order created; client completes checkout
3. Callback/webhook → HMAC verification → ProcessedEvent dedup → status transition + PaymentStatusHistory row
4. Outbox `payment.success|failed` → booking state machine reacts
5. Ambiguity (no callback): `PaymentReconciliationScheduler` queries provider — **receipt-first**: local state flips only on provider-confirmed receipt (L158-208)

## Refund lifecycle (the PAY-002 fix, verified in source)

1. Cancellation → CancellationTier math **from BookingPriceSnapshot** (never live prices) in minor units
2. Refund intent persisted (saga state) → real provider refund API call (Razorpay/Stripe clients)
3. `RefundWebhookService` confirms asynchronously; `payment.refunded` emitted
4. **V14 partial UNIQUE on gateway_refund_id** — replayed webhook or double-submit cannot create a second refund row
5. Failures land in /admin/failed-refunds with approval-queue retry (REFUND_RETRY)

## Fail-fast guarantees

[PaymentService.java](../../../backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java) `@PostConstruct` (L107): IllegalStateException when
- simulation enabled in production profile (L113)
- production without provider credentials (L120/127)
- conflicting gateway config (L136)

## Money invariants

See [money-invariant-matrix.tsv](money-invariant-matrix.tsv). Core: all longs minor-unit; rounding per MONEY_SCALE_AND_ROUNDING_CONTRACT.md; FX via CurrencyRate snapshots; tax post-FX; refund ≤ captured (checked in saga math — static read).

## Disputes

Razorpay webhook → `PaymentDispute` → ops triage (/admin/disputes, dual-signed module). No customer filing UI (PG-02 product decision).

## NOT proven (honest)

- **Zero end-to-end provider proof ever** ("No end-to-end payment has ever run" — changelog note): PR-PAY-01 is the launch gate
- Reconciliation scheduler behavior under provider 5xx storms: untested (no integration tests)
- Stripe Connect deauthorized-account edge states: handler branches exist, unexercised (PAY-CR-04)
