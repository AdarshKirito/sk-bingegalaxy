# 06 — API Contracts

Depth = Level B (structural + spot traces) + a **complete endpoint inventory** (extracted 2026-07-12). This documents the structure, the verified slices, the method-level request-body diff for the money/booking flows, and the full endpoint list.

> **Companion:** [06a-ENDPOINT-CATALOG.md](06a-ENDPOINT-CATALOG.md) — all **424 backend endpoints** (method + full path + controller + auth-tier + source line), extracted from the controllers. Raw data: `evidence/endpoint-inventory.tsv`.

## Surface

- Frontend: `frontend/src/services/endpoints.js` — ~369 `api.*` calls across 18 service groups; base URL `/api/v1`.
- Backend: ~48 controllers. booking-service dominates with all customer/admin/internal booking APIs under `/api/v1/bookings/**` (note: binges, event-types, checkout, slot-holds, invoices, waitlist, currencies, taxes, notifications, loyalty are all sub-paths of `/bookings`). auth under `/api/v1/auth/**`; availability `/api/v1/availability/**`; payments `/api/v1/payments/**`; notifications `/api/v1/notifications/**`.

## Auth/scoping per endpoint class (verified)

| Class | Auth | Scoping |
|---|---|---|
| Public discovery (`/bookings/binges`, event-types, add-ons) | none | none (public) |
| Customer (`/bookings`, `/bookings/my*`, `/payments`) | JWT cookie | customer id (own only) — runtime-confirmed `/my` scoped |
| Admin binge-owned (`/bookings/admin/**`, taxes, pricing, risk) | JWT + ADMIN | `requireManagedBinge`/`requireBingeOwnership` — **except SEC-001/002 endpoints** |
| Super-admin global (`/currencies`, `/loyalty`, CMS, notifications) | JWT + SUPER_ADMIN (or scoped delegation) | gateway scope-map elevation |
| Internal (`/**/internal/**`) | `X-Internal-Secret` + SYSTEM role | not gateway-routed (runtime-confirmed 403/404) |

## Contract observations

- **Idempotency:** write endpoints accept `Idempotency-Key` (frontend auto-generates); booking create is idempotent (`IdempotencyService`). Good.
- **Error shape:** `common-lib ApiResponse` / `GlobalExceptionHandler` normalize errors; frontend `extractErrorMessage` unwraps them. Consistent.
- **Pagination:** admin list endpoints use `Pageable`; some recovery queries page but lack binge scoping (SEC-001).
- **Over-posting / mass-assignment:** DTOs are explicit request classes (`CreateBookingRequest` etc., `@Valid`), not entity binding — low mass-assignment risk (spot-checked booking create).
- **Internal-model exposure:** `PublicBingeDto` correctly strips `adminId`; internal DTO carries it. Good separation.

## API sweep result (2026-07-12, base-path level)

Extracted all frontend `api.*` call path-prefixes (`endpoints.js` + services) and all backend controller base paths + gateway route predicates. **Every frontend call prefix maps to a real, gateway-routed backend controller** — no gross orphan/broken calls found. Highest-count prefixes: `/bookings/admin` (184 calls), `/auth/admin` (22), `/payments/admin` (18), `/bookings` (12), `/notifications/admin` (11). The one that looked suspicious — `/booking-transfers/by-token` (no class-level `@RequestMapping` in the base-path grep) — is real: the gateway routes `/api/v1/booking-transfers/**` (`api-gateway.yml:103`, with a documented magic-link exemption for `/by-token/**`), and the controller uses method-level full paths. Gateway route predicates present for: `/auth/**`, `/site-content/**`, `/availability/**`, `/bookings/**` (+ SSE `/bookings/admin/events/stream`), `/booking-transfers/**`, `/api/v2/loyalty/**`, `/payments/**`, `/notifications/**`.

## Method-level field-drift diff (2026-07-12 — DONE for highest-risk flows)

The mechanical per-endpoint request-body diff was completed for the money/booking-critical flows by reading each backend request DTO and the exact frontend payload object that populates it.

**Key structural fact:** 11 DTOs are annotated `@JsonIgnoreProperties(ignoreUnknown = false)` (**strict** — a stray field returns 400, mass-assignment guard): `RegisterRequest`, `LoginRequest`, `ForgotPasswordRequest`, `ChangePasswordRequest` (auth); `CreateBookingRequest`, `RescheduleBookingRequest`, `JoinWaitlistRequest`, `CustomerReviewRequest` (booking); `InitiatePaymentRequest`, `PaymentCallbackRequest`, `RefundRequest` (payment). The global Jackson default is **lenient** (Spring Boot ignore-unknown; only local mappers set `FAIL_ON_UNKNOWN=false`), so every other DTO silently drops extras.

| Flow | Frontend payload | Backend DTO | Verdict |
|---|---|---|---|
| Register | `Register.jsx:67-82` (14 fields, `confirmPassword` **excluded**) | `RegisterRequest` (strict) | ✅ exact match |
| Create booking (customer) | `BookingWizard.jsx:624-635` | `CreateBookingRequest` (strict) | ✅ exact match (`fxLockToken` optional, never sent) |
| Add-on selection | `{addOnId, quantity}` | `AddOnSelection` | ✅ match |
| Reschedule | `BookingConfirmation.jsx:110-114` / `MyBookings.jsx:363-367` | `RescheduleBookingRequest` (strict) | ✅ match |
| Join waitlist | `BookingWizard.jsx:581-587` | `JoinWaitlistRequest` (strict) | ✅ match |
| Payment initiate | `PaymentPage.jsx:175-180` | `InitiatePaymentRequest` (strict) | ✅ match |
| Payment callback | `PaymentPage.jsx:203-208` | `PaymentCallbackRequest` (strict) | ✅ subset (optional error fields omitted) |
| Refund | `AdminBookings.jsx:836-840` | `RefundRequest` (strict) | ✅ match |
| Record cash / add-payment | `AdminBookings.jsx:875-917` | `RecordCashPaymentRequest` / `AddPaymentRequest` | ✅ match (`bookingTotalAmount` ceiling sent) |
| Admin create booking | `BookingWizard.jsx:636-646` | `AdminCreateBookingRequest` (lenient) | ⚠️ sends `redeemLoyaltyPoints` (no DTO field → dropped; always `null`, see API-001) |
| Checkout preview / lock-fx | `checkoutService.preview/lockFx` | `CheckoutPreviewRequest` / `FxLockRequest` | ⚠️ **orphaned — never called** (PRICE-002) |

**Result:** every *strict* customer-facing DTO receives an explicitly-constructed payload that matches field-for-field — **no 400-inducing drift** (positive control; the developers deliberately build explicit payloads rather than spreading form state). Residual drift is Low: the orphaned checkout client (→ PRICE-002), the admin loyalty field dropped silently, and `BookingPage.jsx:32` analytics reading a nonexistent `payload.totalAmount` (→ API-001).

**Still spot-level:** response-DTO → frontend-consumer field diff for every one of the ~369 calls (only the money/booking response shapes were traced).

## Known contract issues

- **SEC-001/002:** admin endpoints that accept `X-Binge-Id` without ownership validation (IDOR-class across tenants).
- **Orphans:** no gross orphans at base-path level (above); census found 0 orphan frontend pages. Method-level diff (done, 2026-07-12) surfaced one orphaned client pair — `checkoutService.preview`/`.lockFx` imported but never invoked, and their endpoints `/checkout/preview` + `/checkout/lock-fx` have no frontend consumer (→ **PRICE-002**).
- **API-001 (Low):** residual field-drift — admin `redeemLoyaltyPoints` dropped by lenient `AdminCreateBookingRequest`; `BookingPage.jsx:32` analytics reads nonexistent `payload.totalAmount`; `redeemLoyaltyPoints` type `Long` vs `Integer` across DTOs; `CheckoutPreviewRequest`/`Response` javadocs disagree on POST vs GET.

## Method to complete the bidirectional trace (recommended follow-up)

1. Extract every `api.<verb>('<path>'…)` from `endpoints.js` → normalized path set.
2. Extract every controller `@<Verb>Mapping` → normalized path set.
3. Diff: calls with no matching endpoint (frontend drift) vs endpoints with no caller (dead/admin-only/internal).
4. For each matched pair, diff request/response DTO fields.
This is mechanical and was deferred under session-limit pressure; it is the main remaining API-layer gap.
