# Specialist Investigation 04 — Database & Data Integrity (read-only)

Agent completed 2026-07-11 (opus classifier unavailable at review; reconciled by lead + corroborated by live DB introspection). Static analysis; several items now CONFIRMED against live DB (see runtime log). Evidence, not final conclusion.

## 1. Entity ↔ migration drift
- `ddl-auto=validate` all four JPA services (`config-server/.../*.yml`), so `@Index` in `@Table` are inert; length/nullability drift weakly checked.
- NOT VERIFIED / no gross drift in sampled high-value tables (Booking, SlotHold, Payment, Refund, TaxRule, SurgePricingRule, LoyaltyLedgerEntry map to DDL).
- CONFIRMED resolved drift: `Booking.customerPhone` length=20 vs V1 VARCHAR(15), widened by V31.
- CONFIRMED cosmetic drift: entity `@Index` names never match migration index names (harmless under validate, misleading).
- QUESTION: money scale inconsistent — bookings NUMERIC(10,2)/(12,2) vs snapshots/invoices/ledgers NUMERIC(14,4) (`V39`), FX NUMERIC(18,8) vs (20,10). Cross-table reconciliation → sub-cent rounding deltas.

## 2. @Lob-on-TEXT bug class
- CONFIRMED CLEAN — zero active `@Lob`; two javadoc blocks document its removal (`SiteContent.java:21-25`, `BingeSiteContent.java:34-37`). Large TEXT uses `@Column(columnDefinition="TEXT")`. Bug class in institutional memory, not in code.

## 3. Constraints for integrity
- CONFIRMED — NO DB-level double-booking prevention. `bookings` no UNIQUE/EXCLUSION on slot tuple (live DB: only `bookings_pkey`+`idx_booking_ref`). `slot_holds` no unique on slot (live DB: only pkey+token). Prevented entirely in app code.
- CONFIRMED — good webhook/idempotency dedup: `payments.transaction_id` UNIQUE (live: `idx_payment_transaction_id`); `processed_webhook_event` PK `(event_id,provider)` (live confirmed); Stripe-style `idempotency_key` table (live: unique composite).
- HIGH CONFIDENCE — double/over-refund NOT DB-enforced. `refunds.gateway_refund_id` only NON-unique index (`V1:41`); no CHECK amount>0; no constraint SUM(refunds)≤payment.amount. Over-refund protection app-only.
- PROBABLE — no unique on `gateway_payment_id`/`gateway_order_id` in payments; relies on webhook dedup + transaction_id.
- QUESTION — idempotency_key PK includes nullable user_id (Postgres forces PK NOT NULL → unauthenticated monetary POST would fail to record; latent since payment endpoints authed).
- CONFIRMED strong where present: loyalty `uq_ledger_idempotency`, `ck_balance_non_negative`, lot CHECKs (`V21`); slot_holds `chk_slot_hold_status`+`chk_slot_hold_duration` (`V40`); immutability triggers on price_snapshots + ledger_entries (`V39`).

## 4. Money & precision
- CONFIRMED CLEAN — no money as float. All monetary columns NUMERIC/DECIMAL (live DB confirms bookings numeric(10,2)/(12,2)/(14,2), fx_rate numeric(18,8)). Tax rate as integer basis points (`TaxRule.rateBps`). Loyalty points BIGINT. Only DOUBLE PRECISION = binges lat/long geo + DTO-only rating/distance.
- QUESTION — NUMERIC(10,2) caps single amount at 99,999,999.99; note for high-inflation currencies now that binges are multi-currency (V68).

## 5. Timezone storage
- CONFIRMED — systemic naive timestamps. Zero `timestamptz`/`WITH TIME ZONE` in all SQL. bookings.booking_date DATE + start_time TIME; slot_holds.expires_at TIMESTAMP. `V21:27-28` documents "TIMESTAMP WITHOUT TZ stored in UTC." (Live DB confirms bookings timestamps `without time zone`.)
- PROBABLE — fragile venue-TZ pattern; correctness depends on app applying `Binge.timezone` to naive local times. Any `LocalDateTime.now()` vs server clock is a latent bug.

## 6. Indexes
- CONFIRMED — hot paths indexed via migrations: bookings FK/date/status/composite (`V1,V15,V62`); partial index on outbox poller; loyalty partial indexes; payment binge_id.
- PROBABLE — redundant single-column indexes on bookings (write amplification): `idx_booking_date` prefix of `idx_bookings_date_status`; `idx_booking_customer` covered by others; `idx_booking_binge_date` prefix of composite. ≥3 redundant.
- QUESTION — booking_event_log/ledgers reference bookings by booking_ref string, no FK (intentional append-only decoupling).

## 7. Migration safety
- CONFIRMED — V60s–V74 all additive/safe (no DROP/DELETE/TRUNCATE; ADD COLUMN IF NOT EXISTS).
- CONFIRMED — earlier destructive migrations are intentional cutovers with backfills: `V8` DELETE bookings WHERE binge_id IS NULL (real data loss, pre-tenant cleanup); `V17`/`V28` loyalty v1 table drops (after v2 backfill). Ordering coherent.
- CONFIRMED — good lock hygiene: `V23` ADD CONSTRAINT NOT VALID then VALIDATE + IF NOT EXISTS; `V39` FK adds wrapped in existence checks; `V62` documents deliberate non-CONCURRENTLY.
- PROBABLE — non-idempotent seed: `V21:549-565` bare INSERT loyalty_program (safe under Flyway once).
- QUESTION — `V17`/`V8` deletes have no evidence of pre-delete archival.

## 8. Cross-service data duplication
- CONFIRMED — clean DB-per-service (auth_db, availability_db, payment_db, booking_db, Mongo notification_db; live DB confirms 4 PG DBs). No cross-DB reads.
- CONFIRMED — binge_id shared by value, no cross-DB FK (correct); binges table only in booking_db; others store bare BIGINT. IDs stable BIGSERIAL.
- PROBABLE — customer PII denormalized/duplicated across bookings, payments, Mongo notifications with no propagation on update/anonymization.
- QUESTION — availability granularity mismatch: blocked_slots whole-hour vs bookings 30-min → half-hour booking could slip inside partially-blocked hour.

## 9. Soft-delete, audit, retention
- CONFIRMED — strong on auth PII: users get deleted_at/anonymized_at/deletion_requested_at/consent/data_retention_expires_at (`V14`) — DPDP/GDPR story.
- CONFIRMED — good audit/immutability: created/updated everywhere; append-only trails (booking_event_log, payment audit_log, loyalty_membership_event); DB-enforced immutable ledgers via triggers; webhook dedup permanent.
- HIGH CONFIDENCE — retention/PII gaps in booking & payment: bookings + payments hold name/email/phone with NO soft-delete, NO retention column, NO link to auth anonymization. When auth anonymizes a user, PII copies in booking_db/payment_db persist → erasure incomplete cross-service.
- **HIGH CONFIDENCE — Mongo indexes/TTL likely NOT applied.** notification-service.yml does not set `spring.data.mongodb.auto-index-creation`; no auto-index-creation/ensureIndex anywhere. Spring Boot 3 default is FALSE → annotations inert:
  - `Notification` `@Indexed(expireAfter="P90D")` TTL NOT created → notifications never auto-expire → unbounded growth + recipientEmail/phone PII forever (contradicts 90-day comment).
  - `BookingReminder` `@CompoundIndex(unique)(bookingRef,reminderType)` not enforced → duplicate reminders/double-sends possible.
  - PushSubscription/NotificationPreference/Template unique indexes similarly unenforced.
  - **NOTE: needs live Mongo verification (`db.notifications.getIndexes()`) — flagged for runtime log.**

## Contradictions
- Comment vs reality (Mongo TTL/dedup won't apply with auto-index off).
- FK asymmetry: V23 added binge FKs to 5 child tables, yet flagship `bookings.binge_id` (nullable) still has no FK.
- Rigor asymmetry: loyalty v2 + V39 finance exemplary (immutable ledgers, CHECKs, unique idempotency) vs core booking/refund money path leans entirely on app logic.
- `loyalty_program.allow_negative_balance` toggle contradicted by hard `ck_balance_non_negative` wallet CHECK.

## Follow-ups (priority)
1. Live-DB confirm Mongo indexes/TTL (notifications PII unbounded, reminders double-send if absent).
2. Confirm app double-booking guard airtight (no DB backstop); consider Postgres EXCLUDE/partial-unique.
3. Add unique on refunds.gateway_refund_id + over-refund CHECK.
4. Decide retention/anonymization for booking_db/payment_db PII (cross-service erasure).
5. Read unopened finance entities (Invoice/InvoiceLine/LedgerEntry/CreditNote/CurrencyRate/FxRateLock) + V38,V51-53 for precision consistency.
6. Evaluate dropping redundant bookings single-column indexes.
7. Resolve idempotency_key nullable-user_id-in-PK and nullable-key-in-unique.
