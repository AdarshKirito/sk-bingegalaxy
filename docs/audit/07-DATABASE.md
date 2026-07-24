# 07 — Database & Data Integrity

Evidence: `evidence/specialist-04-database-integrity.md` + live `psql`/`mongosh` introspection (`21-RUNTIME-VERIFICATION-LOG.md` R2/R5).

> **Companions (full column-level catalogs, live-introspected 2026-07-12):** [07a-DATABASE-CATALOG.md](07a-DATABASE-CATALOG.md) — every Postgres table (90 domain tables, 1,218 columns, PK/UQ/FK) across the 4 DBs; [07b-MONGO-CATALOG.md](07b-MONGO-CATALOG.md) — `notification_db` collections + fields + indexes. Raw data: `evidence/db-columns.tsv`, `evidence/db-constraints.tsv`. This doc is the *analysis*; the catalogs are the *inventory*.

## Topology (CONFIRMED clean DB-per-service)

| Datastore | Owner | Migrations | Notes |
|---|---|---|---|
| `auth_db` (Postgres) | auth-service | Flyway V1–V19 | users, sessions, tokens, authority grants, audit |
| `availability_db` (Postgres) | availability-service | V1–V2 | blocked_dates, blocked_slots |
| `booking_db` (Postgres) | booking-service | V1–V74 (70 tables live) | bookings, binges, pricing/tax/FX, loyalty, outbox/saga |
| `payment_db` (Postgres) | payment-service | V1–V13 | payments, refunds, disputes, webhook dedup, approvals |
| `notification_db` (Mongo) | notification-service | none (annotations) | notifications, reminders, subscriptions, templates |

`ddl-auto=validate` everywhere (Hibernate validates, never creates). No cross-DB reads; `binge_id` is shared by value (bare BIGINT, no cross-DB FK) — correct for microservices. IDs are stable BIGSERIAL from booking_db.

## Integrity constraints (live-confirmed)

**Well protected (unique indexes present):**
- Payment dedup: `payments.transaction_id` UNIQUE; `processed_webhook_event(event_id, provider)` UNIQUE; `idempotency_key` composite UNIQUE (both booking & payment DBs).
- Event dedup: `processed_event.event_key` UNIQUE; `outbox_event.event_id` UNIQUE.
- Transfers: partial UNIQUE `booking_ref WHERE status='PENDING'`.
- Loyalty/finance: `uq_ledger_idempotency`, `ck_balance_non_negative`, lot CHECKs, immutability triggers on `booking_price_snapshots` + `ledger_entries` (`V39`), slot-hold status/duration CHECKs (`V40`).

**Missing backstops (issues):**
- **DATA-001 (High):** no UNIQUE/EXCLUSION on the booking slot tuple; `bookings` has only `bookings_pkey` + `idx_booking_ref` (live-confirmed). `slot_holds` has no slot uniqueness. Double-booking is app-logic-only (advisory lock).
- **DATA-002 (High):** `refunds` has no unique on `gateway_refund_id`, no `amount>0` CHECK, no `SUM(refunds)≤paid` constraint.
- **DATA-003 (High):** Mongo TTL + unique indexes inert (auto-index-creation off).

## Money & precision (CONFIRMED clean)

All monetary columns are `NUMERIC` (live: bookings `numeric(10,2)/(12,2)/(14,2)`, `fx_rate numeric(18,8)`, `surge_multiplier numeric(5,2)`). Tax rate stored as integer basis points (`TaxRule.rateBps`); loyalty points BIGINT. Only DOUBLE PRECISION columns are geo lat/long. **DATA-006 (Low):** scale differs across finance tables (10,2 vs 14,4 vs FX 18,8 vs 20,10) → potential sub-cent reconciliation deltas.

## Timezone (CONFIRMED naive)

Zero `timestamptz` in any schema; `bookings.booking_date DATE` + `start_time TIME`, all timestamps `without time zone` (live-confirmed). Convention (`V21:27-28`): "TIMESTAMP WITHOUT TZ stored in UTC." Correctness depends on the app applying `Binge.timezone` — robust for the IST venue, latent DST risk for the two `America/Chicago` binges (BOOK-003).

## Indexes

Hot paths well-indexed via migrations (`V1,V15,V62` composites on binge/date/status/customer; partial index on the outbox poller; loyalty partial indexes). **DATA-007 (Low):** three redundant single-column `bookings` indexes (prefixes of composites) add write cost.

## `@Lob`-on-TEXT bug class — CONFIRMED CLEAN

Zero active `@Lob`; two javadoc blocks document its removal. Large TEXT uses `@Column(columnDefinition="TEXT")`. The historically-painful bug is not present.

## Migration safety

Recent V60–V74 all additive (`ADD COLUMN IF NOT EXISTS`, no DROP/DELETE/TRUNCATE). Earlier destructive migrations (`V8` deletes null-binge bookings; `V17`/`V28` drop loyalty-v1 tables) are intentional cutovers with backfills, ordered coherently. Good lock hygiene (`V23` NOT VALID→VALIDATE; `V39` guarded FK adds). A CI gate (`scripts/check-migration-safety.sh`) rejects unguarded DROP/TRUNCATE/DELETE.

## Cross-service PII

**DATA-004 (High):** customer name/email/phone are denormalized into `bookings`, `payments`, and Mongo `notifications` with no soft-delete, no retention column, and no propagation from auth's anonymization → erasure is incomplete cross-service.

## FK asymmetry (note)

`V23` added binge FKs to five child tables, yet the flagship `bookings.binge_id` (nullable) still has no FK — the highest-value table is the least constrained. Consider adding it once null-binge rows are confirmed gone.
