# 05 — Backend Services (per-service audit)

> **Dated original service review.** Prefer [`../08-BACKEND.md`](../08-BACKEND.md) and [`../14-PAYMENT-REFUND-DISPUTE.md`](../14-PAYMENT-REFUND-DISPUTE.md). Statements that payment/refund is incomplete or refunds do not call Razorpay are superseded.

Java 17 · Spring Boot 3.4.5 · Spring Cloud 2024.0.1. Depth per `19-COVERAGE-MANIFEST.md`. 698 `.java` files, 75 test classes.

## discovery-server (:8761)
Eureka server, secured. No DB, no domain logic. Boots first. No findings.

## config-server (:8888)
Spring Cloud Config (native profile), basic-auth (`configuser`), Eureka client. Serves 6 service configs. Real secrets use `${VAR}` no-default (fail-fast) — good; VAPID key + reCAPTCHA secret are exceptions (SEC-004/008). Deploy-order root for app services.

## api-gateway (:8080→:8090)
Spring Cloud Gateway (WebFlux), reactive Redis (rate-limit), Resilience4j. **Security keystone:** `JwtAuthenticationFilter` (verify + strip/inject identity headers, session revocation), `CsrfProtectionFilter`, `UserRateLimitFilter`. Routes to all services via `lb://`. Controllers: `CsrfTokenController`, `FallbackController`. Does **not** validate `X-Binge-Id` (SEC-001 root enabler). 7 tests. Sound edge design; runtime-confirmed controls.

## auth-service (:8081, auth_db)
Identity, JWT issuance, MFA/TOTP, sessions (Redis revocation), authority grants/delegation, site content, privacy/anonymization. 10 entities, 15 services, 5 schedulers, 5 controllers. Produces USER_REGISTERED / PASSWORD_RESET / NOTIFICATION_SEND. **Findings:** captcha stub loads in prod (SEC-003); strong auth hardening otherwise (BCrypt12, HIBP, password history, OTP limiting). PII lifecycle columns present (V14). 7 tests.

## availability-service (:8082, availability_db)
Blocked dates/slots + live availability compute; Feign→booking for venue timezone (30s cache). 2 entities, 1 controller. Correctly uses the **internal** binge endpoint + ownership check. **Findings:** blocked-slot whole-hour vs 30-min booking granularity (DATA-008); DST latent (BOOK-003); availability advisory-only (safe — DB lock authoritative). 2 tests.

## booking-service (:8083, booking_db) — the core
66 entities, 32 controllers, 47 services, 17 schedulers. Owns binges/venues/rooms/event-types, pricing/tax/FX/surge, loyalty v2, checkout, invoices, booking lifecycle, outbox/saga/CQRS read model. **Strengths:** transactional outbox, advisory-lock concurrency, single-authority state machine, idempotent payment-event consumers, immutable price snapshots, binge-scoped caches. **Findings:** cross-binge PII leak (SEC-001), invoice-list leak (SEC-002), ops control-plane (SEC-005), slot-hold dead code (BOOK-001), multi-room duplicate TOCTOU (DATA-005), no DB double-booking backstop (DATA-001), poison-message retry (REL-001). 41 tests — but none cover the confirmed isolation gaps (TEST-001).

## payment-service (:8084, payment_db)
Razorpay (order/callback/webhook), refunds, disputes (V12), maker-checker approvals (V11), webhook dedup (V13). 9 entities, 8 services, 6 schedulers, 2 controllers. Feign→booking for binge ownership + amount (uses internal endpoint correctly). **Strengths:** HMAC webhook verification, over-refund pessimistic-lock + SUM guard (app-level), idempotency keys, out-of-order-tolerant. **Findings:** payment FATAL guards inert (SEC-003), no DB over-refund constraint (DATA-002), cross-service PII (DATA-004). **PAY-001:** dedicated deep pass incomplete. 5 tests.

## notification-service (:8085, MongoDB)
Kafka fan-in (7 topics) → email (Thymeleaf) / Web Push (VAPID) / WhatsApp/SMS (config). 6 Mongo documents, 6 controllers, 5 schedulers. **Findings:** Mongo TTL/unique indexes inert (DATA-003) → PII never expires + possible double-send; VAPID private key committed (SEC-004). 6 tests.

## common-lib (shared jar)
`KafkaTopics`, security filters (`GatewayHeaderAuthFilter`, `InternalApiAuthFilter`), events, enums (UserRole/BookingStatus/PaymentStatus/…), `MoneyUtil`, DLQ config, log masking, `BingeContext`, `GlobalExceptionHandler`. 7 tests. The correctness of these shared filters is load-bearing for the whole platform's security — well-factored.

## Cross-service themes

- **Consistent, correct patterns:** internal-secret auth, binge-ownership via internal endpoint, outbox + idempotent consumers, ShedLock on schedulers, Resilience4j fallbacks.
- **The systemic weakness is not the services individually but the convention-based tenant isolation** that a few booking-service endpoints violate (see `09-SECURITY`).
