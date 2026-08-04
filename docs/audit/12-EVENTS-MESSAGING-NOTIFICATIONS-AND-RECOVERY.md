# 12 — Events, Messaging, Notifications and Recovery (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC

## Topic fabric

20 topics defined in [KafkaTopics.java](../../backend/common-lib/src/main/java/com/skbingegalaxy/common/constants/KafkaTopics.java); DLT convention `-dlt`; 16 `@KafkaListener`s. Full producer/consumer accounting: [evidence/producer-consumer-matrix.tsv](evidence/producer-consumer-matrix.tsv); schema/compat notes: [evidence/event-contract-matrix.tsv](evidence/event-contract-matrix.tsv).

## Delivery guarantees (VERIFIED-STATIC)

| Property | Mechanism |
|---|---|
| No event loss on publish | **Transactional outbox** in booking + payment (OutboxEvent, relay scheduler) |
| At-most-once effects | ProcessedEvent dedup per consumer + IdempotencyKey |
| Poison handling | Retry policy → `-dlt` topics |
| Replay | DLT replay tooling in ops console (recovery queues) |
| Recovery queues | Admin recovery-queue console, tenant-scoped (SEC-001 fix verified) |

## Gaps found

1. **8 published topics have no in-repo consumer** (booking.confirmed, rescheduled, transferred, checked-in, completed, room.approved, room.rejected, user.registered, password.reset — see matrix). Either dead weight or contracts for externals; decide and document (EVT-01, P3).
2. **auth-service publishes directly (no outbox)** — `user.anonymized` (UserAnonymizationService.java:159) and auth lifecycle events can be lost if Kafka is down at publish time and retries exhaust (EVT-02, P2).
3. **No DLT/outbox depth alerting** — a stuck relay or growing DLT is invisible until users complain (OBS-01, P1; no PrometheusRule manifests exist).
4. **Notification dedup TTL 1 h** — same notification can re-send after an hour on redelivery storms (NOT-03, P3).

## Notification pipeline

- MongoDB store, TTL 90 d; templates versioned (super-admin editable via /admin/notification-templates)
- Channels: email (SMTP), WebPush (VAPID keys), webhooks; SMS/WhatsApp are mocks (launch checklist requires disable-or-integrate)
- Reminder schedulers (ShedLock); AdminNotification fan-out for ops events
- Dedup collection prevents duplicate sends within 1 h window

## Recovery machinery

- Outbox relay reprocesses unpublished rows on restart
- SagaState resumes cancellation/refund flows
- Recovery-queue console: inspect, resolve, replay failed events per binge
- ShedLock prevents duplicate scheduled recovery across replicas

## Risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| OBS-01 | P1 | No alert rules for DLT depth, outbox lag, consumer lag |
| EVT-02 | P2 | Auth events not outboxed |
| EVT-01 | P3 | 8 producer-only topics — dead or undocumented contracts |
| NOT-03 | P3 | 1 h dedup window |
