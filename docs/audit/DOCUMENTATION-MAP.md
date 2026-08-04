# Documentation Map — Every Markdown File and Its Status

> Audit run AUD-2026-07-25-01 · 109 tracked .md files · statuses assigned against commit `6440f58`

Status legend: **CURRENT** (accurate for this commit) · **STALE** (contains claims contradicted by current source — details in [22-DOCUMENTATION-CONTRADICTION-REGISTER.md](22-DOCUMENTATION-CONTRADICTION-REGISTER.md)) · **HISTORICAL** (point-in-time record, correctly labeled, keep as-is) · **NEW** (created by this run)

## Root

| File | Status | Notes |
|---|---|---|
| [README.md](../../README.md) | **STALE → banner added** | Claims Flyway V19/V77/V14 (now V20/V80/V16), 70 routes/421 mappings (now 71/477), and a 2026-07-16 NO-GO citing since-fixed blockers (PWA caching SEC-009 fixed via NetworkOnly) |
| [ARCHITECTURE.md](../../ARCHITECTURE.md) | **STALE → banner added** | "2026-07-16 working tree", V19/V77/V14 heads |
| [PRODUCTION-LAUNCH-CHECKLIST.md](../../PRODUCTION-LAUNCH-CHECKLIST.md) | CURRENT (generic) | Gates remain valid; superseded in precision by [PRODUCTION-READINESS-CHECKLIST.md](PRODUCTION-READINESS-CHECKLIST.md) |
| [BACKUP-RESTORE.md](../../BACKUP-RESTORE.md) | CURRENT | Procedures match scripts/ and k8s/backups.yml; restore rehearsal still unproven |
| [STRESS-TEST-REPORT-26APR2026.md](../../STRESS-TEST-REPORT-26APR2026.md) | HISTORICAL | 10 bugs listed; the CRITICAL one (customer could write loyalty config) is **fixed in current source** (`@PreAuthorize("hasRole('SUPER_ADMIN')")`, LoyaltyV2SuperAdminController.java:49) |

## docs/ (July-23 "fresh cut" set)

| File | Status | Notes |
|---|---|---|
| [docs/00-AUDIT-INDEX.md](../00-AUDIT-INDEX.md) | **STALE → banner added** | Audited a dirty tree (599 uncommitted files) that has since been committed; P0-1 resolved |
| [docs/01-SYSTEM-OVERVIEW.md](../01-SYSTEM-OVERVIEW.md) … [docs/06-FRONTEND-UI-UX.md](../06-FRONTEND-UI-UX.md) | STALE (mildly) | Mostly accurate on architecture; census numbers and tree-state claims outdated |
| [docs/07-ISSUE-REGISTER.md](../07-ISSUE-REGISTER.md) | **STALE → banner added** | P0-1 (uncommitted tree) FIXED; P0-2 (tokens in git) still open; several fixed items not marked |
| [docs/08-RECOMMENDATIONS-ROADMAP.md](../08-RECOMMENDATIONS-ROADMAP.md) | STALE (partially) | Roadmap items partially completed; superseded by [REMEDIATION-ROADMAP-CURRENT.md](REMEDIATION-ROADMAP-CURRENT.md) |

## docs/ changelogs & contracts

| File | Status | Notes |
|---|---|---|
| [docs/CHANGELOG-2026-07-21.md](../CHANGELOG-2026-07-21.md) | HISTORICAL | Claims (430 backend tests passed, real-PG16 migration tests) are historical evidence, not re-proven |
| [docs/CHANGELOG-2026-07-24-loyalty.md](../CHANGELOG-2026-07-24-loyalty.md) | HISTORICAL | Loyalty v2 slider + V80; claims 439 tests passed (historical) |
| [docs/MONEY_SCALE_AND_ROUNDING_CONTRACT.md](../MONEY_SCALE_AND_ROUNDING_CONTRACT.md) | CURRENT | Matches MoneyUtils/minor-unit implementation spot-checks |

## docs/audit/ (2026-07-11/12 historical set)

| Files | Status | Notes |
|---|---|---|
| Old numbered set 00–21 (now partially replaced), specialist-01…10, evidence/* from July 11–16 | HISTORICAL | Audited commit e3edbc1 with runtime evidence (builds/tests/k6 executed then). Superseded statuses tracked in [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md). Replaced files preserved in `docs/_previous/2026-07-25T00-00-00Z/` |
| [docs/audit/README.md](README.md) | **STALE — broken links** | References `../23-ISSUE-REGISTER.md` and `../28-PRODUCTION-READINESS-CHECKLIST.md` which **do not exist** (contradiction DOC-CR-07) |

## docs/audit/ (this run — NEW)

00–26 numbered docs, DOCUMENTATION-MAP.md, EXECUTIVE-SUMMARY-CURRENT.md, ISSUE-REGISTER-CURRENT.md, CURRENT-PROBLEMS-AND-ERRORS.md, PRODUCTION-GRADE-IMPROVEMENTS.md, REMEDIATION-ROADMAP-CURRENT.md, PRODUCTION-READINESS-CHECKLIST.md, HISTORICAL-AND-SUPERSEDED-FINDINGS.md, FINAL-AUDIT-VALIDATION.md, AUDIT_STATUS.md, evidence/* regenerated files — all **NEW/CURRENT** at `6440f58`.

## docs/runbooks/, docs/codebase/, docs/_previous/

| Path | Status | Notes |
|---|---|---|
| [docs/runbooks/operational-runbooks.md](../runbooks/operational-runbooks.md) | CURRENT | Ops procedures |
| [docs/runbooks/kafka-kraft-migration.md](../runbooks/kafka-kraft-migration.md) | CURRENT | Matches kraft overlay |
| docs/codebase/* | STALE (mildly) | Per-service writeups predate loyalty v2 / V78-V80 |
| docs/_previous/* | HISTORICAL ARCHIVE | Never edit |
