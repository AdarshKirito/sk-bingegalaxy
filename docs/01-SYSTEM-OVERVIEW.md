# 01 — System Overview

## What the product is

SK Binge Galaxy is a **private venue / event booking platform**: a customer-facing PWA for discovering venues ("Binges") and booking rooms/slots with add-ons, payments, loyalty and notifications; plus a large **admin/operations console** for venue owners and platform super-admins to manage inventory, pricing, taxes, currencies, approvals, disputes, refunds, waitlists, messaging, risk flags, CMS and loyalty programs.

The central domain noun is a **Binge** = a venue/tenant. Almost everything is scoped by `binge_id`. A given admin operates one or more Binges; a super-admin operates the platform.

## Personas & roles

| Role | Who | Surface |
|---|---|---|
| **CUSTOMER** | Guests & registered end-users | PWA: discover venues, book, pay, manage bookings, loyalty, messages, notifications, profile |
| **ADMIN** | Venue owner/operator (per-Binge) | Admin console **scoped to their Binge(s)**, further constrained by a per-binge *module permission matrix* and by a *delegation/authority-handover* grant system |
| **SUPER_ADMIN** | Platform operator | Everything, cross-Binge: user management, currencies, loyalty program config, CMS, audit log, sessions overview, approvals |

Two extra access mechanisms layer on top of the role:
- **Authority Handover / delegation:** a super-admin can grant an ADMIN a time-boxed *scope* (e.g. `LOYALTY`, `CURRENCIES`, `ALL_USERS`). The gateway elevates that admin's *effective* role to SUPER_ADMIN **for the matching path only**, for the duration of the grant.
- **Per-binge module matrix:** each Binge can enable/disable/lock individual admin modules; the booking-service enforces a fail-closed 403 when a module is off or no binge context is present.

## Runtime shape (verified)

```
React/Vite PWA  ──►  API Gateway (Spring Cloud Gateway, host :8090)
                       │  strips spoofable X-User-* headers; validates JWT (Bearer or httpOnly cookie);
                       │  role/scope/super-admin gating; sid revocation (Redis); CSRF double-submit;
                       │  rate limits; CORS; security headers; API version header
                       ├──► auth-service        :8081  ── PostgreSQL auth_db
                       ├──► availability-service :8082  ── PostgreSQL availability_db
                       ├──► booking-service     :8083  ── PostgreSQL booking_db   (the domain core)
                       ├──► payment-service     :8084  ── PostgreSQL payment_db    ── Razorpay / Stripe
                       └──► notification-service :8085  ── MongoDB notification_db  ── FCM/Twilio/WebPush/email
        Kafka  ◄──────────────── lifecycle events flow between all services ──────────────►
        Redis  ── gateway sessions/rate-limits, auth session-revocation denylist, booking advisory locks/holds
   Config Server :8888 (native, baked-in per-service YAML) · Discovery :8761 (Eureka) · Zipkin · common-lib (shared JAR)
```

Only the **gateway** and **frontend** are public entry points. Internal `/internal/**` endpoints are guarded by a shared-secret header (`X-Internal-Secret`, constant-time compared). Provider webhooks (Razorpay disputes, email delivery) are public but HMAC-verified.

## Technology (verified from POM/compose)

| Layer | Stack |
|---|---|
| Backend | Java 17, Spring Boot 3.4.5, Spring Cloud 2024.0.1, Maven multi-module, jjwt 0.12.5, MapStruct 1.6.3, Lombok |
| Frontend | React 18.3, Vite 5, React Router, **Zustand + React Context (both)**, Axios, Workbox/PWA, react-toastify, Sentry |
| Data | PostgreSQL 16 (auth/availability/booking/payment), MongoDB 7 (notification), Redis 7 |
| Messaging | Kafka (Confluent 7.6), transactional outbox + idempotent/inbox consumers + DLQ |
| Payments | Razorpay (orders/callbacks/refunds/disputes/reconciliation) + Stripe scaffolding; simulation mode default-on for dev |
| Infra | Docker Compose (local), Kubernetes/Istio/Argo/HPA/PDB manifests, Jenkins pipeline |
| Observability | Actuator, Prometheus/Grafana, Zipkin tracing, structured JSON logs w/ PII masking, Sentry (frontend + source) |

## Scale & shape of the codebase

| Module | Java files | Notable |
|---|---:|---|
| booking-service | 392 | **The domain core.** Binge/room inventory, booking lifecycle, pricing engine (rate codes, surge, tax, FX), loyalty v2, invoicing/ledger, waitlist, transfers, check-in, risk flags, freezes, saga/outbox |
| auth-service | 91 | Identity, login/MFA/TOTP, sessions + revocation, authority/delegation, privacy/DPDP, site-content CMS |
| payment-service | 82 | Orders/callbacks/refunds/disputes/approvals/reconciliation, Razorpay + Stripe, connected accounts |
| notification-service | 61 | Mongo templates, channel routing (email/SMS/WhatsApp/push/webpush), reminders, digests, retry |
| common-lib | 39 | Shared enums/events/DTOs, money util, gateway-header + internal-secret filters, Kafka DLQ config |
| availability-service | 25 | Blocked dates/slots, availability checks |
| api-gateway | 18 | The trust boundary (filters below) |
| config-server / discovery-server | 2 / 2 | Native config, Eureka |

Frontend: ~70 page components (heavily admin-weighted), a single 312-line axios client (`services/api.js`) with endpoints called inline in pages, Zustand stores + React contexts, PWA service worker (autoUpdate), i18n locales, a two-vocabulary CSS system.

Flyway heads: **auth V20 · availability V2 · booking V79 · payment V16.** `ddl-auto=validate` everywhere (Flyway owns schema; entity/migration drift fails startup — an intentional integrity gate, and a real operational footgun on deploy).

See [02-ARCHITECTURE-AND-DEPENDENCIES.md](02-ARCHITECTURE-AND-DEPENDENCIES.md) for how the modules depend on and change each other.
