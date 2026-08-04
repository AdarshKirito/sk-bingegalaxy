# SK Binge Galaxy — Forensic Production-Readiness Audit

> **Historical/detail set (July 11–12, commit e3edbc1).** ⚠️ Corrected 2026-07-25: this banner previously linked to `../23-ISSUE-REGISTER.md` and `../28-PRODUCTION-READINESS-CHECKLIST.md`, which **do not exist** (contradiction DOC-CR-07). The canonical current audit (2026-07-25, commit `6440f58`) is [00-AUDIT-INDEX.md](00-AUDIT-INDEX.md) in this folder; current register: [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md); readiness gates: [PRODUCTION-READINESS-CHECKLIST.md](PRODUCTION-READINESS-CHECKLIST.md). Counts/headline findings below describe the original July 11–12 audit; per-finding dispositions: [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md).

Canonical audit deliverable set. Produced 2026-07-11 against commit `e3edbc1` (`main`, including the uncommitted July overhaul). Method: static code inspection + parallel read-only specialist investigations + live runtime verification against the running Docker stack (dev data). **Audit and documentation only — no application code, config, schema, or infrastructure was changed.**

## Read in this order

1. **[00-AUDIT-INDEX.md](00-AUDIT-INDEX.md)** — map, methodology, tool availability, counts, how to resume/repeat.
2. **[00-EXECUTIVE-SUMMARY.md](00-EXECUTIVE-SUMMARY.md)** — verdict, top risks, what's working, coverage.
3. **[17-ISSUE-REGISTER.md](17-ISSUE-REGISTER.md)** — 38 active canonical issues (full field template) with severity, evidence, fixes.
4. **[18-REMEDIATION-ROADMAP.md](18-REMEDIATION-ROADMAP.md)** — P0→P4 sequenced plan.

## Full set

| Doc | Contents |
|---|---|
| 00-AUDIT-INDEX | Map, methodology, tool availability, counts, resume/repeat |
| 00-EXECUTIVE-SUMMARY | Verdict + top risks + coverage statement |
| 01-PRODUCT-AND-DOMAIN | Implemented domain model, admin hierarchy, product QUESTIONs |
| 02-REPOSITORY-INVENTORY | What exists: apps/services/langs/versions/DBs/queues/tests |
| 02-ARCHITECTURE | Verified architecture, services, ports, patterns |
| 03-SERVICE-DEPENDENCY-MAP | Dependency edges + blast-radius per service |
| 04-FRONTEND | Routes, components, state, PWA, UX/a11y coverage notes |
| 05-BACKEND-SERVICES | Per-service audit (9 modules) |
| 06-API-CONTRACTS | Endpoint surface, auth/scoping, contract drift, method-level diff |
| 06a-ENDPOINT-CATALOG | All 424 backend endpoints (method/path/controller/tier/src) |
| 07-DATABASE | Schemas, constraints, money/timezone, migrations, integrity (analysis) |
| 07a-DATABASE-CATALOG | Every Postgres column (90 tables · 1,218 cols · keys) — live introspection |
| 07b-MONGO-CATALOG | Mongo notification_db collections/fields/indexes — live introspection |
| 08-EVENTS-AND-MESSAGING | Kafka topics, producer/consumer matrix, outbox/DLQ |
| 09-SECURITY-RBAC-PRIVACY | Trust model, permission matrix, isolation, secrets, privacy |
| 10-WORKFLOWS | End-to-end traces + failure-scenario matrix |
| 11-OPERATIONAL-MODULES | Each admin module classified by completeness |
| 12-INTEGRATIONS-AND-OTA-READINESS | Providers + OTA/channel readiness |
| 13-DEVOPS-RELIABILITY | Compose/k8s/Jenkins, observability, operator playbook |
| 14-PERFORMANCE-CONCURRENCY | Confirmed/likely bottlenecks + required load tests |
| 15-TESTING-QUALITY | Test inventory + critical-path coverage + gaps |
| 16-TRACEABILITY-MATRIX | Capability → role → route → API → service → data → tests → issues |
| 17-ISSUE-REGISTER | Canonical issues (stable IDs) |
| 18-REMEDIATION-ROADMAP | Prioritized remediation |
| 19-COVERAGE-MANIFEST | Honest depth accounting; what was NOT verified |
| 20-CONTRADICTION-REGISTER | Docs vs code contradictions |
| 21-RUNTIME-VERIFICATION-LOG | Every command run against the live stack |
| evidence/ | Raw specialist-investigation outputs + git/file inventory |

## Confidence labels

CONFIRMED (direct evidence) · HIGH CONFIDENCE (strong, runtime path not fully executed) · PROBABLE (evidence suggests, infra/creds unavailable) · QUESTION (needs a product decision) · NOT VERIFIED (could not inspect/exercise).

## Headline findings

- **SEC-001 (Critical):** cross-binge customer PII leak via the admin recovery-queue endpoints.
- **SEC-003 (High):** the `production` Spring profile is never activated → security stubs + payment guards run in real deployments.
- **DATA-001 (High):** no DB backstop for double-booking (advisory lock is the sole guard).
- **BOOK-001 (High):** the slot-hold reserve→consume hand-off is dead code — holds don't actually reserve.
- **PAY-002 (Critical):** refunds are book-keeping only — no Razorpay refund call, so no money moves despite "refunded" status/email.
- **PRICE-002 (Medium):** the FX-lock / multi-currency feature is dormant (lock never consumed; native per-binge pricing) — corrects a prior positive-control claim.
- Plus PII/Mongo-retention data-integrity gaps and secret-hygiene issues. The architecture is mature and largely correct; the blockers are a focused, mostly-small punch list.

## Coverage honesty (Rule 6)

Deep (Level A): auth/authz/sessions, tenant isolation, booking/availability/concurrency, database integrity, payment/refund (static, complete — PAY-002 found), pricing/tax/FX, method-level API field-drift. Runtime-corroborated: double-booking prevention, Mongo indexes absent, event backbone, FX/native-currency. Census/structural only this run: full frontend visual/keyboard/contrast/screen-reader UX, performance/load, devops-reliability. Runtime-unverified: live Razorpay payment-confirm + refund end-to-end (super-admin MFA), all visual/UI rendering. See `19-COVERAGE-MANIFEST.md`.

## Provenance

`AUDIT_STATUS.md` (repo `docs/`) is the restart/handoff record; it was co-edited by a second model session (Codex/GPT-5) and records both sessions' stances. The `docs/audit/*` deliverables were authored from the Claude session's evidence.
