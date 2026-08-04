# Executive Summary — Current

> **Audit:** AUD-2026-07-25-01 · **Commit:** `6440f5825153f9024fc0f5c6f7ee83ee3881aa4d` (main = origin/main, clean tree) · **Date:** 2026-07-25
> **Method:** repository-wide forensic static audit (1,423 files; 73% deep-read) + documentation reconciliation. ~~No builds, tests, containers, or provider calls were executed~~ **SUPERSEDED 2026-07-26 (execution phase):** builds, tests, migrations, live runtime, concurrency races and k6 were all **executed** via containerized toolchains and a rebuilt compose stack. See the execution-phase update at the end of this summary.

## Verdict

**NO-GO for production — but the gap is proof, not construction.** *(2026-07-26: the build/test/runtime/migration proof gaps below are now closed; the remaining NO-GO drivers are provider-sandbox proof, secrets in git history, disabled integration tests, alerting and TLS — see the update section.)*

This codebase is *substantially better than its own documentation claims*. The July blockers are almost all genuinely fixed in source: tenant isolation (with a regression test), real provider refunds with receipt-first reconciliation, a three-layer oversell defense ending in a database trigger, PWA token security, GDPR anonymization fan-out, and a disciplined money contract. Code hygiene is exceptional (zero TODOs, zero swallowed exceptions across 712 Java files).

What blocks launch is that **almost none of it has ever been proven to run**: no payment has ever executed end-to-end against any provider, the V75 oversell backstop has no automated test, no alert would fire if refund reconciliation silently died, TLS ingress exists only as a Jenkinsfile comment — and two files of real-format admin JWTs are sitting in git history.

## Issue totals

| Severity | Count | Headline items |
|---|---:|---|
| **P0** | 2 | Tokens in git (SEC-HYG-01); zero provider-sandbox proof (PR-PAY-01) |
| **P1** | 6 | No Testcontainers/V75 test; zero alert rules; Ingress/TLS aspirational; MFA-key⇄JWT coupling; ddl-auto unpinned; runtime posture never asserted |
| **P2** | 14 | Restore never rehearsed; auth events not outboxed; stale load evidence; artifacts/binaries tracked; no PR CI; no secret scanning; SMS/WhatsApp mocks… |
| **P3** | 20 | Producer-only topics; a11y gaps; monolith page; governance scaffolding… |

Full register: [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md) · Plain-language: [CURRENT-PROBLEMS-AND-ERRORS.md](CURRENT-PROBLEMS-AND-ERRORS.md) · Best-practice gaps: [PRODUCTION-GRADE-IMPROVEMENTS.md](PRODUCTION-GRADE-IMPROVEMENTS.md)

## Top 5 blockers

1. **SEC-HYG-01** — admin_token.txt + stress-tokens.txt tracked at HEAD and in history; purge + rotate (in the SEC-CR-02-safe order)
2. **PR-PAY-01** — the revenue path has never run end-to-end anywhere, ever
3. **TEST-01** — the system's strongest invariants (V75 trigger, advisory locks, outbox) have zero integration tests
4. **OBS-01** — no alerts: failures would be discovered by customers
5. **DEP-01 / PR-SEC-01** — no TLS manifests; production posture never demonstrated on a running environment

## Top 5 strengths (verified in source)

1. Three-layer booking-oversell defense (holds → `pg_advisory_xact_lock` → V75 DB trigger)
2. Financial integrity machinery: durable intents, receipt-first reconciliation, V14 double-refund-proof index, snapshot-based refund math in minor units
3. Defense-in-depth RBAC: gateway → @PreAuthorize → binge tenancy → 17-module deny-list with dual sign-off — including the *fixed and regression-tested* recovery-queue leak
4. Event fabric: transactional outbox (booking/payment), ProcessedEvent dedup, DLT everywhere, replay console
5. Frontend security posture: httpOnly cookies, single-flight refresh, auto idempotency keys, NetworkOnly API, single DOMPurify-guarded HTML sink

## Top 5 improvements (beyond fixes)

1. Testcontainers + contract tests + coverage ratchets (make the pyramid real)
2. Alerting/SLO starter pack + dashboards-as-code
3. Supply-chain: digest pinning, SBOM, signing, PR-level CI with gitleaks
4. Proof-driven data lifecycle: restore drills + automated erasure verification
5. Governance: CODEOWNERS, ADRs, generated (never hand-typed) census numbers in docs

## Documentation reconciliation (this run)

14 contradictions found and dispositioned ([22-DOCUMENTATION-CONTRADICTION-REGISTER.md](22-DOCUMENTATION-CONTRADICTION-REGISTER.md)): stale Flyway heads/route counts and an outdated NO-GO rationale in README/ARCHITECTURE (banners added), the July-23 register's fixed-but-still-listed P0-1, two broken links in docs/audit/README.md, and three internal specialist claims disproved during reconciliation (.env never committed; k6_bin not tracked; GDPR pipeline exists). Replaced documents archived under `docs/_previous/2026-07-25T00-00-00Z/`.

## Path to GO

Wave 0 (secret sequencing) → Wave 1 (sandbox campaign, TLS, posture assertion, alerts) → Wave 2 (Testcontainers, k6 re-run, restore drill) — see [REMEDIATION-ROADMAP-CURRENT.md](REMEDIATION-ROADMAP-CURRENT.md). Every gate with evidence requirements: [PRODUCTION-READINESS-CHECKLIST.md](PRODUCTION-READINESS-CHECKLIST.md).

## Execution-phase update (2026-07-26)

The mandatory execution pass recovered the environment and **ran everything runnable**, closing the "never proven to run" gap for all non-provider items:

| Executed | Result |
|---|---|
| Backend build + full test suites (containerized Maven, Java 17) | **846 tests, 0 failures** (6 skipped = the known @Disabled integration test, TEST-01) |
| Frontend `npm ci` + vitest + tsc + vite build | **362/362 tests**, typecheck clean, PWA build 11.0s |
| `docker compose build` all 9 images from current source | **EXIT 0** — cured a stale-image gap (running images predated commits) |
| Migrations | Empty-DB chain proven (all 4 schemas) **and** live upgrade proven: auth **V20** + payment **V16** applied on restart, `success=t` |
| Live runtime | 15/15 containers healthy; auth cycle, CSRF double-submit, consent gate, booking create/duplicate-reject/cancel all pass |
| Concurrency race (4 users × 3 rooms, synchronized) | Exactly 3 bookings, one per room; 4th correctly rejected — DB-verified, no oversell |
| Outbox → Kafka → notification | Mongo notification docs match exactly the winning bookings |
| k6 smoke (CURRENT, not the HISTORICAL 26-Apr data) | **336/336 checks, 0.00% failed, p95 21.69ms** |

New findings from execution: **TEST-EX-04** (k6 helper's register payload predates the consentGiven contract), stale-runtime-image process gap (fixed by rebuild), plus PERF-EX-01/CQ-EX-01 from the build logs. Full detail: [ENVIRONMENT-RECOVERY-AND-EXECUTION.md](ENVIRONMENT-RECOVERY-AND-EXECUTION.md), [CURRENT-BUILD-AND-TEST-RESULTS.md](CURRENT-BUILD-AND-TEST-RESULTS.md), [CURRENT-RUNTIME-VERIFICATION.md](CURRENT-RUNTIME-VERIFICATION.md), [CURRENT-MIGRATION-VERIFICATION.md](CURRENT-MIGRATION-VERIFICATION.md), [CURRENT-PROVIDER-VERIFICATION.md](CURRENT-PROVIDER-VERIFICATION.md).

**Verdict after execution:** still **NO-GO**, but the blocker list shrinks to items that genuinely require external resources or code changes: PR-PAY-01 (provider sandbox — credentials are user-owned), SEC-HYG-01 (tokens in git history), TEST-01 (integration tests disabled), OBS-01 (alerts), DEP-01 (TLS).
