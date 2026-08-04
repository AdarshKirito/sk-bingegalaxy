# 26 — Commands and Evidence Ledger (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · every consequential command this run, in order, with result summaries. Full raw outputs in [evidence/commands-and-results.md](evidence/commands-and-results.md). **No build, test, container, or provider command was executed.**

## Phase 0 — Baseline

| # | Command | Result |
|---|---|---|
| 1 | `git rev-parse --show-toplevel` (workspace root) | FAILED — empty broken .git at `d:\sk-binge-galaxy`; true root is nested `sk-binge-galaxy/` |
| 2 | `git rev-parse HEAD` / `git branch -vv` / `git remote -v` | `6440f58…` on `main` = origin/main (AdarshKirito/sk-bingegalaxy) |
| 3 | `git status --porcelain` | **empty** → [evidence/git-status-baseline.txt](evidence/git-status-baseline.txt) |
| 4 | `git ls-tree -r HEAD` (parsed) | 1,423 rows → [evidence/source-snapshot.tsv](evidence/source-snapshot.tsv) |
| 5 | Tool availability: `java`, `mvn`, `node`, `npm`, `docker version` | Java/Maven/Node/npm **absent**; Docker CLI 29.6.1 present, **daemon down** → static-only scope |
| 6 | `git diff --stat e3edbc1..HEAD` | 566 files, +49,367/−6,439 |

## Phase 1 — Census (grep/glob counts)

| # | Command family | Result |
|---|---|---|
| 7 | glob counts: `**/*.java`, `**/*.jsx`, `**/*.sql`, `**/*.md` | 712 / 153 / 119 / 109 |
| 8 | grep `@RestController`, `@*Mapping`, `@Entity|@Document`, repositories | 47 / 477 / 93 / 93 |
| 9 | grep `@Scheduled`, `@KafkaListener`; KafkaTopics constants | 33 / 16 / 20 |
| 10 | compose service count, Dockerfile glob, k8s glob | 23 / 11 / 23 |
| 11 | Flyway heads per service (filename sort) | auth V20, availability V2, booking V80, payment V16 |

## Phase 2 — Verifications (lead spot-checks, file:line)

| # | Check | Result |
|---|---|---|
| 12 | AuthService.java L464-480 | `SUPER_ADMIN_REQUIRE_MFA` default **"true"**; compose L456 overrides "false" (dev) |
| 13 | auth application.yml L11-12 | profile group `kubernetes → production` (SEC-003 partial fix) |
| 14 | V75__room_occupancy_db_backstop.sql | EXISTS (trigger backstop) |
| 15 | PaymentService.java L107-136 | @PostConstruct fail-fast (4 IllegalStateException sites) |
| 16 | SecretCipher.java L55-57 | CRYPTO_SECRET_KEY falls back to JWT-derived key (SEC-CR-02) |
| 17 | BookingRepository.java:433 / BookingService.java:261 | `pg_advisory_xact_lock` wired |
| 18 | AdminRecoveryQueueController + AdminRecoveryQueueScopeTest | SEC-001 tenant fix + regression test |
| 19 | `git ls-files --error-unmatch .env`; `git log --all -- .env` | `.env` **never tracked/committed** (disproves specialist claim) |
| 20 | `git ls-files "*.zip"`; ls-files k6_bin; target/ count | k6.zip 28.67 MB tracked; k6_bin NOT tracked; 0 target/ files |
| 21 | token file shape (prefix only, values never printed) | admin_token.txt = real JWT format; stress-tokens.txt = 4 lines; both **tracked at HEAD** |
| 22 | `.gitignore` lines 25-27, 50-52 | token+env ignore rules exist but post-date the token commits |
| 23 | grep `anonym` in auth-service; USER_ANONYMIZED refs | UserAnonymizationService (requestDeletion L56, anonymizeUser L84, cron L101, publish L159) + 3 consumers (disproves "no GDPR") |
| 24 | LoyaltyV2SuperAdminController.java:47-49 | class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` — 26-Apr CRITICAL fixed |

## Phase 3 — Evidence generation

| # | Command | Output |
|---|---|---|
| 25 | Archive: Copy-Item → `docs/_previous/2026-07-25T00-00-00Z/` | 8 files preserved (old 00/02 + 4 TSVs + README/ARCHITECTURE) |
| 26 | Endpoint scraper (controller annotations) | 429 rows → [evidence/endpoint-inventory-current.tsv](evidence/endpoint-inventory-current.tsv) |
| 27 | Route scraper (App.jsx) | 71 rows → [evidence/frontend-routes-current.tsv](evidence/frontend-routes-current.tsv) |
| 28 | API-pair scraper (axios calls) | 372 rows → [evidence/frontend-api-pairs-current.tsv](evidence/frontend-api-pairs-current.tsv) |
| 29 | Coverage classifier (git ls-files → A/B/C/X) | 1040/183/163/37 → [evidence/final-file-coverage.tsv](evidence/final-file-coverage.tsv) |

## Phase 4 — Close-out (recorded in FINAL-AUDIT-VALIDATION.md)

| # | Command | Purpose |
|---|---|---|
| 30 | `git status --porcelain` (final) | Prove only docs/ files changed |
| 31 | `git rev-parse HEAD` (final) | Prove commit unchanged (= 6440f58) |
| 32 | Change manifest generation | [evidence/audit-file-change-manifest.tsv](evidence/audit-file-change-manifest.tsv) |

## Specialist passes (9 read-only subagents)

Security/RBAC · database/migrations · booking/concurrency · payments/financial · events/notifications · frontend/PWA · DevOps/supply-chain · testing/quality · product/workflows — each returned a written report; all P0/P1 claims re-verified by lead before use; three claims disproved (DOC-CR-10/11/12).
