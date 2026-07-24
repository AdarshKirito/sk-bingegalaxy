# 00 — Audit Index

> **Superseded as the current front door:** use [`../00-AUDIT-INDEX.md`](../00-AUDIT-INDEX.md). This file preserves the original audit's scope, counts and tool/runtime record; its V74/V13, 424-endpoint, 1,336-file and 38-active-issue statements are historical.

Entry point and map for the SK Binge Galaxy forensic production-readiness audit.

## Purpose

An evidence-based, repository-wide audit of SK Binge Galaxy (a private venue/event-booking platform: React 18 PWA + 8 Spring Boot microservices + `common-lib`, on PostgreSQL ×4 / MongoDB / Redis / Kafka). The deliverable is a canonical Markdown set a new senior team can use to understand, operate, remediate, and safely extend the system, and to judge production-readiness. **Audit and documentation only — no application source/config/migration/infra/test changes** (Rule 1).

## Commit audited

- Repository root: `d:\sk-binge-galaxy\sk-binge-galaxy` · Branch: `main` · Commit: `e3edbc1b5b11c11b7826ae18e2f8246aec076a9f`
- Working tree carries a large **uncommitted July overhaul** (sessions/loyalty/tax/surge/FX/push; migrations up to V74). The audit reads the working tree as current truth and never reverts pre-existing user changes (Rule 2). Line numbers cite that working tree.

## Scope

In scope: every backend service + `common-lib`, the frontend SPA, all databases/migrations, Kafka topics/consumers, security/RBAC/tenant-isolation, booking/pricing/payment/refund/dispute workflows, operational admin modules, integrations, devops/reliability, performance (static), tests (static), and documentation-to-code consistency. Runtime verification was performed against the local Docker stack where the harness allowed (auth, isolation, double-booking, Mongo indexes, event backbone, FX/native-currency). Out of scope: application code fixes; anything requiring capabilities absent on the host (see Limitations).

## Methodology

Master-prompt phases 0–6: safety/git-state capture → repository census → existing-docs audit + safe archive → specialist investigations (reconciled by the lead; subagent output treated as evidence, not conclusion) → cross-layer reconciliation → runtime verification → independent verification. Every conclusion carries a confidence label and `path:line` (or a logged command / DB query) as evidence (Rule 4). Positive controls are recorded alongside defects so a reader does not re-investigate them.

## Tool availability (recorded honestly — Rule; "never claim a tool not available")

| Capability | Status |
|---|---|
| Docker + Compose | Available; 15-service stack healthy — used for runtime checks |
| PostgreSQL / MongoDB CLI (via `docker exec`) | Available — used (read-only + approved test writes) |
| Web search | Available |
| JDK / Maven | **Not on host** — backend tests not executed |
| Node / npm | **Not on host** — frontend tests/build not executed |
| k6 / load tooling | **Not on host** — no new load test |
| Browser automation / Playwright runner | **Not available** — no visual/keyboard/contrast verification |
| Model / effort | Session began under `claude-fable-5`; continued under Opus 4.8. Effort setting not exposed → "not visible". |

## Document map

| Doc | Contents |
|---|---|
| [00-AUDIT-INDEX](00-AUDIT-INDEX.md) | this file |
| [00-EXECUTIVE-SUMMARY](00-EXECUTIVE-SUMMARY.md) | verdict, top risks, maturity, limitations |
| [01-PRODUCT-AND-DOMAIN](01-PRODUCT-AND-DOMAIN.md) | implemented domain model + admin hierarchy vs intended |
| [02-REPOSITORY-INVENTORY](02-REPOSITORY-INVENTORY.md) | what exists: apps/services/langs/versions/DBs/queues/tests |
| [02-ARCHITECTURE](02-ARCHITECTURE.md) | how it fits: topology, patterns, ownership |
| [03-SERVICE-DEPENDENCY-MAP](03-SERVICE-DEPENDENCY-MAP.md) | dependency edges + blast-radius per service |
| [04-FRONTEND](04-FRONTEND.md) | routes, components, forms, state/PWA, a11y |
| [05-BACKEND-SERVICES](05-BACKEND-SERVICES.md) | per-service deep audit |
| [06-API-CONTRACTS](06-API-CONTRACTS.md) | endpoints, auth/scoping, method-level field-drift diff |
| [06a-ENDPOINT-CATALOG](06a-ENDPOINT-CATALOG.md) | all 424 backend endpoints (method/path/controller/tier/src) |
| [07-DATABASE](07-DATABASE.md) | per-DB/table, migrations, integrity (analysis) |
| [07a-DATABASE-CATALOG](07a-DATABASE-CATALOG.md) | every Postgres column (90 tables, 1,218 cols, keys) — live introspection |
| [07b-MONGO-CATALOG](07b-MONGO-CATALOG.md) | Mongo `notification_db` collections/fields/indexes — live introspection |
| [08-EVENTS-AND-MESSAGING](08-EVENTS-AND-MESSAGING.md) | topics, producer/consumer, outbox/DLQ, notifications |
| [09-SECURITY-RBAC-PRIVACY](09-SECURITY-RBAC-PRIVACY.md) | permission matrix, isolation trace, secrets/privacy |
| [10-WORKFLOWS](10-WORKFLOWS.md) | end-to-end traces + failure-scenario matrix |
| [11-OPERATIONAL-MODULES](11-OPERATIONAL-MODULES.md) | each admin module classified with data-path verification |
| [12-INTEGRATIONS-AND-OTA-READINESS](12-INTEGRATIONS-AND-OTA-READINESS.md) | providers + OTA/channel readiness |
| [13-DEVOPS-RELIABILITY](13-DEVOPS-RELIABILITY.md) | compose/k8s/CI, observability, operator recovery |
| [14-PERFORMANCE-CONCURRENCY](14-PERFORMANCE-CONCURRENCY.md) | contention analysis + required load tests |
| [15-TESTING-QUALITY](15-TESTING-QUALITY.md) | test inventory + missing-test matrix |
| [16-TRACEABILITY-MATRIX](16-TRACEABILITY-MATRIX.md) | capability→role→route→API→service→DB→event→tests→issues |
| [17-ISSUE-REGISTER](17-ISSUE-REGISTER.md) | canonical issues (full template) |
| [18-REMEDIATION-ROADMAP](18-REMEDIATION-ROADMAP.md) | P0→P4 sequenced plan |
| [19-COVERAGE-MANIFEST](19-COVERAGE-MANIFEST.md) | per-area Level A/B/C coverage + what's NOT verified |
| [20-CONTRADICTION-REGISTER](20-CONTRADICTION-REGISTER.md) | docs-vs-code contradictions |
| [21-RUNTIME-VERIFICATION-LOG](21-RUNTIME-VERIFICATION-LOG.md) | every command/query run, with output |
| [../AUDIT_STATUS.md](../AUDIT_STATUS.md) | canonical restart/handoff record |
| `evidence/specialist-01..05-*.md`, `evidence/*.tsv/.txt` | raw specialist findings + census artifacts |

## Key counts

- First-party files (coverage baseline): **1,336** · Backend Java: ~698 (75 test classes) · Frontend: ~157 JSX/TS + ~87 JS services/utils.
- Services: **8 runtime + common-lib** · Controllers: ~48 · JPA/Mongo objects: auth 10, availability 2, booking 66, payment 9, notification 6.
- Migrations: auth V1–19, availability V1–2, booking V1–74, payment V1–13 (Mongo: none).
- Kafka topics: 20 · Frontend routes: 70 · Endpoint calls: ~369 · Tests: 75 backend + 40 Vitest + 7 Playwright.
- **Issues: 38 active** (Critical 2 · High 8 · Medium 15 · Low 12 · Informational 1) + 1 resolved (PAY-001). Full list in [17-ISSUE-REGISTER](17-ISSUE-REGISTER.md).

## Confidence definitions (Rule 5)

- **CONFIRMED** — direct evidence in source/config/schema/runtime/test/DB.
- **HIGH CONFIDENCE** — strong evidence, full runtime path not executed.
- **PROBABLE** — evidence suggests it, but runtime/data/creds/infra unavailable.
- **QUESTION** — correct behaviour depends on an underivable product decision.
- **NOT VERIFIED** — area could not be inspected or exercised.

## Severity definitions

- **Critical** — cross-binge access, credential/data/financial exposure, double-booking, irrecoverable inconsistency, platform outage, silent financial mismatch.
- **High** — major workflow/security/financial failure or likely production incident without an acceptable workaround.
- **Medium** — meaningful defect or maintainability/reliability problem with a workaround or limited scope.
- **Low** — minor/localized defect or improvement.
- **Informational** — observation/optional enhancement with no current harm.

## Priority definitions

- **P0** — block launch (fix first). · **P1** — before real customers / money at scale. · **P2** — hardening & correctness. · **P3** — polish / low. · **P4** — informational.

## Limitations (honest — Rule 6)

Uniform Level-A coverage of the whole repository was **not** achieved. Deep (Level A) coverage of highest-risk areas (auth, isolation, booking concurrency, DB integrity, payment/refund static, pricing/tax/FX) with runtime corroboration; structural (B) or census (C) elsewhere. Explicitly NOT done: full visual/keyboard/contrast/screen-reader UX grading (no browser automation), performance/load testing (no k6), backend/frontend test execution (no JDK/Node on host), the live Razorpay payment-confirm + refund end-to-end (super-admin MFA), the response-DTO→consumer diff across all 369 calls, and application code fixes (Rule 1). See [19-COVERAGE-MANIFEST](19-COVERAGE-MANIFEST.md) for the file-level accounting.

## How to resume

Read [../AUDIT_STATUS.md](../AUDIT_STATUS.md) — it holds the git state, tool matrix, phase tracker, continuation log (1–5), still-open list, last verified action, and exact next action. It is the canonical handoff record and is updated after every phase.

## How to repeat the audit

1. From repo root, capture `git status --porcelain` and `git rev-parse HEAD` (record initial state).
2. Bring up the stack (`docker compose up -d`) and confirm health.
3. Re-run the runtime checks in [21-RUNTIME-VERIFICATION-LOG](21-RUNTIME-VERIFICATION-LOG.md) (auth probes, DB introspection, event-pipeline check, FX/native-currency query).
4. Re-derive counts (services, controllers, migrations, routes, endpoint calls) and diff against Key counts above.
5. Walk the issue register top-down; re-verify each CONFIRMED claim's `path:line`.
6. Confirm the run stayed documentation-only: non-doc `git status` entries must be unchanged from the initial snapshot.
