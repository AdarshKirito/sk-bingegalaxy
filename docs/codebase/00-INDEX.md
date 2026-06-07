# Codebase Reference — Line-by-Line Documentation

This directory is the **per-file, line/method-level** companion to the top-level
[`ARCHITECTURE.md`](../../ARCHITECTURE.md). Where `ARCHITECTURE.md` describes the *system*,
these documents walk **every source file**, module by module, explaining each class, field,
method, and the reasoning behind the non-obvious code.

## Scope

The repository tracks ~1,136 files. This set documents the **source files** — Java, JSX/JS,
YAML config, SQL migrations, manifests, scripts. It deliberately excludes non-source
artifacts that have no line-by-line value: build logs (`build*.log`, `*.out`, up to 200 MB),
k6 result JSON/logs, `target/` compiled duplicates, `node_modules`, `.vite` metadata, and
ad-hoc stress-test text dumps. Those are inventoried but not annotated.

## Documents

| # | Module | Files | Status |
|---|--------|-------|--------|
| 01 | [common-lib](01-common-lib.md) | shared enums, events, DTOs, exceptions, money, security, logging | ✅ complete |
| 02 | [discovery + config](02-discovery-and-config.md) | Eureka + Config server | ✅ complete |
| 03 | [api-gateway](03-api-gateway.md) | edge filters, controllers, routes | ✅ complete |
| 04 | [auth-service](04-auth-service.md) | identity, JWT, MFA, sessions, authority, CMS | ✅ complete |
| 05 | [availability-service](05-availability-service.md) | date/slot blocking | ✅ complete |
| 06a | [booking · entities](06a-booking-entities.md) | 47 JPA entities | ✅ complete |
| 06b | [booking · services](06b-booking-services.md) | pricing, tax, FX, saga, checkout, etc. | ✅ complete |
| 06c | [booking · controllers](06c-booking-controllers.md) | 26 REST + SSE controllers | ✅ complete |
| 06d | [booking · schedulers/listeners/config](06d-booking-schedulers.md) | outbox, timeouts, Kafka | ✅ complete |
| 06e | [booking · loyalty-v2](06e-booking-loyalty.md) | engines, perks, services | ✅ complete |
| 07 | [payment-service](07-payment-service.md) | Razorpay, refunds, disputes | ✅ complete |
| 08 | [notification-service](08-notification-service.md) | multi-channel delivery | ✅ complete |
| 09 | [frontend](09-frontend.md) | React SPA (pages, components, services) | ✅ complete |
| 10 | [infra / k8s / scripts / CI](10-infra.md) | deployment & ops | ✅ complete |

## Conventions

- File headers use the repo-relative path. Line references look like `Foo.java:42`.
- "Why" notes capture the rationale baked into the code comments plus the design intent.
- Trivial Lombok DTOs are documented as field tables rather than line-by-line getters.
