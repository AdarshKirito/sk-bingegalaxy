# 06d — `booking-service` · Schedulers, Listeners, Config, Health

The async backbone: 9 ShedLock-guarded schedulers, 2 Kafka listeners, the SSE event bus, Kafka/
cache/security config, two boot-time schema migrations, and the data seeder. The outbox publisher
and payment listener are traced in [ARCHITECTURE.md §9](../../ARCHITECTURE.md); this doc walks
each file.

Source roots: `scheduler/`, `listener/`, `config/`, `health/` under
`backend/booking-service/src/main/java/com/skbingegalaxy/booking/`

---

## scheduler/ (all `@SchedulerLock` / ShedLock-guarded → one replica runs each)

### `OutboxPublisher.java` (174 lines)
`@Scheduled(fixedDelay=2000)` — polls `outbox_event`, ships to Kafka keyed by `aggregateKey`,
3-way failure classification (recoverable code-bug → retry forever, exhausted → `failedPermanent`,
transient → retry), and pushes to the binge-scoped admin SSE stream after each publish. Lock
`1s`–`30s`. Full walk in [ARCHITECTURE.md §9.2](../../ARCHITECTURE.md).

### `PendingBookingTimeoutScheduler.java`
`@Scheduled` (default 5 min) — finds PENDING bookings past their checkout window and
**system-cancels** them (same path the recovery console reuses), which triggers saga
compensation + slot release + the freeze-policy `recordPendingPaymentTimeout`. Lock `4m`–`9m`.

### `SlotHoldExpiryScheduler.java`
Two jobs: `@Scheduled` (default 60s) → `SlotHoldService.expireStaleHolds` (release expired
checkout holds); a nightly cron (`app.slot-hold.purge-cron`, default 03:15) →
`purgeOldTerminalHolds` (table hygiene). Lock `5s`–`55s`.

### `WaitlistOfferExpiryScheduler.java`
`@Scheduled` (5 min) — expires waitlist offers that weren't accepted in time so the slot can be
re-offered to the next entry. Lock `1m`–`4m`.

### `NoShowAutomationScheduler.java`
`@Scheduled` (default 15 min) — the daily-audit no-show sweep: CONFIRMED bookings whose start
time (venue-local) has passed without check-in are marked NO_SHOW via the state machine, feeding
the freeze-policy `recordNoShow`. Lock `1m`–`10m`.

### `BookingTransferExpiryScheduler.java`
`@Scheduled` (5 min) → `BookingTransferService.expireStalePending` — expires unaccepted transfer
offers. Lock `1m`–`4m`.

### `BingeGracePeriodScheduler.java`
`@Scheduled` (30 min) — enforces the 24h post-approval grace period: warns the admin at 12h
remaining and auto-deactivates an approved-but-empty binge after 24h (via `BingeService`). Lock
`4m`–`9m`.

### `CqrsReconciliationJob.java`
Nightly cron (`app.cqrs.reconciliation-cron`, default 02:15) → `BookingProjectionService.replayAll`
— rebuilds the `booking_read_model` from the event log to heal any projection drift. Lock
`1m`–`10m`.

### `SseHeartbeatScheduler.java`
`@Scheduled(fixedRate=30s)` — emits a heartbeat on every open admin SSE connection so proxies
don't drop idle streams. (No ShedLock — each replica heartbeats its own connections.)

---

## listener/ (Kafka consumers, group-scoped, idempotent)

### `PaymentEventListener.java` (262 lines)
`@KafkaListener` for `PAYMENT_SUCCESS` / `PAYMENT_FAILED` / `PAYMENT_REFUNDED` (group
`booking-group`). `onPaymentSuccess` (out-of-order guard, atomic collect, saga `PAYMENT_RECEIVED`),
`onPaymentFailed` (saga compensation → auto-cancel), `onPaymentRefunded` (reflect the refund).
Dedup via `ProcessedEvent`. Traced in [ARCHITECTURE.md §8/§9.4](../../ARCHITECTURE.md).

### `WaitlistPromotionListener.java`
`@KafkaListener(BOOKING_CANCELLED, group=booking-waitlist-group)` — a **separate consumer group**
(so it processes cancellations independently of the saga listener) that calls
`WaitlistService.promoteWaitlistOnCancellation` to offer the freed slot to the next waitlister.

---

## config/

### `AdminEventBus.java`
In-memory, **binge-scoped** SSE event bus. Holds `SseEmitter`s per binge; `publish(bingeId, type,
payload)` fans an event to every connected admin of that venue; handles subscribe/unsubscribe/
timeout/completion. The `OutboxPublisher` and controllers push here; `AdminSseController` exposes
the stream.

### `KafkaConfig.java`
Producer (JSON serializer, keyed by `aggregateKey`) + consumer factory wiring, and imports
`common-lib`'s `KafkaDlqErrorHandlerConfig` (the `<topic>-dlt` poison-pill handler). Pre-creates
the topics via `TopicBuilder`.

### `SecurityConfig.java`
Stateless chain with `InternalApiAuthFilter` + `GatewayHeaderAuthFilter`. Rules:
`/admin/**` + `/waitlist/admin/**` → ADMIN/SUPER_ADMIN; `/internal/**` → SYSTEM;
`/api/v2/loyalty/super-admin/**` → SUPER_ADMIN, `/api/v2/loyalty/admin/**` → ADMIN/SUPER_ADMIN;
a permitAll block for public catalog/binge/funnel/media/transfer-by-token paths; actuator health
permitAll, rest SYSTEM; swagger ADMIN/SUPER_ADMIN; else authenticated.

### `CacheConfig.java`
`@EnableCaching` + cache manager beans for hot read paths (e.g. venue clock / operating window /
catalog lookups) to cut DB round-trips.

### `ShedLockConfig.java`
`@EnableSchedulerLock` + JDBC `LockProvider` over the `shedlock` table (created in
`init-databases.sql`) — the cluster-safe scheduling backbone all the schedulers above depend on.

### `BingeContextFilter.java`
The per-request `@Order(1)` servlet filter that reads `X-Binge-Id` (or `bingeId` param) into
`BingeContext` and clears it in `finally`.

### `OpenApiConfig.java`
Springdoc bean (title/version/security scheme) for swagger.

### `DataSeeder.java`
Boot-time seed data (`CommandLineRunner`) — default catalog/binge fixtures for a fresh DB so the
app is usable immediately in dev.

### `BingeAboutSchemaMigration.java` + `CustomerPricingProfileSchemaMigration.java`
`ApplicationRunner` data-shape migrations that run at startup to backfill/normalize JSON config
(binge "about" experience config; customer pricing profile fields) for rows predating a schema
change — complementing the Flyway DDL migrations with idempotent data fixes.

---

## health/

### `KafkaHealthIndicator.java`
Custom actuator health indicator surfacing Kafka broker/consumer connectivity in
`/actuator/health` (so an outage shows up red rather than silently stalling the outbox/listeners).

---

## event/ (Spring application events — not Kafka)
`BookingCancelledEvent`, `BookingCheckoutEvent`, `BookingCompletedEvent` — in-process events
published by `BookingService`/checkout and consumed by `@TransactionalEventListener(AFTER_COMMIT)`
handlers (e.g. the loyalty v2 listener reverses points on cancel — see
[06e](06e-booking-loyalty.md)). Kept in-process so they fire only after the DB commit and can't
roll back the originating transaction.

## Tests (`src/test/scheduler/`)
`OutboxPublisherTest`, `PendingBookingTimeoutSchedulerTest`, `NoShowAutomationSchedulerTest`,
plus the listener test `PaymentEventListenerTest` — covering the outbox failure fork, stale-pending
cancellation, no-show marking, and the payment→saga flow.
