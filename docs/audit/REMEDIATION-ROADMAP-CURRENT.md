# Remediation Roadmap — Current

> AUD-2026-07-25-01 · commit `6440f58` · ordered by dependency and risk, not just severity. Effort classes: S (<½ day), M (1-3 days), L (1-2 weeks). Supersedes docs/08-RECOMMENDATIONS-ROADMAP.md.

## Wave 0 — Sequencing-critical security (do in THIS order)

| # | Action | Issues | Effort | Why this order |
|---|---|---|---|---|
| 0.1 | Set `CRYPTO_SECRET_KEY` in every environment (Vault + compose env names) | SEC-CR-02 | S | **Must precede any JWT rotation** or MFA bricks |
| 0.2 | Delete admin_token.txt + stress-tokens.txt from HEAD; add gitleaks CI + pre-commit | SEC-HYG-01, SEC-OP-04 | S | Stops the bleeding before history rewrite |
| 0.3 | Purge git history (filter-repo/BFG) — **also drop k6.zip, playwright artifacts, root logs in the same rewrite** (one force-push, one clone-reset for the team) | SEC-HYG-01, HYG-01/02/03 | M | One coordinated rewrite instead of three |
| 0.4 | Rotate JWT_SECRET; revoke all sessions via denylist | SEC-HYG-01 | S | Safe now because of 0.1 |
| 0.5 | Turn SecretCipher fallback into production boot-failure | SEC-CR-02 | S | Prevents recurrence |

## Wave 1 — Launch gates

| # | Action | Issues | Effort |
|---|---|---|---|
| 1.1 | Provider sandbox campaign (Razorpay + Stripe): pay/fail/refund/partial/webhook-replay/dispute/reconcile; archive evidence in production-proof/ | PR-PAY-01, PAY-CR-04 | L |
| 1.2 | Ingress + cert-manager manifests (Issuer/Certificate, HSTS) | DEP-01 | M |
| 1.3 | Staging posture assertion script: production profile on, simulation refused, COOKIE_SECURE, super-admin MFA — archive output | PR-SEC-01 | S |
| 1.4 | Pin `ddl-auto: validate` in all 4 relational services; boot-verify once | DB-02 | S |
| 1.5 | Alert starter pack (8 PrometheusRules: outbox age, DLT depth, consumer lag, reconciliation last-success, 5xx, OOM, PG saturation, cert expiry) + routing | OBS-01, PAY-CR-03 | M |

## Wave 2 — Proof infrastructure

| # | Action | Issues | Effort |
|---|---|---|---|
| 2.1 | Testcontainers suite: PG (Flyway chain + **V75 two-thread contention test**), Kafka (outbox relay + dedup), Mongo (TTL) — wire into Jenkins | TEST-01, DB-03-class | L |
| 2.2 | Re-enable/rewrite BookingFlowIntegrationTest | BOOK-02 | M |
| 2.3 | k6 re-run at current commit (smoke/spike/soak) + new single-room contention scenario; measure advisory-lock hold time | PERF-01, PERF-02 | M |
| 2.4 | Restore drill: scripted PG + Mongo restore into scratch namespace, timed, documented RTO/RPO | DB-04 | M |
| 2.5 | Coverage gates (JaCoCo + vitest thresholds at baseline) | TEST-03 | S |

## Wave 3 — Operational hardening

| # | Action | Issues | Effort |
|---|---|---|---|
| 3.1 | GH Actions PR workflow (build, unit tests, gitleaks) | CI-01 | S |
| 3.2 | Digest-pin all images; SBOM (CycloneDX) both stacks | SUP-01, SUP-02 | M |
| 3.3 | Auth outbox (or erasure reconciliation sweep) + nightly PII-absence verification | EVT-02, PRIV-02 | M |
| 3.4 | SMS/WhatsApp: integrate or hide channels | INT-01 | S-M |
| 3.5 | Contract tests on top-20 endpoints | API-01 | M |
| 3.6 | Decide + document the 8 producer-only topics (delete or contract) | EVT-01, EVT-03 | S |

## Wave 4 — Quality and product backlog

| # | Action | Issues |
|---|---|---|
| 4.1 | Split AdminBookings.jsx; `orElse(null)` sweep on money/booking paths; static-analysis gates | FE-02, QUAL-02/03 |
| 4.2 | A11y pass: reduced-motion, axe in Playwright, contrast audit | A11Y-01 |
| 4.3 | Product decisions: approval-queue action types, authority-lock UI, dispute filing, loyalty-legacy thaw | PG-01..03 |
| 4.4 | Governance: CODEOWNERS, branch protection, ADR folder, consent ledger, RoPA, Loki retention | GOV-01..04 |
| 4.5 | i18n scaffolding + PWA update toast (when market/UX demands) | GLB-01/02 |

## Verification rule

Each wave closes only when its issues' **Verify** steps (in [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md)) pass and evidence is archived. Gate mapping to launch: [PRODUCTION-READINESS-CHECKLIST.md](PRODUCTION-READINESS-CHECKLIST.md).
