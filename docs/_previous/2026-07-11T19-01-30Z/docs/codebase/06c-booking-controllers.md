# 06c — `booking-service` · Controllers

26 REST controllers (+ one SSE) under `controller/`. They are thin: validate input, resolve
binge scope, delegate to the services in [06b](06b-booking-services.md), and wrap results in
`ApiResponse`/`PagedResponse`. The full endpoint catalogue is in
[ARCHITECTURE.md §6](../../ARCHITECTURE.md); this doc walks each controller and its surface.

Source root: `backend/booking-service/src/main/java/com/skbingegalaxy/booking/controller/`

---

## Customer-facing

### `BookingController.java` (304 lines) — `/api/v1/bookings`
The customer core. Reads: `{bookingRef}`, `my`/`my/current`/`my/past`, `my/reviews/pending`,
public catalog (`event-types`, `add-ons`, `event-categories`, `addon-categories`,
`booked-slots`, `slot-capacity`), `my-pricing`, `venue-rooms`/`venue-rooms/available`,
`surge-rules`, `{ref}/timeline`. Writes: create is `POST /` (in this controller via the wizard
payload), `{ref}/cancel` (customer self-cancel), `{ref}/reschedule`, `{ref}/transfer`,
`recurring` + `recurring/{groupId}`, customer review GET/POST, and admin review GET/POST. Each
write resolves the customer from the gateway `X-User-*` headers.

### `CheckoutController.java` — `/api/v1/bookings/checkout`
`POST /preview` (→ `CheckoutQuoteService.preview`, the pre-commit total) and `POST /lock-fx`
(→ `FxLockService.lockFx`, freezes an FX rate for the checkout window).

### `SlotHoldController.java` — `/api/v1/bookings/slot-holds`
Customer: `GET /{token}`, `DELETE /{token}` (release), `GET /my`. Admin: `GET /admin`,
`DELETE /admin/{token}`. Backs the "hold this slot while I check out" UX.

### `WaitlistController.java` — `/api/v1/bookings/waitlist`
Customer: `DELETE /{entryId}` (leave), `GET /my`. Admin: `GET /admin`, `GET /admin/count`,
`DELETE /admin/{entryId}`, `POST /admin/{entryId}/offer`, `PATCH /admin/{entryId}/priority`.

### `CustomerFreezeController.java` — `/api/v1/bookings` (freeze paths)
Customer: `GET /freezes/me`, `GET /freezes/me/binge/{bingeId}`. Admin: `GET /admin/freezes`,
`POST /admin/freezes` (manual freeze), `DELETE /admin/freezes/{id}` (lift).

### `BookingTransferController.java` — (no class-level base)
Magic-link transfers. Owner: `POST /{ref}/transfers`, `GET /{ref}/transfers`,
`POST /{ref}/transfers/{id}/revoke`. Recipient (token-bearer, public per gateway):
`GET /booking-transfers/by-token/{token}`, `POST .../accept`, `POST .../decline`.

### `InvoiceController.java` — `/api/v1/bookings`
`GET /{ref}/invoice` (customer download), `GET /admin/invoices` (admin list), `POST
/admin/{ref}/invoice/resend`.

### `BingeController.java` (259 lines) — `/api/v1/bookings` (binge paths)
Public/customer: `GET /binges`, `/binges/{id}`, `/binges/{id}/customer-dashboard`,
`/customer-about`, `/reviews/summary`, `/reviews`, `/cancellation-tiers`. Admin: full binge CRUD
(`/admin/binges` list/by-admin/create/pending/approve/reject/update/toggle/delete), CMS config
(`customer-dashboard`/`customer-about` GET+PUT), and cancellation tiers/policy GET+PUT.

### `BingeSiteContentController.java` — `/api/v1/bookings`
`GET /binges/{bingeId}/site-content/{slug}` (public CMS read) and `PUT /admin/binges/{bingeId}/
site-content/{slug}` (admin edit).

### `BookingAnalyticsController.java` — `/api/v1/bookings/analytics`
`POST /funnel` — the anonymous funnel-telemetry ingest (guest-callable, fire-and-forget metrics
via `BookingAnalyticsMetrics`; public + CSRF-exempt by design).

### `PublicCurrencyController.java` — `/api/v1/bookings/currencies`
Public list of active display currencies (for the currency switcher).

### `PublicTaxController.java` — `/api/v1/bookings/taxes`
`GET /preview` — server-computed tax preview used by the wizard so the client never computes tax
locally.

---

## Admin

### `AdminBookingController.java` (1,021 lines) — `/api/v1/bookings/admin`
The largest controller — the admin operations hub. Booking ops (`today`, `upcoming`, `by-date`,
`by-status`, `search`, `PATCH /{ref}`, `{ref}/cancel|confirm|check-in|checkout|undo-check-in`,
`dashboard-stats`), operational-date (`get`/`advance`/`set`), **catalog CRUD** (event-types,
add-ons, event-categories + `/global`, addon-categories + `/global` — each with create/update/
toggle-active/delete), reports (`reports`, `reports/date-range`, `audit`, `house-accounts`),
manual booking (`create-booking`, `customer-booking-count`, customer review summaries),
**CQRS/saga ops** (`{ref}/events`, `{ref}/replay`, `replay-all`, `{ref}/override-status`,
`sagas/failed`, `sagas/compensating`), **venue rooms** (CRUD + approve/reject + blocks), and
**surge rules** (CRUD + toggle). Routed under the admin RBAC gate.

### `AdminPricingController.java` — `/api/v1/bookings/admin/pricing`
Rate-code CRUD (`rate-codes` list/active/{id}/create/update/toggle/delete), customer pricing
(`customer/{id}` get/save/delete, `bulk-assign-rate-code`, `member-label`), and resolution
(`resolve/{customerId}`, `resolve-rate-code/{rateCodeId}`, `customer-detail/{customerId}`).

### `AdminTaxController.java` — `/api/v1/bookings/admin/taxes`
`PUT /{id}`, `DELETE /{id}`, `GET /global`, `POST /global` — tax-rule management (SUPER_ADMIN/
delegated per the gateway scope).

### `AdminCurrencyController.java` — `/api/v1/bookings/admin/currencies`
`POST /{code}/toggle`, `DELETE /{code}` — currency activation (SUPER_ADMIN scope `CURRENCIES`).

### `AdminSupportController.java` — `/api/v1/bookings/admin/support`
Support console: `GET /{ref}` (case view), notes CRUD (`{ref}/notes` GET/POST, `notes/{id}`
PATCH/DELETE, `notes/{id}/pin`), `{ref}/resend-confirmation`, `{ref}/escalate`, `{ref}/goodwill`
(issue goodwill credit).

### `AdminRiskFlagController.java` — `/api/v1/bookings/admin/risk-flags`
`GET /booking/{ref}`, `POST /{id}/acknowledge`, `POST /booking/{ref}/manual`.

### `AdminRecoveryQueueController.java` (333 lines) — `/api/v1/bookings/admin/recovery`
The human-in-the-loop repair console. Read queues (`stuck-pending`, `expired-holds`,
`paid-not-confirmed`, `no-show`, `summary`, `funnel`) + repair actions (`expired-holds/{token}/
release`, `stuck-pending/{ref}/cancel`, `paid-not-confirmed/{ref}/replay`). Documented in
[ARCHITECTURE.md §9.5](../../ARCHITECTURE.md).

### `AdminOpsController.java` — `/api/v1/bookings/admin/ops`
Danger-zone ops (SUPER_ADMIN scope `OPS`): `POST /replay-dlt` (re-drive dead-lettered events),
`POST /outbox/retry-failed` (reset `failedPermanent` rows), `GET /health` (Kafka/outbox health).

### `AdminNotificationController.java` — `/api/v1/bookings/admin/notifications`
The in-app bell: `GET /unread-count`, `POST /{id}/read`, `POST /read-all`.

### `CheckInController.java` — `/api/v1/bookings/admin`
`POST /{ref}/check-in/qr/issue`, `POST /{ref}/check-in/otp/issue`, `POST /check-in/verify`
(consume a QR/OTP token → CHECK_IN).

### `ExportController.java` — `/api/v1/bookings/admin/export`
`GET /csv` (produces `text/csv`) — streamed booking export.

### `MediaController.java` — `/api/v1/bookings`
`POST /admin/media/upload` (catalog images) and `GET /media/{filename:.+}` (serve uploaded
media). Covered by `MediaControllerTest`.

---

## Real-time & internal

### `AdminSseController.java` — `/api/v1/bookings/admin/events`
`GET /stream` (`text/event-stream`) — the binge-scoped **Server-Sent Events** feed the admin
dashboard subscribes to. The `OutboxPublisher` and `AdminEventBus` push booking/payment/lifecycle
events here for live updates; `SseHeartbeatScheduler` keeps the connection alive. This is the
dedicated gateway route `booking-service-sse`.

### `InternalBookingController.java` — `/api/v1/bookings/internal`
`GET /amount/{bookingRef}` — the SYSTEM-only endpoint payment-service's `BookingAmountClient`
calls (shared-secret authenticated) to read the authoritative booking total.

---

## Cross-cutting
- **Binge scoping**: admin controllers resolve scope via `AdminBingeScopeService`
  (`requireManagedBinge`/`requireBingeOwnership`) before mutating.
- **RBAC**: enforced at the gateway by path shape (`/admin/**`, `/super-admin/**`) plus
  `@PreAuthorize` on sensitive methods (recovery, ops).
- **Validation**: request bodies are Bean-Validated (`@Valid`), mapping to 400 via the shared
  `GlobalExceptionHandler`.

## Tests (`src/test/controller/`)
`AdminBookingControllerOverrideStatusTest`, `AdminTaxControllerAuthzTest`,
`BookingControllerAuthzTest`, `MediaControllerTest` — authorization and the super-admin
status-override path. Service-layer and integration tests are in [06b](06b-booking-services.md).
