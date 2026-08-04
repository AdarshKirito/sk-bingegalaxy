# 17 — Performance, Capacity and Concurrency (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · static sizing review; **all load evidence is HISTORICAL** (no k6 executed this run)

## Capacity configuration (VERIFIED-STATIC)

| Resource | Setting | Assessment |
|---|---|---|
| Hikari | 20 conn/svc, 5 min-idle, 5 s timeout | Sane for 100-VU target |
| PostgreSQL | max_connections=200 vs 100 app baseline | 2× headroom ✅ |
| Tomcat | 400 threads | Generous vs pool of 20 — DB is the intended bottleneck ✅ |
| JVM | -Xms192m -Xmx320m, G1GC, OOM heap-dump + exit | Tight but tuned; k8s limits aligned |
| HPA | 2–5 replicas CPU/mem targets | Scaling story exists (k8s only) |
| Redis | single instance in compose | Dev-only posture |

## Historical load evidence (label: HISTORICAL)

| Campaign | Artifact | Result then |
|---|---|---|
| 26-Apr-2026 stress | [STRESS-TEST-REPORT-26APR2026.md](../../STRESS-TEST-REPORT-26APR2026.md) | 10 bugs found; CRITICAL loyalty-config RBAC bug — **since fixed** (LoyaltyV2SuperAdminController @PreAuthorize) |
| July k6 smoke/spike/soak | k6-*-final.json, production-proof/ | Passed thresholds at the July-12 audit commit |
| Worst-case rounds | stress-worstcase-*.json/txt | Documented in report |

None of these bind to commit `6440f58` — 566 files changed since e3edbc1. **PERF-01 (P2): re-run the k6 suite against the current commit before launch.**

## Static concurrency observations

1. **Advisory-lock scope** — the booking-create transaction holds `pg_advisory_xact_lock` through pricing-snapshot work; at high contention on one room/slot this serializes heavier work than strictly necessary (PERF-02, P2 — measure before optimizing)
2. **Outbox relay batch size** — scheduler-driven; no backpressure metric exported (ties to OBS-01)
3. **CQRS read model** keeps admin list queries off the hot bookings table ✅
4. **33 @Scheduled jobs** — all ShedLock-guarded (verified constants); no overlapping-execution risk
5. Loyalty caches in Redis reduce read amplification on membership checks ✅

## Sizing risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| PERF-01 | P2 | No load evidence at current commit |
| PERF-02 | P2 | Advisory-lock hold-time includes snapshot work |
| OBS-01 | P1 | No saturation/latency alerts to catch capacity issues in prod |
