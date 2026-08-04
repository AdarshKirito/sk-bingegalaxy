# Service Dependency Graph — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · edges verified by reading the cited files

## Sync edges (HTTP)

| # | From → To | Mechanism | Evidence |
|---|---|---|---|
| S1 | frontend → gateway | axios base `/api`, httpOnly cookies + CSRF + Idempotency-Key | frontend/src/services http client |
| S2 | gateway → auth/availability/booking/payment/notification | Spring Cloud Gateway routes; header injection after JWT+denylist | api-gateway route config + filters |
| S3 | booking → auth | user lookup, authority-lock checks (`AuthorityLockGuard`) | booking-service/service/AuthorityLockGuard.java |
| S4 | booking → availability | slot validation at create | booking create path internal client |
| S5 | booking ↔ payment | refund intents / booking state confirmation | internal clients both directions |
| S6 | payment → Razorpay/Stripe | provider SDK/HTTP | RazorpayGatewayClient, StripeGatewayClient |
| S7 | notification → SMTP/WebPush/webhooks | delivery adapters | notification adapters |
| S8 | * → config-server (boot) / discovery (runtime) | Spring Cloud | bootstrap configs |

All internal edges (S3-S5) carry the `INTERNAL_API_SECRET` header enforced by the common-lib filter.

## Async edges (Kafka)

See [producer-consumer-matrix.tsv](producer-consumer-matrix.tsv) — 20 topics, 16 listeners, DLT everywhere, outbox in booking+payment, direct-publish in auth (EVT-02).

## Store ownership (no shared databases)

| Service | Store | Cross-service access |
|---|---|---|
| auth | auth_db (PG) | none — others call APIs |
| availability | availability_db (PG) | none |
| booking | booking_db (PG) | none |
| payment | payment_db (PG) | none |
| notification | MongoDB | none |
| gateway | Redis (sessions/limits) | booking also uses Redis for holds/loyalty caches (separate keyspaces) |

`bingeId` is shared **by value**; no cross-DB foreign keys exist — integrity depends on internal-API validation and event contracts (by design).

## Boot dependency order (compose-verified)

infra (PG/Mongo/Redis/Kafka) → init jobs → discovery → config → gateway+services (healthcheck-gated) → frontend.
