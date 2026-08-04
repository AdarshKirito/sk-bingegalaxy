# 10 — Booking, Availability and Concurrency (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; no concurrency execution performed this run

## The three-layer oversell defense (all VERIFIED-STATIC)

| Layer | Mechanism | Evidence |
|---|---|---|
| 1. Provisional | `SlotHold` with `@Version` optimistic locking, TTL expiry via `SlotHoldExpiryScheduler`; consume/expire/convert transitions | booking-service slot hold service + entity |
| 2. Transactional | `pg_advisory_xact_lock(roomId/slot key)` serializes competing creates | [BookingRepository.java:433](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/repository/BookingRepository.java); invoked at BookingService.java:261 |
| 3. Database backstop | **V75 trigger** re-counts occupancy on insert/update and raises on oversell — catches any app-layer bug | `V75__room_occupancy_db_backstop.sql` |

Historical evidence: the 26-Apr-2026 stress campaign and July k6 runs exercised double-booking scenarios (HISTORICAL — [STRESS-TEST-REPORT-26APR2026.md](../../STRESS-TEST-REPORT-26APR2026.md), production-proof/). DATA-001 (double booking) is **FIXED** by this stack; the residual gap is test coverage, not design: **no Testcontainers test exercises the V75 trigger or real advisory-lock contention** (register DB-03/TEST-01).

## Booking state machine

`BookingStateMachine` guards transitions (PENDING → CONFIRMED → CHECKED_IN → COMPLETED; cancellation branches; NO_SHOW via audit). Invalid transitions throw; event log (`BookingEventLog`) records every change. CQRS `BookingReadModel` powers admin lists.

## Holds lifecycle

- Create: capacity-checked, versioned insert
- Consume: converts to booking inside the advisory-lock transaction
- Expire: scheduler releases abandoned holds (ShedLock-safe)
- BOOK-001 (holds not enforced) is **FIXED** — consume/expire/convert all present and wired

## Availability-service

Small (V2): blocks + slot queries; booking-service validates against it via internal API at create time. RoomBlock and BlockedDates admin flows write here through booking-service orchestration.

## Waitlist & transfer concurrency

- Waitlist promotion is Kafka-driven off `booking.cancelled` — single consumer group prevents double-promotion; ProcessedEvent dedupes redelivery
- Transfers: token-scoped, single-accept semantics enforced by state transition on `BookingTransfer`

## Timezone correctness

Bookings store venue-local schedule against `Binge.timezone` (change-request governed); no naive `LocalDateTime.now()` in booking math spot-checks (server clock used only for TTL/audit timestamps).

## Residual risks

| ID | Sev | Summary |
|---|---|---|
| TEST-01 | P1 | No DB-level automated test for V75 trigger / advisory-lock contention (mocked in unit tests); regressions would only surface in prod or manual k6 |
| BOOK-02 | P3 | `BookingFlowIntegrationTest` is `@Disabled` — dead weight or missing coverage |
| PERF-02 | P2 | Advisory-lock hold time includes pricing snapshot work — lock-scope review advised at higher concurrency (static observation) |
