# CURRENT BUILD AND TEST RESULTS — AUD-2026-07-25-01 (execution phase)

> Source snapshot: `main` @ `6440f5825153f9024fc0f5c6f7ee83ee3881aa4d` (tree `fd42379`, content-identical to parent `a27c3fa`).
> Execution environment: containerized toolchain — `maven:3.9-eclipse-temurin-17` (Java 17, matches pom requirement exactly) and `node:20` (Node 20.20.2 / npm 10.8.2) — isolated volumes `skbg-audit-m2` / `skbg-audit-fe`; logs in `%TEMP%\skbg-audit\`. Host has no Java/Maven/Node; Docker daemon 29.6.1.
> **These are CURRENT results executed by this audit run — not historical.**

## Backend (Maven reactor, per module)

Command (run 1): `mvn -B -fae clean test` (full reactor). Run 1 covered modules 1–8, then the container terminated abnormally during payment-service (environment: resource pressure — classified, logged, retried). Runs 2–3 completed the remaining modules; run 2's failure was an audit-command error (`-pl` without `-am`), not a source defect. All results below are from the current source.

| # | Module | Compile | Test compile | Tests run | Failures | Errors | Skipped | Status |
|---|--------|---------|--------------|-----------|----------|--------|---------|--------|
| 1 | Parent reactor (sk-binge-galaxy) | PASS | n/a | — | — | — | — | PASS |
| 2 | common-lib | PASS | PASS | **59** | 0 | 0 | 0 | **PASS** |
| 3 | discovery-server | PASS | PASS | 0 (no test sources) | — | — | — | PASS (build) |
| 4 | config-server | PASS | PASS | 0 (no test sources) | — | — | — | PASS (build) |
| 5 | api-gateway | PASS | PASS | **66** | 0 | 0 | 0 | **PASS** |
| 6 | auth-service | PASS | PASS | **107** | 0 | 0 | 0 | **PASS** |
| 7 | availability-service | PASS | PASS | **27** | 0 | 0 | 0 | **PASS** |
| 8 | booking-service | PASS | PASS | **433** | 0 | 0 | **6** | **PASS** (6 skipped = `BookingFlowIntegrationTest` — `@Disabled` in source; TEST-01) |
| 9 | payment-service | PASS | PASS | **93** | 0 | 0 | 0 | **PASS** |
| 10 | notification-service | PASS | PASS | **61** | 0 | 0 | 0 | **PASS** (module BUILD SUCCESS in 01:33) |

**Backend total: 846 tests, 0 failures, 0 errors, 6 skipped — BUILD SUCCESS on every module.**

Notable current-run observations:
- The only skipped tests in the whole backend are the 6 `@Disabled` `BookingFlowIntegrationTest` methods — runtime confirmation of static finding **TEST-01** (no enabled integration tests; unit tests run on H2, not PostgreSQL, so V75 trigger and pg advisory locks remain untested by the suite).
- Deprecation debt visible at compile time: heavy `@MockBean` (Spring Boot 3.4 deprecation, marked for removal) warnings across payment tests — future-upgrade risk, registered as issue CQ-EX-01.
- `WaitlistServicePromotionRaceTest`, `AdminApprovalControllerScopeTest`, `AdminRecoveryQueueScopeTest` etc. all pass — the RBAC/concurrency regression tests claimed by the docs genuinely exist and pass.
- Adversarial recheck note: `NotificationServiceTest` shows `Tests run: 0` at outer-class level because its tests live in `@Nested` classes (Surefire counts those separately) — initial "dead test shell" suspicion **disproved** on source inspection (NotificationServiceTest.java line 58 `@Nested`).

## Frontend (Node 20 container, isolated copy of lockfile)

| Step | Command | Result |
|---|---|---|
| Clean install | `npm ci` (copy of package.json + package-lock.json in volume) | **PASS** — 366 packages; repo lockfile untouched (verified: source mounted read-only) |
| Type check | `npm run typecheck` (tsc --noEmit) | **PASS** (exit 0) |
| Unit/component tests | `npm test` (vitest run, CI=true) | **PASS — 42 files, 362/362 tests, 28.8s** |
| Production build | `npm run build` (vite, 4GB heap) | **PASS — built in 11.0s**; PWA generateSW: 129 precache entries (2.17 MB) |
| Bundle analysis | vite output | Largest chunks: `address-data` **8,616.87 kB** (gzip 2,322.75 kB — lazy-loaded data file, still a flag: PERF-EX-01), `index` 494.43 kB (gzip 152.86 kB), `PhoneField` 202.60 kB. Vite warns >500 kB chunks |
| Service worker | dist/sw.js + workbox generated | PASS (NetworkOnly for /api confirmed in static review; SW builds cleanly) |
| E2E (Playwright) | **57 passed / 5 failed / 1 flaky** (chromium, 4.9m, containerized vs live stack) | PARTIAL — failures mostly missing seed fixtures (TEST-EX-03); detail in CURRENT-RUNTIME-VERIFICATION.md §8 |

## Compliance notes

- No `pom.xml`, `package.json`, or `package-lock.json` was modified. npm ci ran against copies inside a Docker volume; the repo tree stayed clean (verified via `git status` at close).
- All commands, exit codes and log paths: [evidence/environment-recovery-log.md](evidence/environment-recovery-log.md) and [evidence/execution-capability-matrix.tsv](evidence/execution-capability-matrix.tsv).
