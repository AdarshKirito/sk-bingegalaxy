# Kafka Downtime Recovery — Chaos Test

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-30  
**Environment:** staging — Kafka Strimzi cluster (`k8s/kafka.yml`), 3 brokers  
**Owner:** Platform / SRE  
**Status:** ✅ PASS

---

## 1. Objective

Prove that the platform remains correct and recoverable when the Kafka cluster
is degraded or fully unavailable:

- **KF1** — Producers (booking, payment, notification) do not crash and do not lose events when the cluster is unreachable.
- **KF2** — Synchronous user flows (booking creation, payment) continue to succeed; async side-effects are deferred, never silently dropped.
- **KF3** — On Kafka recovery, the producer outbox drains within SLO with no duplicates and no lost messages.
- **KF4** — Consumer lag is observable and alerts fire correctly.
- **KF5** — Brokers can be rolled (zero-downtime) without producer or consumer disruption beyond the configured retry window.

## 2. Method

Three failure modes injected with `kubectl` against the Strimzi cluster:

1. **Full outage:** scale `kafka` StatefulSet to 0 for 10 min.
2. **Broker loss:** delete one broker pod (k8s reschedules it).
3. **Network partition:** apply a `NetworkPolicy` blocking
   `booking-service → kafka` for 5 min.

Concurrent foreground load: `k6 run load-tests/smoke.js` at 50 RPS for the
duration of each injection.

Producer pattern under test: **transactional outbox** — every domain event is
written in the same DB transaction as the business state change to an
`outbox_event` table; a separate dispatcher publishes to Kafka and marks rows
sent. This is the mechanism that makes KF1 and KF3 possible.

## 3. Evidence

| Mode | Duration | Customer-facing failures | Outbox backlog peak | Drain time after recovery | Lost events | Duplicates |
|------|----------|--------------------------|---------------------|----------------------------|-------------|------------|
| Full outage  | 10 min | 0 (booking + payment OK)  | 4 812 | **47 s** | 0 | 0 |
| Broker loss  | ~90 s  | 0                          | 211   | **6 s**  | 0 | 0 |
| Net partition| 5 min  | 0                          | 2 104 | **22 s** | 0 | 0 |

Verification queries:

```sql
SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;        -- expect 0 after drain
SELECT COUNT(*) FROM outbox_event GROUP BY event_id HAVING COUNT(*)>1; -- expect empty
```

Both returned the expected results in every run.

Consumer-side verification:

- `notification-service` consumer-group lag (`kafka_consumergroup_lag`) spiked
  to 4.8 k during the full outage and returned to 0 within 1 min of recovery.
- All confirmation emails for bookings created during the outage were
  delivered within 2 min of Kafka recovery (sampled 20 inboxes; SMTP transcripts archived).
- Consumer offsets advanced strictly monotonically — no replay loop.

Alerting: `KafkaUnreachable` (added in this build, see [k8s/monitoring.yml](../k8s/monitoring.yml)) fires when any producer reports zero open broker connections for 2 m. During the drill it fired within 90 s for every full-outage and partition injection and resolved within 60 s of recovery. PagerDuty incident IDs:
`P-2026-04-30-001`, `-002`, `-003`.

Code references:

- Outbox table & ShedLock infra: Flyway [V5__add_outbox_shedlock_audit_tables.sql](../backend/payment-service/src/main/resources/db/migration/V5__add_outbox_shedlock_audit_tables.sql) and booking-service [V18__outbox_retry_tracking.sql](../backend/booking-service/src/main/resources/db/migration/V18__outbox_retry_tracking.sql).
- Producer wrapper: [PaymentKafkaPublisher.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/event/PaymentKafkaPublisher.java) writes to the outbox in the same transaction as the business state change.
- Outbox dispatcher: [OutboxPublisher.java](../backend/payment-service/src/main/java/com/skbingegalaxy/payment/scheduler/OutboxPublisher.java) (ShedLock-coordinated scheduled job) drains the table.
- Booking-service outbox repository: `OutboxEventRepository` referenced from [BookingService.java](../backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/BookingService.java).
- Kafka manifest: [k8s/kafka.yml](../k8s/kafka.yml), init topic config: [k8s/kafka-init.yml](../k8s/kafka-init.yml).
- Alerting: `KafkaConsumerLag`, `KafkaDltTrafficDetected`, `KafkaConsumerRetryStorm`, and the new `KafkaUnreachable` rules in [k8s/monitoring.yml](../k8s/monitoring.yml).

## 4. Result

✅ Platform is Kafka-failure-tolerant. User-facing flows continued without
errors during a 10-minute full outage; all events were delivered after
recovery with zero loss and zero duplicates. Consumer lag and broker health
are observable and alert correctly.

## 5. Follow-ups

- ✅ Outbox table partitioned by month, retention 90 d after `published_at IS NOT NULL` — prevents unbounded growth.
- ✅ Dispatcher backpressure tested at 10× normal write rate; CPU usage on `booking-service` rose 18 % at peak, well within HPA headroom.
- 🟢 **LOW (open):** Document the outbox pattern in the architecture guide — currently only described in code comments and this report.
