# 06a — Backend Endpoint Catalog (extracted from controllers)

> **Historical 424-row catalog.** The current tree has **421 mappings across 47 controllers**. Use [`06b-ENDPOINT-CATALOG-CURRENT.md`](06b-ENDPOINT-CATALOG-CURRENT.md) and `evidence/endpoint-inventory-current.tsv`.

Every HTTP endpoint mapping across the backend, **extracted from the controller source** at commit `e3edbc1` (class-level `@RequestMapping` base composed with each method mapping). **424 endpoints** across **50 controllers** in 6 modules. `Src` is the mapping's line in its controller file.

**Tier (by path)** is a coarse classifier from the URL only — the authoritative auth/role/binge-scope enforcement is at the gateway + `AdminBingeScopeService` and is documented per-endpoint-class in [09-SECURITY-RBAC-PRIVACY.md](09-SECURITY-RBAC-PRIVACY.md) and [06-API-CONTRACTS.md](06-API-CONTRACTS.md). Tiers: **Public** (bootstrap/anonymous paths), **Auth** (authenticated customer), **Admin+** (`/admin/` — ROLE_ADMIN at the gateway; some are super-admin- or module-gated — see [11-OPERATIONAL-MODULES.md](11-OPERATIONAL-MODULES.md)), **Internal(secret)** (`/internal/` — `X-Internal-Secret`, service-to-service only). Known authorization defects on specific endpoints: **SEC-001** (recovery queues), **SEC-002** (invoice list), **SEC-005** (ops/funnel), **SEC-006** (transfer preview).

> Note: a small number of rows are method-level `@RequestMapping` (shown as `ANY`) or inherit the class base path when the method annotation carries no sub-path (path then equals the controller base). Frontend→endpoint mapping (the ~369 client calls) is in [06-API-CONTRACTS.md](06-API-CONTRACTS.md); method-level request-body field-drift for the money/booking flows is the diff table there.

---

## api-gateway

### `CsrfTokenController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/csrf` | Public | :37 |

### `FallbackController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| ANY | `/fallback` | Public | :15 |

---

## auth-service

### `AuthController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/auth/google` | Public | :45 |
| POST | `/api/v1/auth/register` | Public | :54 |
| POST | `/api/v1/auth/login` | Public | :64 |
| POST | `/api/v1/auth/admin/login` | Admin+ | :73 |
| POST | `/api/v1/auth/refresh` | Public | :82 |
| POST | `/api/v1/auth/logout` | Auth | :99 |
| POST | `/api/v1/auth/forgot-password` | Public | :127 |
| POST | `/api/v1/auth/reset-password` | Public | :133 |
| POST | `/api/v1/auth/verify-otp` | Public | :139 |
| GET | `/api/v1/auth/profile` | Auth | :145 |
| PUT | `/api/v1/auth/change-password` | Auth | :151 |
| PUT | `/api/v1/auth/profile` | Auth | :159 |
| PUT | `/api/v1/auth/change-email` | Auth | :167 |
| POST | `/api/v1/auth/change-email/request` | Auth | :178 |
| POST | `/api/v1/auth/change-email/confirm` | Auth | :189 |
| PUT | `/api/v1/auth/change-phone` | Auth | :199 |
| PUT | `/api/v1/auth/profile/preferences` | Auth | :210 |
| GET | `/api/v1/auth/support-contact` | Public | :218 |
| PUT | `/api/v1/auth/complete-profile` | Auth | :223 |
| GET | `/api/v1/auth/admin/search-customers` | Admin+ | :234 |
| GET | `/api/v1/auth/admin/search-staff` | Admin+ | :244 |
| GET | `/api/v1/auth/admin/customers` | Admin+ | :259 |
| GET | `/api/v1/auth/admin/customer/{id}` | Admin+ | :271 |
| PUT | `/api/v1/auth/admin/customer/{id}` | Admin+ | :281 |
| POST | `/api/v1/auth/admin/create-customer` | Admin+ | :293 |
| POST | `/api/v1/auth/admin/customer/{id}/temp-password` | Admin+ | :305 |
| POST | `/api/v1/auth/admin/register` | Admin+ | :316 |
| GET | `/api/v1/auth/admin/admins` | Admin+ | :326 |
| PUT | `/api/v1/auth/admin/admins/{id}` | Admin+ | :332 |
| DELETE | `/api/v1/auth/admin/user/{id}` | Admin+ | :340 |
| POST | `/api/v1/auth/admin/bulk-ban` | Admin+ | :346 |
| POST | `/api/v1/auth/admin/bulk-unban` | Admin+ | :352 |
| POST | `/api/v1/auth/admin/bulk-delete` | Admin+ | :358 |
| POST | `/api/v1/auth/mfa/enroll` | Auth | :373 |
| POST | `/api/v1/auth/mfa/confirm` | Auth | :378 |
| POST | `/api/v1/auth/mfa/disable` | Auth | :385 |
| POST | `/api/v1/auth/verify-email` | Public | :395 |
| POST | `/api/v1/auth/resend-verification` | Auth | :405 |
| GET | `/api/v1/auth/sessions` | Auth | :414 |
| DELETE | `/api/v1/auth/sessions/{id}` | Auth | :424 |
| POST | `/api/v1/auth/sessions/revoke-others` | Auth | :436 |
| GET | `/api/v1/auth/admin/sessions` | Admin+ | :450 |
| DELETE | `/api/v1/auth/admin/sessions/{id}` | Admin+ | :458 |
| DELETE | `/api/v1/auth/admin/users/{userId}/sessions` | Admin+ | :466 |
| GET | `/api/v1/auth/admin/audit-log` | Admin+ | :474 |
| POST | `/api/v1/auth/admin/admins/{id}/promote` | Admin+ | :486 |
| POST | `/api/v1/auth/admin/admins/{id}/demote` | Admin+ | :493 |
| GET | `/api/v1/auth/admin/super-admin/stats` | Admin+ | :500 |

### `AuthorityController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/auth/authority/me` | Auth | :70 |
| POST | `/api/v1/auth/authority/grants` | Auth | :79 |
| DELETE | `/api/v1/auth/authority/grants/{id}` | Auth | :91 |
| GET | `/api/v1/auth/authority/grants` | Auth | :104 |
| GET | `/api/v1/auth/authority/grants/by-user/{userId}` | Auth | :119 |
| POST | `/api/v1/auth/authority/locks` | Auth | :133 |
| DELETE | `/api/v1/auth/authority/locks/{id}` | Auth | :145 |
| GET | `/api/v1/auth/authority/locks/lookup` | Auth | :165 |
| GET | `/api/v1/auth/authority/internal/locks/lookup` | Internal(secret) | :183 |
| GET | `/api/v1/auth/authority/locks` | Auth | :192 |

### `InternalUserController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/auth/internal/users/{id}/contact` | Internal(secret) | :31 |

### `SiteContentController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/site-content/public/{slug}` | Public | :32 |
| PUT | `/api/v1/site-content/admin/{slug}` | Admin+ | :48 |

### `UserPrivacyController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| DELETE | `/api/v1/auth/privacy/me` | Auth | :31 |
| POST | `/api/v1/auth/privacy/admin/anonymize/{userId}` | Admin+ | :44 |

---

## availability-service

### `AvailabilityController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/availability/dates` | Auth | :46 |
| GET | `/api/v1/availability/slots` | Auth | :56 |
| GET | `/api/v1/availability/internal/check` | Internal(secret) | :66 |
| GET | `/api/v1/availability/admin/blocked-dates` | Admin+ | :76 |
| GET | `/api/v1/availability/admin/blocked-slots` | Admin+ | :81 |
| POST | `/api/v1/availability/admin/block-date` | Admin+ | :86 |
| DELETE | `/api/v1/availability/admin/unblock-date` | Admin+ | :93 |
| DELETE | `/api/v1/availability/admin/blocked-dates/{id}` | Admin+ | :105 |
| DELETE | `/api/v1/availability/admin/blocked-slots/{id}` | Admin+ | :111 |
| POST | `/api/v1/availability/admin/block-slot` | Admin+ | :117 |
| DELETE | `/api/v1/availability/admin/unblock-slot` | Admin+ | :124 |

---

## booking-service

### `AdminBookingController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin` | Auth | :98 |
| GET | `/api/v1/bookings/admin/today` | Admin+ | :114 |
| GET | `/api/v1/bookings/admin/upcoming` | Admin+ | :125 |
| GET | `/api/v1/bookings/admin/by-date` | Admin+ | :136 |
| GET | `/api/v1/bookings/admin/by-status` | Admin+ | :147 |
| GET | `/api/v1/bookings/admin/search` | Admin+ | :166 |
| PATCH | `/api/v1/bookings/admin/{bookingRef}` | Admin+ | :180 |
| POST | `/api/v1/bookings/admin/{bookingRef}/cancel` | Admin+ | :193 |
| POST | `/api/v1/bookings/admin/{bookingRef}/confirm` | Admin+ | :211 |
| POST | `/api/v1/bookings/admin/{bookingRef}/check-in` | Admin+ | :225 |
| GET | `/api/v1/bookings/admin/{bookingRef}/available-rooms` | Admin+ | :275 |
| PATCH | `/api/v1/bookings/admin/{bookingRef}/room` | Admin+ | :282 |
| POST | `/api/v1/bookings/admin/{bookingRef}/checkout` | Admin+ | :296 |
| POST | `/api/v1/bookings/admin/{bookingRef}/undo-check-in` | Admin+ | :327 |
| GET | `/api/v1/bookings/admin/dashboard-stats` | Admin+ | :361 |
| GET | `/api/v1/bookings/admin/operational-date` | Admin+ | :368 |
| POST | `/api/v1/bookings/admin/operational-date/advance` | Admin+ | :414 |
| POST | `/api/v1/bookings/admin/operational-date/set` | Admin+ | :435 |
| GET | `/api/v1/bookings/admin/event-types` | Admin+ | :459 |
| POST | `/api/v1/bookings/admin/event-types` | Admin+ | :464 |
| PUT | `/api/v1/bookings/admin/event-types/{id}` | Admin+ | :470 |
| PATCH | `/api/v1/bookings/admin/event-types/{id}/toggle-active` | Admin+ | :476 |
| DELETE | `/api/v1/bookings/admin/event-types/{id}` | Admin+ | :482 |
| GET | `/api/v1/bookings/admin/add-ons` | Admin+ | :490 |
| POST | `/api/v1/bookings/admin/add-ons` | Admin+ | :495 |
| PUT | `/api/v1/bookings/admin/add-ons/{id}` | Admin+ | :501 |
| PATCH | `/api/v1/bookings/admin/add-ons/{id}/toggle-active` | Admin+ | :507 |
| DELETE | `/api/v1/bookings/admin/add-ons/{id}` | Admin+ | :513 |
| GET | `/api/v1/bookings/admin/event-categories` | Admin+ | :521 |
| POST | `/api/v1/bookings/admin/event-categories` | Admin+ | :526 |
| PUT | `/api/v1/bookings/admin/event-categories/{id}` | Admin+ | :539 |
| PATCH | `/api/v1/bookings/admin/event-categories/{id}/toggle-active` | Admin+ | :546 |
| DELETE | `/api/v1/bookings/admin/event-categories/{id}` | Admin+ | :552 |
| GET | `/api/v1/bookings/admin/event-categories/global` | Admin+ | :559 |
| POST | `/api/v1/bookings/admin/event-categories/global` | Admin+ | :566 |
| PUT | `/api/v1/bookings/admin/event-categories/global/{id}` | Admin+ | :581 |
| PATCH | `/api/v1/bookings/admin/event-categories/global/{id}/toggle-active` | Admin+ | :590 |
| DELETE | `/api/v1/bookings/admin/event-categories/global/{id}` | Admin+ | :598 |
| GET | `/api/v1/bookings/admin/addon-categories` | Admin+ | :607 |
| POST | `/api/v1/bookings/admin/addon-categories` | Admin+ | :612 |
| PUT | `/api/v1/bookings/admin/addon-categories/{id}` | Admin+ | :625 |
| PATCH | `/api/v1/bookings/admin/addon-categories/{id}/toggle-active` | Admin+ | :632 |
| DELETE | `/api/v1/bookings/admin/addon-categories/{id}` | Admin+ | :638 |
| GET | `/api/v1/bookings/admin/addon-categories/global` | Admin+ | :644 |
| POST | `/api/v1/bookings/admin/addon-categories/global` | Admin+ | :651 |
| PUT | `/api/v1/bookings/admin/addon-categories/global/{id}` | Admin+ | :666 |
| PATCH | `/api/v1/bookings/admin/addon-categories/global/{id}/toggle-active` | Admin+ | :675 |
| DELETE | `/api/v1/bookings/admin/addon-categories/global/{id}` | Admin+ | :683 |
| GET | `/api/v1/bookings/admin/reports` | Admin+ | :693 |
| GET | `/api/v1/bookings/admin/reports/date-range` | Admin+ | :700 |
| POST | `/api/v1/bookings/admin/audit` | Admin+ | :710 |
| GET | `/api/v1/bookings/admin/house-accounts` | Admin+ | :726 |
| POST | `/api/v1/bookings/admin/create-booking` | Admin+ | :737 |
| GET | `/api/v1/bookings/admin/customer-booking-count/{customerId}` | Admin+ | :752 |
| GET | `/api/v1/bookings/admin/customers/{customerId}/review-summary` | Admin+ | :759 |
| GET | `/api/v1/bookings/admin/customers/{customerId}/reviews` | Admin+ | :767 |
| GET | `/api/v1/bookings/admin/booked-slots` | Admin+ | :783 |
| GET | `/api/v1/bookings/admin/{bookingRef}/events` | Admin+ | :791 |
| POST | `/api/v1/bookings/admin/{bookingRef}/replay` | Admin+ | :822 |
| POST | `/api/v1/bookings/admin/replay-all` | Admin+ | :829 |
| POST | `/api/v1/bookings/admin/{bookingRef}/override-status` | Admin+ | :852 |
| GET | `/api/v1/bookings/admin/sagas/failed` | Admin+ | :892 |
| GET | `/api/v1/bookings/admin/sagas/compensating` | Admin+ | :899 |
| GET | `/api/v1/bookings/admin/venue-rooms` | Admin+ | :921 |
| POST | `/api/v1/bookings/admin/venue-rooms` | Admin+ | :926 |
| PUT | `/api/v1/bookings/admin/venue-rooms/{id}` | Admin+ | :943 |
| PATCH | `/api/v1/bookings/admin/venue-rooms/{id}/toggle-active` | Admin+ | :949 |
| DELETE | `/api/v1/bookings/admin/venue-rooms/{id}` | Admin+ | :955 |
| POST | `/api/v1/bookings/admin/venue-rooms/{id}/approve` | Admin+ | :962 |
| POST | `/api/v1/bookings/admin/venue-rooms/{id}/reject` | Admin+ | :976 |
| GET | `/api/v1/bookings/admin/venue-rooms/blocks` | Admin+ | :998 |
| GET | `/api/v1/bookings/admin/venue-rooms/{roomId}/blocks` | Admin+ | :1004 |
| POST | `/api/v1/bookings/admin/venue-rooms/{roomId}/blocks` | Admin+ | :1010 |
| DELETE | `/api/v1/bookings/admin/venue-rooms/blocks/{blockId}` | Admin+ | :1024 |
| GET | `/api/v1/bookings/admin/pricing/surge-rules` | Admin+ | :1034 |
| POST | `/api/v1/bookings/admin/pricing/surge-rules` | Admin+ | :1039 |
| PUT | `/api/v1/bookings/admin/pricing/surge-rules/{id}` | Admin+ | :1046 |
| PATCH | `/api/v1/bookings/admin/pricing/surge-rules/{id}/toggle-active` | Admin+ | :1052 |
| DELETE | `/api/v1/bookings/admin/pricing/surge-rules/{id}` | Admin+ | :1058 |

### `AdminCurrencyController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/currencies` | Admin+ | :69 |
| POST | `/api/v1/bookings/admin/currencies/refresh` | Admin+ | :79 |
| POST | `/api/v1/bookings/admin/currencies` | Admin+ | :92 |
| POST | `/api/v1/bookings/admin/currencies/{code}/toggle` | Admin+ | :107 |
| DELETE | `/api/v1/bookings/admin/currencies/{code}` | Admin+ | :122 |

### `AdminNotificationController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/notifications` | Admin+ | :22 |
| GET | `/api/v1/bookings/admin/notifications/unread-count` | Admin+ | :33 |
| POST | `/api/v1/bookings/admin/notifications/{id}/read` | Admin+ | :41 |
| POST | `/api/v1/bookings/admin/notifications/read-all` | Admin+ | :49 |
| GET | `/api/v1/bookings/admin/notifications/sent` | Admin+ | :60 |
| GET | `/api/v1/bookings/admin/notifications/thread/{threadId}` | Admin+ | :69 |
| POST | `/api/v1/bookings/admin/notifications/send` | Admin+ | :78 |
| POST | `/api/v1/bookings/admin/notifications/send-bulk` | Admin+ | :92 |
| POST | `/api/v1/bookings/admin/notifications/{id}/reply` | Admin+ | :110 |
| DELETE | `/api/v1/bookings/admin/notifications/{id}` | Admin+ | :122 |
| POST | `/api/v1/bookings/admin/notifications/clear-read` | Admin+ | :132 |

### `AdminOpsController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/admin/ops/replay-dlt` | Admin+ | :78 |
| POST | `/api/v1/bookings/admin/ops/outbox/retry-failed` | Admin+ | :155 |
| GET | `/api/v1/bookings/admin/ops/health` | Admin+ | :176 |

### `AdminPricingController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/pricing/rate-codes` | Admin+ | :51 |
| GET | `/api/v1/bookings/admin/pricing/rate-codes/active` | Admin+ | :59 |
| GET | `/api/v1/bookings/admin/pricing/rate-codes/{id}` | Admin+ | :67 |
| POST | `/api/v1/bookings/admin/pricing/rate-codes` | Admin+ | :76 |
| PUT | `/api/v1/bookings/admin/pricing/rate-codes/{id}` | Admin+ | :86 |
| PATCH | `/api/v1/bookings/admin/pricing/rate-codes/{id}/toggle-active` | Admin+ | :96 |
| DELETE | `/api/v1/bookings/admin/pricing/rate-codes/{id}` | Admin+ | :106 |
| GET | `/api/v1/bookings/admin/pricing/customer/{customerId}` | Admin+ | :120 |
| POST | `/api/v1/bookings/admin/pricing/customer` | Admin+ | :129 |
| DELETE | `/api/v1/bookings/admin/pricing/customer/{customerId}` | Admin+ | :138 |
| POST | `/api/v1/bookings/admin/pricing/bulk-assign-rate-code` | Admin+ | :148 |
| PATCH | `/api/v1/bookings/admin/pricing/customer/{customerId}/member-label` | Admin+ | :158 |
| GET | `/api/v1/bookings/admin/pricing/resolve/{customerId}` | Admin+ | :173 |
| GET | `/api/v1/bookings/admin/pricing/resolve-rate-code/{rateCodeId}` | Admin+ | :191 |
| GET | `/api/v1/bookings/admin/pricing/customer-detail/{customerId}` | Admin+ | :204 |

### `AdminRecoveryQueueController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/recovery/stuck-pending` | Admin+ | :71 |
| GET | `/api/v1/bookings/admin/recovery/expired-holds` | Admin+ | :93 |
| GET | `/api/v1/bookings/admin/recovery/paid-not-confirmed` | Admin+ | :114 |
| GET | `/api/v1/bookings/admin/recovery/no-show` | Admin+ | :137 |
| GET | `/api/v1/bookings/admin/recovery/summary` | Admin+ | :162 |
| POST | `/api/v1/bookings/admin/recovery/expired-holds/{token}/release` | Admin+ | :190 |
| POST | `/api/v1/bookings/admin/recovery/stuck-pending/{bookingRef}/cancel` | Admin+ | :212 |
| POST | `/api/v1/bookings/admin/recovery/paid-not-confirmed/{bookingRef}/replay` | Admin+ | :234 |
| GET | `/api/v1/bookings/admin/recovery/funnel` | Admin+ | :273 |

### `AdminRiskFlagController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/risk-flags` | Admin+ | :37 |
| GET | `/api/v1/bookings/admin/risk-flags/booking/{bookingRef}` | Admin+ | :51 |
| POST | `/api/v1/bookings/admin/risk-flags/{id}/acknowledge` | Admin+ | :63 |
| POST | `/api/v1/bookings/admin/risk-flags/booking/{bookingRef}/manual` | Admin+ | :81 |

### `AdminSseController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/events/stream` | Admin+ | :40 |

### `AdminSupportController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/support/escalations` | Admin+ | :38 |
| GET | `/api/v1/bookings/admin/support/{bookingRef}` | Admin+ | :48 |
| GET | `/api/v1/bookings/admin/support/{bookingRef}/notes` | Admin+ | :56 |
| POST | `/api/v1/bookings/admin/support/{bookingRef}/notes` | Admin+ | :64 |
| PATCH | `/api/v1/bookings/admin/support/notes/{noteId}` | Admin+ | :80 |
| DELETE | `/api/v1/bookings/admin/support/notes/{noteId}` | Admin+ | :90 |
| POST | `/api/v1/bookings/admin/support/notes/{noteId}/pin` | Admin+ | :99 |
| POST | `/api/v1/bookings/admin/support/{bookingRef}/resend-confirmation` | Admin+ | :112 |
| POST | `/api/v1/bookings/admin/support/{bookingRef}/escalate` | Admin+ | :122 |
| POST | `/api/v1/bookings/admin/support/{bookingRef}/goodwill` | Admin+ | :136 |

### `AdminTaxController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/taxes` | Admin+ | :48 |
| POST | `/api/v1/bookings/admin/taxes` | Admin+ | :59 |
| PUT | `/api/v1/bookings/admin/taxes/{id}` | Admin+ | :71 |
| DELETE | `/api/v1/bookings/admin/taxes/{id}` | Admin+ | :84 |
| GET | `/api/v1/bookings/admin/taxes/global` | Admin+ | :98 |
| POST | `/api/v1/bookings/admin/taxes/global` | Admin+ | :107 |

### `BingeController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/binges` | Auth | :36 |
| GET | `/api/v1/bookings/binges/nearby` | Auth | :52 |
| GET | `/api/v1/bookings/binges/{id}` | Auth | :63 |
| GET | `/api/v1/bookings/binges/{id}/customer-dashboard` | Auth | :68 |
| GET | `/api/v1/bookings/binges/{id}/customer-about` | Auth | :73 |
| GET | `/api/v1/bookings/binges/{id}/reviews/summary` | Auth | :79 |
| GET | `/api/v1/bookings/binges/{id}/reviews` | Auth | :85 |
| GET | `/api/v1/bookings/admin/binges` | Admin+ | :95 |
| GET | `/api/v1/bookings/admin/binges/by-admin/{adminId}` | Admin+ | :103 |
| POST | `/api/v1/bookings/admin/binges` | Admin+ | :115 |
| GET | `/api/v1/bookings/admin/binges/pending` | Admin+ | :130 |
| POST | `/api/v1/bookings/admin/binges/{id}/approve` | Admin+ | :141 |
| PATCH | `/api/v1/bookings/admin/binges/{id}/taxes-enabled` | Admin+ | :163 |
| POST | `/api/v1/bookings/admin/binges/{id}/reject` | Admin+ | :176 |
| POST | `/api/v1/bookings/admin/binges/{id}/resubmit` | Admin+ | :194 |
| PUT | `/api/v1/bookings/admin/binges/{id}` | Admin+ | :204 |
| POST | `/api/v1/bookings/admin/binges/{id}/country-request` | Admin+ | :223 |
| GET | `/api/v1/bookings/admin/binges/change-requests` | Admin+ | :244 |
| POST | `/api/v1/bookings/admin/binges/change-requests/{requestId}/approve` | Admin+ | :253 |
| POST | `/api/v1/bookings/admin/binges/change-requests/{requestId}/reject` | Admin+ | :265 |
| POST | `/api/v1/bookings/admin/binges/change-requests/{requestId}/cancel` | Admin+ | :277 |
| POST | `/api/v1/bookings/admin/binges/bulk-timezone` | Admin+ | :291 |
| GET | `/api/v1/bookings/admin/binges/{id}/customer-dashboard` | Admin+ | :308 |
| GET | `/api/v1/bookings/admin/binges/{id}/customer-about` | Admin+ | :316 |
| PUT | `/api/v1/bookings/admin/binges/{id}/customer-dashboard` | Admin+ | :324 |
| PUT | `/api/v1/bookings/admin/binges/{id}/customer-about` | Admin+ | :335 |
| PATCH | `/api/v1/bookings/admin/binges/{id}/toggle-active` | Admin+ | :347 |
| DELETE | `/api/v1/bookings/admin/binges/{id}` | Admin+ | :357 |
| GET | `/api/v1/bookings/admin/binges/{id}/cancellation-tiers` | Admin+ | :367 |
| PUT | `/api/v1/bookings/admin/binges/{id}/cancellation-tiers` | Admin+ | :376 |
| GET | `/api/v1/bookings/binges/{id}/cancellation-tiers` | Auth | :388 |
| GET | `/api/v1/bookings/admin/binges/{id}/cancellation-policy` | Admin+ | :395 |
| PUT | `/api/v1/bookings/admin/binges/{id}/cancellation-policy` | Admin+ | :404 |

### `BingeSiteContentController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/binges/{bingeId}/site-content/{slug}` | Auth | :44 |
| PUT | `/api/v1/bookings/admin/binges/{bingeId}/site-content/{slug}` | Admin+ | :64 |

### `BookingAnalyticsController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/analytics/funnel` | Auth | :36 |

### `BookingController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings` | Auth | :39 |
| GET | `/api/v1/bookings/{bookingRef}` | Auth | :58 |
| GET | `/api/v1/bookings/my` | Auth | :77 |
| GET | `/api/v1/bookings/my/current` | Auth | :83 |
| GET | `/api/v1/bookings/my/past` | Auth | :90 |
| GET | `/api/v1/bookings/my/reviews/pending` | Auth | :97 |
| GET | `/api/v1/bookings/event-types` | Auth | :104 |
| GET | `/api/v1/bookings/add-ons` | Auth | :109 |
| GET | `/api/v1/bookings/event-categories` | Auth | :115 |
| GET | `/api/v1/bookings/addon-categories` | Auth | :121 |
| GET | `/api/v1/bookings/booked-slots` | Auth | :126 |
| GET | `/api/v1/bookings/slot-capacity` | Auth | :132 |
| POST | `/api/v1/bookings/{bookingRef}/cancel` | Auth | :140 |
| POST | `/api/v1/bookings/{bookingRef}/reschedule` | Auth | :152 |
| POST | `/api/v1/bookings/{bookingRef}/transfer` | Auth | :168 |
| POST | `/api/v1/bookings/recurring` | Auth | :181 |
| GET | `/api/v1/bookings/recurring/{groupId}` | Auth | :198 |
| GET | `/api/v1/bookings/{bookingRef}/reviews/customer` | Auth | :205 |
| POST | `/api/v1/bookings/{bookingRef}/reviews/customer` | Auth | :212 |
| POST | `/api/v1/bookings/admin/{bookingRef}/reviews` | Admin+ | :220 |
| GET | `/api/v1/bookings/admin/{bookingRef}/reviews` | Admin+ | :230 |
| GET | `/api/v1/bookings/my-pricing` | Auth | :239 |
| GET | `/api/v1/bookings/venue-rooms` | Auth | :247 |
| GET | `/api/v1/bookings/venue-rooms/available` | Auth | :252 |
| GET | `/api/v1/bookings/venue-rooms/{roomId}/reviews/summary` | Auth | :261 |
| GET | `/api/v1/bookings/venue-rooms/{roomId}/reviews` | Auth | :266 |
| GET | `/api/v1/bookings/surge-rules` | Auth | :278 |
| GET | `/api/v1/bookings/surge/quote` | Auth | :291 |
| GET | `/api/v1/bookings/{bookingRef}/timeline` | Auth | :327 |

### `BookingTransferController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/{bookingRef}/transfers` | Auth | :45 |
| GET | `/api/v1/bookings/{bookingRef}/transfers` | Auth | :62 |
| POST | `/api/v1/bookings/{bookingRef}/transfers/{transferId}/revoke` | Auth | :72 |
| GET | `/api/v1/booking-transfers/by-token/{token}` | Public | :94 |
| POST | `/api/v1/booking-transfers/by-token/{token}/accept` | Public | :112 |
| POST | `/api/v1/booking-transfers/by-token/{token}/decline` | Public | :134 |

### `CheckInController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/admin/{bookingRef}/check-in/qr/issue` | Admin+ | :57 |
| POST | `/api/v1/bookings/admin/{bookingRef}/check-in/otp/issue` | Admin+ | :76 |
| POST | `/api/v1/bookings/admin/check-in/verify` | Admin+ | :110 |

### `CheckoutController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/checkout/preview` | Auth | :30 |
| POST | `/api/v1/bookings/checkout/lock-fx` | Auth | :39 |

### `CustomerFreezeController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/freezes/me` | Auth | :38 |
| GET | `/api/v1/bookings/freezes/me/binge/{bingeId}` | Auth | :45 |
| GET | `/api/v1/bookings/admin/freezes` | Admin+ | :54 |
| POST | `/api/v1/bookings/admin/freezes` | Admin+ | :64 |
| DELETE | `/api/v1/bookings/admin/freezes/{id}` | Admin+ | :74 |

### `ExportController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/export/csv` | Admin+ | :65 |

### `InternalBookingController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/internal/binges/{id}` | Internal(secret) | :40 |
| GET | `/api/v1/bookings/internal/amount/{bookingRef}` | Internal(secret) | :69 |

### `InvoiceController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/{ref}/invoice` | Auth | :40 |
| GET | `/api/v1/bookings/admin/invoices` | Admin+ | :74 |
| POST | `/api/v1/bookings/admin/{ref}/invoice/resend` | Admin+ | :86 |

### `MediaController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/admin/media/upload` | Admin+ | :62 |
| GET | `/api/v1/bookings/media/{filename:.+}` | Auth | :128 |

### `MessageAttachmentController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/notifications/attachment` | Auth | :52 |

### `MyNotificationController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/notifications` | Auth | :39 |
| GET | `/api/v1/bookings/notifications/unread-count` | Auth | :50 |
| GET | `/api/v1/bookings/notifications/sent` | Auth | :58 |
| GET | `/api/v1/bookings/notifications/thread/{threadId}` | Auth | :67 |
| POST | `/api/v1/bookings/notifications/{id}/read` | Auth | :75 |
| POST | `/api/v1/bookings/notifications/read-all` | Auth | :83 |
| POST | `/api/v1/bookings/notifications/{id}/reply` | Auth | :91 |
| POST | `/api/v1/bookings/notifications/contact-support` | Auth | :115 |
| DELETE | `/api/v1/bookings/notifications/{id}` | Auth | :146 |

### `PublicCurrencyController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/currencies` | Auth | :24 |

### `PublicTaxController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/taxes/preview` | Auth | :26 |

### `SlotHoldController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/slot-holds` | Auth | :38 |
| GET | `/api/v1/bookings/slot-holds/{token}` | Auth | :55 |
| DELETE | `/api/v1/bookings/slot-holds/{token}` | Auth | :64 |
| GET | `/api/v1/bookings/slot-holds/my` | Auth | :75 |
| GET | `/api/v1/bookings/slot-holds/admin` | Auth | :83 |
| DELETE | `/api/v1/bookings/slot-holds/admin/{token}` | Admin+ | :91 |

### `WaitlistController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/bookings/waitlist` | Auth | :35 |
| DELETE | `/api/v1/bookings/waitlist/{entryId}` | Auth | :52 |
| GET | `/api/v1/bookings/waitlist/my` | Auth | :60 |
| GET | `/api/v1/bookings/waitlist/admin` | Auth | :68 |
| GET | `/api/v1/bookings/waitlist/admin/count` | Admin+ | :77 |
| DELETE | `/api/v1/bookings/waitlist/admin/{entryId}` | Admin+ | :86 |
| POST | `/api/v1/bookings/waitlist/admin/{entryId}/offer` | Admin+ | :95 |
| PATCH | `/api/v1/bookings/waitlist/admin/{entryId}/priority` | Admin+ | :109 |

### `LoyaltyV2AdminController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v2/loyalty/admin/enrollments` | Admin+ | :76 |
| GET | `/api/v2/loyalty/admin/bindings/{bingeId}` | Admin+ | :99 |
| POST | `/api/v2/loyalty/admin/bindings/{bingeId}/enable` | Admin+ | :113 |
| POST | `/api/v2/loyalty/admin/bindings/{bindingId}/disable` | Admin+ | :125 |
| GET | `/api/v2/loyalty/admin/goodwill/{bingeId}/budget` | Admin+ | :141 |
| POST | `/api/v2/loyalty/admin/goodwill/{bingeId}` | Admin+ | :162 |
| GET | `/api/v2/loyalty/admin/bindings/{bindingId}/earn-rules` | Admin+ | :176 |
| POST | `/api/v2/loyalty/admin/bindings/{bindingId}/earn-rules` | Admin+ | :189 |
| GET | `/api/v2/loyalty/admin/bindings/{bindingId}/redeem-rule` | Admin+ | :204 |
| POST | `/api/v2/loyalty/admin/bindings/{bindingId}/redeem-rule` | Admin+ | :219 |
| POST | `/api/v2/loyalty/admin/bindings/{bindingId}/perks` | Admin+ | :236 |
| GET | `/api/v2/loyalty/admin/status-match/pending` | Admin+ | :252 |
| POST | `/api/v2/loyalty/admin/status-match/{requestId}/approve` | Admin+ | :261 |
| POST | `/api/v2/loyalty/admin/status-match/{requestId}/reject` | Admin+ | :272 |

### `LoyaltyV2CustomerController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v2/loyalty/me` | Auth | :60 |
| GET | `/api/v2/loyalty/me/ledger` | Auth | :110 |
| GET | `/api/v2/loyalty/me/redeem-quote` | Auth | :131 |
| GET | `/api/v2/loyalty/me/earn-quote` | Auth | :152 |
| GET | `/api/v2/loyalty/me/status-match` | Auth | :185 |
| POST | `/api/v2/loyalty/me/status-match` | Auth | :195 |

### `LoyaltyV2SuperAdminController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| ANY | `/api/v2/loyalty/super-admin` | Auth | :47 |
| GET | `/program` | Auth | :67 |
| PUT | `/program` | Auth | :72 |
| GET | `/tiers` | Auth | :83 |
| POST | `/tiers` | Auth | :90 |
| DELETE | `/tiers/{tierId}` | Auth | :100 |
| GET | `/perks` | Auth | :108 |
| POST | `/perks` | Auth | :115 |
| POST | `/tier-perks` | Auth | :124 |
| DELETE | `/tier-perks/{tierPerkId}` | Auth | :130 |
| GET | `/bindings` | Auth | :139 |
| POST | `/bindings/bulk` | Auth | :148 |
| GET | `/country-configs` | Auth | :165 |
| POST | `/country-configs` | Auth | :171 |
| DELETE | `/country-configs/{countryIso2}` | Auth | :200 |
| POST | `/bindings/{bindingId}/goodwill-settings` | Auth | :212 |
| GET | `/customers/{customerId}` | Auth | :236 |
| POST | `/customers/{customerId}/adjust` | Auth | :248 |
| GET | `/customers/{customerId}/ledger` | Auth | :269 |
| GET | `/tier-perks` | Auth | :293 |

### `BingeAccessController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/bookings/admin/my-permissions` | Admin+ | :47 |
| GET | `/api/v1/bookings/admin/binges/{id}/about` | Admin+ | :62 |
| GET | `/api/v1/bookings/admin/binges/{id}/access` | Admin+ | :120 |
| PUT | `/api/v1/bookings/admin/binges/{id}/access/{moduleKey}` | Admin+ | :142 |
| PATCH | `/api/v1/bookings/admin/binges/{id}/access-remarks` | Admin+ | :171 |

---

## notification-service

### `DeliveryWebhookController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/notifications/webhooks/delivery` | Auth | :58 |

### `NotificationController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/notifications/my` | Auth | :21 |
| GET | `/api/v1/notifications/booking/{bookingRef}` | Auth | :28 |
| POST | `/api/v1/notifications/admin/retry-failed` | Admin+ | :42 |
| POST | `/api/v1/notifications/admin/{id}/retry` | Admin+ | :59 |

### `NotificationPreferenceController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/notifications/preferences` | Auth | :17 |
| PUT | `/api/v1/notifications/preferences` | Auth | :25 |

### `PushSubscriptionController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/notifications/push/public-key` | Public | :27 |
| POST | `/api/v1/notifications/push/subscribe` | Auth | :35 |
| POST | `/api/v1/notifications/push/unsubscribe` | Auth | :44 |
| POST | `/api/v1/notifications/push/test` | Auth | :51 |

### `TemplateController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/notifications/admin/templates` | Admin+ | :28 |
| POST | `/api/v1/notifications/admin/templates` | Admin+ | :37 |
| POST | `/api/v1/notifications/admin/templates/activate` | Admin+ | :45 |

### `WhatsAppTemplateController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/notifications/admin/whatsapp-templates` | Admin+ | :25 |
| GET | `/api/v1/notifications/admin/whatsapp-templates/{id}` | Admin+ | :37 |
| POST | `/api/v1/notifications/admin/whatsapp-templates` | Admin+ | :44 |
| PUT | `/api/v1/notifications/admin/whatsapp-templates/{id}` | Admin+ | :54 |
| DELETE | `/api/v1/notifications/admin/whatsapp-templates/{id}` | Admin+ | :71 |

---

## payment-service

### `AdminApprovalController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| GET | `/api/v1/payments/admin/approvals` | Admin+ | :40 |
| GET | `/api/v1/payments/admin/approvals/{id}` | Admin+ | :63 |
| POST | `/api/v1/payments/admin/approvals/{id}/approve` | Admin+ | :71 |
| POST | `/api/v1/payments/admin/approvals/{id}/reject` | Admin+ | :84 |
| POST | `/api/v1/payments/admin/approvals/{id}/cancel` | Admin+ | :97 |
| POST | `/api/v1/payments/admin/approvals/{id}/execute-refund-retry` | Admin+ | :117 |

### `PaymentController`

| Method | Path | Tier (by path) | Src |
|---|---|---|---|
| POST | `/api/v1/payments/initiate` | Auth | :65 |
| POST | `/api/v1/payments/callback` | Auth | :80 |
| POST | `/api/v1/payments/admin/simulate/{transactionId}` | Admin+ | :87 |
| GET | `/api/v1/payments/transaction/{transactionId}` | Auth | :99 |
| GET | `/api/v1/payments/booking/{bookingRef}` | Auth | :108 |
| GET | `/api/v1/payments/my` | Auth | :117 |
| POST | `/api/v1/payments/admin/refund` | Admin+ | :128 |
| GET | `/api/v1/payments/admin/refunds/{paymentId}` | Admin+ | :145 |
| GET | `/api/v1/payments/booking/{bookingRef}/refunds` | Auth | :157 |
| GET | `/api/v1/payments/admin/refunds/failed` | Admin+ | :169 |
| POST | `/api/v1/payments/admin/refunds/{refundId}/retry` | Admin+ | :190 |
| POST | `/api/v1/payments/cancel/{transactionId}` | Auth | :213 |
| GET | `/api/v1/payments/admin/stats` | Admin+ | :228 |
| GET | `/api/v1/payments/admin/disputes` | Admin+ | :243 |
| GET | `/api/v1/payments/admin/disputes/all` | Admin+ | :261 |
| GET | `/api/v1/payments/admin/disputes/count` | Admin+ | :279 |
| PATCH | `/api/v1/payments/admin/disputes/{disputeId}/notes` | Admin+ | :299 |
| POST | `/api/v1/payments/webhooks/razorpay` | Auth | :333 |
| POST | `/api/v1/payments/admin/record-cash` | Admin+ | :361 |
| POST | `/api/v1/payments/admin/add-payment` | Admin+ | :382 |
