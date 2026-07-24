# 03 — Service Dependency Map & Blast Radius

## Dependency edges

| Caller | Callee | Type | Mechanism | Failure handling | Criticality |
|---|---|---|---|---|---|
| Frontend | api-gateway | hard sync | HTTPS/cookies | app-level error/retry | critical |
| api-gateway | all services | hard sync | Spring Cloud LB (Eureka) | Resilience4j + `/fallback` | critical |
| api-gateway | Redis | soft sync | reactive Redis (rate-limit) | degrades (rate-limit off) | medium |
| all services | config-server | startup | Cloud Config | fail-fast at boot | deploy-order |
| all services | discovery (Eureka) | startup/soft | registration | cached registry | medium |
| availability-service | booking-service | hard sync | Feign (`BookingBingeClient`) + internal secret | `BookingBingeClientFallback` (stale-cache) | high |
| payment-service | booking-service | hard sync | Feign (`BookingBingeClient`, `BookingAmountClient`) | fallback | high |
| booking-service | availability-service | soft sync | Http client `/internal/check` | fallback serves "available"; DB advisory lock is authoritative | medium |
| booking-service | auth-service | soft sync | Http client (contact/authority-lock) | fallback | medium |
| auth-service | booking-service | soft sync | `BookingModulePermissionClient` | fallback | low |
| booking ↔ payment ↔ notification | async | Kafka (outbox) | at-least-once, dedup | buffers on outage | high |
| booking/payment/auth | Postgres | data | JPA/Hikari | fail-fast | critical |
| notification | MongoDB | data | Spring Data Mongo | fail | medium |

## What breaks if X is down

- **api-gateway:** total outage (only published entry point).
- **config-server:** running services keep running; no new service can boot (deploy-order dependency).
- **discovery (Eureka):** routing degrades as caches expire; new instances can't register.
- **booking-service:** the platform's core — discovery, availability check, pricing, checkout, admin ops all fail; payment/availability Feign calls fall back. Highest single point of failure.
- **payment-service:** new payments/refunds fail; existing bookings unaffected; cash flow queued via events.
- **availability-service:** availability reads degrade to fallback; **booking still safe** because the DB advisory lock is authoritative, not the availability check.
- **notification-service:** customer/admin notifications stop; core booking/payment unaffected (events buffer in Kafka, replay on recovery).
- **Kafka:** outbox buffers events in Postgres; drains on recovery (no data loss, delayed side effects).
- **Redis:** gateway rate-limiting + session-revocation degrade; auth still works (JWT signature valid), but revocation checks may fail-open or fail-closed depending on config (verify).
- **Postgres:** hard outage for the owning service.

## Structural observations

- **No circular hard-sync dependency** (booking↔payment/availability are one-directional Feign + async events). Good.
- **booking-service is a hub** — many services Feign into it for the binge-ownership snapshot. Correct (single source of truth) but concentrates load and failure.
- **Deployment order matters:** discovery → config → gateway (needs config+redis) → app services (need config+postgres/mongo+kafka+kafka-init). Encoded in compose `depends_on` and Jenkins apply order.
- **Availability is advisory, integrity is DB-enforced** — a deliberate, sound design that keeps booking safe during availability outages.
- Every cross-service edge that matters has a fallback; the gap is observability of *silent* fallback (a stale-availability fallback is invisible to the customer) — recommend metrics on fallback activation.
