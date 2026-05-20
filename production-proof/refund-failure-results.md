# Refund Failure Test Results

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-30  
**Environment:** staging, Razorpay sandbox with fault injection  
**Owner:** Payments team  
**Status:** ✅ PASS

---

## 1. Objective

Prove that refunds remain consistent and recoverable under failure:

- **RF1** — Refund request succeeds at the gateway → ledger and notifications updated exactly once.
- **RF2** — Refund request fails at the gateway → booking remains `CONFIRMED` (or `CANCELLED_REFUND_PENDING`), the user sees a deterministic error, and an automatic retry job picks it up.
- **RF3** — Partial refund (< original amount) reconciles correctly against the booking ledger.
- **RF4** — Double-refund attempt (same booking, same admin) → second call → `409 REFUND_ALREADY_REQUESTED`.
- **RF5** — Refund webhook lost → reconciler job catches it within 10 minutes.
- **RF6** — Refund processed manually outside the system (gateway dashboard) → `payment-service` reconciler imports it on next run, no duplicate.

## 2. Method

Fault-injection at the Razorpay sandbox using their test cards:

| Card                | Behaviour          |
|---------------------|--------------------|
| `4111 1111 1111 1111` | always succeeds  |
| `4000 0000 0000 0002` | always declines  |
| `5104 0600 0000 0008` | succeed then drop webhook |

Harness: `pwsh ./probe.ps1 -Mode refund` driving 200 refunds across the four
behaviours, plus 30 partial refunds at 25 %, 50 %, and 75 % of original.

Reconciler: [PaymentReconciliationScheduler](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/scheduler) (in-process Spring scheduled job, ShedLock-coordinated across replicas via the `shedlock` table from Flyway [V5__add_outbox_shedlock_audit_tables.sql](../backend/payment-service/src/main/resources/db/migration/V5__add_outbox_shedlock_audit_tables.sql)). The 5-minute cadence was forced on demand for this drill by restarting one payment-service pod and triggering an early run via the actuator scheduling endpoint.

## 3. Evidence

| Scenario | Attempts | Refunded once | Wrong amount | Stuck PENDING | Auto-recovered |
|----------|----------|---------------|--------------|----------------|----------------|
| RF1 success | 100 | **100** | 0 | 0 | n/a |
| RF2 decline | 50  | 0 (correct) | 0 | 0 (booking returned to `CONFIRMED`) | n/a |
| RF3 partial | 30  | **30** | 0 | 0 | n/a |
| RF4 double-call | 20 attempts on 10 bookings | 10 (1st each) | 0 | 0 (second → `409`) | n/a |
| RF5 lost webhook | 20 | **20** (after reconciler) | 0 | 0 | reconciler caught all 20 within 1 cycle (≤ 5 min) |
| RF6 out-of-band | 10 | **10** | 0 | 0 | reconciler imported all 10 |

DB invariants checked after every run:

```sql
-- No booking should have refunded total exceeding successful captures.
SELECT p.booking_ref,
       SUM(p.amount) FILTER (WHERE p.status='SUCCESS')              AS captured,
       COALESCE(SUM(r.amount) FILTER (WHERE r.status='SUCCESS'), 0) AS refunded
  FROM payments p
  LEFT JOIN refunds r ON r.payment_id = p.id
 GROUP BY p.booking_ref
HAVING COALESCE(SUM(r.amount) FILTER (WHERE r.status='SUCCESS'), 0)
     > SUM(p.amount) FILTER (WHERE p.status='SUCCESS');
```

Returned **0 rows** after every batch.

Operator visibility:

- Stuck refunds appear on the **Finance Ops** Grafana dashboard panel
  `refund_pending_seconds_bucket{le="600"}` and page on-call after 30 min.
- Admin recovery tool: `Admin → Payments → Pending refunds` lists items,
  shows reconciler last-run timestamp, and offers a one-click retry.

## 4. Result

✅ Refunds are exactly-once, partial-amount-correct, double-call-safe,
webhook-loss-tolerant, and importable from the gateway dashboard. No funds
were over- or under-refunded across 230 attempts.

## 5. Follow-ups

- ✅ Refund logic covered by `RefundCalculationServiceTest` (booking-service unit test) and exercised end-to-end by `BookingFlowIntegrationTest` in the `integration-tests` profile.
- ✅ Runbook: [Refund stuck > 30 min](../production-proof/launch-readiness-report.md#runbook-index) (linked from PagerDuty alert).
- 🟢 **LOW (open):** Add Slack `#finance-alerts` notification when the
  reconciler imports a refund processed out-of-band — today only the
  dashboard reflects it.
