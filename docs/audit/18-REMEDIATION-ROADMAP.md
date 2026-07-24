# 18 — Remediation Roadmap

> **Historical July remediation plan.** Most of it shipped in the working tree. Remaining/current work: [`../24-REMEDIATION-ROADMAP.md`](../24-REMEDIATION-ROADMAP.md).

Sequenced by priority, biased to the smallest safe fix. IDs reference `17-ISSUE-REGISTER.md`. Effort: XS (<½ day) · S (≤1 day) · M (1–3 days) · L (up to a week).

## P0 — Block launch (do first)

| Order | ID | Fix | Effort | Depends on |
|---|---|---|---|---|
| 1 | SEC-003 | Activate the `production` profile in real deployments: `SPRING_PROFILES_ACTIVE: "kubernetes,production"` or `spring.profiles.group.kubernetes: production`. Re-verify captcha + payment FATAL guards now load. | XS | — |
| 2 | SEC-001 | Add class-level `requireManagedBinge` + a `bingeId` predicate to the four recovery-queue repository queries; reject null binge for non-super-admin. | S | — |
| 3 | SEC-002 | Change `InvoiceController.listInvoicesForBinge` from `requireSelectedBinge` to `requireManagedBinge`. | XS | — |

**Exit criteria for P0:** a binge-A admin presenting `X-Binge-Id: B` cannot read binge-B rows on any admin endpoint; a production boot fails if `PAYMENT_SIMULATION_ENABLED=true` or a non-`rzp_live_` key is set; captcha is validated.

## P1 — Before real customers / money at scale

| Order | ID | Fix | Effort |
|---|---|---|---|
| 4 | SEC-007 | `git rm --cached admin_token.txt stress-tokens.txt`; rotate `JWT_SECRET` if the tokens were ever valid; purge from history. | S |
| 5 | SEC-004 | Remove the committed VAPID `private-key` default so boot fails without the env var; rotate the exposed key. | XS |
| 6 | DATA-002 | Add `UNIQUE(gateway_refund_id)` + `CHECK(amount>0)` on `refunds`; confirm the app `SUM(refunds) ≤ paid` guard runs under the payment row lock. | S |
| 7 | DATA-003 | Set `spring.data.mongodb.auto-index-creation: true` (or create indexes via `IndexOperations`); verify TTL exists per environment. | S |
| 8 | DATA-001 | Add a Postgres `EXCLUDE`/partial-unique constraint on active-booking room+date+time-window (capacity-1) as a backstop behind the advisory lock; dedupe existing rows first. | M |
| 9 | BOOK-001 | Product decision: wire `consumeHold` into `createBooking` (add `holdToken`, consume in-transaction, count foreign live holds) **or** remove the hold feature + its docs/UI claims (DOC-003). | M |
| 10 | DATA-004 | On auth anonymization, emit an event consumed by booking/payment/notification to redact PII; add retention columns. | M |

## P2 — Hardening & correctness

| ID | Fix | Effort |
|---|---|---|
| SEC-005 | Scope `AdminOpsController` + funnel to owned binge (or super-admin only); module-gate the currently-unmapped admin paths; stop the interceptor fail-open on null bingeId. | S |
| DATA-005 | Re-check duplicate/unpaid guards after the advisory lock, or add partial unique `(customer_id,event_type_id,booking_date,start_time) WHERE status='PENDING'`. | S |
| DATA-008 | Reconcile availability blocked-slot hour granularity with 30-min bookings. | M |
| REL-001 | Cap all outbox failure classes and route poison messages to a DLQ/parking table with alerting. | S |
| BOOK-002 | Make waitlist promotion create a real hold/PENDING; confirm `markEntryConverted` is called from the booking path. | M |
| DEVOPS-002 | `git rm --cached` tracked build/k6/log artifacts (incl. `k6.zip`). | S |
| DEVOPS-003 | Verify each service Dockerfile `HEALTHCHECK` or add explicit compose healthchecks. | S |
| TEST-001 | Add authz (cross-binge), over-refund, and multi-room-duplicate regression tests. | M |
| PRICE-001 | Extract a single shared `compute()` used by checkout preview + all booking paths (create/update/reschedule/recurring) so display-vs-charge cannot drift. | M |
| PRICE-002 | Decide FX-lock direction: **remove** the dead surface (`/checkout/lock-fx`, `/checkout/preview`, `FxLockService`, `fxLockToken`, unused `checkoutService` client) and document native per-binge pricing; **or** wire frontend→`lockFx`→`consume` if multi-currency charging is on the roadmap. | S (remove) / M (wire) |
| A11Y-001 | Single-Escape to close non-destructive modals (or add an `aria-live` "press again" hint); keep double-press only for unsaved-data modals. | S |
| A11Y-002 | Give each field error an `id`; reference via `aria-describedby`; set `aria-invalid` on invalid inputs; add `htmlFor`/`id` on sibling-label forms. | M |
| DOC-001/003 | Finish rebuilding the codebase/architecture docs (this audit) and align slot-hold claims. | M |

## P3 — Polish / low

API-001 (delete orphaned checkout client with PRICE-002; fix `payload.totalAmount` analytics ref; decide admin-side loyalty redemption), SEC-006 (mask transfer-preview emails), SEC-008 (remove reCAPTCHA dev default), DATA-006 (standardize money scale), DATA-007 (drop redundant bookings indexes), BOOK-003 (DST validation for `America/Chicago` venues), DEVOPS-004 (`.env`↔example reconcile), DOC-002 (add LICENSE).

## P4 — Informational

DEVOPS-005 (delete stray `backend;C` dir).

## Cross-cutting recommendation

The recurring root cause behind SEC-001/002/005 is that **tenant isolation is enforced endpoint-by-endpoint by convention**, and a few endpoints forgot. Beyond the point fixes, consider a **defense-in-depth control**: a mandatory service-side filter (or an aspect on `/admin/**`) that resolves and validates binge ownership once, so a newly added endpoint is secure by default rather than secure-if-remembered. This is the single change that most reduces the chance of the next isolation bug.
