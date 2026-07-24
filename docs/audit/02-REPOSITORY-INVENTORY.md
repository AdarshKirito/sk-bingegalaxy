# 02 — Repository Inventory

Repository-wide census (master-prompt Phase 1). Commit `e3edbc1` on `main`. Counts are from the census agents + direct listing, reconciled in Phase 4/6; the honest file-level accounting with Level A/B/C depth is in [19-COVERAGE-MANIFEST.md](19-COVERAGE-MANIFEST.md). This document is the *what-exists* inventory; [02-ARCHITECTURE.md](02-ARCHITECTURE.md) is the *how-it-fits* view.

## Structure (top-level, all classified)

| Path | Classification |
|---|---|
| `backend/` | 9 Maven modules (8 services + `common-lib`) — see Services |
| `frontend/` | React 18 + Vite SPA/PWA |
| `k8s/` | 22 Kubernetes manifests + grafana provisioning (rendered via `scripts/render-k8s-manifests.sh`) |
| `docs/` | project documentation (`codebase/`, `runbooks/`, `production-proof/`, `audit/` [this set], `_previous/` [archive]) |
| `scripts/` | operational + k8s-render + stress scripts |
| `load-tests/` | k6 scripts |
| root | `docker-compose.yml`, `Jenkinsfile`, `.env`/`.env.example`, `pom.xml` (aggregator), stress/k6 artifacts (hygiene — DEVOPS-002), `backend;C` stray dir (DEVOPS-005) |

## Applications

- **1 frontend application** — `frontend/` (customer + admin + super-admin SPA; role-gated routes, not separate builds).
- **8 backend runtime services** + **1 shared library** (`common-lib`, not a runtime service).

## Services (9 Maven modules)

| Module | Role | Startup class | Port |
|---|---|---|---|
| `api-gateway` | Spring Cloud Gateway (WebFlux) — edge auth, CSRF, header inject | `ApiGatewayApplication` | 8090 |
| `discovery-server` | Eureka service registry | `DiscoveryServerApplication` | 8761 |
| `config-server` | Spring Cloud Config (native) | `ConfigServerApplication` | 8888 |
| `auth-service` | identity, JWT, sessions, MFA, admin/user mgmt | `AuthServiceApplication` | 8081 |
| `availability-service` | slot generation, blocked dates/slots | `AvailabilityServiceApplication` | 8082 |
| `booking-service` | binge/venue/room/event-type, booking, pricing, tax, surge, FX, loyalty, waitlist, messaging | `BookingServiceApplication` | 8083 |
| `payment-service` | Razorpay orders, webhooks, refunds, disputes, approvals | `PaymentServiceApplication` | 8084 |
| `notification-service` | email/push/WhatsApp fan-in, templates, preferences | `NotificationServiceApplication` | 8085 |
| `common-lib` | shared DTOs, `KafkaTopics`, `EventEnvelope`, DLQ config, filters | (library — no `main`) | — |

Verified as services (not by directory name) via startup classes, `@SpringBootApplication`, Eureka registration, gateway route predicates, and per-service datasource config.

## Packages / shared libraries

- `common-lib` (shared across all backend services): `constants/KafkaTopics`, `event/EventEnvelope`, `KafkaDlqErrorHandlerConfig`/`KafkaDlqProperties`, shared enums (`PaymentMethod`, `PaymentStatus`, …), `ApiResponse`, exception types.
- Frontend service layer: 13 modules under `frontend/src/services/` (`endpoints.js` = 18 API groups / ~369 calls, `api.js` = axios instance/interceptors/CSRF/refresh, plus analytics/i18n/push/loyaltyV2/timeFormat/geo/exportUtils/…).

## Languages & frameworks

| Layer | Language | Framework(s) |
|---|---|---|
| Backend | Java 17 | Spring Boot 3.4.5, Spring Cloud 2024.0.1 (Gateway/WebFlux, Eureka, Config, Kubernetes-client) |
| Frontend | JavaScript (JSX) | React 18.3.1, Vite 5.3.1, react-router-dom 6.23.1, Zustand 5.0.12, axios 1.8.4 |
| Frontend extras | — | vite-plugin-pwa 1.2.0 (autoUpdate SW), i18next 25.1.2 (en/hi/ta/te), @sentry/react 9.15.0 |
| Data access | Java | Spring Data JPA (Postgres), Spring Data MongoDB (notifications), Flyway (Postgres migrations) |
| Messaging | Java | Spring Kafka (+ transactional outbox, DLQ error handler) |

## Detected versions (evidence)

- Spring Boot **3.4.5** (`backend/pom.xml` parent `<version>`), Java **17** (`<java.version>`), Spring Cloud **2024.0.1** (`<spring-cloud.version>`).
- React **^18.3.1**, Vite **^5.3.1**, react-router-dom **^6.23.1**, Zustand **^5.0.12**, axios **^1.8.4**, vite-plugin-pwa **^1.2.0**, i18next **^25.1.2**, @sentry/react **^9.15.0**, Vitest **^4.1.2**, @playwright/test **^1.59.1** (`frontend/package.json`).

## Build tools

- Backend: **Maven** (aggregator `pom.xml` + per-module poms; `spring-boot-maven-plugin`). No Gradle.
- Frontend: **Vite** (npm scripts: `dev`, `build`, `test`/`vitest`, Playwright e2e).
- Container: per-service **Dockerfiles** (all 9 carry `HEALTHCHECK` — DEVOPS-003 resolved), `docker-compose.yml` (17 services).
- CI: **Jenkinsfile** (13 stages). `.github/` contains `dependabot.yml` only — **no GitHub Actions workflows**.

## Databases

| Store | Owner service(s) | Migrations / schema |
|---|---|---|
| PostgreSQL `auth_db` | auth-service | Flyway V1–V19 |
| PostgreSQL `availability_db` | availability-service | Flyway V1–V2 |
| PostgreSQL `booking_db` | booking-service | Flyway V1–V74 (66 JPA entities) |
| PostgreSQL `payment_db` | payment-service | Flyway V1–V13 (9 entities) |
| MongoDB `notification_db` | notification-service | 6 documents; **no migration tool** (index bootstrap gap — DATA-003) |

Database-per-service ownership confirmed (separate datasources; no cross-service direct table access observed). Cross-service PII is *snapshotted* (denormalized), not shared — see DATA-004.

## Queues / streaming

- **Apache Kafka** (Zookeeper-based in compose). Topics enumerated in `common-lib` `KafkaTopics` (20 topics — see [08-EVENTS-AND-MESSAGING.md](08-EVENTS-AND-MESSAGING.md)). Transactional outbox + idempotent consumers + DLQ error handler.

## Caches

- **Redis** — session revocation list (checked at gateway), FX/reference caching, rate-limit/lockout state.

## Infrastructure

- **Local:** `docker-compose.yml` — 17 services (15 long-running incl. gateway:8090 + frontend:3000 published; postgres/mongo/redis/kafka/zookeeper internal; + `kafka-init` one-shot + `zipkin`).
- **Kubernetes:** 22 manifests + grafana provisioning, rendered via `scripts/render-k8s-manifests.sh`. Includes monitoring (Prometheus alerts), namespace/profile config. Not deployed/tested in this audit (structural review only).
- **Observability:** Zipkin (running), Prometheus alert rules (`k8s/monitoring.yml`), correlation via Spring Cloud Sleuth/Micrometer tracing.

## External systems / providers

| Provider | Purpose | Notes |
|---|---|---|
| **Razorpay** | payments (orders + webhooks) | refunds NOT wired (PAY-002); simulation mode flag (SEC-003/DEVOPS-004) |
| SMTP / mail | transactional email | no SMTP in dev (notifications queue PENDING) |
| **Web Push (VAPID)** | browser push | committed key default (SEC-004) |
| WhatsApp / SMS (Twilio) | messaging | config-driven; content templates |
| Google | OAuth login (`/auth/google`) | — |
| reCAPTCHA | login bot-gate | stubbed unless `production` profile (SEC-003/008) |
| Sentry | frontend error tracking | `@sentry/react` |

No OTA / channel-manager / CRS / PMS integration exists — OTA-readiness is assessed architecturally in [12-INTEGRATIONS-AND-OTA-READINESS.md](12-INTEGRATIONS-AND-OTA-READINESS.md).

## Test suites

- Backend: **75 JUnit test classes** (unit + slice). Not executed on host (no JDK/Maven; Docker build excluded for disk-safety) — content audited, results cited as historical only.
- Frontend: **40 Vitest** files + **7 Playwright** e2e specs. Not executed (no Node on host).
- Load: **k6** scripts (`load-tests/`, root `k6-*.json` artifacts) — historical evidence only; no new run (no k6 on host).

## Documentation (project-owned Markdown)

34 pre-audit `.md` files: 5 root, 16 `docs/codebase/`, 3 `docs/runbooks/`, 12 `docs/production-proof/`, 1 `docs/load-tests/`. Contradiction analysis in [20-CONTRADICTION-REGISTER.md](20-CONTRADICTION-REGISTER.md); 17 replaceable files archived to `docs/_previous/2026-07-11T19-01-30Z/` (DOC-001).

## Generated / excluded areas (Level C — with reason)

| Area | Reason for exclusion |
|---|---|
| `node_modules/`, `target/`, `dist/`, `.vite/`, `.npm-cache/` | dependency/build output — no first-party behaviour |
| `playwright-report/`, `test-results/`, `k6_bin/` | generated test artifacts |
| `.git/` | VCS internals |
| tracked artifacts (`k6.zip`, `*.out`, `k6-*.json`, `test_all.txt`, logs) | inert byproducts — flagged as hygiene (DEVOPS-002), not behavioural |
| `docs/_previous/` | audit's own archive of superseded docs |

First-party baseline for coverage accounting: **1,336 files** (`docs/audit/evidence/file-inventory.tsv`). The `~2,639` figure in early census notes counted a broader set before the Level-C exclusions above were applied.
