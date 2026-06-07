# SK Binge Galaxy — Operational Runbooks

Top 10 failure modes with diagnosis steps, immediate mitigation, and root-cause fix.

---

## On-Call Rotation

### Who gets paged

P1/P2 alerts fire to the primary on-call engineer. If unacknowledged after 5 minutes,
PagerDuty escalates to the secondary. If still unacknowledged after 10 minutes, it
escalates to the engineering lead.

**Rotation:** Weekly. Handoff every Monday 10:00 IST.
Update the rotation in PagerDuty — do not rely on informal agreement.

### What counts as P1 / P2 / P3

| Severity | Definition | Response SLA |
|----------|------------|--------------|
| **P1** | Complete service outage OR payment processing down | Page immediately, respond in 15 min |
| **P2** | Degraded service (>5% error rate, >2s p95 latency) OR booking confirmation stuck | Page immediately, respond in 30 min |
| **P3** | Non-critical feature broken, no customer impact | Next business day |

### First response checklist (P1/P2)

1. Acknowledge the page within 15 minutes.
2. Open Grafana dashboard: `sk-binge-galaxy/ops` — check error rate, p95 latency, pod restarts.
3. Check Zipkin for recent 5xx spans to identify the failing service.
4. Check Kubernetes pod status: `kubectl get pods -n sk-binge-galaxy`
5. Look for recent deployments: `kubectl rollout history deploy -n sk-binge-galaxy`
6. If a recent deploy is suspect: **rollback first, investigate later**:
   `kubectl rollout undo deploy/<service-name> -n sk-binge-galaxy`
7. Post status update in `#incidents` Slack channel within 15 minutes of page.
8. Open a postmortem from `docs/runbooks/postmortem-template.md` immediately — fill it
   in as you work, not after.

### Escalation contacts

Document these in your internal wiki / PagerDuty, not here. Hardcoding phone numbers
in a git repo is a security risk if the repo is ever made public.

---

## 1. Stuck PENDING Bookings (Saga Timeout)

**Symptom:** Bookings stay in `PENDING / AWAITING_PAYMENT` indefinitely; customer paid but booking never confirms.

**Diagnosis:**
```sql
-- Find stuck sagas
SELECT booking_ref, saga_status, last_completed_step, created_at
FROM saga_state
WHERE saga_status IN ('AWAITING_PAYMENT','PAYMENT_RECEIVED')
  AND created_at < NOW() - INTERVAL '20 minutes'
ORDER BY created_at;

-- Check outbox for unpublished events
SELECT id, topic, aggregate_key, attempts, last_error, created_at
FROM outbox_event
WHERE sent = false
ORDER BY created_at
LIMIT 20;
```

**Immediate mitigation:**
1. Check Kafka connectivity: `kubectl exec -n sk-binge-galaxy deploy/booking-service -- curl -s http://kafka:9092`
2. If Kafka is healthy, check outbox for `failedPermanent=true` rows — reset via admin API: `POST /api/v1/admin/outbox/retry-all`
3. If payment was collected: manually advance the saga via `POST /api/v1/admin/recovery/advance-saga/{bookingRef}` (SUPER_ADMIN only)

**Root cause fix:** The outbox poller runs every 2s with ShedLock. If it's stuck, restart the booking-service pod with the outbox lock held: `kubectl rollout restart deploy/booking-service -n sk-binge-galaxy`

---

## 2. Kafka Consumer Lag Spike

**Symptom:** Alert fires: `KafkaConsumerLagHigh` or `KafkaPartitionLagSkew`. Notifications delayed, bookings not confirming.

**Diagnosis:**
```bash
# Check lag per consumer group
kubectl exec -n sk-binge-galaxy deploy/booking-service -- \
  kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group booking-group

# Check for hot partition skew
# (Grafana: kafka_consumer_lag_partition by partition)
```

**Immediate mitigation:**
1. Scale the affected service: `kubectl scale deploy/notification-service --replicas=4 -n sk-binge-galaxy`
2. If one partition is hot (all lag on partition 0): the Kafka topic has uneven key distribution — check the producer key pattern
3. Check for poison-pill messages: examine DLT topics (`*.DLT`) for stuck messages

**Root cause fix:** Increase partition count for hot topics. For notification service bulk-cancel storm, the `EmailRateLimiter` (100/min) naturally backs off the consumer.

---

## 3. Database Connection Pool Exhaustion (HikariCP)

**Symptom:** `Connection is not available, request timed out after 5000ms` errors. Service returns 503.

**Diagnosis:**
```bash
# Check current pool metrics
curl -s http://booking-service:8083/actuator/metrics/hikaricp.connections.active
curl -s http://booking-service:8083/actuator/metrics/hikaricp.connections.pending

# Check for long-running queries holding connections
# (via postgres-exporter / Grafana)
```

**Immediate mitigation:**
1. Identify and kill long-running queries in PostgreSQL:
   ```sql
   SELECT pid, now() - query_start AS duration, query, state
   FROM pg_stat_activity
   WHERE state != 'idle' AND query_start < NOW() - INTERVAL '30s'
   ORDER BY duration DESC;

   SELECT pg_terminate_backend(pid) FROM pg_stat_activity
   WHERE state != 'idle' AND query_start < NOW() - INTERVAL '60s';
   ```
2. Rolling restart to release leaked connections: `kubectl rollout restart deploy/booking-service -n sk-binge-galaxy`

**Root cause fix:** `leak-detection-threshold=60000ms` will log the stack trace of the culprit. Fix the code path that doesn't close its connection. Tune `maximum-pool-size` if the workload legitimately needs more (default 8 per pod × 2 pods = 16 total).

---

## 4. Redis Cache Down / Unavailable

**Symptom:** `RedisDown` alert fires. Services degrade or throw `RedisConnectionFailureException`.

**Diagnosis:**
```bash
kubectl get pod -n sk-binge-galaxy -l app=redis
kubectl logs -n sk-binge-galaxy deploy/redis --tail=50
```

**Immediate mitigation:**
1. Check Redis memory: `kubectl exec -n sk-binge-galaxy deploy/redis -- redis-cli INFO memory | grep used_memory_human`
2. If OOM eviction: `kubectl exec -n sk-binge-galaxy deploy/redis -- redis-cli CONFIG SET maxmemory-policy allkeys-lru`
3. Restart Redis: `kubectl rollout restart deploy/redis -n sk-binge-galaxy`
4. Services have circuit breakers — they fall back to DB queries while Redis recovers. Monitor for elevated DB load.

**Root cause fix:** Ensure Redis has sufficient memory limits in `k8s/infrastructure.yml`. Set `maxmemory` appropriately. Configure `allkeys-lru` eviction policy for cache-appropriate behavior.

---

## 5. Payment Callback Missed / Stuck INITIATED

**Symptom:** Customer paid but booking is not confirmed. Payment stays `INITIATED` in payment-service.

**Diagnosis:**
```sql
-- In payment_db
SELECT transaction_id, booking_ref, gateway_order_id, status, created_at, failure_reason
FROM payments
WHERE status = 'INITIATED' AND created_at < NOW() - INTERVAL '30 minutes'
ORDER BY created_at;
```

**Immediate mitigation:**
1. The `PaymentReconciliationScheduler` runs every 5 minutes and auto-resolves stale INITIATED payments by querying Razorpay directly.
2. For urgent cases, trigger manual reconciliation via Razorpay Dashboard: verify the order was paid → use admin API to record cash payment if needed.
3. Check Razorpay webhook logs for missed callbacks: `POST /api/v1/payment/razorpay/callback` endpoint logs all received events.

**Root cause fix:** Razorpay webhooks require the `callback-url` to be publicly reachable. Verify the URL in Razorpay dashboard settings. If it's behind a VPN or firewall, the webhook can't reach it.

---

## 6. Postgres XID Wraparound Approaching

**Symptom:** Alert `PostgresXidWraparoundApproaching` fires (age > 1.5B transactions).

**Diagnosis:**
```sql
SELECT datname, age(datfrozenxid) AS xid_age,
       2000000000 - age(datfrozenxid) AS remaining
FROM pg_database
ORDER BY xid_age DESC;

-- Check for tables blocking VACUUM
SELECT relname, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 10;
```

**Immediate mitigation:**
1. Force VACUUM FREEZE on the most-bloated table: `VACUUM FREEZE ANALYZE outbox_event;`
2. If autovacuum is blocked by a long-running transaction, find and kill it (see #3 above).
3. Scale down write load temporarily if the situation is critical (pause outbox poller via ShedLock).

**Root cause fix:** The V61 migration sets aggressive autovacuum parameters for high-write tables (`outbox_event`, `booking_event_log`). Ensure `pg_stat_user_tables.last_autovacuum` is recent (< 1 hour) for these tables. Monitor `PostgresHighDeadTuples` alert.

---

## 7. JVM Out-of-Memory / Heap Dump

**Symptom:** Pod crashes with `ExitOnOutOfMemoryError`. Heap dump written to `/tmp/heapdump.hprof`.

**Diagnosis:**
```bash
# Copy the heap dump
kubectl cp sk-binge-galaxy/booking-service-xxx:/tmp/heapdump.hprof ./heapdump.hprof

# Check GC metrics in Grafana (JvmHeapCritical alert)
# Look for: large retained objects, memory leaks in caches
```

**Immediate mitigation:**
1. The pod exits immediately on OOM (`-XX:+ExitOnOutOfMemoryError`) — Kubernetes restarts it automatically.
2. If it crash-loops: check for a memory-leak pattern (e.g. unbounded cache growth, N+1 queries loading large result sets).
3. Temporary fix: increase memory limit in `k8s/services.yml` from 512Mi to 768Mi for the affected service.

**Root cause fix:** Analyze the heap dump with Eclipse MAT or VisualVM to identify the top retained objects. Common culprits: Hibernate L2 cache, large Kafka consumer batch accumulation, unbounded `ArrayList` in admin report queries.

---

## 8. Availability Service Slot Conflicts

**Symptom:** Customers report "slot not available" errors on empty calendar, or double-bookings visible in admin dashboard.

**Diagnosis:**
```sql
-- In booking_db: find time-conflicting CONFIRMED bookings
SELECT a.booking_ref, b.booking_ref, a.booking_date, a.start_time, a.duration_minutes
FROM bookings a
JOIN bookings b ON a.binge_id = b.binge_id
  AND a.booking_date = b.booking_date
  AND a.id < b.id
  AND a.status NOT IN ('CANCELLED','NO_SHOW')
  AND b.status NOT IN ('CANCELLED','NO_SHOW')
  AND (a.start_time, a.start_time + (a.duration_minutes || ' minutes')::interval)
  OVERLAPS (b.start_time, b.start_time + (b.duration_minutes || ' minutes')::interval);
```

**Immediate mitigation:**
1. If double-booking occurred: cancel the later booking and issue a full refund via admin panel.
2. Force a slot cache refresh in availability-service: `kubectl rollout restart deploy/availability-service -n sk-binge-galaxy`
3. Check `advisory_lock` usage — the `pg_advisory_lock` call in booking-service protects the slot reservation.

**Root cause fix:** The advisory lock key is `hash(bingeId, date)`. If two different bingeIds hash to the same key, there's no collision (they'd still fail the time-conflict check). Verify the advisory lock is being acquired before the double-check query.

---

## 9. Auth Token Refresh Loop / Session Invalidation Storm

**Symptom:** Customers report being logged out repeatedly. Surge of 401 errors in api-gateway logs.

**Diagnosis:**
```sql
-- In auth_db: check for mass token revocation
SELECT revoked_by, COUNT(*) as count, MAX(revoked_at) as latest
FROM revoked_tokens
WHERE revoked_at > NOW() - INTERVAL '1 hour'
GROUP BY revoked_by
ORDER BY count DESC;

-- Check active sessions
SELECT COUNT(*) FROM user_sessions WHERE revoked_at IS NULL AND expires_at > NOW();
```

**Immediate mitigation:**
1. If a specific admin triggered mass revocation: check `auth_audit_log` for the action.
2. If it's a token replay attack: check for a single IP with many refresh attempts → block at api-gateway level.
3. JWT secret rotation: if the `JWT_SECRET` was rotated in Kubernetes secrets, all existing tokens immediately invalidate. Verify the secret is stable in `kubectl get secret app-secrets -n sk-binge-galaxy -o yaml`.

**Root cause fix:** JWT secret rotation requires a rolling deployment strategy where both old and new secrets are accepted during the transition window. Use dual-key validation for 15-minute overlap on rotation day.

---

## 10. No-Show Auto-Freeze Wrongly Applied

**Symptom:** Customer complains they were frozen (can't book) even though they checked in.

**Diagnosis:**
```sql
-- In booking_db
SELECT b.booking_ref, b.status, b.checked_in, b.actual_check_in_time,
       cf.frozen_until, cf.reason
FROM bookings b
LEFT JOIN customer_freezes cf ON b.customer_id = cf.customer_id
WHERE b.customer_id = :customerId
ORDER BY b.booking_date DESC;
```

**Immediate mitigation:**
1. Manually unfreeze: admin panel → Customer → Remove Freeze, or via API: `DELETE /api/v1/admin/customers/{id}/freeze`
2. Verify the check-in record: `SELECT * FROM bookings WHERE booking_ref = 'SKBG-xxx'` — check `checked_in` and `actual_check_in_time`.
3. If `checked_in = false` despite customer claiming they checked in: check `booking_event_log` for `CHECK_IN_SUCCEEDED` event.

**Root cause fix:** The no-show scheduler has a `grace-minutes=30` buffer after scheduled start time before auto-freezing. If the OTP/QR check-in failed silently, the scheduler sees the booking as a no-show. Ensure `checkin.return-otp-in-response=true` in dev and that the check-in endpoint is reachable from the customer app.

---

## Quick Reference: Key Kubernetes Commands

```bash
# View all pod statuses
kubectl get pods -n sk-binge-galaxy

# Tail logs for a service
kubectl logs -n sk-binge-galaxy deploy/booking-service -f --tail=100

# Rolling restart (zero-downtime)
kubectl rollout restart deploy/booking-service -n sk-binge-galaxy

# Scale a service
kubectl scale deploy/notification-service --replicas=4 -n sk-binge-galaxy

# Check HPA status
kubectl get hpa -n sk-binge-galaxy

# Check circuit breaker status
curl -s http://booking-service:8083/actuator/circuitbreakers | jq .

# Check Prometheus alerts
curl -s http://prometheus:9090/api/v1/alerts | jq '.data.alerts[] | select(.state=="firing")'
```

## Alert Escalation Matrix

| Severity | Channel | Response Time | Who |
|----------|---------|---------------|-----|
| `critical` | PagerDuty + `#sk-binge-critical` | 15 min | On-call engineer |
| `warning` | `#sk-binge-alerts-low` (Slack only) | 4 hours | Primary engineer |
| `info` | Dashboard only | Next business day | Anyone |

---

## 11. Config Server Down

**Symptom:** Services fail to start or reload config with `Could not locate PropertySource` or connection-refused on port 8888. During a rolling restart or scale-up event, new pods cannot fetch config and enter a crash loop.

**Diagnosis:**
```bash
# Is config-server pod running?
kubectl get pods -n sk-binge-galaxy -l app=config-server

# Check config-server logs
kubectl logs -n sk-binge-galaxy deploy/config-server --tail=50

# Can a service reach config-server?
kubectl exec -n sk-binge-galaxy deploy/booking-service -- \
  curl -sf http://config-server:8888/actuator/health
```

**Fallback behavior (by design):**
All services have `spring.cloud.config.fail-fast=false` and carry embedded YAML defaults in their JARs. If config-server is unreachable:
- Already-running pods continue with their cached config — **no impact to live traffic**.
- New pods starting for the first time will use embedded defaults. Verify with: `kubectl logs <new-pod> | grep "cloud.config"`.
- Sensitive values (JWT secret, DB password) come from K8s Secrets mounted as env vars, NOT from config-server. Those pods will start correctly.

**Immediate mitigation:**
1. Restart config-server: `kubectl rollout restart deploy/config-server -n sk-binge-galaxy`
2. If config-server PVC is corrupted: `kubectl delete pod -l app=config-server -n sk-binge-galaxy` (pod recreates from image — config files are baked in)
3. Hold rolling restarts of other services until config-server is healthy.

**Root cause fix:** Config-server uses the `native` profile (classpath files). Crashes are usually OOM — check `kubectl top pod`. Increase memory limit in `k8s/services.yml` if needed.

---

## 12. Eureka (Discovery Server) Down

**Symptom:** Services cannot register; gateway route table goes stale; new pods become unreachable; `KafkaConsumerLagHigh` alerts may fire if notification/booking services can't call availability service.

**Diagnosis:**
```bash
# Is discovery-server running?
kubectl get pods -n sk-binge-galaxy -l app=discovery-server

# Check Eureka dashboard
kubectl port-forward svc/discovery-server 8761:8761 -n sk-binge-galaxy
# Open http://localhost:8761 — registered instances table

# Check gateway route table freshness
kubectl exec -n sk-binge-galaxy deploy/api-gateway -- \
  curl -s http://localhost:8080/actuator/gateway/routes | python3 -m json.tool | head -40
```

**Fallback behavior (by design):**
Spring Cloud Netflix Eureka uses a **30-second client-side registry cache**. All running pods retain their last-known registry snapshot. Existing connections and in-flight requests continue. The key risk window:
- **Scale-up during the outage**: new pods register to a crashed Eureka and ARE NOT visible to the gateway. Callers get `503 No instances available`.
- **30s eviction**: if Eureka is down > 90s and self-preservation is disabled (`enable-self-preservation=false` in our config), the gateway may evict stale entries and start returning 503 for ALL requests.

**Immediate mitigation:**
1. Restart Eureka: `kubectl rollout restart statefulset/discovery-server -n sk-binge-galaxy`
2. If HPA has scaled up during the outage, re-register new pods: `kubectl rollout restart deploy/api-gateway deploy/booking-service -n sk-binge-galaxy`
3. Monitor Grafana HTTP error rate panel — should recover within 60s of Eureka restart.

**Root cause fix:** Discovery-server is a StatefulSet with a PVC. If the PVC is corrupted, wipe it: `kubectl delete pvc discovery-data -n sk-binge-galaxy` (Eureka state is rebuilt from service registrations within 30s). Consider enabling self-preservation for production: `EUREKA_SERVER_ENABLE_SELF_PRESERVATION=true` to prevent mass eviction during network partitions.

---

## 13. Razorpay Dispute Response Window Expiring

**Symptom:** `skbg_payment_dispute_total{outcome="opened"}` counter is rising but no `dispute.won` or `dispute.lost` events follow within 48h. Grafana panel 16 ("Open Payment Disputes") shows count > 0.

**Diagnosis:**
```bash
# List open disputes via admin API
curl -H "X-Binge-Id: <bingeId>" \
     -H "Authorization: Bearer <admin-token>" \
     https://<domain>/api/v1/payments/admin/disputes \
  | python3 -m json.tool

# Most urgent dispute (lowest minutesUntilDeadline)
# minutesUntilDeadline < 1440 = less than 24h remaining — RED alert
```

**Immediate mitigation:**
1. Log in to Razorpay Dashboard → Disputes → Upload evidence before `respondBy` deadline.
2. Use `PATCH /api/v1/payments/admin/disputes/{id}/notes` to record what evidence was submitted.
3. If `minutesUntilDeadline < 0` (overdue): Razorpay will auto-close as LOST. Email the customer with a refund explanation.

**Alert setup** — Two PrometheusRules are already added to `k8s/monitoring.yml`:

- `DisputeOpened` (critical, fires immediately): triggers when any new dispute is opened.
  Ops are paged and must triage via `GET /api/v1/payments/admin/disputes` within 48h.

- `DisputeDeadlineApproaching` (warning, fires after 2h): fires when disputes remain
  open for an extended period, indicating the deadline may be approaching.

Note: Prometheus counters cannot track per-dispute respond-by timestamps. Per-dispute
deadline urgency is surfaced via `minutesUntilDeadline` in the admin API response
(`GET /api/v1/payments/admin/disputes` — sorted by deadline ASC, most urgent first).
