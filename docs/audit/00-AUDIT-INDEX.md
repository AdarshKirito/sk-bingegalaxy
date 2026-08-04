# 00 — Audit Index (Current)

- **Audit run:** AUD-2026-07-25-01
- **Audited commit:** `6440f5825153f9024fc0f5c6f7ee83ee3881aa4d` (branch `main`, synced with `origin/main`, working tree clean at baseline)
- **Audit type:** Repository-wide forensic audit and documentation reconciliation — **static analysis only** (no builds, no tests, no runtime, no provider calls were executed in this run; Java/Maven/Node were unavailable and the Docker daemon was not running on the audit host)
- **Scope rule:** documentation-only changes. No application code, configuration, migration, or infrastructure file was modified.
- **Supersedes:** the 2026-07-11/12 audit set in this folder (evidence retained, statuses re-baselined) and the 2026-07-23 `docs/00`–`08` fresh-cut set (which audited a then-dirty tree that has since been committed).

## Canonical current documents

| # | Document | Contents |
|---|---|---|
| 00 | [00-AUDIT-INDEX.md](00-AUDIT-INDEX.md) | This index |
| 01 | [01-SYSTEM-OVERVIEW.md](01-SYSTEM-OVERVIEW.md) | What the platform is, runtime shape, verified census |
| 02 | [02-REPOSITORY-INVENTORY.md](02-REPOSITORY-INVENTORY.md) | Tracked-file inventory, anomalies, hygiene |
| — | [DOCUMENTATION-MAP.md](DOCUMENTATION-MAP.md) | Every .md in the repo, its status (current/historical/stale) |
| 03 | [03-PRODUCT-AND-DOMAIN.md](03-PRODUCT-AND-DOMAIN.md) | Roles, authority handover, module matrix, domain clusters |
| 04 | [04-ARCHITECTURE-AND-DEPENDENCIES.md](04-ARCHITECTURE-AND-DEPENDENCIES.md) | Services, data stores, dependency versions |
| 05 | [05-SERVICE-DEPENDENCY-MAP.md](05-SERVICE-DEPENDENCY-MAP.md) | Sync/async edges, internal API surface |
| 06 | [06-WORKFLOWS.md](06-WORKFLOWS.md) | End-to-end traces of the 12 core workflows |
| 07 | [07-TRACEABILITY-MATRIX.md](07-TRACEABILITY-MATRIX.md) | Page → route → API → controller → tables → events |
| 08 | [08-SECURITY-RBAC-AND-PRIVACY.md](08-SECURITY-RBAC-AND-PRIVACY.md) | Gateway/JWT/MFA/tenancy/privacy analysis |
| 09 | [09-DATABASE-AND-DATA-INTEGRITY.md](09-DATABASE-AND-DATA-INTEGRITY.md) | Schemas, migrations, constraints, entity drift |
| 10 | [10-BOOKING-AVAILABILITY-AND-CONCURRENCY.md](10-BOOKING-AVAILABILITY-AND-CONCURRENCY.md) | Holds, advisory locks, V75 backstop, state machine |
| 11 | [11-PRICING-PAYMENTS-AND-FINANCIAL-INTEGRITY.md](11-PRICING-PAYMENTS-AND-FINANCIAL-INTEGRITY.md) | Money contract, gateways, refunds, reconciliation |
| 12 | [12-EVENTS-MESSAGING-NOTIFICATIONS-AND-RECOVERY.md](12-EVENTS-MESSAGING-NOTIFICATIONS-AND-RECOVERY.md) | Kafka topics, outbox, DLT, notification pipeline |
| 13 | [13-FRONTEND-UI-UX-AND-ACCESSIBILITY.md](13-FRONTEND-UI-UX-AND-ACCESSIBILITY.md) | PWA, guards, token handling, a11y |
| 14 | [14-API-AND-EVENT-COMPATIBILITY.md](14-API-AND-EVENT-COMPATIBILITY.md) | FE↔BE contract diff, event schema compatibility |
| 15 | [15-DEVOPS-DEPLOYMENT-RELIABILITY-AND-OBSERVABILITY.md](15-DEVOPS-DEPLOYMENT-RELIABILITY-AND-OBSERVABILITY.md) | Compose/k8s/Jenkins/monitoring |
| 16 | [16-TESTING-AND-QUALITY.md](16-TESTING-AND-QUALITY.md) | Test census, coverage gaps, quality signals |
| 17 | [17-PERFORMANCE-CAPACITY-AND-CONCURRENCY.md](17-PERFORMANCE-CAPACITY-AND-CONCURRENCY.md) | Pool sizing, k6 history, capacity notes |
| 18 | [18-SOFTWARE-SUPPLY-CHAIN-AND-REPOSITORY-HYGIENE.md](18-SOFTWARE-SUPPLY-CHAIN-AND-REPOSITORY-HYGIENE.md) | Secrets-in-git, binaries, pinning, licensing |
| 19 | [19-CODE-QUALITY-AND-MAINTAINABILITY.md](19-CODE-QUALITY-AND-MAINTAINABILITY.md) | Smell census, hotspots, conventions |
| 20 | [20-PRIVACY-COMPLIANCE-AND-GOVERNANCE.md](20-PRIVACY-COMPLIANCE-AND-GOVERNANCE.md) | GDPR/anonymization, retention, consent |
| 21 | [21-INTEGRATIONS-OTA-AND-GLOBAL-READINESS.md](21-INTEGRATIONS-OTA-AND-GLOBAL-READINESS.md) | Razorpay/Stripe/webpush/SMTP, i18n/FX/timezones |
| 22 | [22-DOCUMENTATION-CONTRADICTION-REGISTER.md](22-DOCUMENTATION-CONTRADICTION-REGISTER.md) | Every doc claim that contradicts current source |
| 25 | [25-COVERAGE-MANIFEST.md](25-COVERAGE-MANIFEST.md) | File-coverage accounting for this audit |
| 26 | [26-COMMANDS-AND-EVIDENCE-LEDGER.md](26-COMMANDS-AND-EVIDENCE-LEDGER.md) | Every command run and its result |

## Decision documents

| Document | Purpose |
|---|---|
| [EXECUTIVE-SUMMARY-CURRENT.md](EXECUTIVE-SUMMARY-CURRENT.md) | Verdict, blockers, strengths — start here |
| [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md) | **Sole authoritative issue register** (P0–P3) |
| [CURRENT-PROBLEMS-AND-ERRORS.md](CURRENT-PROBLEMS-AND-ERRORS.md) | Everything found wrong, in plain language |
| [PRODUCTION-GRADE-IMPROVEMENTS.md](PRODUCTION-GRADE-IMPROVEMENTS.md) | What production-grade teams do that this repo doesn't yet |
| [REMEDIATION-ROADMAP-CURRENT.md](REMEDIATION-ROADMAP-CURRENT.md) | Ordered fix plan |
| [PRODUCTION-READINESS-CHECKLIST.md](PRODUCTION-READINESS-CHECKLIST.md) | Gate-ID launch checklist |
| [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md) | What older audits found and what happened to each finding |
| [FINAL-AUDIT-VALIDATION.md](FINAL-AUDIT-VALIDATION.md) | End-of-run validation PASS/FAIL |
| [AUDIT_STATUS.md](AUDIT_STATUS.md) | Canonical run-status file |

## Evidence

Machine-generated artifacts live in [evidence/](evidence/): repository baseline, source snapshot (1,423 blob SHAs), endpoint/route/API-pair inventories, coverage manifest, dependency inventories, contract matrices, and the commands ledger. Historical evidence from prior runs is retained and clearly labeled; superseded copies of replaced documents are under `docs/_previous/2026-07-25T00-00-00Z/`.

## How to read confidence labels

Because this run executed no code, every finding carries one of:

- **VERIFIED-STATIC** — confirmed by reading current source at the audited commit (file:line cited).
- **HISTORICAL** — supported only by artifacts from earlier runs (test logs, k6 output, changelog claims). True then; not re-proven now.
- **NOT-VERIFIED** — could not be confirmed in this run (usually requires runtime, a build, or provider sandbox access).
