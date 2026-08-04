# 04 — Architecture and Dependencies (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC

## Module inventory (9 Maven modules)

| Module | Port | Store | Flyway head | Responsibility |
|---|---:|---|---|---|
| discovery-server | 8761 | — | — | Eureka registry |
| config-server | 8888 | — | — | Central config (`configurations/` per service) |
| api-gateway | 8080 (host 8090) | Redis | — | Ingress: JWT validation, session denylist, CSRF, rate limits, header injection, authority elevation |
| auth-service | 8081 | PostgreSQL `auth_db` | **V20** | Identity, MFA/TOTP, sessions, authority grants, resource locks, privacy/anonymization, CMS content |
| availability-service | 8082 | PostgreSQL `availability_db` | **V2** | Slot availability & blocks |
| booking-service | 8083 | PostgreSQL `booking_db` | **V80** | Binges/rooms, pricing/tax/FX, holds/waitlist, booking lifecycle, loyalty v2, ops consoles, outbox |
| payment-service | 8084 | PostgreSQL `payment_db` | **V16** | Orders, callbacks, refunds, disputes, approvals, reconciliation, Stripe Connect, outbox |
| notification-service | 8085 | MongoDB | — (TTL indexes) | Templates, delivery (email/push/webhook), reminders, dedup |
| common-lib | — | — | — | Shared enums, Kafka topic constants, money utils, internal-auth filter |

## Dependency versions (from [backend/pom.xml](../../backend/pom.xml) and lockfiles)

- Java 17 · Spring Boot **3.4.5** · Spring Cloud **2024.0.1** — currently supported line
- jjwt, MapStruct, Mockito pinned in parent POM; frontend has 26 direct deps installed with `npm ci` against committed [package-lock.json](../../frontend/package.json)
- Full inventories: [evidence/backend-dependency-inventory.tsv](evidence/backend-dependency-inventory.tsv), [evidence/frontend-dependency-inventory.tsv](evidence/frontend-dependency-inventory.tsv)

## Data topology

- **PostgreSQL 16**: 4 isolated databases, one per relational service; per-service credentials created by [infra/init-databases.sql](../../infra/init-databases.sql); `max_connections=200` vs Hikari 20×5 services = 100 baseline (headroom ratio 2×)
- **MongoDB 7**: notification-service only; TTL 90 d on `Notification.createdAt`
- **Redis**: gateway session revocation denylist, rate limiting, slot-hold TTLs, loyalty caches
- **No cross-database foreign keys** — `bingeId` shared by value; integrity enforced at internal-API and event-contract boundaries

## Messaging fabric

- Kafka: 20 topic constants in [KafkaTopics.java](../../backend/common-lib/src/main/java/com/skbingegalaxy/common/constants/KafkaTopics.java); DLT suffix `-dlt`
- **Transactional outbox** in booking-service and payment-service (OutboxEvent + relay scheduler); consumers dedupe via ProcessedEvent/IdempotencyKey
- Base compose runs ZooKeeper Kafka RF=1; [docker-compose.kraft.yml](../../docker-compose.kraft.yml) is the ZooKeeper-free overlay; k8s runs RF=3
- Full producer/consumer accounting: [evidence/producer-consumer-matrix.tsv](evidence/producer-consumer-matrix.tsv) — note **8 published topics have no in-repo consumer** (see doc 12)

## Configuration architecture

- Spring Cloud Config server with per-service YAML under config-server `configurations/`
- Profile groups: auth-service [application.yml](../../backend/auth-service/src/main/resources/application.yml) L11-12 maps `kubernetes → production` profile — k8s deployments therefore activate production hardening; **docker-compose does not** (dev posture by design)
- Fail-fast controls: payment-service `@PostConstruct` throws `IllegalStateException` when simulation is enabled in production or credentials are missing ([PaymentService.java](../../backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java) L107-136)
- `SUPER_ADMIN_REQUIRE_MFA` code default **"true"** (AuthService.java L464-480); compose overrides to `"false"` for dev (docker-compose.yml L456)

## Architectural patterns in force (VERIFIED-STATIC)

1. Gateway header-injection trust model + downstream re-verification (defense in depth)
2. Booking concurrency: Redis SlotHold → `pg_advisory_xact_lock` (BookingRepository.java:433) → **V75 DB trigger occupancy backstop** (three independent layers)
3. CQRS read model for admin booking lists (BookingReadModel)
4. Saga state for cancellation/refund propagation (SagaState + refund intents)
5. Server-authoritative money: minor-unit longs, `MONEY_SCALE_AND_ROUNDING_CONTRACT.md` honored by MoneyUtils
6. ShedLock for cluster-safe schedulers (33 `@Scheduled` jobs)
7. Idempotency-Key header auto-attached by frontend axios client for mutating calls

## Diagram

See [evidence/service-dependency-graph.md](evidence/service-dependency-graph.md) for the full sync/async edge map with per-edge evidence.
