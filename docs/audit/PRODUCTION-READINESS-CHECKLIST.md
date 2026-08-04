# Production Readiness Checklist — Gate IDs

> AUD-2026-07-25-01 · commit `6440f58` · Launch requires **every PR-* gate = PASS with archived evidence**. ~~Most gates are OPEN because this audit was static-only~~ **Updated 2026-07-26 (execution phase):** gates provable in a local rebuilt stack are now **PASS-LOCAL** (evidence in the CURRENT-* execution docs); gates needing staging/provider/process remain OPEN. Supersedes the root [PRODUCTION-LAUNCH-CHECKLIST.md](../../PRODUCTION-LAUNCH-CHECKLIST.md) in precision (that file stays as the generic overview).

## Security gates

| Gate | Requirement | Status | Evidence required |
|---|---|---|---|
| PR-SEC-01 | Production profile active; simulation refused; COOKIE_SECURE=true; super-admin MFA enforced — asserted on a running staging env | **OPEN** (code defaults verified safe; runtime unproven) | assertion script output archived |
| PR-SEC-02 | admin_token.txt + stress-tokens.txt purged from HEAD **and history**; JWT rotated; sessions revoked | **OPEN — P0** | empty `git log --all` for both paths on origin; rotation record |
| PR-SEC-03 | `CRYPTO_SECRET_KEY` set in all envs **before** JWT rotation; fallback = boot failure in production | **OPEN — sequencing critical** | Vault entries (names only) + boot log |
| PR-SEC-04 | Secret scanning (gitleaks) gating CI | **OPEN** | pipeline run link |
| PR-SEC-05 | Prod DB bootstrap uses non-dev credentials (not init-databases.sql defaults) | **OPEN** | Vault-sourced creds confirmation |
| PR-SEC-06 | TLS end-to-end: Ingress + cert-manager manifests deployed; HSTS | **OPEN** (manifests don't exist yet) | curl + cert chain from staging |

## Payment gates

| Gate | Requirement | Status | Evidence |
|---|---|---|---|
| PR-PAY-01 | Full sandbox campaign (Razorpay + Stripe): pay, fail, full+partial refund, webhook replay dedup, dispute ingest, reconciliation of orphaned order | **OPEN — P0** | production-proof/ archive at current commit |
| PR-PAY-02 | Simulation disabled outside non-prod (fail-fast observed once in staging) | **OPEN** (code verified; observation pending) | boot log showing IllegalStateException on misconfig |
| PR-PAY-03 | Refund double-execution impossible (V14 index) demonstrated by replayed webhook in sandbox | **OPEN** | sandbox evidence |

## Data gates

| Gate | Requirement | Status |
|---|---|---|
| PR-DATA-01 | Flyway clean-apply on fresh PG16 for all 4 services at current heads (V20/V2/V80/V16) | **PASS-LOCAL (2026-07-26)** — empty-DB chain + live V20/V16 upgrade proven ([CURRENT-MIGRATION-VERIFICATION.md](CURRENT-MIGRATION-VERIFICATION.md)); staging repeat still required |
| PR-DATA-02 | `ddl-auto: validate` pinned + boot-verified in all 4 services | **PASS-LOCAL (2026-07-26)** — rebuilt services boot healthy in validate mode against migrated schema; pinning across all env profiles still to confirm |
| PR-DATA-03 | Restore drill executed (PG + Mongo), RTO/RPO documented | **OPEN** |
| PR-DATA-04 | V75 contention test in CI (Testcontainers) passing | **OPEN** |

## Reliability gates

| Gate | Requirement | Status |
|---|---|---|
| PR-REL-01 | Alert rules deployed (8-rule starter pack minimum) + routing test fired | **OPEN** |
| PR-REL-02 | k6 smoke/spike at current commit within thresholds | **PARTIAL (2026-07-26)** — smoke PASS at current commit (336/336, 0% failed, p95 21.69ms; [CURRENT-RUNTIME-VERIFICATION.md](CURRENT-RUNTIME-VERIFICATION.md) §7); spike/soak not re-run; fix TEST-EX-04 helper drift |
| PR-REL-03 | Canary + rollback drill executed once in staging | **OPEN** |
| PR-REL-04 | HA posture: managed/HA PG (not single StatefulSet), Kafka RF=3, Mongo RF=3 | **OPEN** (manifests exist; deployment unproven) |

## Product/notification gates

| Gate | Requirement | Status |
|---|---|---|
| PR-NOT-01 | Production SMTP verified with real send | **OPEN** |
| PR-NOT-02 | SMS/WhatsApp integrated or hidden in UI | **OPEN** |
| PR-PROD-01 | Approval-queue non-REFUND_RETRY actions: wired or hidden | **OPEN** (product decision) |

## Verified-by-this-audit — no action needed

**Executed 2026-07-26 (upgraded from static):**
- 846 backend + 362 frontend tests pass at current commit in pinned containers
- Booking oversell defense exercised live: 4-user synchronized race → 3 rooms, 3 bookings, zero overlap, correct rejection
- Outbox → Kafka → notification pipeline verified against live Mongo documents
- CSRF double-submit, consent gate, 401 fail-closed auth boundaries probed live

**Static (source-level):**
- Three-layer booking oversell defense present (holds/advisory-lock/V75)
- Tenant isolation on all admin surfaces incl. recovery queues (regression-tested)
- PWA token security + NetworkOnly API caching
- Immutable image tags; OWASP/Trivy/migration gates in Jenkins
- GDPR anonymization pipeline present with 3-service fan-out
- Money contract implemented in minor units with snapshot-based refunds
