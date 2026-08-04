# SK Binge Galaxy — Architecture

> **⚠️ Status banner (2026-07-25, audit AUD-2026-07-25-01 at commit `6440f58`):** the synopsis below dates from 2026-07-16; Flyway heads are now V20/V2/V80/V16. The current architecture document is [docs/audit/04-ARCHITECTURE-AND-DEPENDENCIES.md](docs/audit/04-ARCHITECTURE-AND-DEPENDENCIES.md); dependency map: [docs/audit/05-SERVICE-DEPENDENCY-MAP.md](docs/audit/05-SERVICE-DEPENDENCY-MAP.md). Original preserved below.

> Current synopsis: 2026-07-16 working tree. The canonical architecture is [`docs/02-ARCHITECTURE-AND-DEPENDENCIES.md`](docs/02-ARCHITECTURE-AND-DEPENDENCIES.md); data/domain, security, service deep-dives, frontend and the issue register are the adjacent `docs/00`–`docs/08` deep-audit set.

## Runtime shape

```text
React/Vite PWA
  |
  v
Spring Cloud API Gateway -- Redis/session/rate limits
  |
  +-- auth-service -------- PostgreSQL auth
  +-- availability-service  PostgreSQL availability
  +-- booking-service ----- PostgreSQL booking
  +-- payment-service ----- PostgreSQL payment ---- Razorpay
  +-- notification-service  MongoDB --------------- delivery providers
             ^   |
             | Kafka
             +---+

Config Server and Discovery Server support the eight executable applications.
common-lib is a compile-time shared library, not a service.
```

Only the gateway and frontend are intended as public application entry points. The gateway validates session/JWT context, strips spoofable identity headers and injects trusted headers; downstream services remain responsible for role, Binge scope and object ownership. Internal service endpoints use a shared-secret boundary. Provider webhooks use HMAC and idempotency/dedup machinery.

## Domain/data ownership

- Auth: identity, sessions, MFA, authority/delegation, privacy/content (Flyway V19).
- Availability: blocks and slot availability (V2).
- Booking: Binge/room inventory, holds/waitlist, pricing/tax, booking lifecycle, loyalty/operations, transactional outbox (V77).
- Payment: orders/callbacks/refunds/disputes/approvals/reconciliation (V14).
- Notification: templates/delivery/reminders/push in MongoDB.

Kafka carries lifecycle propagation across service-owned stores. `binge_id` is shared by value; there are no cross-database foreign keys, so internal contracts and event validation are integrity boundaries.

## Important patterns

Current source includes booking advisory locks plus a database occupancy backstop, optimistic versions, ShedLock, retry/DLT/replay, booking outbox/idempotent consumers, server-authoritative money/pricing helpers, profile/secret fail-fast controls, module/delegated authority and observability/health assets.

## Current architecture blockers

- Workbox stores authenticated booking/admin responses outside server authorization (SEC-009).
- Approval endpoints bypass Binge scope, and payment's booking snapshot omits owner/Binge/remaining balance (SEC-010/011).
- Provider calls and database intent/result are not durably coordinated; transport uncertainty is collapsed into failure (PAY-006/007).
- Webhook dedup is not atomic with business state; dispute/cancellation financial sagas are incomplete (PAY-008/009, BOOK-004).
- Revenue queries derive ledger meaning from mutable payment status (PAY-010).

These make the release **NO-GO** despite the mature platform structure. See [`docs/07-ISSUE-REGISTER.md`](docs/07-ISSUE-REGISTER.md) and [`docs/08-RECOMMENDATIONS-ROADMAP.md`](docs/08-RECOMMENDATIONS-ROADMAP.md).
