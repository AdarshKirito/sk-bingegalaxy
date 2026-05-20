# Redis Downtime Recovery — Chaos Test

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-30  
**Environment:** staging — Redis (rate limit + cache + idempotency), 3-replica cluster  
**Owner:** Platform / SRE  
**Status:** ✅ PASS (with one MEDIUM note on rate-limiter degraded mode)

---

## 1. Objective

Prove that the platform fails *open-safe* when Redis is unavailable:

- **RD1** — API gateway continues to serve traffic when Redis is unreachable. Rate-limiting falls back to a documented degraded mode rather than dropping all requests.
- **RD2** — Idempotency-Key replay protection is preserved across a Redis blip (because the source of truth is the database `payment_idempotency` and `idempotency_record` tables; Redis is a hot cache only).
- **RD3** — Catalog and home-CMS caches repopulate on demand without thundering-herd outages on PostgreSQL.
- **RD4** — Session / JWT continues to work (JWT is stateless; Redis is only used for revocation lookup with a documented TTL).

## 2. Method

Three injections:

1. **Full Redis outage:** scale Redis StatefulSet to 0 for 5 min.
2. **Failover:** kill the primary; let the cluster elect a new primary.
3. **Cache flush:** `FLUSHALL` against a healthy primary while under load.

Foreground load: `k6 run load-tests/spike.js` for the duration of each
injection (50 → 400 RPS spike).

## 3. Evidence

| Mode | Customer-facing failures | Gateway behaviour | DB QPS spike | Recovery time |
|------|---------------------------|-------------------|--------------|---------------|
| Full outage      | 0 | Rate limiter fell back to **per-pod in-memory limit (50% of normal budget)**, logged `redis.fallback.engaged`, served traffic | +18 % on `binge_db`, +9 % on `booking_db` (within capacity) | Auto-resumed within **3 s** of Redis return |
| Primary failover | 0 | 1.4 s of `WAIT` on writes, no errors surfaced | negligible | full | 1.4 s |
| FLUSHALL         | 0 | 11 s elevated DB QPS as caches refilled | +42 % on `binge_db` for 11 s, then steady | 11 s |

Mechanisms confirmed:

- **Cache-aside with negative-result TTL** in the binge / home-CMS service prevents thundering herd; verified by tcpdumping PostgreSQL during `FLUSHALL`.
- **Idempotency source-of-truth in PostgreSQL** — the `idempotency_key` table in both booking-service ([V20__idempotency_keys.sql](../backend/booking-service/src/main/resources/db/migration/V20__idempotency_keys.sql)) and payment-service ([V7__idempotency_webhook_audit.sql](../backend/payment-service/src/main/resources/db/migration/V7__idempotency_webhook_audit.sql)) is authoritative; Redis is only a hot-path cache. A controlled test replayed an `Idempotency-Key` 10× while Redis was down: only 1 booking created, 9 returned the cached response from the DB-backed store. ✅
- **Rate-limiter degraded mode** is built into both gateway filters — coarse per-IP [RateLimitFilter.java](../backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/RateLimitFilter.java) and per-user/per-route [UserRateLimitFilter.java](../backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/UserRateLimitFilter.java): when Redis is unreachable, each filter falls through to a per-pod LRU bucket (Bucket4j), increments the shared `skbg_gateway_rate_limit_redis_fallback_total` counter, and logs a throttled WARN. Behaviour is automatic — no operator toggle needed.
- **Alerting:** `RedisUnreachable` and `RateLimitRedisFallbackEngaged` rules added in [k8s/monitoring.yml](../k8s/monitoring.yml). The fallback alert paginates on-call after 10 min of sustained fallback (it is treated as a security event because the global rate-limit budget becomes per-pod × N pods).

Artifacts: `s3://skbg-evidence/2026-04-30/redis-chaos/`.

## 4. Result

✅ Platform survives Redis loss without customer-facing errors. Idempotency
and ownership checks remain correct because Redis is a cache, not a
correctness barrier.

## 5. Follow-ups

- 🟡 **MEDIUM (open):** The per-pod fallback limiter is generous (per-pod budget × N pods). The `RateLimitRedisFallbackEngaged` PrometheusRule (added in this build) pages on-call after 10 minutes of sustained fallback so extended degradation is treated as a security event.
- ✅ Runbook: "Redis down — confirm fallback engaged, do not roll the gateway." Linked from the `RedisUnreachable` alert.
- 🟢 **LOW:** Promote the negative-result TTL constant from per-service to a
  shared common-lib helper to avoid drift.
