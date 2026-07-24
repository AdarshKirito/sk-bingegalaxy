# Specialist Investigation 02 — Multi-Tenant "Binge" Isolation (read-only)

Agent completed 2026-07-11 (opus safety-classifier was unavailable at review time — findings independently reconciled by lead). Evidence, not final conclusion.

## Findings

### 1. Origin & trust of `X-Binge-Id` — CONFIRMED (client-controlled, NOT re-validated at edge)
- Frontend sets it from client-writable storage: `frontend/src/services/api.js:51-59` reads `localStorage.getItem('selectedBinge')` → `X-Binge-Id`.
- Gateway does NOT strip/validate it. `JwtAuthenticationFilter` strips `X-User-*`/`X-Authority-*` (`api-gateway/.../filter/JwtAuthenticationFilter.java:136-153`) but never touches `X-Binge-Id`. Used only for rate-limit bucketing (`UserRateLimitFilter.java:133`) + MDC logging; `GatewayConfig.java:30` passthrough.
- Services read verbatim into ThreadLocal: `BingeContextFilter.java:26-32`, `GatewayHeaderAuthFilter.java:35-38`. `BingeContext` has no ownership notion.
- Net: `X-Binge-Id` fully attacker-controllable. Isolation depends on each endpoint calling `AdminBingeScopeService.requireManagedBinge`/`requireBingeOwnership`. Presence-only `requireSelectedBinge` does NOT validate ownership (`AdminBingeScopeService.java:22-29`).

### 2. Ownership model — HIGH CONFIDENCE (correct where invoked; two tiers)
- `requireManagedBinge`/`requireBingeOwnership` enforce `binge.getAdminId().equals(adminId)` unless SUPER_ADMIN (`AdminBingeScopeService.java:31-61`). `Binge.adminId` is ownership field (`entity/Binge.java:81-82`).
- Correctly guarded: `AdminBookingController` class-wide `@ModelAttribute` (`:68-88`); `ExportController` (`:51-57,72`); `AdminPricingController` (`:33-34`); `AdminTaxController`, `AdminRiskFlagController`, `CustomerFreezeController`, `BingeSiteContentController`, `InvoiceController.downloadInvoice/resend`.
- `AuthorityLockGuard`/`AuthorityLockInterceptor` are SEPARATE (super-admin capability locks on mutations), not tenant ownership.

### 3. Cross-binge PII leak in recovery queue — CONFIRMED (highest severity)
`AdminRecoveryQueueController` (`/api/v1/bookings/admin/recovery/**`) has NO class-level `@ModelAttribute`; read endpoints call NON-binge-scoped repo methods with NO ownership check:
- `stuck-pending` → `bookingRepository.findStuckPending(cutoff,pageable)` (`:79`), query has no `bingeId` (`BookingRepository.java:294-297`); exposes customerId/email/amount (`:82-88`).
- `paid-not-confirmed` → `findPaidButNotConfirmed` (`:122`), no bingeId (`BookingRepository.java:280-283`).
- `no-show` → `findNoShowBookings` (`:147`), no bingeId (`BookingRepository.java:287-289`).
- `expired-holds` → `slotHoldRepository.findExpiredNotReleased` (`:98`), no bingeId (`SlotHoldRepository.java:42-46`); row emits each hold's own bingeId (`:105`) → multi-binge results.
- `summary` aggregates same global queries (`:164-172`).
Any authenticated ADMIN receives other binges' customer PII. Violates the documented invariant (`CrossBingeIsolationTest.java:44-47` "repository layer is the authoritative isolation boundary").

### 4. Cross-binge read via invoice list — HIGH CONFIDENCE
`InvoiceController.listInvoicesForBinge` (`GET /api/v1/bookings/admin/invoices`) uses only `requireSelectedBinge` (presence) then `invoiceService.listForBinge(headerBingeId)` (`:74-80`). Binge-A admin sending `X-Binge-Id: B` gets binge B invoices. Sibling download/resend correctly use `requireBingeOwnership` (`:55,92`) — this is the outlier.

### 5. Cross-binge aggregate leak via funnel — PROBABLE
`AdminRecoveryQueueController.funnel` uses `requireSelectedBinge` only (`:278`) then scopes counts to header bingeId — spoofable to read another binge's conversion aggregates (counts, no PII).

### 5b. Platform control-plane reachable by any binge admin — PROBABLE
`AdminOpsController` (`/api/v1/bookings/admin/ops`, `:50-54`) exposes DLT replay, outbox retry, pipeline health with no binge scoping, only ROLE_ADMIN/SUPER_ADMIN. Single-binge admin can replay/inspect platform-wide event pipeline.

### 6. Internal binge snapshot contract — CONFIRMED correct
- `InternalBingeDto` carries `adminId`+`deniedModules`, served by `/api/v1/bookings/internal/binges/{id}` (`InternalBookingController.java:40-63`). `PublicBingeDto` strips `adminId` (`:8-17,27-67`).
- payment + availability services call INTERNAL endpoint then verify adminId unless SUPER_ADMIN (`PaymentBingeScopeService.java:30-50`, `AvailabilityBingeScopeService.java:30-50`).
- Internal endpoints shared-secret protected, constant-time compare (`InternalApiAuthFilter.java:37-56`).

### 7. V71 module permission matrix — CONFIRMED enforced server-side
- `ModulePermissionInterceptor.preHandle` → 403 "disabled by Super Admin" (`:37-66`), registered on admin/waitlist/slot-holds paths (`AuthorityLockWebConfig.java:28-32`). Deny cache keyed `bingeId:userId` (`BingeModulePermissionService.java:48-64`).
- Cross-service modules (DISPUTES/FAILED_REFUNDS/BLOCKED_DATES) via internal `deniedModules` (`PaymentBingeScopeService:56-61`).
- Frontend `useModuleAccess.js` explicitly cosmetic (`:6-17`).
- BYPASS caveat (PROBABLE): interceptor fail-opens when `bingeId==null` (`:54`) and only maps fixed path list — unmapped admin paths (`/admin/recovery`, `/admin/ops`) never module-gated (compounds #3/#5b).

### 8. Query scoping, caches, events — HIGH CONFIDENCE (mostly good, one latent fallback)
- Admin list scoped: `getAllBookings` uses `findByBingeId` (`BookingService.java:552-554`); export scoped (`:3844-3846`). Latent: `getAllBookings` falls back to `findAll(pageable)` when `bid==null` (currently unreachable but defense-in-depth hazard).
- Caches include bingeId (eventTypes/addOns/surgeRules keyed by BingeContext; availability by bid). `activeBinges` unkeyed but is the global public list.
- Kafka `BookingEvent` carries bingeId (`common-lib/.../event/BookingEvent.java:20`).
- QUESTION: loyalty-v2 caches program-scoped not binge-scoped — whether loyalty programs are global vs per-binge not traced.

### 9. Super/binge/delegated-admin — HIGH CONFIDENCE (scope-based, path-bounded)
- `AuthorityGrant` delegates global scopes, ≤24h, super-admin only (`AuthorityController.java:79-101`). Gateway elevates effectiveRole to SUPER_ADMIN ONLY for SCOPE_MAP paths + matching scope (`JwtAuthenticationFilter.java:228-259,416-456`). Ordinary binge-data paths → no elevation → ownership still enforced.
- Defensive note (NOT VERIFIED exploitable): `AdminBingeScopeService` treats any SUPER_ADMIN as owning every binge (`:37,56`); delegated ADMIN with effective SUPER_ADMIN would bypass — but gateway only sets that on SCOPE_MAP global paths, none binge-owned-data. Recommend targeted test.

## Recommended follow-ups
1. Add binge scoping+ownership to every `AdminRecoveryQueueController` read + `bingeId` predicate to the four repo queries + class `@ModelAttribute requireManagedBinge`.
2. `InvoiceController.listInvoicesForBinge`: `requireSelectedBinge` → `requireManagedBinge`.
3. Consider gateway/service-side mandatory ownership filter so isolation doesn't depend on per-endpoint discipline.
4. Confirm notification-service admin reads are binge/recipient-scoped.
5. Verify loyalty-v2 data is global-only or add bingeId to cache keys.
6. Authz test for delegated-admin effective-SUPER_ADMIN vs binge-owned endpoint.
7. Remove/guard the `findAll(pageable)` fallback in `getAllBookings`.
