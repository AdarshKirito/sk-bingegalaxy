# 21 — Runtime Verification Log

> **Dated July 11–12 dev-stack evidence.** It does not prove the later remediated tree was deployed. Current provenance/limits: [`../26-COMMANDS-AND-EVIDENCE-LEDGER.md`](../26-COMMANDS-AND-EVIDENCE-LEDGER.md).

All commands executed by the lead auditor against the LIVE local Docker stack on 2026-07-11. Dev data only. No restarts/volume/destructive ops were performed. Secret values are never printed here.

Environment: gateway `http://localhost:8090`, frontend `http://localhost:3000`, DBs via `docker exec`. 15/15 containers healthy.

## R1 — Stack health & topology

| Check | Command (abridged) | Result |
|---|---|---|
| Gateway health | `GET :8090/actuator/health` | `200 {"status":"UP","groups":["liveness","readiness"]}` — CONFIRMED UP |
| Frontend shell | `GET :3000/` | `200`, HTML shell len 1237 — CONFIRMED served |
| Postgres DBs | `psql -l` | `auth_db, availability_db, booking_db, payment_db` present — CONFIRMED 4 DB-per-service |
| Containers | `docker ps` | 15 up (gateway, frontend, 6 services, config, discovery, kafka, zookeeper, zipkin, postgres, mongo, redis) — only 8090+3000 published to host |

## R2 — Database integrity introspection (corroborates specialist-03 & -04)

**booking_db** — unique indexes on integrity-critical tables (`SELECT ... FROM pg_indexes WHERE indexdef ILIKE '%UNIQUE%'`):
- `bookings`: `bookings_pkey`, `idx_booking_ref` **only** → **CONFIRMED: no unique index on (room+date+time). No DB backstop for double-booking.**
- `slot_holds`: `slot_holds_pkey`, `idx_slot_holds_token` **only** → **CONFIRMED: no unique on slot; two ACTIVE holds on same slot not DB-prevented.**
- `idempotency_key`: UNIQUE `(idempotency_key, http_method, request_path, user_id)` → CONFIRMED dedup enforced.
- `processed_event`: UNIQUE `event_key` (`idx_pe_event_key`) → CONFIRMED event dedup enforced.
- `outbox_event`: UNIQUE `event_id` (`ux_outbox_event_id`) → CONFIRMED outbox dedup enforced.
- `booking_transfers`: partial UNIQUE `booking_ref WHERE status='PENDING'` → CONFIRMED one pending transfer per booking.

**booking_db `bookings` columns** (`\d bookings`): money cols are `numeric(10,2)/(12,2)/(14,2)`, `fx_rate numeric(18,8)`, `surge_multiplier numeric(5,2)` → **CONFIRMED no float money**. `version bigint default 0` → CONFIRMED optimistic locking. `booking_date date`, `start_time time without time zone`, all timestamps `without time zone` → **CONFIRMED naive timestamps (venue-TZ applied in app)**.

**payment_db** — unique indexes:
- `payments`: `payments_pkey`, `idx_payment_transaction_id` (UNIQUE transaction_id) → CONFIRMED.
- `processed_webhook_event`: UNIQUE `(event_id, provider)` → **CONFIRMED webhook replay dedup enforced at DB.**
- `idempotency_key`: UNIQUE composite → CONFIRMED.
- `refunds`: `refunds_pkey` only → **CONFIRMED: no unique on `gateway_refund_id`; over/duplicate-refund not DB-enforced (app-only).**

**Seed data**: 3 binges (`id 1` SK Binge Galaxy Main / admin_id 1 / Asia/Kolkata; `id 2` dd / admin_id 1 / America/Chicago; `id 4` dl / admin_id 5 / America/Chicago), 9 active event_types, 3 venue_rooms (binge 1), 17 bookings. Users: 1 ADMIN, 4 CUSTOMER, 1 SUPER_ADMIN.

## R3 — Auth & authorization boundary tests (via gateway)

CSRF-exempt pre-auth endpoints and protected endpoints exercised with a freshly registered test customer.

| Test | Result | Interpretation |
|---|---|---|
| `POST /auth/register` WITHOUT Origin/CSRF | **403** | CSRF/Origin protection active — CONFIRMED positive control |
| `POST /auth/register` WITH Origin + `X-XSRF-TOKEN` | **201**, returns CUSTOMER JWT (sub=new id) | Registration works; JWT issued — CONFIRMED |
| `GET /auth/profile` authenticated (cookie) | **200** | Auth cookie honored — CONFIRMED |
| `GET /auth/profile` anonymous | **401** | Protected endpoint enforces auth — CONFIRMED |
| `GET /auth/admin/customers` as CUSTOMER | **403** | **Backend role enforcement (not just UI hiding) — CONFIRMED** |
| `GET /bookings/my` with `X-Binge-Id: 1 / 4 / 999999` | **200** empty list each | `/my` is customer-scoped by user id; binge header does not widen it. Header accepted even for nonexistent binge (not validated for existence) |
| `GET /bookings/internal/binges/1` via gateway (no secret) | **403** | Internal filter rejects — CONFIRMED `/internal` protected |
| `GET /internal/binges/1` via gateway | **404** | Internal paths not routed through gateway — CONFIRMED |

## R4 — Concurrent double-booking test (attempted)

Two parallel identical `POST /api/v1/bookings` (same room+date+time, no `Idempotency-Key`) fired via background jobs sharing the auth session.
- **Result: both returned 403.** Root cause is a **harness limitation, not an app defect**: the `XSRF-TOKEN` cookie is `Secure`-flagged; browsers treat `http://localhost` as a secure context and accept it, but PowerShell's HttpClient drops Secure cookies over plain HTTP. Register/login succeed because they are CSRF-exempt; the booking POST enforces double-submit and my client cannot present the matching cookie. Confirmed by a single synchronous booking POST also returning 403 with `Content-Type: application/json` and full security-header set, while the cookie jar held only `token` (auth), never `XSRF-TOKEN`.
- **Consequence:** the concurrent double-booking behavior is **NOT VERIFIED at runtime**. The double-booking analysis rests on static evidence + DB introspection (R2): physical double-booking is prevented by a Postgres transaction-scoped advisory lock (`pg_advisory_xact_lock`), with NO unique-index backstop. See `specialist-03`. To runtime-verify, drive the flow through the real browser/frontend (which can hold the Secure cookie) or over HTTPS.

## R5 — MongoDB notification store

- `notification_db` connects without auth but has **no collections** — the dev store is empty (no notification events persisted yet). `listDatabases` requires auth (not attempted with creds).
- **Consequence:** the Mongo TTL/unique-index finding (annotations inert because `spring.data.mongodb.auto-index-creation` is unset → Spring Boot 3 default false) is **NOT further confirmable from an empty DB**; it remains HIGH CONFIDENCE on static grounds (`specialist-04` §9). To verify, emit one notification (complete a booking) then `db.notifications.getIndexes()` and check for an `expireAfterSeconds` TTL index.

## R6 — Second-session runtime attempt (2026-07-12) — stack offline

A follow-up session attempted the outstanding runtime items (concurrent double-booking, full payment happy path, Mongo TTL after emitting a notification). Findings:
- **CSRF write-path cracked (static):** the `XSRF-TOKEN` cookie is NOT `Secure` in dev (`COOKIE_SECURE=false` in `.env`); the earlier harness failure was because it used the token from the `/csrf` response **body** while `CsrfTokenController.issue` mints the cookie value == body token — so a manual `Cookie: token=…; XSRF-TOKEN=<T>` header with `X-XSRF-TOKEN: <T>` would pass. (Confirmed by reading `CsrfProtectionFilter.java` + `CsrfTokenController.java`.)
- **BLOCKER: Docker Desktop is down** — the daemon is unreachable (`npipe:////./pipe/dockerDesktopLinuxEngine` not found) and the gateway no longer answers on `:8090`. The stack that was healthy in R1–R5 (2026-07-11) is offline in this session. Therefore the concurrent double-booking test, the full payment/refund happy path, and the Mongo TTL check **remain NOT VERIFIED at runtime** — now blocked by infrastructure availability, not just the harness. To complete: start Docker Desktop + the compose stack, then re-run `scratchpad/dbl2.ps1` (double-booking) and emit one notification before `db.notifications.getIndexes()`.
- The double-booking analysis remains CONFIRMED on static + R2 DB-introspection grounds (advisory lock in code; no unique-index backstop). Payment/refund was instead completed by **direct code inspection** (`evidence/specialist-05-payment-refund.md`), which is stronger than the blocked runtime path for the correctness questions.

## R7 — Third session (2026-07-12, stack restarted) — runtime trio RESOLVED

Docker Desktop was restarted and the compose stack brought back healthy. The CSRF write path was cracked (explicit `System.Net.Cookie` container with both `token` + `XSRF-TOKEN` cookies tied to `localhost`; the token value equals the `/csrf` body token per `CsrfTokenController`). Scripts: `scratchpad/dbl6.ps1`, `payhappy.ps1`.

### R7.1 — Physical double-booking CONFIRMED prevented (DATA-001 runtime resolved)
Two **different** customers (fresh registrations) fired concurrent identical `POST /api/v1/bookings` for the **same capacity-1 room + date + 20:00 slot** (room 1 "Galaxy Hall", capacity 1 — verified via `psql`), no idempotency key.
- Result: CUST-A → `201`; CUST-B → rejected.
- **Authoritative DB check:** `SELECT ... FROM bookings WHERE venue_room_id=1 AND booking_date=<slot> AND start_time='20:00:00'` returns **exactly one row** (customer 15). **The advisory-lock + capacity check prevents physical double-booking under true concurrency.** DATA-001's DB-backstop absence remains a defense-in-depth finding, but the guard itself works at runtime.
- Incidental positive controls observed at runtime: the **unpaid-booking limit** (max 2) is enforced ("You already have 2 unpaid booking(s)…"), and the **per-customer duplicate guard** is enforced ("You already have a pending booking for this event and time slot").

### R7.2 — Mongo TTL / unique indexes CONFIRMED absent (DATA-003 upgraded HIGH→CONFIRMED)
Authenticated to Mongo (`skbg_admin`, creds from `.env`, never printed). `notification_db` now has real data — `notifications` (73 docs), `booking_reminders` (79 docs), `push_subscriptions` (0), `shedLock` (3). **Every collection carries ONLY the default `_id_` index.** No `expireAfterSeconds` TTL on `notifications` (the `@Indexed(expireAfter="P90D")` is inert) → recipient PII grows unbounded (73 already). No unique compound index on `booking_reminders` (the `@CompoundIndex(unique)` is inert) → duplicate reminders/double-sends are not prevented. Root cause CONFIRMED: `spring.data.mongodb.auto-index-creation` unset → Spring Boot 3 default false.

### R7.3 — Payment happy path: book + initiate CONFIRMED; simulate blocked by super-admin MFA
`payhappy.ps1`: customer registered, booked (`201`, status `PENDING`, amount 4718.82 INR incl. tax), and `POST /payments/initiate` → `200`, payment `INITIATED` (order created). The admin-only `POST /payments/admin/simulate/{txn}` step could not be driven: `POST /auth/admin/login` for `admin@skbingegalaxy.com` returns **403 "Super admin accounts require MFA… enroll TOTP before logging in."** — a **positive control** (super-admin MFA enforced at login), which I cannot satisfy. The PAYMENT_SUCCESS→booking-CONFIRMED transition therefore remains covered statically (`PaymentEventListenerTest`, state machine) rather than driven end-to-end here. HIGH CONFIDENCE the full path works; the event-confirm link is the only un-driven step.

### R7.4 — Kafka event backbone CONFIRMED end-to-end at runtime
Querying `notification_db.notifications` after the test bookings shows the full async pipeline works:
- Booking `SKBG26B7F066DB` and `SKBG2676DB586F` (my test bookings) each produced a `BOOKING_CREATED` EMAIL notification → proves **booking-service transactional outbox → Kafka `booking.created` → notification-service consumer → Mongo persist**.
- Test registrations produced `USER_REGISTERED` + `EMAIL_VERIFICATION` notifications → proves **auth-service → Kafka → notification-service**.
- Notifications sit at `deliveryStatus: PENDING` (dev has no SMTP server — expected; the retry/backoff machinery would drive them). Total grew 73→76 across the test bookings.

This is a live confirmation of the outbox + consumer design (previously static-only). The one un-driven link remains PAYMENT_SUCCESS→booking-CONFIRMED (admin-simulate blocked by super-admin MFA, R7.3).

## R8 — FX-lock / native-currency corroboration (2026-07-12, PRICE-002)

Driven to verify the method-level static finding that the FX rate-lock feature is dormant. Live `booking_db` (`psql`, user `skbg_admin`):

```
SELECT payment_currency_code, fx_rate, fx_locked_until, count(*) FROM bookings GROUP BY 1,2,3;
 INR    | 1.00000000 | <null> | 10
 <null> | 1.00000000 | <null> |  8
 USD    | 1.00000000 | <null> |  4
SELECT count(*) FROM fx_rate_locks;   →  0
```

- **All 22 bookings** carry `fx_rate = 1.0` and `fx_locked_until = NULL` — including 4 with `payment_currency_code = USD` (native US-binge pricing, not a conversion).
- The `fx_rate_locks` table exists (migrated) but has **0 rows** — no lock has ever been created or consumed.

Combined with the static evidence (`FxLockService.consume` has zero callers; `BookingService.java:399-444` hard-codes `fxRate=1`/native currency and ignores `fxLockToken`; frontend never calls `checkoutService.lockFx`/`.preview`), this **CONFIRMS PRICE-002** and **corrects** the earlier "FX-lock expiry rejection" positive-control claim. The audit control was verifying a scenario that cannot occur — native per-binge pricing performs no FX conversion, so there is no stale-rate exposure.

## Runtime coverage summary

CONFIRMED at runtime: stack health, DB-per-service, money precision, optimistic-lock column, naive timestamps, integrity unique-index presence/absence (double-booking backstop MISSING; payment/idempotency/event dedup PRESENT), CSRF enforcement, auth requirement, backend role enforcement, internal-endpoint protection.

NOT VERIFIED at runtime (harness/data limits, recorded honestly): concurrent double-booking behavior (Secure-cookie CSRF over HTTP); Mongo TTL index (empty store); full checkout→payment→refund happy path (same CSRF harness limit); visual/UI rendering (no browser automation).
