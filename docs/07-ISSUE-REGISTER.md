# 07 — Issue Register (the problem list)

> **⚠️ Superseded (2026-07-25, commit `6440f58`):** P0-1 (uncommitted tree) and P0-3 (stale deploy vs source) are **FIXED** — the tree was committed and `main` = `origin/main`, clean. P0-2 (tokens in git) is **still open**, tracked as SEC-HYG-01. The sole authoritative register is now [audit/ISSUE-REGISTER-CURRENT.md](audit/ISSUE-REGISTER-CURRENT.md); disposition of every item here: [audit/HISTORICAL-AND-SUPERSEDED-FINDINGS.md](audit/HISTORICAL-AND-SUPERSEDED-FINDINGS.md). Text below preserved as written.

Prioritized. Each item: **what**, **evidence**, **impact / what it breaks later**, **the correct way**. Severity: **P0** = blocks production / data-or-money-loss / security. **P1** = serious, fix before scale. **P2** = correctness/maintainability debt. **P3** = polish.

Legend for confidence: *verified* = read the code/artifact directly; *structural* = inferred from structure + prior audit; *needs-check* = call out to confirm at runtime.

---

## P0 — Blocks production

### P0-1 · Nothing is reproducible from a commit *(verified)*
- **Evidence:** detached `HEAD` at `e3edbc1` with **~599 uncommitted changed files** (`git status`).
- **Impact / later:** the source you audit and the build that's deployed can silently diverge. This is the mechanical root cause of the recurring "I fixed the bug but it's still there" pattern (the fix is in the uncommitted tree; the running container was built from something else). No rollback, no bisect, no reviewable history, no CI gate on the real code. **Every other finding here is un-trustworthy until the tree is committed**, because you can't prove what's running.
- **Correct way:** commit the working tree on a branch **now** (small, themed commits if possible; one big commit if not), get off detached HEAD, push, and make the deploy pipeline build *from a tagged commit only*. Treat "deploy only builds a tagged SHA" as policy.

### P0-2 · Secrets in git history *(verified)*
- **Evidence:** `admin_token.txt` and `stress-tokens.txt` are still in committed `HEAD` (`git cat-file -e HEAD:admin_token.txt` → present); locally only *staged for deletion*.
- **Impact / later:** deleting the file in a new commit does **not** remove it from history. Anyone with repo access (or if the repo ever goes public/leaks) recovers live-ish admin JWTs/tokens. 
- **Correct way:** (1) commit the deletions; (2) `git filter-repo` (or BFG) to purge both files from all history; (3) **rotate** the JWT signing secret and any admin credentials they contain; (4) force-push the cleaned history and re-clone everywhere. Also rotate `JWT_SECRET`/VAPID as a precaution.

### P0-3 · Deployed build likely stale vs. source *(structural, follows from P0-1)*
- **Impact / later:** operator bug reports frequently describe the *stale deployed build*, not the working tree — so fixes get "re-implemented" against code that's already fixed. Wastes cycles and can double-apply changes.
- **Correct way:** after P0-1, rebuild and redeploy from the committed SHA, then re-triage the open bug list against *that* build. Until then, always `git status` before re-implementing a reported bug.

*(Payment/refund money-movement was hardened in this tree — durable intents, at-most-once, receipt-first reconciliation — but its production-readiness gate is **provider sandbox proof** (Razorpay `refund.processed`/`refund.failed` webhooks registered + exercised), which is an operational, not code, gap. Keep it on the P0 go-live checklist.)*

---

## P1 — Fix before scale / launch

### P1-1 · God-classes concentrate regression risk *(verified sizes)*
- **Evidence:** `BookingService.java` **5,189 lines**, `PaymentService.java` **2,247**; frontend `AdminBookings.jsx` **2,385**, `BingeManagement.jsx` **2,029**, `AdminLoyaltyCenter.jsx` 1,616.
- **Impact / later:** these are where new bugs will keep appearing; they're near-impossible to unit-test in isolation, and every feature touches them, so blast radius per change is large. This *is* the maintainability ceiling.
- **Correct way:** decompose by seam — extract `BookingLifecycleService` / `BookingPricingFacade` / `BookingEventPublisher` / `BookingQueryService` from `BookingService`; split `PaymentService` into order / refund / capture / reconciliation collaborators. Frontend: extract table/modal/form components + move fetch logic into hooks. Do it behind existing tests; add golden-master tests first.

### P1-2 · Redis is a soft single-point for security posture *(verified)*
- **Evidence:** `JwtAuthenticationFilter` sid-revocation and gateway rate limiting both **fail open** on Redis error/absence (documented as "availability over strictness"). Booking advisory locks + slot holds also live in Redis.
- **Impact / later:** during a Redis outage, revoked/force-logged-out tokens survive to natural expiry (up to ~15 min) and rate limits stop enforcing — exactly when you might *want* them (incident/abuse). Booking concurrency guarantees also weaken.
- **Correct way:** conscious product sign-off on the fail-open window; consider **fail-closed revocation on privileged (admin/super-admin) paths** while keeping customer paths fail-open; Redis HA (sentinel/cluster) so the window is rare; alert on Redis unavailability.

### P1-3 · Dev-convenience defaults that are dangerous in prod *(verified: compose)*
- **Evidence:** `SUPER_ADMIN_REQUIRE_MFA` defaults **false**; `CRYPTO_SECRET_KEY` defaults to a **JWT-derived** key (rotating `JWT_SECRET` then makes enrolled TOTP secrets undecryptable); `PAYMENT_SIMULATION_ENABLED` defaults true.
- **Impact / later:** if any default leaks into a prod profile: MFA silently off for super-admins; a JWT rotation bricks all MFA; or real bookings run on simulated payments. Silent, high-blast-radius.
- **Correct way:** production profile **fail-fast** if `SUPER_ADMIN_REQUIRE_MFA!=true`, `CRYPTO_SECRET_KEY` unset/derived, or `PAYMENT_SIMULATION_ENABLED=true`. Never let prod boot on a dev default for these three.

### P1-4 · Migration-per-change is a hard, easy-to-forget deploy gate *(verified: `ddl-auto=validate`)*
- **Evidence:** all four Postgres services validate against Flyway; entity/column drift **fails startup**.
- **Impact / later:** a field added without its migration is a **service that won't boot** on deploy (not a soft bug). The `@Lob`-on-TEXT trap is the same class (breaks every read). High-frequency self-inflicted outage.
- **Correct way:** a pre-commit / CI check that every entity change ships a migration (there's already `scripts/check-migration-safety.sh` — wire it into CI as a required gate); an ephemeral-DB integration test that boots each service against the real migration chain on every PR.

### P1-5 · The "four-places-per-endpoint" authz wiring *(structural + verified seams)*
- **Evidence:** a new admin endpoint must be wired in (1) gateway path/scope gating, (2) service `SecurityConfig`, (3) per-binge module matrix, (4) frontend guard + `useModuleAccess`. Missing one ⇒ 403 storm *or* an authz hole. (Prior real bugs: notification bell inside MESSAGES gate; public-DTO ownership 403 storm.)
- **Impact / later:** the single highest-frequency source of real bugs here; grows worse as endpoints multiply.
- **Correct way:** centralize the mapping — one declarative registry (path → required role/scope/module) consumed by gateway + service + frontend, instead of four hand-maintained lists. At minimum, a contract test that asserts every `/admin/**` route is present in all four places.

---

## P2 — Correctness & maintainability debt

### P2-1 · Fail-open-within-namespace authz *(verified)*
- **Evidence:** `/api/v1/auth/** permitAll` and booking's broad `permitAll` catalog list are catch-alls; a new endpoint under them is **public** unless a more-specific matcher precedes it.
- **Correct way:** prefer explicit per-endpoint allow-lists; end each service chain with `anyRequest().authenticated()` and never rely on a broad namespace `permitAll`. Add a test enumerating public endpoints.

### P2-2 · Soft `iss`/`aud` JWT enforcement *(verified)*
- **Evidence:** tokens missing `iss`/`aud` are accepted (backward-compat).
- **Correct way:** once all live tokens carry both (one access-token lifetime after rollout), flip to **hard** enforcement.

### P2-3 · Swallowed exceptions / NPE seams *(verified counts, needs per-site check)*
- **Evidence:** ~14 empty `catch {}`, ~10 `printStackTrace`/`catch(Exception ignored)`, **~75 `.orElse(null)`** across backend main.
- **Impact / later:** empty catches hide failures (a refund/notification that silently no-ops); `.orElse(null)` seams turn a missing row into a downstream NPE far from the cause.
- **Correct way:** audit each site — swallow only with a comment justifying it + a metric/log; replace `.orElse(null)` with `orElseThrow(ResourceNotFound)` or explicit Optional handling at the boundary.

### P2-4 · Dual frontend state (Zustand + Context) *(verified)*
- **Evidence:** `stores/authStore.ts`+`bingeStore.ts` **and** `context/AuthContext.jsx`+`BingeContext.jsx` both model auth/binge.
- **Correct way:** pick one (Zustand is already there and testable); delete the other; have the axios client read from the store, not raw `localStorage`, so there's a single source of truth.

### P2-5 · Two admin CSS vocabularies *(verified: 268 `.adm-*` vs 162 `.admin-*`)*
- **Impact:** pages look unstyled/broken when they target the wrong system.
- **Correct way:** converge on `admin-system.css`; codemod `.adm-*` usages; delete the dead vocabulary.

### P2-6 · Inline endpoint calls, no API layer *(verified: single 312-line `api.js`)*
- **Impact:** ~400 endpoint call-sites scattered across pages ⇒ contract drift, duplicated error handling.
- **Correct way:** a thin per-resource `api/bookings.ts`, `api/payments.ts`, … built on the existing instance; ideally typed.

### P2-7 · Pricing-engine combinatorial correctness *(structural)*
- **Evidence:** layered resolution (base → rate code → customer profile → surge → FX → tax) with HALF_UP at multiple steps.
- **Impact / later:** rounding order and layer interaction are exactly where money bugs hide; a change to one layer can shift totals by a minor unit.
- **Correct way:** golden-master tests over a matrix of (currency × rate code × surge × tax-inclusive/exclusive × customer profile), asserting exact expected totals; document the canonical order of operations (rounding once, at the end, per the money contract).

### P2-8 · Availability↔booking sync hop resilience *(needs-check)*
- **Evidence:** booking→availability has `AvailabilityClientFallback`; confirm availability→booking (and payment→booking) Feign edges have timeouts + fallbacks so a slow booking-service can't pin request threads.
- **Correct way:** explicit connect/read timeouts + circuit breakers on every sync edge; verify the internal calls degrade rather than cascade.

### P2-9 · Config-server has no runtime refresh *(verified: native, baked-in yml)*
- **Impact:** any config change needs a config-server rebuild+redeploy; no `/actuator/refresh` from an external source of truth.
- **Correct way:** acceptable for now; if config churn grows, move to a git-backed config repo + `@RefreshScope` or a proper secrets manager for the sensitive values.

---

## P3 — Polish / hygiene

- **Repo-root clutter *(verified):*** dozens of ad-hoc scripts, `k6.zip` (30MB), `spike.out` (~200MB), `hs_err_pid*.log`, stress/probe `.ps1`, build logs litter the real git root. Mostly gitignored (only one 7MB `trace.zip` is tracked), but it makes the repo hard to navigate. Move to `tools/` / `artifacts/` or delete; ensure `.gitignore` covers all of it.
- **`TODO/FIXME` count is low (5)** — good signal of a maintained codebase; close them out anyway.
- **Swagger gated to admin** — fine; ensure it's disabled entirely in prod if not needed.
- **i18n coverage** — en/hi/ta/te exist; verify new admin strings are keyed, not hard-coded, so non-English stays complete.
- **A11y residue** — custom drawers/click-only rows (see 06) need a keyboard/axe pass.

---

## What is *missing / required* (not "broken", but needed)

1. **A committed, tagged, CI-built release** (P0-1) — the precondition for everything.
2. **Provider sandbox proof** for the money paths (Razorpay refund/dispute webhooks registered + exercised end-to-end).
3. **Runtime/integration test suite**: Testcontainers for the advisory-lock/occupancy concurrency, migration-boot per service, provider fault injection at send/save/commit boundaries, webhook crash-after-marker replay, SW identity-switch, and axe/keyboard.
4. **Redis HA** and an explicit fail-open sign-off.
5. **Secrets management** for prod (independent `CRYPTO_SECRET_KEY`, rotated `JWT_SECRET`, provider keys) + a rotation runbook.
6. **A declarative authz registry** to kill the four-places drift class of bugs.
7. **Golden-master money tests** for the pricing/refund matrix.
8. **Observability wired end-to-end in prod** (the Sentry↔Zipkin trace linking exists in code — confirm DSNs/DPA/retention are configured and dashboards exist).

Sequenced remediation is in [08-RECOMMENDATIONS-ROADMAP.md](08-RECOMMENDATIONS-ROADMAP.md).
