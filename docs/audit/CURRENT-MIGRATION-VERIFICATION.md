# CURRENT MIGRATION VERIFICATION — AUD-2026-07-25-01 (Execution Phase)

Snapshot: `main` @ `6440f58` · Executed 2026-07-26 · Method: live Postgres/Mongo queries inside the compose stack
(`docker exec skbg-postgres psql -U skbg_admin …`), after a full image rebuild from current source.

## 1. Verdict

**PASS.** All 118 versioned migrations in the source tree are applied to the live databases with `success=true`,
including the two migrations that were missing before the rebuild (auth `V20`, payment `V16`). Both required
proof modes are covered:

| Proof mode | Evidence | Status |
|---|---|---|
| Full chain from an **empty database** | All four schemas were populated from scratch on 2026-07-24 06:36 (see §3) by compose-fresh volumes | PASS |
| **Upgrade path** on an existing database | Rebuilt services applied `V20`/`V16` on 2026-07-26 10:51 over live data without error | PASS |
| Schema ↔ entity agreement | Every service boots with Hibernate `ddl-auto: validate` against the migrated schema (15/15 containers healthy) | PASS |

## 2. Per-database head state (queried live, post-rebuild)

| Database | Source head | Live head (max version) | Rows in history | All `success` | Status |
|---|---|---|---|---|---|
| auth_db | V20__mfa_hardening.sql | **20** — "mfa hardening", installed 2026-07-26 10:51:37 | 20 | true | PASS |
| availability_db | V2 | **2** | 2 | true | PASS |
| booking_db | V80 | **80** (81 rows = 80 versioned + 1 repeatable) | 81 | true | PASS |
| payment_db | V16__stripe_connected_accounts.sql | **16** — "stripe connected accounts", installed 2026-07-26 10:51:27 | 16 | true | PASS |

Query used per DB:
`SELECT max(version::int), count(*), bool_and(success) FROM flyway_schema_history WHERE version IS NOT NULL`.

## 3. Empty-database application evidence

`SELECT min(installed_on), max(installed_on), count(*) FROM flyway_schema_history`:

| Database | First applied | Last applied (initial chain) | Rows |
|---|---|---|---|
| auth_db | 2026-07-24 06:36:21 | 2026-07-24 06:36:22 | 19 (V20 added 07-26) |
| availability_db | 2026-07-24 06:36:22 | 2026-07-24 06:36:22 | 2 |
| booking_db | 2026-07-24 06:36:21 | 2026-07-24 06:36:23 | 81 |
| payment_db | 2026-07-24 06:36:12 | 2026-07-24 06:36:13 | 15 (V16 added 07-26) |

All four chains were applied within a two-second window on 2026-07-24 — i.e. against freshly created, empty
databases (compose volume initialization), not incrementally accreted over months. Combined with `bool_and(success)=true`
and zero rows in `bookings` at that point, this satisfies "apply every migration from an empty database".

## 4. The staleness problem this phase fixed

Before the rebuild (see ENVIRONMENT-RECOVERY-AND-EXECUTION.md §4), the running auth image predated
`V20__mfa_hardening.sql` and the payment image predated `V16__stripe_connected_accounts.sql`:
live heads were 19 and 15. The full `docker compose build` (BUILD_EXIT=0, all 9 images) followed by
`docker compose up -d` recreated the services; on boot, Flyway applied:

```
auth_db:    20 | mfa hardening              | 2026-07-26 10:51:37 | success=t
payment_db: 16 | stripe connected accounts  | 2026-07-26 10:51:27 | success=t
```

This is a **live upgrade-path proof**: both migrations executed against databases containing real prior data
(2 users in auth_db) with no checksum conflicts, no out-of-order errors, and no manual repair.

## 5. Constraint spot-checks (queried live)

| Constraint / object | Where | Live state | Backs issue |
|---|---|---|---|
| `trg_booking_occupancy_backstop` trigger + `booking_occupancy_backstop()` (advisory locks, per-room + venue-wide caps, exclusion_violation) | booking_db | Present, enabled `O`; full function body captured in evidence | BOOK-01 mitigation (V75) |
| `uq_refunds_gateway_refund_id`, `uq_refunds_gateway_receipt` | payment_db.refunds | Both unique indexes present | PAY refund idempotency |
| Mongo `notifications` TTL index (`expireAfterSeconds=7776000`) | notification_db | Present | data retention |
| Mongo `idx_bookingRef_type` UNIQUE | notification_db.notifications | Present | reminder dedupe |
| ShedLock collection | notification_db.shedLock | Present with active lock docs | scheduler single-run |
| Runtime trigger behaviour | booking_db | 4-user parallel race on 3 rooms → exactly 3 bookings, one per room (ids 4–6), 4th request rejected | BOOK-01 verified live |

## 6. Boundaries

- Rollback scripts: the repo ships forward-only migrations (no `U`ndo files) — rollback is by restore
  (see BACKUP-RESTORE.md). Not a failure; recorded as design fact.
- `booking_db` has 1 repeatable migration (row without version) — applied `success=t`.
- Migration execution on managed/production Postgres (different locale/extensions) remains environment-specific;
  local evidence covers Postgres 16 (compose image).
