# 05 — Service Deep-Dives

Per-service responsibility, key flows, and the risk notes that matter. Controllers/entities are code-enumerated; internal branch behaviour of the two god-classes is sampled, not exhaustively line-read (flagged where relevant).

## api-gateway (18 files) — the trust boundary
**Controllers:** `CsrfTokenController`, `FallbackController`. **Filters (ordered):** `JwtAuthenticationFilter` (−1, identity/RBAC/delegation/sid), `CsrfProtectionFilter`, `RateLimitFilter` + `UserRateLimitFilter`, `SecurityHeadersFilter`, `ApiVersionHeaderFilter`, `MdcContextFilter`. CORS in `GatewayConfig` (credentials + explicit allow-headers incl. `Idempotency-Key`, `X-XSRF-TOKEN`, `X-Binge-Id`).
**Risk notes:** Redis-backed rate limits + sid revocation both **fail open** on Redis outage. Routing is discovery-based (Eureka `lb://`); route table not in the yml (derived from service ids). Strong, well-commented, above-average. See 03.

## auth-service (91 files)
**Controllers:** `AuthController`, `AuthorityController` (delegation grants + `/internal/locks/lookup`), `InternalUserController` (trusted contact projection), `SiteContentController` (CMS), `UserPrivacyController` (DPDP).
**Key services:** `AuthService`, `UserSessionService` (+Redis revocation cache), `TotpService` (MFA), `EmailVerificationService`, `UserAnonymizationService`, `AuthAuditService`, `JwtProvider`.
**Flows:** register/login/admin-login/google/refresh/logout; MFA enrol/confirm; forced temp-password change; session list + revoke/force-logout (writes `auth:revoked-sid:` denylist the gateway reads); authority-handover grant lifecycle; privacy/erasure fan-out (`user.anonymized`).
**Risk notes:** the `/api/v1/auth/** permitAll` catch-all means matcher ordering is load-bearing (new endpoint under `/auth` is public by default). `CRYPTO_SECRET_KEY` default-derived from `JWT_SECRET` couples TOTP secrets to JWT rotation. MFA-required default false locally.

## availability-service (25 files) — smallest, stable
**Controller:** `AvailabilityController` (public `dates`/`slots`/`event-types`; `/internal/check`). Feign → booking for binge/venue data (with `InternalApiFeignConfig` adding the secret).
**Risk notes:** thin and low-churn; main dependency risk is the sync Feign hop to booking. Confirm it has a fallback/timeout like booking→availability does (booking side has `AvailabilityClientFallback`; verify the reverse edge doesn't block a request thread indefinitely).

## booking-service (392 files) — the domain core
**Controllers (28):** `BingeController`, `BookingController`, `SlotHoldController`, `WaitlistController`, `BookingTransferController`, `CheckInController`, `CustomerFreezeController`, `InvoiceController`, `ExportController`, `MediaController`, `MessageAttachmentController`, `MyNotificationController`, `BookingAnalyticsController`, `InternalBookingController`, `PublicCurrencyController`/`PublicTaxController`, `BingeSiteContentController`, `BingeAccessController` (module matrix), and the **Admin** family (`AdminBookingController`, `AdminOpsController`, `AdminPricingController`, `AdminTaxController`, `AdminCurrencyController`, `AdminNotificationController`, `AdminRiskFlagController`, `AdminRecoveryQueueController`, `AdminSupportController`, `AdminSseController`), plus **Loyalty v2** (`LoyaltyV2Admin/Customer/SuperAdminController`).
**Key services:** `BookingService` **(5,189 lines — god class)**, `PricingService` (1,292), `RefundCalculationService`, `SagaOrchestrator`, `BingeService` (geo/nearby), plus loyalty/pricing/tax/currency subsystems.
**Flows:** binge/room CRUD + approval; slot-hold → booking create (Redis advisory lock + DB occupancy backstop + optimistic version + idempotency claim-first) → confirm/check-in/complete/cancel; waitlist promotion; transfers (magic-link); layered pricing → snapshot; loyalty accrual/redemption; invoicing/ledger/credit-notes; transactional outbox relay; SSE for admin realtime; CSV/export; per-binge module gating; geo "venues near me" (index-backed bounding box).
**Risk notes:** **`BookingService` is the single highest-maintenance-risk file in the repo** — 5k lines mixing lifecycle, pricing calls, event emission, refund orchestration, and DTO mapping. Regressions concentrate here. The layered pricing engine is powerful but complex; edge-case correctness (surge × rate-code × customer-profile × FX × tax interaction, rounding order) should have golden-master tests. Kafka type-header stamping is per-topic (a past break-point). SSE + advisory locks + Redis holds all add moving parts.

## payment-service (82 files)
**Controllers:** `PaymentController` (613), `AdminApprovalController`.
**Key services:** `PaymentService` **(2,247 lines — god class)**, `DisputeWebhookService` (HMAC), `AdminApprovalService`, `IdempotencyService` (claim-first), `WebhookDedupService`, `ConnectedAccountService`, `DisputeAdminService`, `PaymentReconciliationScheduler` (per-row tx, provider calls outside tx, bounded batches). **Providers/clients:** `RazorpayGatewayClient`, `StripeGatewayClient`, `PaymentMethodCatalog`/`Resolver` (country ∩ provider capability).
**Flows:** order create (intent-first) → provider → callback → capture; refund-intent saga (durable intents, stable receipts, provider I/O outside rollback-able tx, at-most-once, receipt-first reconciliation); dispute/chargeback (monotonic, real ledger rows); webhook dedup markers committed atomically with business state; admin approval workflow (binge-scoped, ownership-checked).
**Risk notes:** payment `SecurityConfig` has **no `InternalApiAuthFilter`** (correct — it exposes no `/internal/**`, it's a Feign consumer of booking). The money-movement correctness work is genuinely careful; the risk is that all of it lives inside a 2.2k-line class. Provider sandbox proof (Razorpay refund `refund.processed`/`refund.failed` webhooks) is an open verification item, not code.

## notification-service (61 files) — Mongo, multi-channel
**Controllers:** `NotificationController`, `NotificationPreferenceController`, `TemplateController`, `WhatsAppTemplateController`, `PushSubscriptionController`, `DeliveryWebhookController` (HMAC).
**Providers:** email + `TwilioSms`/`TwilioWhatsApp`/`FcmPush`/`WebPush` with Mock* fallbacks; `ChannelRouter` picks by preference. **Schedulers:** `BookingReminderScheduler`, `DigestScheduler`, `NotificationRetryScheduler` (ShedLock-coordinated). `MongoIndexBootstrap` builds indexes; `KafkaHealthIndicator`.
**Flows:** consume lifecycle events → template render → channel route → deliver → delivery-webhook status → retry/DLT; digests; Web Push (VAPID).
**Risk notes:** cross-service JSON type-header contract was a real past break (booking disables default type headers → notification couldn't deserialize; fixed via per-topic `__TypeId__` + DLQ deserializer). The `vapidPushService` bean-name gotcha is documented. Provider creds (Twilio/FCM/VAPID) are env-gated; Mock providers keep dev working.

## config-server / discovery-server (2 + 2)
Config-server serves **baked-in** `configurations/*.yml` via the `native` profile (config is in the jar, not an external git repo) — simple, but redeploy-to-change and no runtime refresh. Discovery is standard Eureka with a password. Both have a `SecurityConfig`.

**Cross-cutting maintainability signal:** four files carry a disproportionate share of complexity — `BookingService.java` (5,189), `PaymentService.java` (2,247), `AdminBookings.jsx` (2,385), `BingeManagement.jsx` (2,029). Decomposing these is the highest-leverage refactor available (see 08).
