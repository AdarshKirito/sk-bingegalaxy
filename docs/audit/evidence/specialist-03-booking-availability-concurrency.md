# Specialist Investigation 03 — Availability, Slot Holds, Booking Lifecycle & Double-Booking (read-only)

Agent completed 2026-07-11 (opus classifier unavailable at review; reconciled by lead + corroborated by live DB introspection — see runtime log). Evidence, not final conclusion.

## 1. Availability computation
- CONFIRMED — availability generated in-memory, not stored inventory. 30-min slots across `[openMin,closeMin)`, blocked half-hours subtracted (`AvailabilityService.java:66-128,431-495`). Endpoints all `CacheControl.noStore()` (`AvailabilityController.java:46-72`).
- CONFIRMED — `BlockedDate`=whole day; `BlockedSlot` stores startHour/endHour as MINUTES-since-midnight (misleading names, `BlockedSlot.java:32-45`); real DB unique `uk_blocked_slot_binge(bingeId,slotDate,startHour)` (`:15-17`).
- HIGH CONFIDENCE — TZ is venue-local. Clock `systemUTC()` rebased via `clock.withZone(venueZone())` (`ClockConfig.java:19`, `:422-429`), venue zone over Feign (30s cache), fallback `Asia/Kolkata`. Past-slot uses `venueLocalNow()`.
- CONFIRMED — V66 per-day opening hours honored, null/parse-tolerant (`OpeningHoursCodec.java:36-46`; booking mirror `BookingService.java:2392-2399`).
- PROBABLE (DST) — slot generation is minute-of-day arithmetic, never zoned instants; DST spring-forward emits non-existent wall-clock slot, fall-back repeats an hour. Non-issue for IST venues; latent 1h skew for DST-observing venue TZ (`AvailabilityService.java:466-487`).

## 2. Slot holds
- CONFIRMED — `SlotHold` has `@Version` optimistic lock + unique `hold_token`; creation takes SAME `pg_advisory_xact_lock(binge,date)` as booking, caps active holds/customer, defers conflict to `assertSlotAvailableForHold` (`SlotHoldService.java:74-136`).
- CONFIRMED — expiry: `SlotHoldExpiryScheduler` every 60s under ShedLock flips ACTIVE→EXPIRED; daily purge; idempotent version-guarded release.
- CONFIRMED — NO unique constraint preventing two holds on same slot (only hold_token unique); prevented only by advisory lock + count.
- **CONFIRMED CRITICAL — slot-hold → booking hand-off is DEAD CODE.** `consumeHold(...)` (`:206-246`) and `releaseQuietly(...)` (`:255-270`) are NEVER called in production. `CreateBookingRequest` has NO `holdToken` field. `createBooking` never references a hold. Consequence: createBooking conflict/capacity checks consider ONLY bookings, never live holds → a customer who skips the hold and POSTs createBooking directly can take a slot another customer "holds"; hold-holder then rejected at their own createBooking. `SlotHold` Javadoc guarantee "guaranteed against concurrent bookings" NOT upheld. Holds only serialize hold-vs-hold, always expire by TTL (never CONVERTED). Not a physical double-booking hole (see §3), but hold integrity is illusory.

## 3. Double-booking prevention (CRITICAL) — corroborated by live DB introspection
- CONFIRMED — mechanism is PostgreSQL transaction-scoped advisory lock, NOT a DB unique constraint. `pg_advisory_xact_lock(:lockKey)` (`BookingRepository.java:419-420`) keyed `(bingeId<<32 | epochDay)` (`slotLockKey:4317-4321`) serializes every booking mutation for a venue+day. Availability check + per-customer checks run BEFORE lock (fast-fail); authoritative conflict/room decision runs AFTER lock, before INSERT → check-then-write atomic within lock. create: lock `:261`, `hasTimeConflict` `:271`, `countOverlappingBookings` `:282`, `resolveRoomAssignment`/`countRoomBookings` `:348`/`:2520-2560`.
- CONFIRMED — no unique index backstops the slot (only `idx_booking_ref`). **Live DB confirms:** `bookings` unique indexes = `bookings_pkey`, `idx_booking_ref` only; `slot_holds` = `slot_holds_pkey`, `idx_slot_holds_token` only (docs/audit/evidence/db-introspection). Integrity depends entirely on advisory lock on every write path + single writable DB.
- HIGH CONFIDENCE — advisory lock coverage broad: create/update/reschedule/recurring/admin-create/transfer-accept/check-in/waitlist-promote/hold-create all use identical key. Conflict queries also `@Lock(PESSIMISTIC_WRITE)`.
- CONFIRMED — physical double-booking of a single room/slot IS prevented (second sees `countRoomBookings>=capacity`).
- HIGH CONFIDENCE — residual TOCTOU: `existsPendingDuplicate` (`:221-226`) + unpaid-count guard (`:169-176`) run BEFORE the lock and NOT re-checked under it. For a MULTI-ROOM venue, two concurrent same-customer submits WITHOUT `Idempotency-Key` can both pass pre-lock checks, get different rooms → same customer, two PENDING for same slot. Room-less venues safe.
- QUESTION — createBooking relies on availability `/internal/check` which can serve stale "available" from fallback during outage; safe for double-booking (advisory lock authoritative), availability advisory only.

## 4. Inventory decrement/restoration
- CONFIRMED — no stored counter; availability + add-on stock computed by live COUNT/SUM. Add-on `stockPerDay` enforced by summing booked qty across PENDING/CONFIRMED/CHECKED_IN/COMPLETED (`enforceAddOnAvailability:3457-3492`); cancel auto-restores (drops from SUM). Add-on decrement inside locked section.
- CONFIRMED — collectedAmount restored transactionally on cancel (`subtractFromCollectedAmount:1972-1977`); loyalty reversal AFTER_COMMIT.

## 5. Booking state machine
- CONFIRMED — states PENDING/CONFIRMED/CHECKED_IN/COMPLETED/CANCELLED/NO_SHOW. Single authority `BookingStateMachine`, actor-role-aware, audit-by-construction, idempotent for Kafka replays (`:184-223,350-365`).
- CONFIRMED — transitions table `:111-158`; terminal states only via SUPER_ADMIN override whitelist. PENDING→CONFIRMED only on FULL payment SUCCESS (`updatePaymentStatus:2008-2016`); partial stays PENDING.
- CONFIRMED — `NoShowAutomationScheduler` every 15min under ShedLock, marks NO_SHOW past venue-local midpoint, per-venue zone, idempotent.

## 6. Saga / transactional outbox
- CONFIRMED — genuine transactional outbox, no dual-write. `BookingEventPublisher.publish` `@Transactional(MANDATORY)` writes OutboxEvent in same tx (`:65-98`); outbox-write failure rolls back domain. `KafkaTemplate` in BookingService never used for direct send (no dual-write). `OutboxEvent` UNIQUE(event_id) (live DB: `ux_outbox_event_id`).
- CONFIRMED — `OutboxPublisher` drains every 2s under ShedLock, at-least-once; MAX_ATTEMPTS=10 → failedPermanent, except serializer/class-cast bugs keep retrying. Consumers dedupe on eventId.

## 7. Payment → booking confirmation
- CONFIRMED — idempotent via `ProcessedEvent` dedup + DB-unique backstop. Natural key `PAYMENT_SUCCESS:<ref>:<txnId>`, early-return on `existsByEventKey`, writes ProcessedEvent in same tx (`PaymentEventListener.java:41-46,275-285`). `processed_event.event_key` uniquely indexed (live DB: `idx_pe_event_key`).
- CONFIRMED — out-of-order handled: late success on terminal booking records money, sets PARTIALLY_PAID/SUCCESS WITHOUT touching lifecycle, flags MANUAL_REVIEW_FLAGGED, does not advance saga (`:73-123`). `onPaymentRefunded` uses net collected-vs-total (order-independent).
- HIGH CONFIDENCE — same bookingRef key → same partition → sequential; collected-amount update atomic.

## 8. Waitlist
- CONFIRMED — promotion serialized against booking on same advisory key. `WaitlistPromotionListener` consumes BOOKING_CANCELLED, dedups `WAITLIST_PROMOTION:<ref>`, rethrows for DLT. `promoteWaitlistOnCancellation` takes `acquireSlotLock` (`WaitlistService.java:254`), re-validates availability, offers one at a time with expiry.
- HIGH CONFIDENCE — an OFFER does NOT reserve the slot; can be beaten by a direct booking (not a double-book, but "promised" slot not guaranteed). QUESTION: confirm `markEntryConverted` (`:327-338`) is actually called from booking path — not observed.

## Failure-scenario matrix (condensed)
1. Double-click WITH Idempotency-Key: cached response by (key,method,path,userId)+SHA-256; but two SIMULTANEOUS same-key both miss uncommitted row → both run createBooking, one wins, other stopped by advisory-lock/conflict → error not cached copy. Integrity safe, idempotent UX imperfect under true simultaneity.
2. Double-click WITHOUT key, room-less: post-lock hasTimeConflict rejects 2nd. SAFE.
3. Double-click WITHOUT key, MULTI-ROOM, same customer: pre-lock checks TOCTOU → two PENDING same slot different rooms. **NOT SAFE (duplicate-for-same-customer gap).**
4. Two customers same explicit room+slot: serialized, 2nd rejected. SAFE.
5. Hold expires during payment: booking still confirms (holds never consulted); hold guarantee illusory. Integrity safe.
6. Hold by A, B books directly: B ignores A's hold; A fails later. Integrity safe, hold not honored.
7. Cancel races reschedule/promotion: same advisory lock; no overlap. SAFE (offer not guaranteed).
8. Payment event twice: ProcessedEvent dedup + unique index; no-op. SAFE.
9. Payment out of order (success after cancel): money recorded, lifecycle untouched, MANUAL_REVIEW_FLAGGED. SAFE (flagged for ops).
10. DB commit ok but Kafka publish fails: transactional outbox retries; at-least-once. SAFE.
11. Pending abandoned: `PendingBookingTimeoutScheduler` every 5min auto-cancels PENDING>30min. SAFE.

## Follow-ups
1. Decide slot-hold contract: wire consumeHold into createBooking (add holdToken to CreateBookingRequest, count foreign live holds) OR delete hold machinery + docs/UI claims. Currently neither enforced nor removed.
2. Re-check existsPendingDuplicate + unpaid-limit AFTER advisory lock (close multi-room same-customer window) OR partial unique index `(customer_id,event_type_id,booking_date,start_time) WHERE status='PENDING'`.
3. Add schema backstop for physical exclusivity (Postgres EXCLUDE/partial-unique on room+date+window for capacity-1 rooms).
4. Verify `markEntryConverted` is called from booking path.
5. Confirm DST behavior for non-IST venue timezone.
