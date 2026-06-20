# SK Binge Galaxy — Complete Technical Architecture

> A durable, comprehensive reference for the entire platform: every service, the full
> API surface, the event-driven backbone, the core business flows traced through the
> real code, the reliability layer, the data model, the frontend, and the infra.
>
> Companion to `README.md` (quick start) and `production-proof/` (load-test & resilience evidence).

---

## Table of Contents

1. [What this is](#1-what-this-is)
2. [System architecture](#2-system-architecture)
3. [Tech stack](#3-tech-stack)
4. [Multi-tenancy: the Binge model](#4-multi-tenancy-the-binge-model)
5. [Security model](#5-security-model)
6. [Complete API reference](#6-complete-api-reference)
7. [Event-driven backbone (Kafka)](#7-event-driven-backbone-kafka)
8. [Core flows, traced through the code](#8-core-flows-traced-through-the-code)
9. [Reliability & recovery layer](#9-reliability--recovery-layer)
10. [Booking-service subsystems](#10-booking-service-subsystems)
11. [Loyalty v2 subsystem](#11-loyalty-v2-subsystem)
12. [Payment-service](#12-payment-service)
13. [Notification-service](#13-notification-service)
14. [Auth-service](#14-auth-service)
15. [Frontend (React SPA)](#15-frontend-react-spa)
16. [Data model](#16-data-model)
17. [Infrastructure & DevOps](#17-infrastructure--devops)
18. [Coverage notes](#18-coverage-notes)

---

## 1. What this is

SK Binge Galaxy is a **multi-tenant private-theater booking platform**. Customers rent a
private cinema/screening room (a **"Binge"**) for an event — birthday, anniversary,
proposal, movie night — pick a date and 30-minute-grid time slot, add extras (decoration,
cake, food), pay via Razorpay, and check in. Admins operate the venues; super-admins run
the platform.

A single deployment hosts **many venues**, each with its own catalog, pricing, IANA
timezone, operating hours, staff, cancellation/refund/freeze policies, and a super-admin
approval lifecycle. The codebase is production-grade: **8 Spring Boot microservices + a
React 18 SPA**, ~93 Flyway migrations, k6 load-test evidence, Kubernetes manifests, and a
Jenkins pipeline.

---

## 2. System architecture

```
React 18 SPA (Vite, :3000)
        │  httpOnly JWT cookie + CSRF token
        ▼
API Gateway (Spring Cloud Gateway, :8080)  ── Redis (rate limiting)
   header-strip · JWT validate · CSRF · rate-limit · RBAC · authority delegation · routing
        │  injects trusted X-User-* headers
        ▼
┌──────────────────────────────────────────────────────────────┐
│ auth (8081)   availability (8082)   booking (8083)            │
│ payment (8084)   notification (8085)                          │
└──────────────────────────────────────────────────────────────┘
   ▲ Eureka (8761) discovery     ▲ Config Server (8888) centralized config
        │
   PostgreSQL 16 (DB-per-service) · MongoDB 7 (notifications) · Apache Kafka · Zipkin (tracing)
```

| Service | Port | Store | Responsibility |
|---|---|---|---|
| `discovery-server` | 8761 | — | Eureka service registry |
| `config-server` | 8888 | — | Native config; **owns the gateway route table** |
| `api-gateway` | 8080 | Redis | Edge: auth, CSRF, rate-limit, RBAC, routing |
| `auth-service` | 8081 | `auth_db` | Identity, JWT, MFA, sessions, authority delegation, CMS |
| `availability-service` | 8082 | `availability_db` | Date/slot blocking & availability |
| `booking-service` | 8083 | `booking_db` | **Core**: bookings, pricing, tax, FX, loyalty, invoicing, sagas |
| `payment-service` | 8084 | `payment_db` | Razorpay, refunds, disputes, reconciliation |
| `notification-service` | 8085 | MongoDB | Email/SMS/WhatsApp/push delivery |
| `common-lib` | — | — | Shared enums, events, DTOs, exceptions, money utils, security filters |

**Databases** are created by [`infra/init-databases.sql`](infra/init-databases.sql) with
**least-privilege per-service Postgres roles** (`auth_svc`, `booking_svc`, …) — each role
can touch only its own DB. The `shedlock` table lives in `booking_db` for cluster-safe
scheduling.

**Gateway routes** (from `config-server/.../configurations/api-gateway.yml`, all via Eureka
`lb://`):

| Route id | Path | → Service |
|---|---|---|
| `auth-service` | `/api/v1/auth/**` | auth |
| `site-content` | `/api/v1/site-content/**` | auth |
| `availability-service` | `/api/v1/availability/**` | availability |
| `booking-service-sse` | `/api/v1/bookings/admin/events/stream` | booking (SSE) |
| `booking-service` | `/api/v1/bookings/**` | booking |
| `booking-transfer-service` | `/api/v1/booking-transfers/**` | booking |
| `booking-service-loyalty-v2` | `/api/v2/loyalty/**` | booking |
| `payment-service` | `/api/v1/payments/**` | payment |
| `notification-service` | `/api/v1/notifications/**` | notification |

---

## 3. Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.4.5, Spring Cloud 2024.0.1 |
| Discovery / Config | Spring Cloud Netflix Eureka, Spring Cloud Config (native) |
| Gateway | Spring Cloud Gateway (reactive) + custom global filters |
| Auth | JWT (jjwt 0.12.5), BCrypt-12, Spring Security, TOTP MFA |
| Persistence | PostgreSQL 16 (per-service), MongoDB 7 (notifications), Flyway migrations |
| Messaging | Apache Kafka (transactional outbox + DLQ) |
| Cache / Rate-limit | Redis |
| Payments | Razorpay (HMAC-SHA256 verified), pluggable `PaymentProvider` |
| Tracing | Zipkin / Micrometer, MDC correlation |
| Frontend | React 18, Vite, React Router 6, Axios, Zustand, i18next, DOMPurify, Sentry, PWA |
| Containerization | Docker, Docker Compose (multi-stage builds) |
| Orchestration | Kubernetes (HPA, PDB, NetworkPolicy, cert-manager, Argo Rollouts, Istio) |
| CI/CD | Jenkins |

---

## 4. Multi-tenancy: the Binge model

The **Binge** ([`Binge.java`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/Binge.java))
is the tenant boundary. Every booking, payment, event-type, add-on, rate-code, room, tax
rule, and loyalty binding is scoped to a `bingeId`. Key fields:

- **`timezone`** (IANA, default `Asia/Kolkata`) — *all* date/time validation, check-in
  windows, and tax effective-date evaluation use this, never the JVM default. Resolved via
  `VenueClockService.zoneOf(bingeId)`.
- **`openTime` / `closeTime`** — operating-hours guard for bookings.
- **`operationalDate`** — per-venue business date that advances only after a successful
  daily audit.
- **Cancellation policy** — `customerCancellationEnabled`, `customerCancellationCutoffMinutes`,
  `refundOnSuccessfulPaymentCancel`, `refundOnPendingPaymentCancel`.
- **Freeze policy (anti-abuse)** — `freezePolicyEnabled`, `freezeDurationMinutes`,
  `maxPendingCancelsBeforeFreeze`, `maxPendingPaymentTimeoutsBeforeFreeze`,
  `maxNoShowsBeforeFreeze`.
- **Approval lifecycle** — `status` (`PENDING_APPROVAL` / `APPROVED` / rejected);
  ADMIN-created binges need super-admin approval; customers only see APPROVED binges.
  `BingeGracePeriodScheduler` auto-deactivates approved-but-empty binges after 24h.
- **`maxConcurrentBookings`**, **`roomSelectionRequired`**, support contacts (email/phone/
  WhatsApp with E.164 country codes).

Tenancy is enforced by `BingeContextFilter` (populates a `ThreadLocal` from the `X-Binge-Id`
header) + per-service scope services (`AdminBingeScopeService`, `PaymentBingeScopeService`,
`AvailabilityBingeScopeService`) + a dedicated `CrossBingeIsolationTest`. The recurring
bug-class — comparing venue-local `bookingDate`/`startTime` against UTC `now()` — is guarded
by always resolving the venue zone first.

---

## 5. Security model

### 5.1 Gateway as the single source of identity truth

[`JwtAuthenticationFilter`](backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/JwtAuthenticationFilter.java)
(global filter, order −1):

1. **Header stripping** — *unconditionally* removes all inbound `X-User-*` and
   `X-Authority-*` headers, logging `auth.header.spoof.attempt` if a client sent any.
   Downstream services trust these headers absolutely *because* only the gateway can set them.
2. **Token extraction** — `Authorization: Bearer` first, then the **httpOnly cookie**
   `token` (so XSS cannot read it).
3. **Validation** — HMAC-SHA256 `verifyWith(key)` (blocks `alg:none`), 30s clock skew, and
   *soft* issuer/audience enforcement (reject only if present-and-wrong, so tokens minted
   before iss/aud rollout survive).
4. **RBAC by path shape** — `/api/v*/{service}/admin/**` → ADMIN; `/super-admin/**` →
   SUPER_ADMIN. Paths are URI-normalized first to defeat `/../admin/` traversal.
5. **Authority Handover** — see §5.3.
6. Injects trusted `X-User-Id/Email/Role/Name/Phone/Phone-Country-Code`.

Other gateway filters: `CsrfProtectionFilter`, `SecurityHeadersFilter` (CSP etc.),
`RateLimitFilter` + `UserRateLimitFilter` (Redis token-bucket, IP- and user-keyed),
`ApiVersionHeaderFilter`, `MdcContextFilter` (trace correlation). `CsrfTokenController`
issues the CSRF token; `FallbackController` serves circuit-breaker fallbacks.

### 5.2 Auth hardening (auth-service)

- **BCrypt-12** hashing; **constant-time login** (always runs a BCrypt verify against a
  precomputed dummy hash for unknown emails → defeats email enumeration via timing).
- **Account lockout** — 5 failed attempts / 15 min, via atomic DB increment to avoid the
  off-by-one concurrency race.
- **CAPTCHA gate** — required *before* credential check once a failure threshold is crossed
  (`RecaptchaValidationService`, with a dev stub).
- **TOTP MFA** (`TotpService`) — opt-in for customers/admins, **mandatory for SUPER_ADMIN**
  (enforced at promotion *and* at login).
- **Password history** + **HaveIBeenPwned** breach check (`PwnedPasswordService`).
- **JWT + refresh tokens** with a **revocation list** (`RevokedToken`) and session-level
  force-revoke (`UserSession.revokedAt`) → instant "log out all devices".
- **GDPR**: `UserAnonymizationService`, `UserPrivacyController` (self-delete + admin anonymize).
- Full **auth audit log** with typed event/reason codes.

### 5.3 Authority Handover (scoped delegation)

A SUPER_ADMIN grants a regular ADMIN a **scoped, time-boxed** elevation. Scopes
(`AuthorityScope`): `CURRENCIES, NOTIFICATIONS, LOYALTY, OPS, ALL_USERS, CUSTOMER_EDIT,
ADMIN_REGISTER, HOME_CMS, ACCOUNT_CMS, SUPER_DASHBOARD`.

- **Grant** (`AuthorityService.createGrant`) — issuer must be SUPER_ADMIN; cannot grant to
  self (no self-escalation), to a CUSTOMER, or to an existing SUPER_ADMIN; ≥1 scope;
  duration clamped to `[1, MAX]` hours; on save, **revokes the grantee's sessions** so their
  next JWT carries the new `delegatedScopes` + `delegationExpiresAt` claims immediately.
- **JWT stays truthful** — the native `role` claim is never mutated. The **gateway**
  elevates the effective `X-User-Role` to SUPER_ADMIN **per request path**, only when the
  path matches a granted scope in `SCOPE_MAP` and the grant hasn't expired, stamping
  `X-Authority-Delegated/Scope/Native-Role` for downstream audit.
- **Revoke** is idempotent and re-revokes sessions.
- The frontend `SuperAdminRoute` mirror is **UX-only**; the gateway is the real enforcer.

---

## 6. Complete API reference

All paths are gateway-prefixed. `[A]` = ADMIN, `[SA]` = SUPER_ADMIN (or delegated scope),
`[C]` = authenticated customer, `[P]` = public.

### Auth (`/api/v1/auth`)
```
[P]  POST /register · /login · /admin/login · /google · /refresh · /logout
[P]  POST /forgot-password · /reset-password · /verify-otp
[C]  GET  /profile · /support-contact          PUT /profile · /change-password · /change-email
[C]  PUT  /profile/preferences · /complete-profile
[C]  POST /verify-email · /resend-verification
[C]  POST /mfa/enroll · /mfa/confirm · /mfa/disable
[C]  GET  /sessions    DELETE /sessions/{id}    POST /sessions/revoke-others
[A]  GET  /admin/search-customers · /admin/customers · /admin/customer/{id} · /admin/admins
[A]  PUT  /admin/customer/{id} · /admin/admins/{id}   POST /admin/create-customer
[SA] POST /admin/register   DELETE /admin/user/{id}
[A]  POST /admin/bulk-ban · /admin/bulk-unban · /admin/bulk-delete
[A]  GET  /admin/audit-log · /admin/sessions   DELETE /admin/sessions/{id} · /admin/users/{userId}/sessions
```
**Authority** (`/api/v1/auth/authority`): `GET /me`, `POST /grants`, `DELETE /grants/{id}`,
`GET /grants`, `GET /grants/by-user/{userId}`, `POST /locks`, `DELETE /locks/{id}`,
`GET /locks · /locks/lookup · /internal/locks/lookup`.
**Privacy** (`/api/v1/auth/privacy`): `DELETE /me`, `POST /admin/anonymize/{userId}`.
**Site content** (`/api/v1/site-content`): `GET /public/{slug}`, `PUT /admin/{slug}`.

### Availability (`/api/v1/availability`)
```
[P]  GET  /dates · /slots · /internal/check
[A]  GET  /admin/blocked-dates · /admin/blocked-slots
[A]  POST /admin/block-date · /admin/block-slot   DELETE /admin/unblock-date · /admin/unblock-slot
```

### Bookings (`/api/v1/bookings`)
Customer/public:
```
[P]  GET  /binges · /binges/{id} · /binges/{id}/customer-dashboard · /binges/{id}/customer-about
[P]  GET  /binges/nearby?lat&lng&radiusKm&limit   (proximity ranking, each result carries distanceKm)
     ↳ all three anonymous /binges reads return the sanitized PublicBingeDto (no owner
       adminId, approval audit, or anti-abuse freeze thresholds — those stay admin-only)
     ↳ the by-id content reads (/binges/{id}, /customer-dashboard, /customer-about) 404
       for non-published venues (pending / rejected / deactivated) — same response as a
       missing id, so a pending venue's existence is never disclosed by enumeration
[P]  GET  /binges/{id}/reviews · /binges/{id}/reviews/summary · /binges/{id}/cancellation-tiers
[P]  GET  /event-types · /add-ons · /event-categories · /addon-categories · /booked-slots · /slot-capacity
[C]  POST /  (create)            GET /{bookingRef} · /my · /my/current · /my/past · /{bookingRef}/timeline
[C]  POST /{bookingRef}/cancel · /reschedule · /transfer · /recurring
[C]  GET  /my-pricing · /venue-rooms · /venue-rooms/available · /surge-rules
[C]  GET/POST /{bookingRef}/reviews/customer · /my/reviews/pending
[C]  GET  /{ref}/invoice
[C]  POST /checkout/preview · /checkout/lock-fx
[C]  GET/DELETE /slot-holds/{token} · /slot-holds/my
[C]  GET/DELETE /waitlist/my · /waitlist/{entryId}
[C]  GET  /freezes/me · /freezes/me/binge/{bingeId}
[P]  POST /analytics/funnel        GET /currencies · /taxes/preview
[*]  Booking transfers: POST /{ref}/transfers · /transfers/{id}/revoke ;
     POST /booking-transfers/by-token/{token}/accept|decline (magic-link)
```
Admin (`/api/v1/bookings/admin/...`):
```
[A]  bookings: today · upcoming · by-date · by-status · search · PATCH /{ref} ·
     {ref}/cancel|confirm|check-in|checkout|undo-check-in · dashboard-stats
[A]  operational-date · operational-date/advance|set
[A]  catalog CRUD: event-types · add-ons · event-categories · addon-categories (+ /global)
[A]  pricing: rate-codes CRUD · customer pricing · bulk-assign-rate-code · resolve · customer-detail
[A]  binges: list · by-admin · create · pending · approve · reject · toggle-active · delete ·
     customer-dashboard|about config · cancellation-tiers|policy · site-content
[A]  support: notes CRUD/pin · resend-confirmation · escalate · goodwill · timeline
[A]  recovery: stuck-pending · expired-holds · paid-not-confirmed · no-show · summary · funnel
     + actions: {ref}/cancel · {ref}/replay · holds/{token}/release
[A]  risk-flags · notifications (unread/read) · waitlist · slot-holds · freezes
[A]  invoices: list · {ref}/invoice/resend ;  export/csv ;  media upload
[SA] currencies (toggle/delete) · taxes (global CRUD) · ops (replay-dlt · outbox/retry-failed · health)
[A]  events/stream (SSE)
```
Loyalty v2 (`/api/v2/loyalty`):
```
[C]  /me/ledger · /me/redeem-quote · /me/status-match (GET/POST)
[A]  /admin/bindings/{bingeId}/(enable) · earn-rules · redeem-rule · perks · status-match approve/reject
[SA] /super-admin/program · tiers · perks · tier-perks · bindings/bulk ·
     customers/{id} (view · adjust · ledger)
```

### Payments (`/api/v1/payments`)
```
[C]  POST /initiate     GET /transaction/{txnId} · /booking/{ref} · /my · /booking/{ref}/refunds
[P]  POST /callback · /webhooks/razorpay   (HMAC-verified, no JWT)
[A]  POST /admin/refund · /admin/record-cash · /admin/add-payment · /admin/simulate/{txnId} (non-prod)
[A]  GET  /admin/refunds/{paymentId} · /admin/refunds/failed · /admin/stats
[A]  POST /admin/refunds/{refundId}/retry · /cancel/{txnId}
[A]  GET  /admin/disputes · /admin/disputes/all · /admin/disputes/count   PATCH /admin/disputes/{id}/notes
[A]  approvals: GET /{id} · POST /{id}/approve|reject|cancel|execute-refund-retry
```

### Notifications (`/api/v1/notifications`)
```
[C]  GET  /my · /booking/{ref} · preferences
[P]  POST /webhooks/delivery   (provider delivery-status callbacks; HMAC-SHA256 over raw body
     via X-Webhook-Signature, fail-closed when NOTIFICATION_WEBHOOK_SECRET unset)
[A]  POST /admin/retry-failed · /admin/{id}/retry
[SA] templates (activate) · whatsapp-templates CRUD
```

---

## 7. Event-driven backbone (Kafka)

Topics (`common-lib/.../KafkaTopics.java`): `booking.confirmed`, `booking.cancelled`,
`booking.rescheduled`, `booking.transferred`, `booking.checked-in`, `booking.cash-payment`,
`booking.created`, `payment.success`, `payment.failed`, `payment.refunded`,
`waitlist.promoted`, `notification.send`, `room.unblocked`, `user.registered`,
`password.reset`.

| Topic | Publisher | Consumer(s) |
|---|---|---|
| `booking.created/confirmed/cancelled/...` | booking | notification |
| `payment.success` / `payment.failed` | payment | booking (saga), notification |
| `payment.refunded` | payment | booking, notification |
| `booking.cash-payment` | booking | payment |
| `waitlist.promoted` | booking | notification |
| `notification.send` / `user.registered` / `password.reset` | auth | notification |

**Producers** never write to Kafka directly — they write to a **transactional outbox** in
the same DB transaction; a poller publishes. **Consumers** are idempotent (dedup tables) and
protected by a shared DLQ handler. See §9.

---

## 8. Core flows, traced through the code

### 8.1 `createBooking` ([`BookingService.java:141`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/BookingService.java#L141))
`@Transactional(timeout=15)`. Phases:
1. **Tenancy/anti-abuse** — `requireBingeId` → `assertBingeBookable` → per-binge pending-cap
   → timeout cooldown → `assertNotFrozen` (HTTP 423) → load event type.
2. **Temporal** — past/future-horizon checks against **venue-local** `LocalDate.now(zone)`;
   content-dedupe (`existsPendingDuplicate`) as a 3rd duplicate layer.
3. **Duration** — 30–720 min, multiple of 30; `validateWithinOperatingHours`.
4. **Availability + race close** — remote availability check (fail-closed on null) →
   **Postgres advisory lock** on `(binge,date)` → `hasTimeConflict` → capacity check
   (structured `CAPACITY_FULL:` error feeds the waitlist CTA).
5. **Pricing** — `resolveEventPrice` (base + hourly×decimalHours); add-ons with inventory
   guards; guest charge (`pricePerGuest × (guests−1)` — first guest free).
6. **Surge → room → loyalty → tax → FX** (order matters): surge multiplies total
   (components stay pre-surge); room surcharge added **after** surge (flat, no surge on a
   luxury room); loyalty redeem (fail-safe on optimistic-lock); tax on post-discount
   subtotal; FX lock atomic `consumeLock`.
7. **Persist & emit** — snapshot every value onto the row; event-log `CREATED`; saga
   `STARTED`; **outbox** `BOOKING_CREATED` (same tx); `bookingRiskEvaluator` in `REQUIRES_NEW`
   so it can never roll back the booking.

### 8.2 Pricing precedence ([`PricingService.resolveEventPrice:401`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/PricingService.java#L401))
Highest wins: **CUSTOMER-specific → ADMIN override rate code → customer profile's rate code
→ DEFAULT**. An override lacking an entry for the event **falls through** (never zeroes).
`source` + `rateCodeName` are snapshotted on the booking. Surge (`resolveSurge`) is a
separate multiplier (max matching active rule, ignored if ≤ 1.0).

### 8.3 Payment callback + HMAC ([`PaymentService.handleCallback:260`](backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java#L260))
Ordering is security-critical: **dedup** (cached state) → **pessimistic lock**
(`...ForUpdate`) → terminal-state idempotency → **late-capture auto-refund** branch →
**stale (>24h) rejection (400)** → **signature required (403)** → **HMAC verify**. The
verifier uses `Mac("HmacSHA256")` over `orderId|paymentId` and **`MessageDigest.isEqual`**
(constant-time). All callbacks (success *and* failure) are verified — a forged *failure*
could otherwise cancel a legitimate INITIATED payment. Dedup marker is written **after** side
effects (crash-safe). Outcome → SUCCESS/FAILED, publish `payment.success`/`payment.failed`.

### 8.4 Loyalty earn/redeem (FIFO)
- **Earn** ([`EarnEngine`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/loyalty/v2/engine/EarnEngine.java)):
  computes **redeemable points** + **qualifying credits** from one rule with separate
  multipliers; `floor(amount × num/den)` rounds against the customer; credits a **FIFO lot**
  with expiry; QC event de-duped per (membership, booking); hands off to `TierEngine`.
- **Redeem** ([`RedeemEngine`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/loyalty/v2/engine/RedeemEngine.java)):
  `quote` (read-only) vs `burn` (transactional); tier bonus from JSON; **capped by
  `maxRedemptionPercent`** of the booking with points scaled down so the member is never
  overcharged; asymmetric customer-favorable rounding.
- **Wallet debit** (`PointsWalletService.debit`): locks wallet, idempotent at two
  granularities, drains lots **oldest-first**, writes one ledger row per lot + an aggregate
  marker.
- **Expiry** (`ExpiryEngine`): nightly, per-lot `@Transactional`, one bad row can't crash the
  run; orphaned lots throw + log ERROR for manual cleanup.
- **Tier** (`TierEngine`): promotions fire **immediately**; demotions are **deferred** to the
  annual rollover; same-tier re-qualification extends validity.

### 8.5 Cancellation + tiered refund
- **Customer cancel** ([`cancelBookingByCustomer:1001`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/BookingService.java#L1001)):
  ownership → 403 (generic msg); only PENDING; pending-transfer lock → 409; policy via
  `evaluateCustomerCancellation` (tiered or legacy-binary, **venue-local time**, refund
  gated by venue payment-state flags).
- **Core cancel** ([`cancelBooking:1845`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/BookingService.java#L1845)):
  routes through the **state machine**; publishes a `BookingCancelledEvent` carrying
  `refundAmt = total × pct/100` so the loyalty listener (`@Async`, `AFTER_COMMIT`) reverses
  points proportionally; reverses collected amount; emits `booking.cancelled`.
- **Refund math** ([`RefundCalculationService`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/RefundCalculationService.java)):
  reads the **immutable price snapshot** (deterministic after FX/tax moves); proportional tax
  reversal; double-entry ledger rows with deterministic `entryUuid`s.
- **Payment execution** ([`initiateRefund:559`](backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java#L559)):
  pessimistic lock + **DB-level SUM** over-refund guard; SUCCESS/PARTIALLY_REFUNDED only.

### 8.6 Auth login ([`AuthService.login:227`](backend/auth-service/src/main/java/com/skbingegalaxy/auth/service/AuthService.java#L227))
Constant-time lookup → CAPTCHA gate → password verify (held) → ordered rejections
(unknown/inactive/locked) → atomic failure increment + lockout → MFA gate (no-token
challenge response) → success (reset attempts, mint JWT+refresh, open session).

---

## 9. Reliability & recovery layer

### 9.1 Booking state machine ([`BookingStateMachine`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/statemachine/BookingStateMachine.java))
The **only** place `Booking.status` is mutated. Static transition table; each rule declares
target, audit event, allowed roles, and reason-required. Idempotent (replaying an event on a
booking already in the target state is a no-op — makes Kafka redelivery safe).
Audit-by-construction (IP + User-Agent captured). Terminal states (COMPLETED/CANCELLED/
NO_SHOW) only reachable via the SUPER_ADMIN `override` path (separate allow-list, mandatory
reason, tagged `MANUAL_REVIEW_FLAGGED`).

Transition table:
```
PENDING    + PAYMENT_SUCCEEDED/ADMIN_CONFIRM → CONFIRMED
PENDING    + CUSTOMER/ADMIN/SYSTEM_CANCEL    → CANCELLED       + MARK_NO_SHOW → NO_SHOW
CONFIRMED  + CHECK_IN → CHECKED_IN           + CANCEL → CANCELLED   + MARK_NO_SHOW → NO_SHOW
CHECKED_IN + CHECK_OUT → COMPLETED           + UNDO_CHECK_IN → CONFIRMED (reason required)
```

### 9.2 Transactional outbox ([`OutboxPublisher`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/scheduler/OutboxPublisher.java))
Polls every 2s under **ShedLock** (one replica publishes). Keyed by `aggregateKey` →
per-aggregate ordering. **Three-way failure fork**: recoverable code-bug
(`SerializationException`/`ClassCastException`/"Can't convert value") → retry forever (never
drop a business event); exhausted (≥10 attempts) → `failedPermanent`; transient → retry next
tick. Each success persists immediately; failures continue to the next event. Pushes to a
binge-scoped admin **SSE** stream after each publish.

### 9.3 Consumer DLQ ([`KafkaDlqErrorHandlerConfig`](backend/common-lib/src/main/java/com/skbingegalaxy/common/config/KafkaDlqErrorHandlerConfig.java))
`DefaultErrorHandler` with `FixedBackOff(1s, 3)` → on exhaustion routes to `<topic>-dlt`
(lowercase-hyphen, matches pre-created topics + replay allow-list) and commits the offset.
Never-retryable: `DeserializationException`, `IllegalArgumentException`, `NullPointerException`,
`ClassCastException` → straight to DLT. Prevents head-of-line blocking.

### 9.4 Saga ([`SagaOrchestrator`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/SagaOrchestrator.java))
`STARTED → AWAITING_PAYMENT → PAYMENT_RECEIVED → CONFIRMED → COMPLETED`, with
`COMPENSATING → COMPENSATED/FAILED`. Only declared transitions allowed. Before CONFIRMED it
**re-verifies `collected ≥ total`** and enters compensation on underpayment.
`PaymentEventListener.onPaymentFailed` drives compensation (auto-cancel via the same
system-cancel path) and `markFailed` + rethrow (→ DLQ) on compensation failure.

### 9.5 Recovery console ([`AdminRecoveryQueueController`](backend/booking-service/src/main/java/com/skbingegalaxy/booking/controller/AdminRecoveryQueueController.java))
Human-in-the-loop repair that **reuses the automated code paths**: `stuck-pending/{ref}/cancel`
(same system-cancel), `paid-not-confirmed/{ref}/replay` (re-runs the post-payment status
decision, idempotent), `expired-holds/{token}/release`. Read queues + funnel for ops.

---

## 10. Booking-service subsystems

The largest service (~100 entities, ~50 services). Beyond the flows above:

- **Tax engine** — `TaxService`, pluggable `TaxProvider`/`InternalTaxProvider`,
  `JurisdictionResolver`, `TaxContext`; per-rule breakdown serialized to JSON on the booking.
- **Currency/FX** — `CurrencyService`, `CurrencyRate`, `FxRateLock`, `FxLockService`
  (atomic validate-and-consume), `CheckoutQuoteService` (preview totals).
- **Invoicing & ledger** — `InvoiceService`, `InvoicePdfService`, `Invoice`/`InvoiceLine`,
  `LedgerService` (double-entry), `CreditNote`.
- **Slot holds** — `SlotHoldService` + `SlotHoldExpiryScheduler` (temporary reservation
  during checkout).
- **Waitlist** — `WaitlistService`, `WaitlistOfferExpiryScheduler`,
  `WaitlistPromotionListener`, race-tested.
- **Transfers** — `BookingTransferService` (magic-link, token-bearer recipient endpoints),
  `BookingTransferExpiryScheduler`.
- **Recurring bookings** — up to 12 occurrences, per-occurrence availability/pricing/surge.
- **Check-in** — `CheckInService`, QR/OTP tokens (`CheckInToken`), late-arrival detection,
  early-checkout tracking.
- **CQRS** — `BookingReadModel` + `BookingProjectionService` + `CqrsReconciliationJob`.
- **Risk** — `BookingRiskEvaluator`, `BookingRiskFlag`, admin acknowledge/manual-flag.
- **Reviews** — customer + admin reviews, pending-review tracking, binge review summaries.
- **Rooms** — `VenueRoom`, `RoomBlock`, approval status, capacity.
- **Schedulers (8)** — `PendingBookingTimeoutScheduler`, `SlotHoldExpiryScheduler`,
  `WaitlistOfferExpiryScheduler`, `NoShowAutomationScheduler`, `BingeGracePeriodScheduler`,
  `BookingTransferExpiryScheduler`, `CqrsReconciliationJob`, `SseHeartbeatScheduler`,
  `OutboxPublisher` — all ShedLock-guarded.
- **CMS** — `BingeSiteContent`, customer dashboard/about config JSON, `MediaController`.
- **Admin notifications** — in-app notification bell (`AdminNotification`, SSE).
- **Customer freeze** — `CustomerFreezeService`, `CustomerBingeFreeze`.
- **System settings** — `SystemSettingsService`.

---

## 11. Loyalty v2 subsystem

`booking/loyalty/v2/**` — a complete points-and-tiers engine:

- **Engines** — `EarnEngine`, `RedeemEngine`, `ExpiryEngine`, `TierEngine`,
  `PointsWalletService` (FIFO lots + ledger).
- **Config/membership** — `LoyaltyProgram`, `LoyaltyTierDefinition`, `LoyaltyBingeBinding`,
  `LoyaltyBingeEarningRule`, `LoyaltyBingeRedemptionRule`, `LoyaltyMembership`,
  `LoyaltyPointsWallet`, `LoyaltyPointsLot`, `LoyaltyLedgerEntry`, `LoyaltyQualificationEvent`.
- **Services** — `EnrollmentService`, `LoyaltyMemberService`, `LoyaltyAdminService`,
  `LoyaltyConfigService`, `StatusMatchService`, `GuestShadowService`, `MemberNumberGenerator`.
- **Perk registry** (`PerkRegistry` + `PerkDeliveryHandler`) — ~10 handlers: welcome bonus,
  birthday bonus, tier discount %, early-access booking window, extended free-cancellation,
  priority waitlist, reward-catalog claim, surprise-delight budget, status-extension grant,
  bonus-points multiplier.
- **Events** — `LoyaltyV2BookingListener` (earn on completion, reverse on cancel).
- Exposed at `/api/v2/loyalty/{me,admin,super-admin}`.

---

## 12. Payment-service

[`PaymentService`](backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java)
(1,224 lines) behind a pluggable `PaymentProvider` (`RazorpayPaymentProvider` today; Stripe/
Adyen could slot in). Highlights:

- **HMAC-SHA256** callback/webhook verification (constant-time compare); **stale-callback
  rejection**; **late-capture auto-refund**.
- **Over-refund prevention** (pessimistic lock + DB SUM); **over-collection guard**.
- **Webhook idempotency** — `ProcessedWebhookEvent` + `WebhookDedupService`.
- **Disputes/chargebacks** — `PaymentDispute`, `DisputeWebhookService`, `DisputeAdminService`,
  admin console.
- **Cash payments** — `CashPaymentEventListener` (Kafka), `recordCashPayment`, `addPayment`.
- **Admin approval workflow** — `AdminApprovalService`, `AdminApprovalRequest`,
  `AdminApprovalExpiryScheduler` (for sensitive ops like high-value refund retries).
- **Reconciliation** — `PaymentReconciliationScheduler`; `PaymentStatusHistory`; `AuditLog`;
  own transactional outbox.
- Simulation mode (`PAYMENT_SIMULATION_ENABLED`) for local/dev.

Consumes `booking.cancelled` (`BookingCancelledEventListener` → auto-refund); publishes
`payment.success/failed/refunded`.

---

## 13. Notification-service

Only MongoDB service. Kafka consumer (`EventListener`) → `ChannelRouter` →
pluggable providers. Routing cascade **PUSH → WHATSAPP → SMS → EMAIL**, gated by
provider-configured + contact-present + not-muted; security-critical types (`PASSWORD_RESET`)
are **EMAIL-only**. Providers are `@Autowired(required=false)` → graceful degradation when
Twilio/FCM creds are absent: `TwilioSmsProvider`/`MockSmsProvider`,
`TwilioWhatsAppProvider`/`MockWhatsAppProvider`, `FcmPushProvider`/`MockPushProvider`.
Templating (`TemplateService`, `NotificationTemplate`, `WhatsAppTemplate`), per-user
preferences (`NotificationPreferenceService`), `EmailRateLimiter`, delivery-status webhooks
(`DeliveryWebhookController` → open/click/bounce → `DeliveryStatus`), retry/digest/reminder
schedulers.

---

## 14. Auth-service

Entities: `User`, `UserSession`, `RevokedToken`, `EmailVerificationToken`,
`PasswordResetToken`, `PasswordHistoryEntry`, `AuthAuditLog`, `AuthorityGrant`,
`ResourceLock`, `SiteContent`. Services: `AuthService`, `AuthorityService`,
`UserSessionService`, `TokenRevocationService`, `TotpService`, `PasswordHistoryService`,
`PwnedPasswordService`, `CaptchaValidationService`, `EmailVerificationService`,
`UserAnonymizationService`, `CelebrationReminderService`,
`PasswordResetTokenCleanupService`, `AuthAuditService`. `AdminSeeder` auto-creates the admin
on first boot. `JwtProvider` mints/validates access + refresh tokens with iss/aud claims.

---

## 15. Frontend (React SPA)

`App.jsx` — **75 lazy-loaded route chunks** behind `Suspense`. State via **Zustand** + three
contexts: `AuthContext`, `BingeContext` (selected venue), `CurrencyContext`. Libraries:
axios (proactive token refresh in `api.js`), react-router 6, react-toastify, DOMPurify (XSS),
i18next, react-datepicker, react-phone-number-input, `country-state-city`, `@sentry/react`,
`vite-plugin-pwa`.

**Route guards** (UX-only; gateway enforces): `PublicOnlyRoute`, `CompleteProfileRoute`,
`ProtectedRoute` (customer), `AdminRoute`, `SuperAdminRoute` (native or delegated-with-scope),
`BingeRequired` / `AdminBingeRequired` (must select a venue first).

**Booking wizard** ([`booking/BookingWizard.jsx`](frontend/src/components/booking/BookingWizard.jsx)):
4 steps (customer) / 5 (admin: + customer-search). Steps:
`StepCustomer → StepEvent → StepDateTime → StepAddOns → StepReview`. Client mirrors the
backend pricing order (room after surge, server-side tax preview). `handleSubmit` guards:
re-entrancy ref, client freeze, rate-limit countdown, field validation; parses **structured
backend errors** (`FROZEN:`, `CAPACITY_FULL:`, slot-race → back to step 2, HTTP 429
countdown).

**Customer pages**: Home, Login/Register/Forgot/Reset, VerifyEmail, CompleteProfile,
PlatformDashboard, BingeSelector, Dashboard, BookingPage, BookingConfirmation, MyBookings,
Membership, CustomerPayments, AboutBinge, AccountCenter, CustomerSettings,
CustomerNotifications, MySessions, MfaSetup, PaymentPage.

**Admin pages (~45)**: AdminLogin/Register, AdminEntranceDashboard, AdminDashboard,
AdminBookings, AdminBookingCreate, AdminBlockedDates, AdminEventTypes, AdminRateCodes,
AdminCustomerPricing, AdminVenueRooms, AdminSurgeRules, AdminWaitlist, AdminCustomerFreezes,
AdminRiskFlags, AdminSupportConsole, AdminRecoveryQueues, AdminApprovals, AdminDisputes,
AdminFailedRefunds, AdminSlotHolds, AdminTaxes, AdminCurrencies, AdminNotificationTemplates,
AdminOps, AdminReports, AdminUsersConfig, AdminAllUsers, AdminCustomerEdit, AdminAccount,
AdminHomeEditor, AdminAccountPageEditor, AdminLoyaltyCenter, BingeManagement,
SuperAdminDashboard, AuthorityHandover, PlatformDashboard. Real-time via `useRealtimeUpdates`
(SSE). API layer: `services/{api,endpoints,loyaltyV2,analytics,...}.js`.

---

## 16. Data model

Enums (`common-lib`):
- `BookingStatus`: PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW
- `PaymentStatus`: PENDING, INITIATED, SUCCESS, FAILED, REFUNDED, PARTIALLY_REFUNDED, PARTIALLY_PAID
- `PaymentMethod`: UPI, CARD, BANK_TRANSFER, WALLET (+ CASH via admin)
- `UserRole`: CUSTOMER, ADMIN (SUPER_ADMIN as elevated role)
- `NotificationChannel`: EMAIL, SMS, WHATSAPP, PUSH
- `AuthorityScope`: CURRENCIES, NOTIFICATIONS, LOYALTY, OPS, ALL_USERS, CUSTOMER_EDIT, ADMIN_REGISTER, HOME_CMS, ACCOUNT_CMS, SUPER_DASHBOARD

**Booking** (central entity) carries `@Version` (optimistic lock) and a full financial
snapshot: base/addOn/guest/subtotal/tax/total amounts, `taxBreakdownJson`, `currencyCode`/
`fxRate`/`displayAmount` (+ base/display/payment/settlement currency codes), `pricingSource`/
`rateCodeName`, surge multiplier/label, venue-room snapshot, loyalty points earned/redeemed/
discount, check-in/checkout tracking, reschedule/transfer/recurring linkage, support fields
(escalation, goodwill, cancellation reason/actor), and `priceSnapshotId`/`billingAddressId`/
`fxLockedUntil`. A `@PrePersist` hook defends the NOT-NULL finance columns.

Composite indexes: `idx_booking_customer_date`, `idx_booking_binge_date_status`,
`idx_booking_binge_status`.

**Migrations**: auth ~15, availability ~2, booking ~63, payment ~13 (Flyway, source).

---

## 17. Infrastructure & DevOps

- **Config server** serves per-service YAML and the gateway route table.
- **Docker Compose** ([`docker-compose.yml`](docker-compose.yml)): postgres, mongodb, redis,
  zookeeper, kafka(+init), zipkin, all 8 services + frontend; multi-stage builds (no local
  JDK/Maven needed); health checks. KRaft variant: `docker-compose.kraft.yml`.
- **Kubernetes** ([`k8s/`](k8s/)): HPA, PDB, **zero-trust NetworkPolicies** (deny-all +
  explicit allow), cert-manager TLS, RBAC, Argo Rollouts (canary), Istio, external-secrets,
  Postgres HA + maintenance/autovacuum, daily backup CronJobs, Grafana/monitoring/logging.
  Rendered from `.env` via `scripts/render-k8s-manifests.sh`; secrets via
  `scripts/sync-k8s-secrets.sh`.
- **CI/CD**: [`Jenkinsfile`](Jenkinsfile) — immutable image tags, waits for Mongo replica-set
  init, verifies rollouts; expects a `production-env` file credential.
- **Data ops**: `scripts/check-migration-safety.sh`, `restore-postgres-backup.sh`,
  `restore-mongodb-backup.sh`; `BACKUP-RESTORE.md`.
- **Testing**: JUnit unit + integration across every service (saga, pricing, idempotency,
  state-machine, cross-binge isolation, authz, loyalty engines, waitlist race…); Vitest +
  Playwright e2e on the frontend; **k6** load tests with committed evidence
  (`production-proof/`, 0% error rates, forged-HMAC payments 100% rejected).

---

## 18. Coverage notes

This document maps the **entire system** structurally (every service, the full endpoint
surface, all Kafka topics, all enums, the data model, infra) and traces the **highest-value
flows line-by-line** (createBooking, pricing precedence, payment/HMAC, loyalty earn/redeem/
expiry/tier, cancellation/refund, auth login, authority handover, the state machine, outbox,
DLQ, saga compensation, recovery console, the booking wizard).

For exhaustive method-by-method or button-by-button detail of any one area — the tax engine
internals, FX lock lifecycle, individual loyalty perk handlers, each scheduler, or a specific
admin page's full interaction set — drill into the named source files; the cross-references
above point to exact locations.

*Generated as a living reference — update alongside the code it describes.*
