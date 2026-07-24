# 12 — Integrations & OTA Readiness

## Actual external integrations (verified present)

| Provider | Purpose | Auth / config | Status |
|---|---|---|---|
| Razorpay | Payments + refunds | key id/secret (env), HMAC webhook | Implemented; simulation mode via `PAYMENT_SIMULATION_ENABLED` (SEC-003 risk) |
| SMTP mail | Transactional email | spring-mail + Thymeleaf | Implemented |
| Web Push (VAPID) | Browser push | VAPID key pair (SEC-004: committed private key default) | Implemented |
| WhatsApp / SMS | Messaging | `WHATSAPP_*` / `SMS_*` config vars | Config present in `.env.example`, absent from `.env` — likely not wired in dev |
| Sentry | Error tracking (frontend) | `VITE_SENTRY_DSN` | Optional |
| Zipkin | Distributed tracing | micrometer-brave | Implemented |
| Google OAuth | Social login | `GOOGLE_CLIENT_ID` | `/auth/google` endpoint present |

No maps, accounting, CRM, or file-storage provider integration found beyond the above. **No invented integrations.**

## OTA / third-party reservation-channel readiness

**No OTA / channel-manager / CRS / PMS integration exists today.** Assessment of whether the architecture *could* support one:

| Capability | State |
|---|---|
| Binge/property mapping | Architecturally possible (`binges` with stable ids) |
| Room-type mapping | Partial — rooms exist but no "room type" abstraction; individual rooms are the inventory |
| Rate-plan mapping | Partial — rate codes exist, not modeled as external rate plans |
| Availability publishing | Missing — availability is computed live, not published |
| Restriction publishing (stop-sell, min-stay, CTA/CTD) | Missing |
| Reservation ingestion / modify / cancel | Missing (no external booking channel) |
| Overbooking prevention across channels | Missing — advisory lock is single-DB, no channel reconciliation |
| Channel-source tracking / commission | Missing (`bookings` has no channel/commission columns) |
| Idempotency / eventual consistency for channel messages | The outbox/idempotency substrate exists and would be reusable |

**Recommendation (evidence-based, not fashion-driven):** at the current product stage — a private, direct-booking venue platform — **no OTA integration is required.** If added later, the correct boundary is likely a **channel-manager integration** (not per-OTA direct), and its configuration belongs inside existing operational responsibilities (Rooms → external mapping, Rate Codes → rate plans, Blocked Dates → stop-sell), reusing the existing outbox/idempotency infrastructure. A dedicated user-facing "Integrations" module is justified **only** if channel reconciliation/monitoring proves to need its own surface — which current evidence does not establish. Do not build it speculatively.
