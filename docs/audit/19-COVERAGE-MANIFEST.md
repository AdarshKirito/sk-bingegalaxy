# 19 — Coverage Manifest

> **Historical phase coverage record.** Current 1,419-row per-file coverage: [`../25-COVERAGE-MANIFEST.md`](../25-COVERAGE-MANIFEST.md) and `evidence/final-file-coverage.tsv`.

Honest accounting of what was inspected and to what depth (Rule 6: no fake completeness). Levels: **A** = deep inspection (logic/security/data), **B** = structural inspection, **C** = classification-only (with reason).

First-party file baseline: **1,336** files (`docs/audit/evidence/file-inventory.tsv`, excludes node_modules, target, .git, dist, playwright-report, test-results, k6_bin, .vite, .npm-cache, .tmp, docs/_previous).

## Coverage by area

| Area | Depth achieved | How | Confidence |
|---|---|---|---|
| Repository census (all dirs/services/manifests) | A | 3 parallel census agents + direct listing | High — every top-level dir classified |
| AuthN / AuthZ / sessions | A | Specialist agent + runtime auth probes | High |
| Multi-tenant binge isolation | A | Specialist agent + runtime isolation probes | High |
| Availability / booking / holds / concurrency | A | Specialist agent + live DB constraint introspection | High (concurrency runtime NOT VERIFIED — see below) |
| Database & data integrity (4 Postgres DBs + Mongo) | A | Specialist agent + live `psql` introspection | High (Mongo TTL static-only — empty dev store) |
| Payment / refund / disputes | A (static) | **Deep pass COMPLETED** by direct lead inspection 2026-07-12 (`evidence/specialist-05`); found PAY-002 Critical | High for code correctness; disputes flow only skimmed; no runtime (Docker down) |
| Notification delivery reliability | B→A (static) | Direct read of `NotificationService` retry/backoff/deliveryStatus | High — retry+backoff+operator-retry confirmed present |
| Events / messaging / outbox / DLQ | A (via booking agent) + B (census) | Booking-concurrency agent covered outbox/consumers; topic list from common-lib | High for booking/payment flows; notification templates B |
| API contracts (endpoints ↔ controllers) | B | Census (369 frontend calls, ~48 controllers) + spot traces; full bidirectional diff NOT completed | Medium |
| Frontend routing / components / state / PWA | B | Frontend census agent (70 routes, 60 components, guards, api.js) | Medium — no per-component logic audit |
| Frontend forms / UX / accessibility | C→B | Census only; no dedicated UX/a11y pass this run | Low — NOT deeply audited |
| Performance / concurrency / load | C→B | Existing k6 evidence cited as historical; no new load test | Low — NOT independently verified |
| DevOps / reliability (compose/k8s/Jenkins) | B | Docs-infra census agent + incidental findings | Medium |
| Docs vs code consistency | A | Direct read of 34 project MD files + code cross-check | High |
| Integrations / OTA readiness | B | Census (Razorpay, mail, Web Push, WhatsApp/SMS config) | Medium |

## File-class accounting (Level A/B/C by type)

| Class | Files (approx) | Level | Notes |
|---|---|---|---|
| Backend `.java` (main) | ~623 | A for security/domain/data/config; B for DTOs/mappers | 4 services deep-audited; payment partial; all entities/migrations sampled |
| Backend `.java` (test) | 75 | B | Inventoried; quality assessed for gaps (TEST-001); not executed (no host JDK) |
| Flyway migrations `.sql` | ~108 | A (sampled) / B (rest) | High-value + recent (V60–V74) read; ~93 read via grep not line-by-line |
| Frontend `.jsx/.tsx/.ts` | ~157 | B | Routes/guards/state/api deep; per-component logic not audited |
| Frontend `.js` (services/utils) | ~87 | B | endpoints.js + api.js inspected; others structural |
| CSS | 34 | C | Structural note only (design-token system present) |
| Compose / k8s / infra YAML | ~47 | B | Compose + k8s manifests inventoried; not deployed/tested |
| Config-server service YAMLs | 6 | A | Security-relevant configs read (profile, secrets defaults) |
| Docs `.md` (project-owned) | 34 | A | All read for contradiction register |
| Root scripts (`.ps1/.sh/.cjs/.mjs`) | ~27 | C | Operational/stress scripts — classified, key ones (rebuild) summarized |
| Build/crash/log/k6 artifacts | ~90 | C | Inert byproducts (logs, `hs_err_pid*`, k6 json/out, `spike.out` 202MB) — repo-hygiene issue DEVOPS-002 |
| Binary/assets (png, crt, zip) | ~6 | C | No behavioral role (except `k6.zip` = hygiene) |

## Explicitly NOT verified (with reason)

| Item | Status |
|---|---|
| Concurrent double-booking behavior at runtime | **VERIFIED 2026-07-12 (R7.1)** — two customers raced a capacity-1 slot; exactly one booking landed (DB-confirmed). Advisory-lock guard works. |
| Mongo TTL / unique index existence | **VERIFIED 2026-07-12 (R7.2)** — live `getIndexes()` shows only `_id_` on all collections (73 notifications, 79 reminders); TTL + unique absent. DATA-003 CONFIRMED. |
| Full checkout → payment → refund happy path | **Partially verified (R7.3):** book + payment-initiate driven at runtime; admin-simulate blocked by (correctly-enforced) super-admin MFA. Event-confirm + refund covered by code pass (`specialist-05`) + `PaymentEventListenerTest`. |
| Pricing/tax/FX/loyalty full trace | **Upgraded to A (static+runtime, 2026-07-12):** `CheckoutQuoteService`+`TaxService`+`FxLockService`+`BookingService` pricing read in full — PRICE-001 (dup formula) and **PRICE-002 (FX-lock dead end)** found. Tax choke-point + negative-total guard confirmed. **Correction:** the earlier "FX-expiry confirmed" note was wrong — `FxLockService.consume` has 0 callers; runtime shows `fx_rate_locks`=0 rows, all bookings `fx_rate=1`. Full surge-rule matrix + loyalty earn/redeem still spot-level. |
| Frontend UX / accessibility | **Partial A (static):** Modal/ConfirmProvider/ErrorBoundary read — A11Y-001 found; **whole-frontend a11y attribute survey done — A11Y-002 found** (aria-describedby=0, aria-invalid=6 → errors not AT-associated). Full contrast, keyboard sweep, responsive, visual rendering still NOT VERIFIED (no browser automation). |
| Events/notifications | **Runtime-CONFIRMED (R7.4):** outbox→Kafka→consumer→Mongo pipeline works end-to-end (booking + auth producers). Delivery retry/backoff confirmed. Event-schema-evolution/versioning still static-level; no load/perf test (no tool). |
| Disputes flow | **A (static, 2026-07-12):** `DisputeWebhookService` read — **fail-closed** HMAC verification, dedup, booking-status invariant preserved. Positive control, no finding. |
| Loyalty earn/redeem/reversal | **A (static, 2026-07-12):** no-double-earn, idempotent + clamped + balance-aware reversal confirmed; LOYALTY-001 (Low/QUESTION) on earn→spend→cancel edge. |
| AdminApprovalService (maker-checker) | **CONFIRMED:** 4-eyes / no self-approval (`approve:126-130`). |
| API contract sweep | **DONE — base-path + method-level (2026-07-12):** all frontend calls map to real gateway-routed controllers; method-level request-body field-drift diff completed for the highest-risk flows (register/booking-create/payment/callback/refund/reschedule/waitlist/cash/add-payment) → strict-DTO parity is **clean** (positive control), residual drift is Low (API-001) + the orphaned checkout client (PRICE-002). Full response-DTO→frontend-consumer diff for every endpoint still spot-level. |
| Performance / load | NOT VERIFIED — no load tool on host; existing k6 evidence historical. Contention analysis is static (`14-PERFORMANCE-CONCURRENCY.md`). |
| Backend unit/integration test results | No JDK/Maven on host; Docker-based build excluded (disk-safety). Existing logs cited as historical only. |
| Frontend Vitest / Playwright results | No Node on host. |
| Visual/UI rendering, responsive, dark mode, keyboard nav | No browser-automation tooling available. |
| Payment/refund dedicated deep pass | Specialist agent failed on session limit (PAY-001). |
| k8s deployment behavior (HPA, Istio, Argo, rollback) | Not deployed; manifests reviewed structurally only. |

## Completeness statement

This audit did **not** achieve uniform Level-A coverage of the entire repository. It achieved **deep (Level A) coverage of the highest-risk areas** (auth, isolation, booking concurrency, database integrity) with runtime corroboration, and **structural (Level B) or census (Level C) coverage** of the remainder, with payment deep-verification and all frontend-UX/accessibility/performance passes explicitly incomplete this run. The audit is therefore **substantially but not exhaustively complete**; the unresolved areas above are the honest remaining work.
