# 00 — Executive Summary

> **Historical pre-remediation executive summary.** Do not use the risks/counts below as current status. The current executive summary is [`../01-EXECUTIVE-SUMMARY.md`](../01-EXECUTIVE-SUMMARY.md); current issues are [`../23-ISSUE-REGISTER.md`](../23-ISSUE-REGISTER.md).

**Audit:** SK Binge Galaxy — forensic, repository-wide, production-readiness audit.
**Date:** 2026-07-11 · **Commit:** `e3edbc1` (`main`, with a large uncommitted July-2026 overhaul included as current truth).
**Method:** static code inspection + parallel read-only specialist investigations + live runtime verification against the running Docker stack (dev data). Audit-and-documentation only — no application code, config, schema, or infra was changed.

> **Coordination note:** this audit was worked by more than one model session against the same tree. This document and the `docs/audit/*` deliverables were authored from the Claude session's evidence; `AUDIT_STATUS.md` was additionally co-edited by a Codex/GPT-5 continuation. Where the two sessions differ (notably runtime-write authorization), `AUDIT_STATUS.md` records both stances. Findings below are backed by cited evidence in `docs/audit/evidence/` and `21-RUNTIME-VERIFICATION-LOG.md`.

## Verdict

SK Binge Galaxy is an **unusually mature, security-conscious codebase** — far beyond a typical prototype. It has a real transactional outbox, idempotent event consumers, a proper booking state machine, advisory-lock concurrency control, HMAC-verified payment webhooks, maker-checker approvals, per-service databases, JWT-at-the-edge with downstream header trust, CSRF double-submit + Origin pinning, and a full (if aspirational) k8s/Istio/Argo production target. **Most classic vulnerability classes are already closed.** These controls were confirmed both statically and, where reachable, at runtime.

It is **not yet production-ready.** The gaps are not broad sloppiness; they are a **small number of specific, high-impact defects** concentrated in tenant isolation, deployment configuration, and database-level integrity backstops. They are individually fixable — several are one-line or one-migration changes — but until fixed they expose cross-tenant data, defeat production security guards, and leave financial/booking integrity dependent on application logic with no schema safety net.

**Overall production-readiness: NOT READY — blocked on ~9 P0/P1 issues, most of them small.**

## Top risks (fix before real customers)

1. **Cross-binge customer PII leak (SEC-001, Critical, CONFIRMED).** The admin recovery-queue endpoints (`AdminRecoveryQueueController`) query bookings/slot-holds with no binge filter and no ownership check, so any binge admin can read other binges' customers' names, emails, and amounts. `X-Binge-Id` is client-controlled and never validated at the gateway. A second endpoint (`InvoiceController.listInvoicesForBinge`, SEC-002) leaks cross-binge invoices the same way. Same root cause: isolation is enforced per-endpoint by discipline, and these endpoints skipped it.

2. **Refunds never reach the payment gateway (PAY-002, Critical, CONFIRMED).** Every refund path generates a *local* fake gateway id, marks the refund SUCCEEDED, and emails the customer a refund confirmation — but there is **no Razorpay refund API call anywhere** in the service (`RazorpayGatewayClient` has only order-create/fetch; the provider's `refund()` is `NOT_IMPLEMENTED`). In production this means admins, the database, and customers all show "refunded" while no money moves — a silent financial mismatch. It is not simulation-gated. (The rest of the payment path is genuinely strong — webhook HMAC + dedup + pessimistic-lock over-refund guard + maker-checker are all real and confirmed.)

3. **Production security stubs run in the real deployment (SEC-003, High, CONFIRMED).** The code gates dev stubs vs. real implementations on the Spring profile `production`, but no deployment ever activates it (k8s uses `kubernetes`, compose uses none). Result: reCAPTCHA validation is a stub that accepts any token, and the payment service's FATAL guards ("simulation must be off in prod", "live key must be `rzp_live_`") never fire. One-line deployment fix.

3. **No database backstop for double-booking (DATA-001, High, CONFIRMED at runtime).** There is no unique/exclusion constraint on the booking slot tuple — double-booking is prevented solely by a Postgres advisory lock in application code. It currently holds on every known write path, but any new path that forgets the lock, or a multi-primary DB, silently permits double-booking. The related slot-hold feature (BOOK-001) is worse: its reserve-then-consume hand-off is **dead code**, so a "held" slot is not actually protected from a direct booking.

4. **Financial and privacy integrity gaps at the data layer (DATA-002/003/004, High).** Over-/duplicate-refunds are not DB-enforced (no unique on `refunds.gateway_refund_id`, no `SUM(refunds) ≤ paid` check). MongoDB TTL/unique indexes are inert because auto-index-creation is off, so notification PII never expires and reminders can double-send. Auth-side user anonymization does not propagate, so PII copies persist in booking/payment/notification stores (incomplete right-to-erasure).

5. **Committed secrets (SEC-007, High) and a committed VAPID private key (SEC-004, Medium).** Live-looking JWTs are tracked in git; a Web Push private key is a silent config default.

## What is working well (verified)

- **Edge auth & tenant-trust plumbing:** gateway strips spoofable identity headers and re-derives them from a signature-verified JWT; backend services trust only gateway headers; backend ports are not published to the host. Confirmed at runtime: anonymous→401, customer→admin endpoint→403, internal endpoints→403/404 through the gateway, CSRF/Origin enforced (register 403 without, 201 with).
- **Booking concurrency & events:** advisory-lock serialization prevents physical double-booking; genuine transactional outbox (no dual-write); idempotent, order-tolerant payment event handlers with DB-unique dedup (confirmed: `processed_event.event_key`, `processed_webhook_event(event_id,provider)`, `outbox_event.event_id`, `idempotency_key` composite all uniquely indexed in the live DB).
- **Money & data hygiene:** all monetary columns are `NUMERIC` (no float money — confirmed live); optimistic-lock `version` columns; the historically-painful `@Lob`-on-TEXT bug is fully cleaned up; per-service databases with no cross-DB reads; strong loyalty/finance constraints (immutable ledgers, CHECKs, unique idempotency).
- **The internal binge-ownership contract** (`/internal/binges/{id}` carrying `adminId`, public DTO stripping it) is implemented correctly across payment and availability services; the V71 module-permission matrix is enforced server-side, not just in the UI.
- **Runtime-confirmed (2026-07-12):** physical double-booking is genuinely prevented — two customers racing a capacity-1 slot produced exactly one booking (DB-verified); the unpaid-booking limit, per-customer duplicate guard, and **super-admin MFA-at-login** are all enforced live; the payment order-creation path works end-to-end. Pricing has a real negative-total guard and a single tax choke point (money is native per-binge — no FX conversion; the FX-lock code is dormant, PRICE-002).

**Additional confirmed defects from the deeper pass:** the **Mongo TTL / unique indexes are runtime-confirmed absent** (DATA-003 upgraded to CONFIRMED — 73 notifications retained with no expiry, reminder dedup unenforced); the **pricing assembly is duplicated across 5 code paths** (PRICE-001, display-vs-charge drift risk); and the shared **Modal violates the standard dialog keyboard contract** (A11Y-001, double-Escape-to-close).

## Coverage & confidence

Deeply audited (specialist + runtime): authentication/authorization/sessions, tenant isolation, availability/booking/holds/concurrency, database & data integrity, and the security posture of payments and events. Runtime verification confirmed stack health, DB constraints, money types, and the auth/authorization boundaries.

**Partial / not fully verified (recorded honestly, Rule 6):** the dedicated **payment/refund** deep pass was later completed by direct inspection (`evidence/specialist-05`) — it found **PAY-002 (Critical)** and confirmed strong positive controls; only the live Razorpay happy path stays runtime-unverified. The **method-level API field-drift diff** and a **whole-frontend a11y attribute survey** are now done (clean strict-DTO parity; A11Y-002; PRICE-002). Still census-only: full **frontend visual/keyboard/contrast/responsive UX**, **performance/load**, and **DevOps reliability** deep passes. **Runtime-unverified** (harness/data limits): concurrent double-booking behavior (Secure-cookie CSRF over plain HTTP), the full checkout→payment→refund happy path, the Mongo TTL index (empty dev store), and all visual/UI rendering (no browser automation). See `19-COVERAGE-MANIFEST.md` for the file-level accounting and `21-RUNTIME-VERIFICATION-LOG.md` for exactly what was executed.

## Recommended sequencing (see `18-REMEDIATION-ROADMAP.md`)

**P0 (before any real customer):** SEC-001, SEC-002 (add ownership checks — hours of work), SEC-003 (activate `production` profile — one line).
**P1:** SEC-007 (purge/rotate committed tokens), DATA-001 (slot exclusion constraint), DATA-002 (refund unique + check), DATA-003 (Mongo indexes), BOOK-001 (decide & fix slot-hold), DATA-004 (cross-service erasure).
**P2+:** the remaining Medium/Low items and the missing regression tests (TEST-001).

The encouraging headline: the expensive architecture is already built and largely correct. The blocking issues are a focused, mostly-small punch list — not a rebuild.
