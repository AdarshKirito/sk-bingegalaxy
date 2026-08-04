# Current Security Traces — Evidence

> AUD-2026-07-25-01 · commit `6440f58` · request-path traces with file:line

## Trace 1 — Authenticated customer request (happy path)

1. Browser sends cookies (httpOnly access token) + CSRF header → gateway
2. Gateway: JWT signature/expiry check → Redis denylist lookup (revoked session ⇒ 401) → strips inbound `X-User-*` → injects trusted `X-User-Id`, `X-User-Role`, `X-User-Email`
3. Service: controller reads trusted headers; ownership check (e.g., booking lookup scoped by customer id)
4. Response envelope; no stack traces (ControllerAdvice)

## Trace 2 — Admin binge-scoped action

1–2 as above with role=ADMIN
3. `requireManagedBinge(bingeId)` resolves admin's managed set; mismatch ⇒ 403
   - **SEC-001 regression case**: recovery-queue endpoints — AdminRecoveryQueueController routes through `resolveRecoveryScope → requireManagedBinge`; regression test `AdminRecoveryQueueScopeTest` asserts cross-binge denial (VERIFIED)
4. `ModulePermissionInterceptor` checks the 17-module deny-list for (bingeId, userId, module, action)

## Trace 3 — Super-admin loyalty config write

1–2 with role=SUPER_ADMIN (or authority-grant elevation for matched paths only)
3. Class guard: `@PreAuthorize("hasRole('SUPER_ADMIN')")` — LoyaltyV2SuperAdminController.java:49; gateway also path-guards `/api/v2/loyalty/super-admin/**`
4. **History**: 26-Apr-2026 stress test found customers could write loyalty config — now impossible at two layers (FIXED, DOC-CR-09)

## Trace 4 — Authority handover elevation

1. Super-admin creates `AuthorityGrant` (scope ∈ 10 enum values, TTL 1–24 h)
2. Gateway elevates `X-User-Role` **only** for the granted scope's path set; JWT native role unchanged
3. `ResourceLock`/`AuthorityLockGuard` can still deny specific records (booking-service/service/AuthorityLockGuard.java:58)
4. Expiry: grants time out server-side; DelegationBanner surfaces state in UI

## Trace 5 — MFA settings path

- TOTP secret encrypted at rest via SecretCipher (AES-GCM)
- **SEC-CR-02**: SecretCipher.java:55-57 — if `CRYPTO_SECRET_KEY` unset, key derives from `JWT_SECRET`; JWT rotation without CRYPTO_SECRET_KEY set ⇒ all TOTP secrets undecryptable (MFA lockout)
- `SUPER_ADMIN_REQUIRE_MFA`: code default "true" (AuthService.java:464-480); compose dev sets "false" (docker-compose.yml:456)

## Trace 6 — Webhook ingress (unauthenticated by design)

1. Provider → `/payments/webhooks/stripe` or Razorpay callback (CSRF-exempt)
2. HMAC/signature verification against provider secret; failure ⇒ reject
3. Event-id dedup (ProcessedEvent) ⇒ at-most-once effects
4. State transitions via PaymentService; outbox emission for downstream

## Trace 7 — Internal service call

1. booking → payment refund intent with `INTERNAL_API_SECRET` header
2. Common-lib filter validates; missing/wrong ⇒ 401 before controller
3. Internal endpoints not routed by gateway (not externally reachable)

## Negative findings (things verified absent)

- No JWT in localStorage (frontend grep)
- No `dangerouslySetInnerHTML` beyond the single DOMPurify-wrapped CMS point
- No `@CrossOrigin("*")` in services (gateway owns CORS)
- No SQL string concatenation in repositories (JPA/parameterized queries; native queries use bind params — spot-checked incl. BookingRepository advisory-lock native query)
