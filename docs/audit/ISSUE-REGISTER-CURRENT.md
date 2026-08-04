# Issue Register — Current (SOLE AUTHORITATIVE)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · This register supersedes docs/07-ISSUE-REGISTER.md (July-23) and all registers in the July-11/12 set. Historical dispositions: [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md).
>
> Totals: **P0: 2 · P1: 6 · P2: 14 · P3: 20 — 42 open issues.** Confidence labels: VERIFIED-STATIC / HISTORICAL / NOT-VERIFIED.
>
> **Execution-phase addendum (2026-07-26):** the execution pass (see [ENVIRONMENT-RECOVERY-AND-EXECUTION.md](ENVIRONMENT-RECOVERY-AND-EXECUTION.md)) upgraded many VERIFIED-STATIC claims to **VERIFIED-EXECUTED** (846 backend + 362 frontend tests, migrations incl. live V20/V16 upgrade, booking concurrency races, k6 smoke 336/336). It also added findings **OPS-EX-01** (running images were stale vs source — fixed by rebuild, but no process prevents recurrence), **TEST-EX-03** (Playwright e2e suite is not self-contained: 5/63 chromium tests fail against a fresh stack because they presume seeded super-admin/CMS fixtures), **TEST-EX-04** (k6 helper register payload missing `consentGiven` — load-test suite drift), **PERF-EX-01** (frontend `address-data` chunk 8.6 MB), **CQ-EX-01** (deprecated `@MockBean` warnings across test suites). P0s are unchanged: PR-PAY-01 remains NOT-VERIFIED at the provider boundary (credentials empty in env — user-owned), SEC-HYG-01 unchanged.

---

## P0 — Launch blockers

### SEC-HYG-01 — Real-format JWTs committed to git (HEAD + history)
- **Severity/Confidence:** P0 · VERIFIED (command evidence)
- **Where:** [admin_token.txt](../../admin_token.txt) (451 B, `eyJhbGciOiJIUzI…` prefix), [stress-tokens.txt](../../stress-tokens.txt) (4 JWT lines) — both tracked at HEAD, committed in `3d65090`; `.gitignore:25-27` rules were added **after** commit and do not untrack
- **Impact:** anyone with repo access (or a leaked clone) holds admin-shaped tokens; if any were signed post-JWT-rotation (2026-07-13) they may still validate
- **Why it happened:** test tooling wrote tokens to repo root; ignore rules came later
- **Fix:** (1) delete both files from HEAD; (2) purge history (`git filter-repo` / BFG — coordinate force-push with all clones); (3) rotate `JWT_SECRET` again **after** setting `CRYPTO_SECRET_KEY` (see SEC-CR-02 interaction!); (4) revoke all sessions via denylist; (5) add gitleaks CI gate (SEC-OP-04)
- **Verify:** `git log --all -- admin_token.txt` empty on all remotes; old tokens rejected at gateway
- **Owner action class:** operator (destructive git — needs explicit human approval)

### PR-PAY-01 — Zero end-to-end payment/refund proof against any provider
- **Severity/Confidence:** P0 gate · VERIFIED-STATIC (code complete) + NOT-VERIFIED (behavior)
- **Where:** payment-service Razorpay/Stripe clients, webhooks, reconciliation — all code-verified; changelog states "No end-to-end payment has ever run"
- **Impact:** the entire revenue path — order, callback, webhook signature, refund, dispute, reconciliation — has never been observed working against a real provider sandbox. Any credential, signature, currency-unit or URL mistake ships silently
- **Fix:** provider sandbox campaign: happy-path pay, failed pay, webhook replay, full+partial refund, refund webhook, dispute ingest, reconciliation of an orphaned order — with evidence archived under production-proof/
- **Verify:** documented sandbox run at current commit; then re-run per release
- **Owner action class:** operator (needs sandbox credentials)

---

## P1 — Fix before launch

### TEST-01 — No integration-level tests: V75 trigger, advisory locks, Flyway, Kafka, Mongo TTL
- P1 · VERIFIED-STATIC. Zero `@Testcontainers` anywhere; the DB backstop (V75) that guards oversell has **no automated test**; lock paths are Mockito-mocked; `BookingFlowIntegrationTest` is `@Disabled` (BOOK-02)
- Fix: Testcontainers suite — PG (Flyway chain + V75 contention test with 2 threads), Kafka (outbox relay + listener dedup), Mongo (TTL/dedup). Wire into Jenkins
- Verify: CI runs them; V75 test demonstrably rejects oversell

### OBS-01 — Zero alert rules (PrometheusRule absent)
- P1 · VERIFIED-STATIC. Monitoring collects (ServiceMonitor/Zipkin/Loki) but **nothing alerts**: DLT depth, outbox lag, consumer lag, refund-reconciliation stalls, error rate, saturation, cert expiry
- Fix: PrometheusRule set + routing (see PRODUCTION-GRADE-IMPROVEMENTS §4 for the starter pack)

### DEP-01 — Ingress + TLS not in manifests (aspirational)
- P1 gate · VERIFIED-STATIC. cert-manager referenced in Jenkinsfile only; no Ingress/Certificate manifests in k8s/
- Fix: Ingress + cert-manager Issuer/Certificate manifests; HSTS at edge; then PR-SEC-01 runtime assertion

### SEC-CR-02 — MFA encryption key silently derives from JWT_SECRET
- P1 · VERIFIED-STATIC. [SecretCipher.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/security/SecretCipher.java) L55-57 fallback. Rotating JWT_SECRET without CRYPTO_SECRET_KEY set ⇒ **every TOTP enrollment becomes undecryptable** (mass MFA lockout). Directly endangers the SEC-HYG-01 remediation (which requires JWT rotation!)
- Fix: set `CRYPTO_SECRET_KEY` everywhere **before** any JWT rotation; then make the fallback a startup **failure** in production profile

### DB-02 — `ddl-auto=validate` not provably pinned
- P1 · NOT-VERIFIED (absence of proof). Not found in tracked service YAMLs; compose sets no override. Boot default with Flyway is likely `none` (safe) but unproven without a boot log
- Fix: pin `spring.jpa.hibernate.ddl-auto: validate` explicitly in every service config; boot each service once to prove entity↔schema match

### PR-SEC-01 — Production posture never asserted at runtime
- P1 gate · VERIFIED-STATIC defaults + NOT-VERIFIED runtime. Code defaults are safe (MFA "true", simulation fail-fast, `kubernetes→production` profile group) — but no deployed environment has ever demonstrated: production profile active, simulation refused, `COOKIE_SECURE=true`, MFA enforced for super admins
- Fix: staging boot + smoke assertion script; archive output as launch evidence

---

## P2 — Fix soon after (or before, if effort is small)

| ID | Finding | Evidence | Fix direction |
|---|---|---|---|
| SUP-01 | No image digest pinning (all 29 FROM/image refs tag-only) | [evidence/container-image-inventory.tsv](evidence/container-image-inventory.tsv) | Pin `@sha256:` digests; Dependabot updates them |
| CI-01 | No PR-level CI (Jenkins-only; `.github/` has just dependabot) | .github/ | Minimal GH Actions: build+test+gitleaks on PR |
| DB-04 | Backups exist, restore **never rehearsed** | scripts/, k8s/backups.yml | Quarterly restore drill into scratch namespace; document RTO/RPO |
| EVT-02 | auth-service publishes Kafka directly (no outbox) — `user.anonymized`, `user.registered` can be lost | UserAnonymizationService.java:159 | Outbox in auth or reconciliation sweep for unpropagated erasures |
| PRIV-02 | No automated end-to-end erasure verification | doc 20 | Scheduled cross-DB PII-absence check for anonymized users |
| API-01 | No consumer-driven contract tests; dynamic FE paths uncovered by static join | doc 14 | Spring Cloud Contract or Pact on top-20 endpoints |
| PERF-01 | No load evidence at current commit (566 files changed since last k6) | doc 17 | Re-run k6 smoke/spike/soak vs staging |
| PERF-02 | Advisory-lock hold spans pricing-snapshot work | BookingService.java:261 | Measure under contention; consider snapshot-before-lock |
| HYG-01 | [k6.zip](../../k6.zip) 28.67 MB tracked | ls-files | Remove; fetch in CI (note: also bloats every clone forever until history purge) |
| HYG-03 | playwright-report/ + test-results/ artifacts tracked (incl. 7.2 MB trace.zip) | ls-files | Untrack + .gitignore |
| SEC-OP-04 | No secret-scanning gate | Jenkinsfile | gitleaks stage + pre-commit hook |
| INT-01 | SMS/WhatsApp are mocks but channels appear in UI | notification adapters | Integrate or hide before launch |
| TEST-03 | No coverage thresholds in CI (no JaCoCo gate, no vitest threshold) | Jenkinsfile, package.json | Add gates at current baseline, ratchet up |
| PAY-CR-04 | Stripe Connect deauthorized/edge account states unexercised | handler branches | Cover in sandbox campaign (PR-PAY-01) |

---

## P3 — Backlog (tracked, not blocking)

| ID | Finding |
|---|---|
| EVT-01 | 8 producer-only topics (booking.confirmed/rescheduled/transferred/checked-in/completed, room.approved/rejected, user.registered, password.reset) — dead weight or undocumented contracts |
| EVT-03 | No event schema registry / documented evolution rule |
| NOT-03 | Notification dedup TTL only 1 h |
| QUAL-02 | 79 × `orElse(null)` NPE seams |
| QUAL-03 | No static-analysis gate (SpotBugs/ErrorProne/strict ESLint) |
| QUAL-04 | config-server/discovery-server: zero tests |
| FE-02 | AdminBookings.jsx ~1,800 LOC monolith |
| A11Y-01 | No `prefers-reduced-motion`; contrast unaudited |
| BOOK-02 | `BookingFlowIntegrationTest` @Disabled |
| HYG-02 | 40+ root-level stress/log/probe files tracked |
| HYG-04 | infra/init-databases.sql dev passwords (ensure prod bootstrap differs) |
| SUP-02 | No SBOM generation |
| SUP-03 | No image signing/SLSA provenance |
| GOV-01 | No versioned-consent ledger |
| GOV-02 | Loki retention not configured in repo |
| GOV-03 | No CODEOWNERS/branch-protection evidence |
| GOV-04 | No RoPA/data-processing inventory (needed for EU) |
| GLB-01 | No i18n framework (English-only UI) |
| GLB-02 | No "new version available" PWA toast |
| PG-01/02/03 | Product gaps: authority-lock management UI absent; dispute filing ops-only (decide); approval queue executes REFUND_RETRY only |

---

## Fixed — verified this run (do not re-report)

SEC-001 cross-binge recovery-queue leak · SEC-009 PWA API caching · DATA-001 double booking (3-layer defense) · BOOK-001 slot holds · PAY-002 book-keeping-only refunds (fixed in source; behavior gate = PR-PAY-01) · P0-1 uncommitted tree · 26-Apr loyalty-config RBAC CRITICAL · SEC-003 production profile (partial: k8s yes via profile group; compose is dev by design). Full table: [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md).
