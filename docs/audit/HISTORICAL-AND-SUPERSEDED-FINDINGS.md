# Historical and Superseded Findings

> AUD-2026-07-25-01 · commit `6440f58` · Disposition of every major finding from prior audits (July-11/12 set at e3edbc1; July-16 cut; July-23 fresh cut; 26-Apr-2026 stress report). Statuses verified against current source this run.

## From the July audits

| Original ID | Finding | Disposition at 6440f58 | Evidence |
|---|---|---|---|
| SEC-001 | Cross-binge recovery-queue PII leak | **FIXED** | AdminRecoveryQueueController → `resolveRecoveryScope→requireManagedBinge`; regression test AdminRecoveryQueueScopeTest |
| SEC-003 | Dev posture reachable in production | **PARTIALLY FIXED** | Profile group `kubernetes→production` (auth application.yml L11-12); payment fail-fast; compose stays dev by design — residual = runtime assertion gate PR-SEC-01 |
| SEC-007 | Secrets committed (first token incident) | **PARTIALLY FIXED** | JWT rotated 2026-07-13; but token files still tracked + history unpurged → reopened as **SEC-HYG-01 (P0)** |
| SEC-009 | PWA cached authenticated API responses | **FIXED** | `/api` NetworkOnly in vite.config.js |
| DATA-001 | Double-booking possible | **FIXED** | Advisory lock (BookingRepository.java:433) + V75 trigger backstop + holds; residual = test coverage (TEST-01) |
| BOOK-001 | Slot holds not enforced | **FIXED** | SlotHold @Version, consume/expire/convert, SlotHoldExpiryScheduler |
| PAY-002 | Refunds were book-keeping-only (no provider call) | **FIXED IN SOURCE** | Durable intents, real Razorpay/Stripe refund calls, RefundWebhookService, receipt-first reconciliation (L158-208), V14 unique index; behavior gate = PR-PAY-01 |
| PAY-00x (tenant binding) | Payment/approval endpoints lacked binge binding | **FIXED** | requireManagedBinge on payment admin surface (tenant-isolation-matrix) |
| MONGO-00x | Missing Mongo indexes / anonymization | **FIXED** | TTL 90 d + dedup indexes + anonymization listener |
| P0-1 (July-23) | 599 uncommitted working-tree files | **FIXED** | Tree committed (`3d65090`), merged (`6440f58`); clean porcelain at baseline |
| P0-2 (July-23) | Tokens in git | **STILL OPEN** → SEC-HYG-01 | ls-files confirms tracked |
| Detached-HEAD warning (July-23) | Repo on detached HEAD | **FIXED** | main = origin/main |

## From the 26-Apr-2026 stress report (10 bugs)

| Bug | Disposition |
|---|---|
| CRITICAL: customer could write loyalty config | **FIXED** — class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` (LoyaltyV2SuperAdminController.java:49) + gateway path guard |
| Race conditions in booking under spike | **FIXED** by the three-layer defense (V75 was the direct response) |
| Hold-expiry leaks | **FIXED** — SlotHoldExpiryScheduler |
| Rate-limit gaps on auth endpoints | **FIXED** — gateway Redis limits |
| Remaining 6 (assorted 4xx/5xx handling, pagination, timeout tuning) | **FIXED per July changelogs** (HISTORICAL claim — spot-checks consistent; no contrary evidence found) |

## Historical evidence retained (never edit)

- production-proof/ — July runtime/load/security artifacts (point-in-time)
- docs/audit/evidence/* from July 11–16 (labeled historical)
- STRESS-TEST-REPORT-26APR2026.md
- docs/_previous/** incl. this run's archive `2026-07-25T00-00-00Z/`
- CHANGELOG-2026-07-21.md / CHANGELOG-2026-07-24-loyalty.md (test-pass claims are historical)

## Rule

A finding listed FIXED here must never be re-reported as new. If it regresses, open a fresh ID in [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md) referencing this table.
