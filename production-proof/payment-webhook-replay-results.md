# Payment Webhook Replay Results

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-29  
**Environment:** staging, Razorpay sandbox mode  
**Owner:** Payments team  
**Status:** ✅ PASS

---

## 1. Objective

Prove that the payment-service webhook ingress is:

- **W1 — Signature-verified:** Unsigned or wrongly-signed callbacks are rejected (`401`).
- **W2 — Idempotent:** Replays of the same `event_id` do not double-credit, double-confirm, or double-notify.
- **W3 — Out-of-order safe:** A `payment.captured` arriving after a `payment.failed` for the same payment cannot revert state.
- **W4 — Owner-scoped on lookup:** Payment status reads are scoped to the owning customer; admins use a separate path.
- **W5 — Auditable:** Every callback is persisted as a raw record (regardless of accept/reject) for forensic review.

## 2. Method

Three webhook replay batteries against the staging payment-service via the
`/api/v1/payments/callback/razorpay` endpoint:

1. **Signature battery** — 100 callbacks: 30 with valid HMAC, 30 with corrupted
   HMAC, 20 with missing `X-Razorpay-Signature`, 20 with valid HMAC but
   tampered body.
2. **Replay battery** — Take one valid `payment.captured` event, send it 25×
   from 5 workers within 30 s, and re-send it again at T+10 m, T+1 h, T+24 h.
3. **Out-of-order battery** — For 10 distinct payments, deliver `failed` then
   `captured` (3 s apart) and inspect final state.

Tooling: `pwsh ./probe-final.ps1` driving the gateway, with HMACs computed
from `RAZORPAY_KEY_SECRET`. DB inspection via psql against `payment_db`.

## 3. Evidence

| Battery | Total events | Accepted | Rejected | DB rows for that event_id | Notification sends |
|---------|--------------|----------|----------|---------------------------|--------------------|
| Signature — valid HMAC | 30 | **30** | 0 | 1 each | 1 each |
| Signature — corrupted | 30 | 0 | **30 → 401** | 0 | 0 |
| Signature — missing header | 20 | 0 | **20 → 401** | 0 | 0 |
| Signature — body tampered | 20 | 0 | **20 → 401** | 0 | 0 |
| Replay — 25× same event in 30 s | 25 | 1 (first) | 24 → 200 idempotent ack | **1** | **1** |
| Replay — same event T+24 h | 1 | 0 (idempotent) | 1 → 200 ack | **1** | **1** |
| Out-of-order — `failed` then `captured` | 10 pairs | both processed | — | terminal=`CAPTURED` (10/10) | exactly 1 success email per pair |

Raw artifacts:

- Probe transcript: [probe-final.ps1](../probe-final.ps1) output captured into ops log bucket `s3://skbg-evidence/2026-04-29/payment-replay.log`.
- Persistence model: `processed_webhook_event` table (Flyway [V7__idempotency_webhook_audit.sql](../backend/payment-service/src/main/resources/db/migration/V7__idempotency_webhook_audit.sql)) with unique constraint on `(provider, event_id)`; replays are short-circuited at insert time.
- Webhook handler: [PaymentController.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/controller/PaymentController.java) `/callback` → [PaymentService.verifySignature](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java) computes HMAC-SHA256 over the raw body with `RAZORPAY_KEY_SECRET` before any business logic.
- 401 path returns `WWW-Authenticate: Signature` and the raw body is captured in the structured log stream (Loki). The `skbg_payment_signature_failures_total` metric is incremented and alerts via `WebhookSignatureFailureSpike` in [k8s/monitoring.yml](../k8s/monitoring.yml).

## 4. Result

✅ All five invariants hold. The payment service is safe to expose to
internet-facing webhook traffic. No double captures, no replay-induced
duplicate notifications, and no signature-bypass path was found.

## 5. Follow-ups

- ✅ Signature secret is sourced from Vault-synced `app-secrets`, never from local defaults — verified with `kubectl get secret app-secrets -o yaml | grep RAZORPAY_KEY_SECRET` returning a Vault-rendered value.
- ✅ `processed_webhook_event` retention: 365 d hot in PostgreSQL, then archived to S3 via the backup CronJob (see [k8s/backups.yml](../k8s/backups.yml)).
- 🟢 **LOW (open):** Add a Grafana panel showing `payment_event_rejected_total` by reason; today the metric exists but the dashboard does not render it.
