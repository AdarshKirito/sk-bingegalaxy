# Launch Readiness Report

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Target launch date:** 2026-05-08  
**Date prepared:** 2026-05-01  
**Owner:** Engineering leadership / SRE / Product  
**Status:** ✅ **GO** (with three MEDIUM follow-ups tracked, none launch-blocking)

---

## 1. Executive Summary

SK Binge Galaxy is **ready for production launch on 2026-05-08**, conditional
on the GA cut from `v1.0.0-rc4` and the operational checks in §6 being
re-run on launch morning.

All ten production-proof artifacts (this folder) are green. The single
CRITICAL identified in the 2026-04-26 adversarial run (super-admin
authorization gap) has been patched in `rc4` and re-tested with the same
harness. Backups, restores, rollback, chaos, payment safety, and security
scans all pass within their declared SLOs.

## 2. Scorecard

| Domain | Report | Status |
|--------|--------|--------|
| Booking concurrency | [booking-race-test-results.md](booking-race-test-results.md) | ✅ |
| Payment webhook replay | [payment-webhook-replay-results.md](payment-webhook-replay-results.md) | ✅ |
| Duplicate payment safety | [duplicate-payment-results.md](duplicate-payment-results.md) | ✅ |
| Refund integrity | [refund-failure-results.md](refund-failure-results.md) | ✅ |
| Kafka chaos | [kafka-downtime-recovery.md](kafka-downtime-recovery.md) | ✅ |
| Redis chaos | [redis-downtime-recovery.md](redis-downtime-recovery.md) | ✅ (1 MEDIUM) |
| Backup / restore | [db-backup-restore-proof.md](db-backup-restore-proof.md) | ✅ |
| Deploy / rollback | [k8s-rollback-proof.md](k8s-rollback-proof.md) | ✅ |
| Security | [security-test-report.md](security-test-report.md) | ✅ |
| Load tests | [../load-tests](../load-tests/README.md) (smoke / spike / soak) | ✅ |

## 3. SLOs and SLAs

Targets agreed with Product. Measurement window: 30 d rolling.

| Service / journey | SLI | SLO | SLA (external) |
|-------------------|-----|-----|----------------|
| API gateway availability | Successful (`<5xx`) requests / total | **99.9 %** | 99.5 % |
| Booking creation latency | p95 of `POST /api/v1/bookings` | **≤ 800 ms** | n/a |
| Payment capture latency | p95 of `POST /api/v1/payments` end-to-end | **≤ 1.5 s** | n/a |
| Refund completion | refund visible in user account from request | **≤ 10 min p95** | 24 h |
| Notification delivery | email queued → sent | **≤ 2 min p95** | 1 h |
| Backup recency (RPO) | age of last good backup | **≤ 60 min** | n/a |
| Recovery time (RTO) | full restore wall-clock | **≤ 30 min** | 4 h |
| Customer support response | first human reply | n/a | **≤ 4 h business hours** |

Burn-rate alerts: 2 % budget in 1 h → page; 5 % budget in 6 h → page; 10 % in
24 h → ticket.

## 4. Operational artifacts

### Dashboards (Grafana, folder `SK Binge Galaxy → Production`)

Grafana is deployed in-cluster ([k8s/monitoring.yml](../k8s/monitoring.yml)) with Prometheus, Loki, and Zipkin datasources pre-provisioned. Dashboards are curated in Grafana itself (not yet checked into the repo as JSON — see follow-ups) and cover:

- **Platform Overview** — gateway QPS, error rate, latency by service.
- **Booking Funnel** — search → select → book → pay conversion, per-binge.
- **Finance / Payments** — captured / refunded / failed by gateway, refund ageing, daily revenue, `skbg_payment_*` security counters.
- **Notifications** — queue depth, send rate, bounce / failure rate.
- **Infra Health** — Kafka lag, Redis hit ratio, PostgreSQL replication lag, MongoDB primary state.
- **SLO Burn** — burn-rate panels per SLO (built on the alerts in [k8s/monitoring.yml](../k8s/monitoring.yml)).
- **Security** — rate-limit triggers, JWT failures, header-spoof attempts, webhook 401s, Redis-fallback engagement.

### Alerts (PrometheusRule + PagerDuty)

| Alert | Severity | Routes to |
|-------|----------|-----------|
| `BookingErrorRateHigh` (>1 % 5xx 5 m) | P1 | on-call SRE |
| `PaymentErrorRateHigh` | P1 | on-call SRE + payments lead |
| `RefundStuckPending` (>30 min) | P2 | on-call SRE + finance |
| `KafkaUnreachable` | P1 | on-call SRE |
| `RedisUnreachable` | P2 | on-call SRE |
| `PostgresReplicaLag` (>30 s) | P2 | on-call SRE |
| `BackupCronJobFailed` | P2 | on-call SRE |
| `CertExpiringSoon` (<14 d) | P3 | platform team |
| `SLOBurnRateFast` (2 %/1 h) | P1 | on-call SRE |

### Runbook index

Each PagerDuty alert links to a runbook. Top runbooks:

1. **Booking 5xx spike** — check Argo Rollouts canary, gateway saturation, DB connections.
2. **Payment 5xx spike / gateway down** — flip Razorpay to standby, surface banner via remote-config flag.
3. **Refund stuck > 30 min** — confirm reconciler ran; manual retry from Admin → Payments → Pending refunds.
4. **Kafka full outage** — confirm outbox is buffering; do not roll producers.
5. **Redis down** — confirm rate-limit fallback engaged; do not roll gateway.
6. **PostgreSQL primary failover** — verify replica promotion; confirm app reconnects.
7. **Bad deploy** — `kubectl rollout undo` per service; Argo Rollouts will auto-abort if caught early.
8. **Backup CronJob failed** — re-run job, verify last-good age, raise RPO incident if > 60 min.
9. **Security: super-admin or admin route returning data to customer role** — block route at gateway, page security lead, do not trust logs.
10. **Customer can't log in / token rejected en masse** — check JWT key rotation grace period.

### Support / admin recovery tools

Operational tooling shipped with the platform (frontend pages under [frontend/src/pages](../frontend/src/pages)):

- **Admin → Bookings** ([AdminBookings.jsx](../frontend/src/pages/AdminBookings.jsx)) — search, view, manually cancel, manually refund.
- **Admin → Waitlist** ([AdminWaitlist.jsx](../frontend/src/pages/AdminWaitlist.jsx)) — manage waitlist promotions and walk-ins.
- **Admin → Loyalty** ([AdminLoyaltyCenter.jsx](../frontend/src/pages/AdminLoyaltyCenter.jsx)) — manage tiers, perks, bindings (super-admin role).
- **Admin → Users** ([AdminAllUsers.jsx](../frontend/src/pages/AdminAllUsers.jsx)) — search, lock/unlock, view audit trail.
- **Audit log** — every admin write is persisted via the audit tables created in payment-service Flyway [V5__add_outbox_shedlock_audit_tables.sql](../backend/payment-service/src/main/resources/db/migration/V5__add_outbox_shedlock_audit_tables.sql) and surfaced in admin pages.
- **Outbox / Cache operator surfaces:** today reachable only via DB / `kubectl exec` (open follow-up: ship dedicated admin pages — see §5).

### Business dashboards (separate from platform observability)

- **Daily revenue** by binge, event type, currency.
- **Active customers** (DAU / MAU), repeat-booking rate.
- **Conversion funnel** end-to-end with per-step drop-off.
- **Loyalty engagement** — tier distribution, perk redemption, churn risk.
- **NPS / support tickets** — feed from external help-desk tool.

## 5. Open follow-ups (non-blocking)

| Severity | Item | Owner | Due |
|----------|------|-------|-----|
| 🟡 MEDIUM | Content-hash dedupe for `POST /api/v1/bookings` without idempotency key | Booking team | 2026-05-22 |
| 🟡 MEDIUM | Threat model document ratification | AppSec | 2026-05-30 |
| 🟡 MEDIUM | Forward-only migration CI check (today enforced by review) | SRE | 2026-05-30 |
| 🟡 MEDIUM | Restore-drill CI job against synthetic data | SRE | 2026-06-15 |
| 🟢 LOW | Check Grafana dashboard JSON into the repo for declarative provisioning | SRE | 2026-06 |
| 🟢 LOW | Dedicated admin UI for Outbox inspection and Cache flush | Platform | 2026-06 |
| 🟢 LOW | Dashboard / log polish items (documented inline in each report) | various | 2026-06 |

None of these block GA; all are tracked in the launch Jira epic.

## 6. Launch-day checklist (re-run morning of 2026-05-08)

- [ ] CI green on `main` at the launch SHA; image tag is immutable.
- [ ] `mvn test` and frontend `npm run build` / `npm run typecheck` / `npm test -- --run` all clean.
- [ ] Trivy / dependency-check / npm audit gates green.
- [ ] Backups in last 60 min (RPO).
- [ ] Restore drill log < 7 d old (`production-proof/db-backup-restore-proof.md`).
- [ ] Rollback dry-run < 7 d old (`production-proof/k8s-rollback-proof.md`).
- [ ] Razorpay sandbox → live flip checklist signed off by payments lead.
- [ ] SMTP / WhatsApp / SMS providers verified with a real test send.
- [ ] All on-call shifts assigned and paged-tested for the launch window.
- [ ] Maintenance / status page armed.
- [ ] Comms templates (status, incident, customer email) reviewed.
- [ ] Feature flags set to launch baseline.

## 7. Sign-off

| Role | Name | Decision | Date |
|------|------|----------|------|
| Engineering lead | _____ | GO / NO-GO | _____ |
| SRE lead | _____ | GO / NO-GO | _____ |
| Security lead | _____ | GO / NO-GO | _____ |
| Product | _____ | GO / NO-GO | _____ |
| Finance | _____ | GO / NO-GO | _____ |
