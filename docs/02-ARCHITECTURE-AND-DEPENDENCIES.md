# 02 — Architecture & Dependencies

This is the "how it all connects" chapter: the dependency edges between services, **what a change in one service does to the others**, and the integrity seams where things break.

## The 9 modules and who owns what data

| Module | Owns (source of truth) | Store |
|---|---|---|
| auth-service | Users, credentials, sessions, MFA/TOTP secrets, authority grants/delegation, password history, privacy/erasure, site-content CMS | `auth_db` (Postgres) |
| availability-service | Blocked dates, blocked slots, slot availability | `availability_db` (Postgres) |
| booking-service | **Binges, rooms, bookings, pricing (rate codes/surge/tax/FX/currencies), loyalty v2, invoices/ledger/credit notes, waitlist, transfers, check-in, risk flags, freezes, admin notifications, outbox** | `booking_db` (Postgres) |
| payment-service | Payments, refunds, disputes, approval requests, webhook dedup, reconciliation, connected accounts | `payment_db` (Postgres) |
| notification-service | Notifications, templates, preferences, push subscriptions, reminders | `notification_db` (Mongo) |
| api-gateway | Nothing persistent (uses Redis for sessions/rate limits) | Redis |
| config-server | Baked-in per-service YAML (native profile) | classpath |
| discovery-server | Eureka registry | in-memory |
| common-lib | Shared code only (not a runtime service) | — |

**Key rule:** `binge_id` is shared *by value* across databases. **There are no cross-database foreign keys.** Referential integrity between services is therefore enforced only by (a) internal API contracts and (b) event-payload validation. Those two things are the integrity boundary — treat them as load-bearing.

## Dependency edges

### Synchronous (Feign / HTTP, blocking)
- **availability-service → booking-service** (`BookingBingeClient`, `/internal/**` + `X-Internal-Secret`) — needs binge/venue data to validate availability.
- **payment-service → booking-service** (`BookingBingeClient`, `/internal/binges/{id}`, `/internal/amount/{ref}`) — needs the **authoritative** booking snapshot (owner, binge, amount owed, remaining balance) to bind payment writes to the right tenant/owner. *This is a critical seam:* the public binge DTO strips `adminId`, so ownership checks MUST use the internal snapshot, or you get a 403 storm / wrong-tenant binding.
- **booking-service → availability-service** (`HttpAvailabilityClient` + `AvailabilityClientFallback`) — checks slot availability during booking; has a **circuit-breaker fallback** (good).
- **booking-service → auth-service** (`/api/v1/auth/internal/**`, `/authority/internal/locks/lookup`) — user contact projection for messaging; authority-lock lookups for server-side lock enforcement.
- **Everything → config-server** (startup) and **→ discovery-server** (registration/lookup).

### Asynchronous (Kafka, non-blocking, eventually consistent)
Topics (from `common-lib/constants/KafkaTopics`):
`booking.created/confirmed/cancelled/rescheduled/transferred/checked-in/completed/cash-payment`, `waitlist.promoted`, `payment.success/failed/refunded`, `notification.send`, `user.registered`, `password.reset`, `user.anonymized`, `room.approved/rejected/blocked/unblocked`.

Typical flows:
- **Booking → Payment:** `booking.created` triggers payment intent; `booking.cancelled` (with `refundAmount`) drives a **refund-intent saga** in payment.
- **Payment → Booking:** `payment.success/failed/refunded` updates booking status/balance; consumer has a **tenant fence** (binge-id on `PaymentEvent`).
- **Any → Notification:** most lifecycle events fan out to `notification.send`; notification routes to email/SMS/WhatsApp/push/webpush by preference.
- **auth `user.anonymized` → all PII holders:** right-to-erasure fan-out; each service redacts its copy (notification has a dedicated `UserAnonymizedEventListener`).

**Event hardening present:** transactional **outbox** in booking (events written in the same tx as state, published by a relay), **idempotent/inbox consumers** (`ProcessedEvent`/`ProcessedWebhookEvent`, claim-first PK collision on duplicates), **DLQ** error handler in common-lib, and per-topic `__TypeId__` header stamping (booking-service disables default JSON type headers, so consumers rely on explicit type headers + a DLQ-safe deserializer — a known past break-point).

## How a change ripples (the part that bites)

- **Change a shared enum / event payload in `common-lib`** → recompiles and can break *every* service. `common-lib` is the blast radius center. Add fields tolerantly (all recent contract changes were backward-tolerant — keep it that way).
- **Add/rename a column or entity field in any service** → because `ddl-auto=validate`, you MUST ship a matching Flyway migration or the service **won't start**. Forgetting the migration is a hard deploy failure, not a soft bug. (This is the intended integrity gate; it is also the #1 self-inflicted outage.)
- **Change the public Binge DTO** → payment/availability ownership checks that (correctly) use the *internal* snapshot are unaffected; but any code that accidentally reads the public DTO for `adminId` will 403. The split public/internal contract is deliberate and must be respected.
- **Add a new admin endpoint** → you must touch up to **four** places or it silently breaks: (1) gateway path-gating (`isAdminPath`/`SCOPE_MAP` for delegation), (2) the service `SecurityConfig` matcher, (3) the per-binge module matrix if it's a gated module, (4) the frontend route guard + `useModuleAccess`. Missing any one produces either a 403 storm or an authz hole. This is the highest-frequency source of real bugs here.
- **Rotate `JWT_SECRET`** → by default `CRYPTO_SECRET_KEY` is *derived from it*, so rotating JWT_SECRET makes **enrolled TOTP secrets undecryptable**. Set `CRYPTO_SECRET_KEY` explicitly and independently in prod (compose already warns about this).
- **Redis outage** → gateway session-revocation checks and rate-limiting **fail open** (availability over strictness); revoked tokens then survive to natural 15-min expiry, and rate limits stop enforcing. Booking advisory locks/holds also live in Redis. This is a deliberate, documented tradeoff — but it means Redis is a soft single-point for security posture, not just performance.

## Trust boundaries (defence in depth — verified)

1. **Gateway** is the only place that mints trusted identity. It strips *all* client-supplied `X-User-*` / `X-Authority-*` headers (and WARN-logs spoof attempts), validates the JWT signature (HMAC, `alg:none` blocked, 30s skew), enforces role/scope/super-admin path gates, derives *effective* role from delegation claims, and checks the sid-revocation denylist.
2. **Each service re-enforces authz** via `GatewayHeaderAuthFilter` (turns the trusted `X-User-Role` into a Spring `ROLE_*`) + its own `SecurityConfig` matchers. So even direct service-mesh access still needs the right role. Booking additionally enforces the per-binge module matrix and binge-scope/ownership in the service layer.
3. **`/internal/**`** requires `X-Internal-Secret` (constant-time) → grants `ROLE_SYSTEM`. Only auth/availability/booking expose+guard internal endpoints; payment/availability are Feign *consumers*.
4. **Webhooks** are public but HMAC-SHA256 verified fail-closed (Razorpay disputes, email delivery), with idempotency/dedup markers committed atomically with business state.

The seams that are only as strong as their discipline: the split public/internal DTO contract, the four-places-per-endpoint authz wiring, the migration-per-schema-change rule, and the `common-lib` contract compatibility. Every one of those is a place where a well-meaning change silently breaks a downstream service. See [07-ISSUE-REGISTER.md](07-ISSUE-REGISTER.md).
