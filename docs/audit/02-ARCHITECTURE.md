# 02 — Architecture (as verified)

> **Dated original-audit architecture.** Prefer the current [`../03-ARCHITECTURE.md`](../03-ARCHITECTURE.md). The runtime is five domain services plus three infrastructure applications; current migrations reach booking V77/payment V14.

Snapshot of the system as it actually exists at commit `e3edbc1` (including the uncommitted July overhaul). Supersedes the June-dated `ARCHITECTURE.md` (archived under `docs/_previous/`).

## Shape

React/Vite PWA (single SPA) → Spring Cloud Gateway → 6 domain microservices, behind Eureka discovery + Config Server, over Postgres (4 DBs) / MongoDB / Redis / Kafka, with Zipkin tracing. Java 17, Spring Boot 3.4.5, Spring Cloud 2024.0.1.

```
Browser (PWA :3000)
   │  cookie JWT + X-Binge-Id + CSRF double-submit
   ▼
API Gateway (:8090)  ── validates JWT, strips/injects identity headers, rate-limits (Redis), CSRF/Origin
   ├─ auth-service         (:8081, auth_db)          identity, JWT issue, MFA, sessions, authority grants
   ├─ availability-service (:8082, availability_db)  slot/day blocking, availability compute (Feign → booking)
   ├─ booking-service      (:8083, booking_db)       ★ core: bookings, binges, venues/rooms, event-types,
   │                                                   pricing/tax/FX/surge, loyalty, checkout, outbox/saga
   ├─ payment-service      (:8084, payment_db)       Razorpay, refunds, disputes, approvals (Feign → booking)
   └─ notification-service (:8085, Mongo)            email/push/WhatsApp fan-in from Kafka
Infra: discovery (:8761), config (:8888), Kafka+ZooKeeper, Redis, Postgres, Mongo, Zipkin
```

## Services (verified startup + role)

| Service | Port | DB | Key deps | Async role |
|---|---|---|---|---|
| discovery-server | 8761 | — | Eureka server | — |
| config-server | 8888 | — | Cloud Config (native) | — |
| api-gateway | 8080→8090 | — | Gateway (WebFlux), Redis, Resilience4j | — |
| auth-service | 8081 | auth_db | JPA, Kafka, Redis, ShedLock | produces USER_REGISTERED, PASSWORD_RESET, NOTIFICATION_SEND |
| availability-service | 8082 | availability_db | JPA, Feign→booking, Resilience4j | — |
| booking-service | 8083 | booking_db | JPA, Kafka, Feign, Caffeine, ShedLock | outbox → booking.* ; consumes payment.* , booking.cancelled |
| payment-service | 8084 | payment_db | JPA, Kafka, Feign→booking, ShedLock | outbox → payment.* ; consumes booking.cancelled, cash-payment |
| notification-service | 8085 | Mongo | Kafka, Mail, Thymeleaf, ShedLock (Mongo) | consumes 7 topics; produces none (health only) |
| common-lib | — | — | shared jar | KafkaTopics, security filters, events, enums, MoneyUtil |

Config strategy: thin per-service `application.yml` (name + `configserver` import); real config in `config-server/.../configurations/<service>.yml` (native profile). A `kubernetes` profile swaps Eureka for spring-cloud-kubernetes.

## Cross-cutting patterns (verified)

- **Edge security:** JWT-at-gateway, header trust downstream, internal shared-secret for `/internal/**` (see `09-SECURITY`).
- **Concurrency:** Postgres transaction-scoped advisory lock keyed `(bingeId<<32 | epochDay)` serializes all booking mutations per venue-day. Optimistic `@Version` on bookings/holds. ShedLock guards all schedulers.
- **Eventing:** genuine transactional outbox in booking + payment (`OutboxEvent` written in the domain transaction; `OutboxPublisher` drains every 2s, at-least-once); idempotent consumers dedup on `ProcessedEvent.event_key`. Topic names centralized in `common-lib KafkaTopics`. DLQ error handler in common-lib (poison-message caveat REL-001).
- **Resilience:** Resilience4j circuit breakers + Feign fallbacks on cross-service calls; Caffeine caches (binge-scoped keys) in booking.
- **Observability:** Micrometer tracing → Zipkin; logstash-logback JSON encoder; card-number-masking log converters. (k8s adds Prometheus/Grafana/Loki — not exercised in this audit.)

## Deployment targets (two, divergent)

- **docker-compose.yml** — single-node dev: ZooKeeper-backed Kafka (single broker), in-container Postgres/Mongo/Redis, resource limits on every service. Optional `docker-compose.kraft.yml` overlay for KRaft-mode Kafka. This is the running stack (15 containers healthy).
- **k8s/** — an elaborate production target (22 manifests): Istio mTLS, Argo Rollouts canaries, HPA/PDB, CloudNativePG HA Postgres, External Secrets/Vault, Prometheus/Grafana/Loki, backups. Rendered via `scripts/render-k8s-manifests.sh`, deployed by the Jenkinsfile (13 stages). Not stale — a prod-grade superset of compose — but **not exercised** by this audit.

## Notable architectural observations

- The architecture is **more mature than the product stage** — full saga/outbox, CQRS read model, loyalty engine, FX locking, maker-checker payments. This is a strength (integrity) and a maintenance surface.
- **booking-service is a large modular monolith** (66 entities, 32 controllers, 47 services, 17 schedulers) — it owns binges, venues, rooms, event-types, pricing, tax, FX, loyalty, checkout, invoices, and the booking lifecycle. It is the natural bottleneck and the highest-value single service.
- The `production` Spring profile is a first-class part of the design (dev stubs vs real impls) but is **never activated** (SEC-003) — an architecture-level configuration defect.
