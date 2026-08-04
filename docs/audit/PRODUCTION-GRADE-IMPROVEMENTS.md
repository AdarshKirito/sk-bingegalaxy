# Production-Grade Improvements — What Real Production Teams Do That This Repo Doesn't Yet

> **This is the "what could be better" document you asked for.** Audit run AUD-2026-07-25-01, commit `6440f58`. Each section: what production-grade looks like → where this repo stands → the concrete gap. This list is aspirational best practice; the *must-fix* subset lives in [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md) and the ordered plan in [REMEDIATION-ROADMAP-CURRENT.md](REMEDIATION-ROADMAP-CURRENT.md).

---

## 1. Testing pyramid with real infrastructure

**Production grade:** unit tests → Testcontainers integration tests (real PG/Kafka/Mongo/Redis) → contract tests → a small e2e suite — all gating every merge, with coverage ratchets.
**Here:** strong unit layer (80 backend + 42 frontend files, real assertions), then **nothing until manual k6**. Zero Testcontainers. The V75 oversell trigger — the single most important invariant in a booking system — is untested. No contract tests despite 429 endpoints and 372 client call sites. No coverage gate at all.
**Do:** Testcontainers suite (PG+Flyway+V75 contention, Kafka outbox/dedup, Mongo TTL); enable the disabled BookingFlowIntegrationTest; Pact/Spring-Cloud-Contract on the top-20 endpoints; JaCoCo + vitest thresholds at today's baseline, ratcheted quarterly.

## 2. Secrets lifecycle, not secrets files

**Production grade:** secrets never touch git (scanning enforced pre-commit + CI), live in Vault/External-Secrets everywhere, rotate on schedule, with a documented break-glass and a tested rotation runbook per secret (including *dependencies between secrets*).
**Here:** k8s side is genuinely good (External Secrets + Vault, no inline base64). But tokens sit in git history (SEC-HYG-01), nothing scans for the next leak, and the JWT→MFA-key coupling (SEC-CR-02) means an innocent rotation can brick MFA — exactly the kind of dependency a rotation runbook exists to catch.
**Do:** gitleaks in CI + pre-commit; history purge; `CRYPTO_SECRET_KEY` set explicitly everywhere and fallback turned into a production boot failure; per-secret rotation runbook with an order-of-operations table.

## 3. Progressive delivery with verified rollback

**Production grade:** every release canaries automatically, analysis gates promotion, rollback is *rehearsed*, and database changes are expand/contract so N and N−1 run simultaneously.
**Here:** surprisingly close on paper — Argo Rollouts canary 5→25→50→100 with AnalysisTemplate, Jenkins auto-`rollout undo`, migration-safety script in the pipeline. But no evidence any canary or rollback has ever executed, and Flyway community edition means roll-forward-only.
**Do:** run a full canary + forced-rollback drill in staging; adopt an expand/contract migration convention doc; record one rollback rehearsal per quarter.

## 4. Observability that pages people

**Production grade:** SLOs defined per user journey (booking success rate, payment success rate, p99 checkout latency), alert rules on burn rates + system signals, dashboards as code, runbooks linked from every alert.
**Here:** collection excellent (ServiceMonitor, Zipkin/Brave, Loki, per-service health), runbooks exist — but zero PrometheusRule and no dashboards in the repo. The system can be observed but never asks for help.
**Do (starter alert pack):** outbox oldest-row age > 5 min; any DLT depth > 0 for 10 min; Kafka consumer lag growth; refund-reconciliation scheduler last-success age; HTTP 5xx rate; JVM OOM/restart count; PG connection saturation > 80%; cert expiry < 14 d; Grafana dashboards committed alongside.

## 5. Supply-chain integrity

**Production grade:** digest-pinned base images, SBOM per artifact, image signing (cosign) verified at admission, provenance attestations, PR-level CI identical to main CI.
**Here:** immutable per-commit tags ✅, `npm ci` + lockfile ✅, OWASP+Trivy gates ✅, Dependabot ✅ — a good core. Missing: digest pinning (29 tag-only image refs), SBOM, signing, and any CI before merge (Jenkins-only).
**Do:** pin digests; CycloneDX SBOM in both builds; cosign sign+verify; a 5-minute GitHub Actions PR workflow (build, unit tests, gitleaks).

## 6. Data lifecycle you can prove

**Production grade:** backups restored on a schedule (rehearsed RTO/RPO numbers), erasure verified end-to-end automatically, retention policies enforced everywhere including logs.
**Here:** daily backup CronJob + restore scripts + 90 d Mongo TTL + a real anonymization pipeline — solid design, zero proof: no restore drill, no erasure verification, no auth outbox for the erasure event, no Loki retention config.
**Do:** quarterly restore drill (scripted, timed, documented); nightly PII-absence check across all four DBs for anonymized users; outbox (or sweep) for auth events; Loki retention limits.

## 7. Contract-first APIs and events

**Production grade:** OpenAPI published per service and diffed in CI (breaking-change alarm), event schemas in a registry with enforced compatibility mode, deprecation policy documented.
**Here:** consistent URI versioning and envelopes, but contracts live only in code; events are convention-only POJOs; 8 topics are published into the void.
**Do:** springdoc-openapi + CI diff; either adopt a registry (even a JSON-schema folder with CI compat checks) or delete the dead topics; write the one-page event-evolution rule.

## 8. Performance as a regression suite

**Production grade:** load tests bound to commits, run on a schedule against staging, with thresholds in CI and capacity models updated per release.
**Here:** excellent k6 suite exists (smoke/spike/soak/worst-case) with real history — but the results are frozen artifacts from 566 files ago, and the runner binary is committed to git instead of fetched.
**Do:** nightly k6 smoke vs staging with thresholds; the spike/soak suite per release candidate; delete k6.zip, fetch in CI; add a booking-contention scenario that hammers one room (validating V75 + the advisory-lock hold-time question PERF-02).

## 9. Frontend operational maturity

**Production grade:** error tracking wired (Sentry) with release tagging, web-vitals/RUM budgets, a11y checks in CI (axe), i18n scaffolding before the second market, update-available UX for PWAs.
**Here:** strong security posture (httpOnly, single-flight refresh, idempotency keys, DOMPurify), clean guard structure — but no runtime error telemetry confirmed wired, no a11y automation, English-only, silent SW updates, and one 1,800-line page.
**Do:** verify Sentry DSN wiring per env; axe-core in Playwright; `prefers-reduced-motion` + contrast pass; "new version" toast; split AdminBookings into container + 5-6 child components.

## 10. Governance that scales past one maintainer

**Production grade:** CODEOWNERS + branch protection + signed commits; ADRs for irreversible decisions; consent versioning; RoPA before EU data; docs whose census numbers are generated, not hand-typed.
**Here:** excellent domain audit trails in-app (BookingEventLog, RateCodeChangeLog, membership events); repo-level governance absent; three docs carried stale hand-written numbers until today.
**Do:** CODEOWNERS + protected main + required checks; a `docs/adr/` folder (first entries: money contract, outbox pattern, module deny-list model); consent-version ledger; make census numbers CI-generated (the scripts from this audit's evidence generation are reusable).

---

## Ranked "next five" if effort is scarce

1. **Provider sandbox campaign** (PR-PAY-01) — nothing else matters if money doesn't work
2. **Secrets purge + rotation done in the right order** (SEC-HYG-01 with SEC-CR-02 sequencing)
3. **Testcontainers for V75 + outbox** (TEST-01) — your best invariants deserve proof
4. **Alert starter pack** (OBS-01) — 8 rules, one afternoon, transforms operability
5. **Ingress/TLS manifests + staging posture assertion** (DEP-01/PR-SEC-01) — makes "production" a real, checkable thing
