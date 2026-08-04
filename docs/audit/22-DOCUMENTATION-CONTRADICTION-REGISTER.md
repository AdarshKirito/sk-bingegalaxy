# 22 — Documentation Contradiction Register (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58`
> Every claim in repo documentation that contradicts verified current source. Each entry: where the claim lives, what it says, what is actually true, and the fix applied by this run.

| ID | Location | Stale claim | Verified truth (evidence) | Action taken |
|---|---|---|---|---|
| DOC-CR-01 | [README.md](../../README.md) "Current source milestones" | Flyway heads auth V19, booking V77, payment V14 | **V20 / V80 / V16** (migration file census) | Current-status banner added to README pointing here |
| DOC-CR-02 | [README.md](../../README.md) | "47 controllers/421 mappings… 70 routes and 407 API pairs" | 47 controllers / **477** mappings / **71** routes / 429 endpoint rows (evidence TSVs) | Banner (same) |
| DOC-CR-03 | [README.md](../../README.md) production-status note | NO-GO blockers include "authenticated PWA response caching" | SEC-009 **fixed**: `/api` NetworkOnly in [vite.config.js](../../frontend/vite.config.js); remaining blockers differ (see EXECUTIVE-SUMMARY-CURRENT) | Banner (same) |
| DOC-CR-04 | [ARCHITECTURE.md](../../ARCHITECTURE.md) | "2026-07-16 working tree", V19/V77/V14 | Commit 6440f58 (2026-07-24), V20/V80/V16 | Banner added |
| DOC-CR-05 | [docs/00-AUDIT-INDEX.md](../00-AUDIT-INDEX.md) (July-23 set) | Audit cut of a dirty tree; "599 uncommitted files" P0 | Tree committed (`3d65090`, merged `6440f58`); working tree **clean** at this baseline | Banner added; P0-1 marked FIXED |
| DOC-CR-06 | [docs/07-ISSUE-REGISTER.md](../07-ISSUE-REGISTER.md) | P0-1 open (uncommitted tree); detached-HEAD warning | P0-1 FIXED; HEAD attached to main, synced with origin | Banner added; register superseded by [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md) |
| DOC-CR-07 | [docs/audit/README.md](README.md) | Links to `../23-ISSUE-REGISTER.md` and `../28-PRODUCTION-READINESS-CHECKLIST.md` | **Both files do not exist** anywhere in the repo | Banner added with corrected links |
| DOC-CR-08 | docs/codebase/* service writeups | Pre-loyalty-v2, pre-V78-V80 descriptions | Loyalty v2 live (V21/V22/V28/V80); payment-methods-by-country live | Mapped as STALE in [DOCUMENTATION-MAP.md](DOCUMENTATION-MAP.md) (files left intact — historical value) |
| DOC-CR-09 | [STRESS-TEST-REPORT-26APR2026.md](../../STRESS-TEST-REPORT-26APR2026.md) | CRITICAL: customer can write loyalty config | **Fixed**: class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` — LoyaltyV2SuperAdminController.java:49 | Noted in DOCUMENTATION-MAP (report kept as HISTORICAL) |
| DOC-CR-10 | Specialist report (this run, internal) | ".env with real secrets tracked at HEAD" | `.env` **never committed** (`git log --all -- .env` empty; not in `ls-files`) | Corrected in docs 02/08/18 |
| DOC-CR-11 | Specialist report (this run, internal) | "k6_bin ~60 MB tracked" | k6_bin **not tracked**; the tracked binary is [k6.zip](../../k6.zip) (28.67 MB) | Corrected in doc 18 |
| DOC-CR-12 | Specialist report (this run, internal) | "No GDPR/anonymization code found" | Full pipeline exists: UserAnonymizationService + user.anonymized + 3 consumers | Corrected in docs 08/20 |
| DOC-CR-13 | [docs/audit/AUDIT_STATUS.md](AUDIT_STATUS.md) (as first written this run) | All phases marked DONE before documents existed | Deliverables now written; status file updated truthfully at close | AUDIT_STATUS updated in final validation step |
| DOC-CR-14 | Old docs/audit numbered set (July-11/12) | Findings statuses frozen at e3edbc1 (e.g., SEC-001 open, PAY-002 open) | SEC-001 FIXED, PAY-002 FIXED-IN-SOURCE, etc. | Full disposition table in [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md) |

## Rules going forward

1. Census numbers (routes/mappings/migration heads) belong in **generated evidence**, not prose — regenerate, don't hand-edit.
2. Any doc making a "current state" claim must carry its audit commit hash.
3. Superseded docs get a banner, never silent edits; originals archived under `docs/_previous/`.
