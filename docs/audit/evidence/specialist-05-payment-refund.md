# Specialist Investigation 05 — Payment / Refund / Disputes (lead direct inspection)

Completed by the lead auditor via direct file reads 2026-07-12 (the two dedicated agents both died on session limits; Docker was down so no runtime). Resolves PAY-001. Evidence is `path:line` in `backend/payment-service/src/main/java`.

## CONFIRMED defects

### PAY-002 (Critical/High) — Refunds are book-keeping only; no Razorpay refund API call exists
Every refund path generates a **local fake** gateway id `"RFD-"+UUID` and marks the refund `SUCCEEDED`/`REFUNDED` immediately, then publishes a refund event (which triggers the customer "you've been refunded" notification) — **without ever calling Razorpay to move money**:
- `PaymentService.initiateRefund` `:605-645` (admin refund)
- `PaymentService.handleCallback` late-capture auto-refund `:317-332`
- `PaymentService.retryFailedRefund` / `executeApprovedRefundRetry` `:958-995`
There is **no** refund method on `RazorpayGatewayClient` (only `createOrder` `:65` and `fetchOrderStatus` `:102`; grep for any `/v1/payments/*/refund` or `razorpay.refund` returns nothing). `RazorpayPaymentProvider.refund()` is `NOT_IMPLEMENTED` (`RazorpayPaymentProvider.java:74-82`). Not gated by `paymentSimulationEnabled` — the fake id is generated unconditionally. Consequence in a real deployment: admins see refunds as SUCCEEDED, the DB shows REFUNDED, customers are emailed a refund confirmation, but no money is returned — a **silent financial mismatch**. Caveat: may be acceptable at the current pre-launch stage, but it must be wired to Razorpay's refund API before real payments, and there is no `TODO` marking it beyond the provider stub.

### PAY-003 (Medium; High once refunds are real) — Late-capture auto-refund bypasses signature verification
In `handleCallback`, the normal path rejects unsigned callbacks and HMAC-verifies the signature (`:359-372`) specifically to stop forged notifications. But the **late-capture branch runs earlier** (`:299-347`): for a payment in `FAILED`+"Booking cancelled" state it trusts `request.getStatus()`, transitions FAILED→SUCCESS→REFUNDED, writes an auto-refund, calls `recordWebhookProcessed`, and returns — **all before reaching the signature check**. An attacker who knows a cancelled booking's `gatewayOrderId` could forge a "success" callback to drive these state transitions + a refund record without a valid signature. Impact is limited today (refunds are fake, PAY-002), but becomes a real unauthorized-refund vector once refunds move money. Fix: verify the signature at the top of `handleCallback`, before any state-changing branch.

### PAY-004 (Low) — `PaymentProvider` abstraction is an incomplete stub
`RazorpayPaymentProvider.verifyCallback` only checks field *presence*, not signature validity (`:57-72`), and `refund` returns `NOT_IMPLEMENTED` (`:74-82`), with comments telling callers to keep using the legacy `PaymentService` path. Dead/incomplete abstraction — harmless while unused, but a trap if a future caller trusts `provider.verifyCallback()`.

### PAY-005 (Low) — `@Retry` on order creation can create duplicate Razorpay orders
`RazorpayGatewayClient.createOrder` is annotated `@Retry(name="razorpay")` (`:63`). Razorpay does not dedup orders by `receipt` unless configured, so a retry after a timeout-but-succeeded call can create two orders for one booking. Low impact (orders, not captures), but worth an idempotency key or receipt-based reconciliation.

## Positive controls CONFIRMED (no action)
- **Webhook dedup:** `webhookDedupService.razorpayEventId(orderId,paymentId,status)` short-circuits duplicates before side effects (`:276-285`); backed by `processed_webhook_event(event_id,provider)` UNIQUE (V13, live-confirmed).
- **Pessimistic lock** on the payment row for callback + refund (`findByGatewayOrderIdForUpdate` `:287`, `findByIdForUpdate` `:571`).
- **Idempotent terminal-state** short-circuit (`:291-296`).
- **Signature required on all *normal* callbacks** — unsigned rejected (`:359-363`), HMAC-SHA256 verified with constant-time `MessageDigest.isEqual` (`verifySignature :1252-1265`).
- **Stale-callback rejection** (>24h, `:350-354`).
- **Over-refund guard** is real and under the pessimistic lock: DB-authoritative `sumCompletedRefundsByPaymentId` + `amount > remaining` reject (`:591-603`). → This **mitigates DATA-002 at the app layer**; the missing DB constraint remains a defense-in-depth gap only.
- **Pay-after-cancel** handled via the late-capture auto-refund (`:304-343`) — correct intent (aside from PAY-003).
- **Correct minor-unit conversion** for zero-decimal (JPY/KRW…) and three-decimal (KWD/BHD…) currencies (`RazorpayGatewayClient.toSubunits :56-60`) — a detail most implementations get wrong.
- **HTTP timeouts (5s connect/read) + circuit breaker + fallback** on the gateway client (`:38-45,64,91-94`).
- **Config validation** fails fast: simulation-in-production and live-keys-with-simulation are FATAL (`:86-119`) — **but inert because the `production` profile is never active (SEC-003).**
- **Cash payments** idempotent (returns existing SUCCESS, `:677-682`); currency resolved from the booking snapshot, not hardcoded INR (`:655-666`).

## Not covered (Docker down — no runtime; agent died)
Disputes flow (`DisputeWebhookService`, `PaymentDispute` V12) read only briefly; maker-checker self-approval check in `AdminApprovalService` not fully traced; full happy-path + refund not runtime-exercised. Recommend a short follow-up on disputes + self-approval.
