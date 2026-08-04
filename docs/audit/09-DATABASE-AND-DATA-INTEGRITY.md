# 09 — Database and Data Integrity (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · static migration/entity analysis; **no live schema inspected** (no DB running)

## Migration inventory (VERIFIED-STATIC)

| Service | Head | Files | Notable recent |
|---|---|---:|---|
| auth-service | **V20** | 20 | authority grants, resource locks, privacy/anonymization support |
| availability-service | **V2** | 2 | stable |
| booking-service | **V80** | 80 | V21/V22 loyalty v2 schema+backfill, V28 drops loyalty v1, V56 room-selection flag, V71 module scoping, **V75 occupancy trigger backstop**, V77-V79 payment-methods-by-country era, V80 loyalty config lock |
| payment-service | **V16** | 16 | durable payment intents, V14 partial UNIQUE on `gateway_refund_id`, Stripe Connect accounts |
| infra | — | 1 | init-databases.sql (roles + DBs + ShedLock table) |

Total 118 Flyway files + init script = 119 SQL. Per-file catalog: [evidence/current-database-catalog.md](evidence/current-database-catalog.md); deep analysis: [evidence/migration-analysis.md](evidence/migration-analysis.md).

## Integrity mechanisms (VERIFIED-STATIC)

| Mechanism | Where | Evidence |
|---|---|---|
| **V75 trigger backstop** | booking_db | `V75__room_occupancy_db_backstop.sql` — DB-level oversell rejection independent of app code |
| Advisory locks | booking create | `pg_advisory_xact_lock` BookingRepository.java:433, invoked BookingService.java:261 |
| Optimistic versioning | SlotHold and money-bearing entities | `@Version` fields |
| Partial unique indexes | payment_db | V14 `gateway_refund_id` WHERE NOT NULL — double-refund row impossible |
| Outbox tables | booking_db, payment_db | OutboxEvent + relay |
| Idempotency | booking/payment | IdempotencyKey tables + ProcessedEvent dedup |
| ShedLock | booking_db | init-databases.sql L67-74 |
| Price snapshots | booking_db | BookingPriceSnapshot freezes money at booking time |

## Entity ↔ migration drift

93 entities/documents vs migrations — spot-diff in [evidence/entity-migration-diff.tsv](evidence/entity-migration-diff.tsv). No hard drift found in sampled high-risk tables (bookings, payments, refunds, loyalty wallets). **Caveat:** `spring.jpa.hibernate.ddl-auto=validate` could not be confirmed in tracked service YAMLs; compose does not set `SPRING_JPA_HIBERNATE_DDL_AUTO`. If any environment defaults to `update`, drift could silently accumulate — **register DB-02 (verify + pin `validate` everywhere)**. This is the honest static limit: validation would occur at boot, which this audit could not run.

## Migration safety

- [scripts/check-migration-safety.sh](../../scripts/check-migration-safety.sh) wired into Jenkinsfile L197 — blocks destructive patterns
- Historical claim (CHANGELOG-2026-07-21): migrations tested against real PostgreSQL 16 — **HISTORICAL**, not re-proven
- No `flyway.out-of-order`; versions linear per service (verified by filename sequence scan)
- V28 destructive drop of loyalty v1 tables was one-way — flagged in migration-analysis with rollback notes

## MongoDB (notification-service)

- TTL index 90 d on `Notification.createdAt` (VERIFIED-STATIC)
- Dedup collection with **1 h TTL window** — duplicate suppression only within an hour (register NOT-03)
- Anonymization consumer redacts PII on `user.anonymized`

## Data-integrity risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| DB-02 | P1 | `ddl-auto=validate` not provably pinned in every service/env |
| DB-03 | P2 | Zero Testcontainers tests: V75 trigger, advisory-lock paths and Flyway chains have **no automated DB-level test** (advisory locks are mocked in unit tests) |
| DB-04 | P2 | Restore procedures exist (scripts + k8s backups.yml, daily 2AM, 14 d retention) but **no restore rehearsal evidence** |
| EVT-02 | P2 | auth-service direct-publish (no outbox) for `user.anonymized` |
| NOT-03 | P3 | 1 h notification dedup window |
