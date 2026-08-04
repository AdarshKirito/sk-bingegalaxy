# Current Booking Concurrency Audit — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · static trace of the create-booking critical section

## The critical section, line by line

1. **Hold** — customer acquires `SlotHold` (capacity-checked insert, `@Version`); expiry scheduler (`SlotHoldExpiryScheduler`, ShedLock) reclaims abandoned holds
2. **Create request** — `POST /bookings` with Idempotency-Key (dup request ⇒ same result, no second booking)
3. **Advisory lock** — BookingService.java:261 calls repository lock; [BookingRepository.java:433](../../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/repository/BookingRepository.java) executes `SELECT pg_advisory_xact_lock(:key)` on the room/slot key — competing creates for the same slot serialize here
4. **Occupancy re-check** — inside the lock, current confirmed+held count re-validated
5. **Snapshot + insert** — BookingPriceSnapshot frozen; booking row inserted
6. **V75 trigger** — on insert/update, the DB re-counts occupancy and RAISEs on oversell — final backstop independent of steps 1–4
7. **Outbox** — `booking.created` written in the same transaction; relay publishes after commit

## Failure-mode table

| Scenario | Outcome | Layer that saves it |
|---|---|---|
| Two creates race same slot | Second waits on advisory lock, then fails occupancy re-check | Layer 2 |
| App code regression skips re-check | DB trigger raises, transaction rolls back | Layer 3 (V75) |
| Client retries after timeout | Idempotency-Key returns original result | Idempotency |
| Hold expires mid-checkout | Create fails occupancy check; UI prompts re-hold | Layer 1+2 |
| Kafka down at create | Booking commits; outbox relay publishes later | Outbox |
| Scheduler double-runs across replicas | ShedLock prevents | ShedLock |

## State machine

`BookingStateMachine` rejects illegal transitions (e.g., COMPLETED→CONFIRMED); every transition logged to BookingEventLog. Cancellation branches into the refund saga (see payment evidence).

## What is NOT proven (honest)

- No Testcontainers test exercises V75 or real lock contention (mocks only) — TEST-01
- `BookingFlowIntegrationTest` is `@Disabled` — BOOK-02
- Load evidence for this exact commit: none (PERF-01); last real contention proof is HISTORICAL (26-Apr stress + July k6)
- Advisory-lock hold time includes snapshot pricing work — measure under contention (PERF-02)
