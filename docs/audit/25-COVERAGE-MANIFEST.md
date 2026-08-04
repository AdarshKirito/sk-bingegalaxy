# 25 — Coverage Manifest (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · accounting for all 1,423 tracked files

## Method

Every tracked path was classified in [evidence/final-file-coverage.tsv](evidence/final-file-coverage.tsv) by review depth:

| Class | Meaning | Count | % |
|---|---|---:|---:|
| **A** | Deep coverage — read directly by lead or specialist agents (backend main sources, all 118 migrations, service configs, frontend src, infra/CI files) | 1,040 | 73.1% |
| **B** | Partial coverage — structure and key content reviewed (test files, documentation) | 183 | 12.9% |
| **C** | Inventoried — existence/purpose recorded, content skimmed or size-checked (root scripts, logs, k6 JSON) | 163 | 11.5% |
| **X** | Excluded — binaries and generated artifacts (images, fonts, zips, playwright-report, test-results, .ca-patch) | 37 | 2.6% |

## How coverage was achieved

- **Lead auditor:** baseline, census, all spot verifications (17 targeted file reads with line citations), all conflict resolutions, evidence generation
- **9 specialist passes** (read-only subagents): security/RBAC, database/migrations, booking/concurrency, payments/financial, events/notifications, frontend/PWA, DevOps/supply-chain, testing/quality, product/workflows
- **Cross-checking:** every specialist claim used in a document was either directly re-verified by the lead (all P0/P1 claims) or labeled with its confidence level; three specialist claims were **disproved** and recorded in [22-DOCUMENTATION-CONTRADICTION-REGISTER.md](22-DOCUMENTATION-CONTRADICTION-REGISTER.md) (DOC-CR-10/11/12)

## Known coverage limits (honest)

1. No file was executed — class A means *read*, not *run*
2. Generated `frontend/package-lock.json` reviewed only for lock presence, not per-dependency
3. Class C root scripts were size/purpose-checked, not line-audited (they are not runtime code)
4. Historical evidence folders (production-proof/, docs/_previous/, old audit evidence) were inventoried, not re-validated

## Verification pointer

Snapshot integrity: [evidence/source-snapshot.tsv](evidence/source-snapshot.tsv) (1,423 blob SHAs at HEAD) — any future re-audit can diff against this to see exactly what changed.
