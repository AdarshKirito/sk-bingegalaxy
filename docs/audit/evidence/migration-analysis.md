# Migration Analysis — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · 118 Flyway files reviewed statically

## Linearity & hygiene

- Version sequences are linear per service (filename scan; no duplicates, no gaps that Flyway would reject)
- Descriptive names throughout (`V75__room_occupancy_db_backstop.sql` style) ✅
- No `flyway.out-of-order` configured
- CI gate: [scripts/check-migration-safety.sh](../../../scripts/check-migration-safety.sh) wired at Jenkinsfile:197 — blocks destructive patterns pre-deploy ✅

## Destructive/one-way migrations (flagged)

| Migration | Risk | Note |
|---|---|---|
| booking V28 (drop loyalty v1 tables) | One-way; no rollback | Executed after V21/V22 backfill; legacy bindings preserved as ENABLED_LEGACY frozen — data preserved, schema not |
| booking V22 (backfill) | Data migration | Idempotent-style inserts (static read) |

## High-value integrity migrations

| Migration | What it guarantees |
|---|---|
| booking **V75** | DB-level occupancy backstop trigger — oversell impossible even if app code regresses (NO automated test — TEST-01) |
| payment **V14** | Partial UNIQUE (gateway_refund_id WHERE NOT NULL) — double-refund row structurally impossible |
| booking V80 | Loyalty config lock (single-row enforcement for country earn config) |
| auth V19-V20 | Authority grants + privacy/anonymization support columns |

## Rollback posture

- Flyway community edition (no undo scripts) — roll-forward strategy implied
- Backups: k8s CronJob daily 2AM/14 d + restore scripts — **restore never rehearsed** (DB-04)
- Recommendation embedded in remediation roadmap: pre-deploy snapshot + tested restore path per release

## Historical validation claims

CHANGELOG-2026-07-21: "migrations tested on real PG16" — **HISTORICAL** (pre-V78+). No Testcontainers-based migration test exists to make this continuous (TEST-01).

## ddl-auto honesty note

`spring.jpa.hibernate.ddl-auto` was **not found** explicitly pinned to `validate` in tracked service YAMLs, and compose sets no `SPRING_JPA_HIBERNATE_DDL_AUTO`. Spring Boot's default with Flyway present is typically `none` — safe — but this is **inferred, not proven** without a boot log. Register DB-02 requires explicit pinning to `validate` in every service config.
