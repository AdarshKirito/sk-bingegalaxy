# SK Binge Galaxy — Private Venue & Event Booking Platform

A React PWA over Spring Cloud for private venue/event booking. The runtime is **five domain services** (auth, availability, booking, payment, notification), **three infrastructure applications** (gateway, config, discovery), and `common-lib`, with PostgreSQL per relational service, MongoDB notifications, Redis and Kafka.

> **Production status (audit cut 2026-07-16): NO-GO.** The July remediation closed most original findings, including real Razorpay refunds, booking hold/occupancy controls, Mongo index/anonymization work, production profiles and earlier tenant leaks. Current release blockers are authenticated PWA response caching, payment/approval tenant binding, ambiguous provider refund/capture outcomes, webhook/dispute accounting and paid-cancellation settlement. Start with [`docs/00-AUDIT-INDEX.md`](docs/00-AUDIT-INDEX.md), the [issue register](docs/07-ISSUE-REGISTER.md) and [remediation roadmap](docs/08-RECOMMENDATIONS-ROADMAP.md).

## Technology

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.4.5, Spring Cloud 2024.0.1, Maven |
| Frontend | React 18.3, Vite 5.3, React Router, Zustand, Axios, Workbox/PWA |
| Data | PostgreSQL 16 (auth/availability/booking/payment), MongoDB 7, Redis |
| Messaging | Kafka, transactional booking outbox, retry/DLT/idempotency patterns |
| Payments | Razorpay orders/callbacks/refunds/disputes and reconciliation; simulation for development |
| Deployment | Docker Compose, Kubernetes/Istio/Argo/HPA/PDB, Jenkins |
| Observability | Actuator, Prometheus/Grafana, Zipkin, structured logs; Sentry source integration |

## Applications

| Application | Port | State | Responsibility |
|---|---:|---|---|
| discovery-server | 8761 | — | Eureka registry |
| config-server | 8888 | — | centralized configuration |
| api-gateway | 8080 (host 8090) | Redis/session | ingress, JWT/session headers, CSRF, rate limits, routing |
| auth-service | 8081 | auth_db | users, login/MFA/sessions, authority/delegation, privacy |
| availability-service | 8082 | availability_db | availability and blocking |
| booking-service | 8083 | booking_db | Binges/rooms, pricing/tax, holds/waitlist, booking lifecycle, loyalty/ops |
| payment-service | 8084 | payment_db | orders/callbacks/refunds/disputes/approvals/reconciliation |
| notification-service | 8085 | MongoDB | templates, delivery, reminders, push/webhooks |

`common-lib` is a shared JAR, not a runtime service. API base path is `/api/v1` (loyalty also exposes `/api/v2/loyalty`). Current source has 47 controllers/421 mappings, while the frontend declares 70 routes and 407 normalized unique API method/path pairs; the static route diff is clean.

## Quick start (development only)

```bash
docker compose up --build -d
```

- Frontend: <http://localhost:3000>
- Gateway: <http://localhost:8090> (`/actuator/health`)

Compose defaults to simulated payments. Do not supply real provider credentials or expose the stack as production until every P0/P1 gate in the checklist passes. For local non-Docker development, use Java 17+, Maven 3.9+ and Node 20+, then start discovery → config → gateway → domain services → Vite.

## Current source milestones

- Flyway: auth V19, availability V2, booking V77, payment V14.
- Shared topic constants include booking/payment/notification/auth/room lifecycle plus `user.anonymized`.
- `LICENSE` and `NOTICE` are present; the repository is proprietary/all-rights-reserved.
- Historical runtime/load/security artifacts are under `production-proof/` and `docs/audit/`; they are point-in-time evidence, not proof of the current remediated deployment.

## Documentation

The canonical audit is the fresh deep-audit set [`docs/00-AUDIT-INDEX.md`](docs/00-AUDIT-INDEX.md) through [`docs/08-RECOMMENDATIONS-ROADMAP.md`](docs/08-RECOMMENDATIONS-ROADMAP.md) (re-authored 2026-07-23; supersedes the earlier thin 00–28 stub set). Detailed investigation/history is preserved under `docs/audit/`; pre-overhaul originals are byte-preserved under `docs/_previous/2026-07-11T19-01-30Z/`. `docs/codebase/` contains partially superseded reference pages—prefer the canonical set.

## Project structure

```text
backend/            Maven parent: 8 executable apps + common-lib
frontend/           React/Vite SPA/PWA, unit/e2e configuration
k8s/ grafana/       deployment and monitoring assets
scripts/            build/render/operations helpers
load-tests/         k6 scenarios and historical evidence
production-proof/   dated verification artifacts
docs/               canonical 00–28 audit + detailed evidence/runbooks/archive
docker-compose.yml  local multi-service environment
Jenkinsfile         CI/CD pipeline
```
