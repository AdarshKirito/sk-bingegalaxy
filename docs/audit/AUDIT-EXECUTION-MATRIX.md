# AUDIT EXECUTION MATRIX — AUD-2026-07-25-01

> Zero-omission enforcement: every category below rolls up to an itemized inventory with per-item status.
> Legend: **S** static review · **B** build · **T** tests executed · **R** runtime verified · **D** docs reconciled.
> Statuses: PASS / FAIL / PARTIAL / BLOCKED-EXTERNAL / NOT-APPLICABLE (N-A). Item-level detail lives in the
> referenced evidence files; this matrix is the roll-up with no blank rows.
> Snapshot: `main` @ `6440f58`. Owner: lead auditor + 9 specialist subagents (pass 2) + adversarial rechecks (pass 5).

## 1. Repository directories (all 14 top-level entries)

| Directory | S | Coverage | Evidence | Issues | Disposition |
|---|---|---|---|---|---|
| backend/ (9 modules) | PASS | every file classified (A/B/C) | evidence/final-file-coverage.tsv | see §2 | AUDITED |
| frontend/ | PASS | every file classified | same | FE issues in register | AUDITED |
| docs/ | PASS | all 109 MD classified | DOCUMENTATION-MAP.md | DOC-CR-01..14 | RECONCILED |
| infra/ | PASS | reviewed | 15-DEVOPS doc | OBS-01 | AUDITED |
| k8s/ (23 manifests) | PASS | all manifests reviewed | deployment-capability-matrix.tsv | DEP-01 | AUDITED |
| load-tests/ (5 JS) | PASS + executed | smoke run live | CURRENT-RUNTIME-VERIFICATION.md | PERF-02 | AUDITED+RUN |
| scripts/ | PASS | reviewed | 18-SUPPLY-CHAIN doc | — | AUDITED |
| production-proof/ | PASS | classified HISTORICAL | HISTORICAL-AND-SUPERSEDED-FINDINGS.md | — | HISTORICAL |
| k6_bin/ | PASS | binary verified executable, not tracked in git | tool-detection-results.md | SEC-HYG-02 (k6.zip tracked) | AUDITED |
| root scripts/logs (61 files) | PASS | each classified | final-file-coverage.tsv class C/X | SEC-HYG-01 (tokens) | AUDITED |
| .github/ | N-A | no workflows exist (Jenkins-only CI) | 15-DEVOPS | CI-01 | RECORDED |
| .mvn/, gradle | N-A | absent | — | — | N-A |
| docs/_previous/ | PASS | archive (audit-created) | audit-file-change-manifest.tsv | — | ARCHIVE |
| sk-binge-galaxy;C/ (workspace stray) | PASS | empty dir + broken outer .git noted | repository-baseline.md | HYG-EX-01 | RECORDED |

## 2. Services (build/test/runtime per module — all 10)

| Service | S | B | T | R (rebuilt image, healthy, current) | Evidence |
|---|---|---|---|---|---|
| common-lib | PASS | PASS | 59/59 | n/a (library) | CURRENT-BUILD-AND-TEST-RESULTS.md |
| discovery-server | PASS | PASS | no tests (N-A) | PASS | CURRENT-RUNTIME-VERIFICATION.md |
| config-server | PASS | PASS | no tests (N-A) | PASS | same |
| api-gateway | PASS | PASS | 66/66 | PASS (health UP; routing verified) | same |
| auth-service | PASS | PASS | 107/107 | PASS (V20 applied; login/register verified) | same |
| availability-service | PASS | PASS | 27/27 | PASS (V2) | same |
| booking-service | PASS | PASS | 433/433 (6 skipped @Disabled) | PASS (V80; booking flow verified) | same |
| payment-service | PASS | PASS | 93/93 | PASS (V16 applied; provider dormant) | same |
| notification-service | PASS | PASS | 61/61 | PASS (Mongo indexes verified live) | same |
| frontend (nginx PWA) | PASS | PASS (vite 11.0s) | 362/362 vitest | PASS (HTTP 200; SW precache 129) | same |

## 3. Controllers / endpoints / routes / API pairs

| Inventory | Count | S | R | Evidence |
|---|---|---|---|---|
| Controllers | 47 | all reviewed (pass 2) | sampled via boundary probes | endpoint-inventory-current.tsv |
| HTTP endpoints | 429 | all inventoried + RBAC-classified | 401/403/200 boundary matrix sampled live (public, authed, internal classes) | current-security-traces.md + CURRENT-RUNTIME-VERIFICATION.md |
| Frontend routes | 71 | all inventoried | 5 key routes exercised via e2e/HTTP | frontend-routes-current.tsv |
| Frontend→API pairs | 372 static | all joined against backend | login/profile/booking pairs exercised live | frontend-api-pairs-current.tsv |
| Kafka topics | 20 (+8 DLT observed live = deployed superset) | producer/consumer matrix complete | topics listed live; consumer groups active | producer-consumer-matrix.tsv |
| Producers/consumers | 16 @KafkaListener, outbox producers | all traced | consumption verified via booking flow events | event-contract-matrix.tsv |
| Schedulers | 33 @Scheduled | all inventoried | ShedLock collection verified live in Mongo | 12-EVENTS doc |
| Entities / repositories | 93 / 93 | all inventoried; 16 high-risk diffed vs schema | live schema matches (validate mode boots) | entity-migration-diff.tsv |
| Migrations | 118 (auth V20, avail V2, booking V80, payment V16) | all read | **all applied from empty DBs by rebuilt services** | CURRENT-MIGRATION-VERIFICATION.md |
| Docker services | 23 compose entries | all reviewed | 15 core containers healthy (+ init/one-shot exited 0) | CURRENT-RUNTIME-VERIFICATION.md |
| K8s resources | 23 manifests | all reviewed | BLOCKED-EXTERNAL (no staging cluster) | EXTERNAL-BLOCKERS §B3 |
| External integrations | Razorpay, Stripe, Google OAuth, SMTP, WhatsApp, Web Push, Sentry, Zipkin | all statically traced | provider calls BLOCKED-EXTERNAL; Zipkin container healthy | CURRENT-PROVIDER-VERIFICATION.md |
| Test suites | 80 backend classes, 42 vitest files, 7 e2e specs | inventoried | backend+vitest executed (846 + 362 pass); e2e executed: 57 passed / 5 failed / 1 flaky (PARTIAL, TEST-EX-03) | CURRENT-BUILD-AND-TEST-RESULTS.md |
| Markdown documents | 109 | every one classified current/historical/superseded | banners applied to 5 stale docs | DOCUMENTATION-MAP.md |

## 4. Major workflows (static trace + runtime status)

| Workflow | S (traced in 06-WORKFLOWS.md) | R | Notes |
|---|---|---|---|
| Registration → login → profile | PASS | **PASS (executed live)** | fresh user registered via gateway |
| Admin login + MFA boundary | PASS | PARTIAL (admin login exercised; TOTP enrolment not scripted) | SUPER_ADMIN_REQUIRE_MFA=false in local compose (documented default) |
| Browse → availability → hold → booking | PASS | **PASS (executed live)** | see runtime doc §5 |
| Concurrency: same-slot competition | PASS | **PASS (executed live — k6 contention + duplicate attempt rejected)** | V75 trigger + advisory locks live |
| Cancellation → refund saga | PASS | PARTIAL (cancellation executed; refund needs provider) | B1 |
| Payment capture/webhook | PASS | BLOCKED-EXTERNAL (B1); mocked-contract PASS | 93/93 |
| Outbox → Kafka → notification | PASS | **PASS (observed live: booking events consumed; notifications persisted)** | runtime doc §6 |
| DLT routing + replay | PASS | PARTIAL (DLT topics exist live; poison-message injection not run) | PERF-02 |
| Waitlist promotion | PASS | unit-verified (race test) | not runtime-scripted |
| Booking transfer | PASS | unit-verified | not runtime-scripted |
| Reminder scheduling (unique) | PASS | **PASS (unique Mongo index verified live)** | |
| GDPR anonymization | PASS (cron + consumers traced) | not runtime-triggered (2:30 AM cron) | verified static + tests |
| Loyalty accrual/redemption | PASS | unit-verified (433 booking tests incl. loyalty) | 26-Apr bug fix regression-tested |
| PWA offline/cache behavior | PASS | PASS (SW built; NetworkOnly /api confirmed in built sw.js) | |

## 5. Final deliverables (all present)

00–22, 25, 26 numbered docs · DOCUMENTATION-MAP · EXECUTIVE-SUMMARY-CURRENT · ISSUE-REGISTER-CURRENT ·
CURRENT-PROBLEMS-AND-ERRORS · PRODUCTION-GRADE-IMPROVEMENTS · REMEDIATION-ROADMAP-CURRENT ·
PRODUCTION-READINESS-CHECKLIST · HISTORICAL-AND-SUPERSEDED-FINDINGS · AUDIT_STATUS · FINAL-AUDIT-VALIDATION ·
ENVIRONMENT-RECOVERY-AND-EXECUTION · AUDIT-EXECUTION-MATRIX (this) · CURRENT-RUNTIME-VERIFICATION ·
CURRENT-BUILD-AND-TEST-RESULTS · CURRENT-MIGRATION-VERIFICATION · CURRENT-PROVIDER-VERIFICATION ·
EXTERNAL-BLOCKERS-AND-UNVERIFIED-ITEMS + evidence/* (30 files).

No required row in this matrix is blank. Items not executed carry an explicit PARTIAL / BLOCKED-EXTERNAL / N-A
with reasons and tracking IDs.
