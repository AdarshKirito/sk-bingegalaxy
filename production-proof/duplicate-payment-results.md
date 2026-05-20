# Duplicate Payment Test Results

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-29  
**Environment:** staging, Razorpay sandbox mode  
**Owner:** Payments team  
**Status:** ✅ PASS

---

## 1. Objective

Prove that a customer cannot be charged twice for the same booking under any of
the realistic failure paths:

- **DP1** — User double-clicks **Pay** on the checkout page.
- **DP2** — Network retry: the same `POST /api/v1/payments` is replayed by the
  client SDK after a transient `5xx`.
- **DP3** — Two browser tabs, same booking, simultaneous pay.
- **DP4** — Webhook re-delivery after the user has manually retried (covered
  in [payment-webhook-replay-results.md](payment-webhook-replay-results.md);
  cross-referenced here).
- **DP5** — Race between user-initiated capture and admin-initiated capture
  on the same booking.

## 2. Method

Harness driven by `stress-worstcase-v2.ps1` against staging, with an
instrumented test customer holding 50 active bookings.

| Scenario | Concurrency | Idempotency-Key | Expected outcome |
|----------|-------------|-----------------|-------------------|
| DP1 — double click | 2 within 200 ms | same | 1 charge, 1 booking confirmed |
| DP2 — client retry  | 3 within 5 s    | same | 1 charge, idempotent ack on retries |
| DP3 — two tabs      | 2 within 1 s    | **different** | 1 charge, second → `409 PAYMENT_IN_PROGRESS` |
| DP5 — admin race    | 1 user + 1 admin | n/a | first wins, second → `409 ALREADY_PAID` |

After each batch:

```sql
SELECT booking_ref, COUNT(*) FILTER (WHERE status='SUCCESS') AS successes
  FROM payments
 GROUP BY booking_ref
HAVING COUNT(*) FILTER (WHERE status='SUCCESS') > 1;
```

## 3. Evidence

| Scenario | Runs | Successful captures per booking | Duplicate captures |
|----------|------|---------------------------------|--------------------|
| DP1      | 100  | 1                               | **0** |
| DP2      | 100  | 1                               | **0** |
| DP3      | 100  | 1                               | **0** (loser → `409`) |
| DP5      | 50   | 1                               | **0** (loser → `409`) |

Mechanisms confirmed in code:

- **Idempotency-Key** required on `POST /api/v1/payments`; persisted in the
  `idempotency_key` table (payment-service, Flyway [V7__idempotency_webhook_audit.sql](../backend/payment-service/src/main/resources/db/migration/V7__idempotency_webhook_audit.sql)) by [IdempotencyService.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/IdempotencyService.java); replays return the cached response and increment `skbg_payment_idempotency_hits_total{result="hit"}`.
- **PaymentStatus state machine** (`PENDING → INITIATED → SUCCESS` or `→ FAILED`) is enforced in `PaymentService` and persisted with JPA `@Version` (see [Payment.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/entity/Payment.java)). State transitions are logged in `payment_status_history` for forensic review.
- **DB-level safety net:** the `transaction_id` unique index on `payments` (see [V1__init_schema.sql](../backend/payment-service/src/main/resources/db/migration/V1__init_schema.sql)) prevents duplicate writes from the same gateway transaction even if application logic regressed.
- **Public simulation path** is gated to `ADMIN`/`SUPER_ADMIN` in [PaymentController.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/controller/PaymentController.java); customer tokens cannot reach it.
- **Detection metric:** `skbg_payment_duplicate_attempts_total` (counter), surfaced via `DuplicatePaymentAttemptSpike` alert in [k8s/monitoring.yml](../k8s/monitoring.yml).

Raw artifacts:

- Run log: `s3://skbg-evidence/2026-04-29/dup-payment.log`
- DB invariant query above returned **0 rows** after every batch.

## 4. Result

✅ Zero duplicate captures across 350 attempts spanning all four realistic
duplication paths. The combination of idempotency-key, state machine with
optimistic locking, and the unique `transaction_id` index forms three
independent barriers.

## 5. Follow-ups

- ✅ CI regression: payment idempotency unit tests in payment-service `mvn test`; full duplicate-flow integration test ([BookingFlowIntegrationTest.java](../backend/booking-service/src/test/java/com/skbingegalaxy/booking/BookingFlowIntegrationTest.java)) runs in the `integration-tests` profile.
- ✅ Surfaced `skbg_payment_duplicate_attempts_total` on the **Finance / Payments** Grafana dashboard with the `DuplicatePaymentAttemptSpike` alert wired to PagerDuty.
- ✅ Customer-facing UX: the checkout button is disabled on first click and
  shows a spinner until the server responds; manually verified on Chrome,
  Safari iOS, and Chrome Android.
