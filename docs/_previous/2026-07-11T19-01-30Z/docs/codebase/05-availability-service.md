# 05 — `availability-service` (:8082, `availability_db`)

The smallest business service. Owns the **calendar**: which dates and 30-minute slots are
blocked per binge, and answers the booking-service's "is this slot free?" question. It does
*not* know about bookings themselves — booking-service combines this calendar with existing
reservations.

Source root: `backend/availability-service/src/main/java/com/skbingegalaxy/availability/`

---

## `AvailabilityServiceApplication.java`
`@SpringBootApplication(scanBasePackages = {availability, common.exception})` — explicitly
pulls in `common-lib`'s `GlobalExceptionHandler`. `@EnableDiscoveryClient` (Eureka) +
`@EnableFeignClients` (for the booking-service callback).

---

## entity/

### `BlockedDate.java`
`blocked_dates` table; unique constraint `(bingeId, blockedDate)` so a date can't be
double-blocked per venue. Fields: `id`, `bingeId`, `blockedDate` (the whole-day block),
`reason`, `blockedBy` (admin id), `createdAt`. A row means "this entire day is closed."

### `BlockedSlot.java`
`blocked_slots` table; unique `(bingeId, slotDate, startHour)`. The important subtlety is in the
field comments: `startHour`/`endHour` are **minutes since midnight (0–1439 / 1–1440)**, *not*
hours — the DB column names were kept for backward compatibility, but the public DTOs expose
`startMinute`/`endMinute` to avoid the ambiguity. Plus `reason`, `blockedBy`, `createdAt`.

---

## repository/

### `BlockedDateRepository.java`
Spring Data JPA. Every finder/exists/delete comes in **two flavors** — global and
`...ByBingeId...` — so the service can run binge-scoped or (legacy/global) unscoped:
`existsByBlockedDate` / `existsByBingeIdAndBlockedDate`, `findByBlockedDateBetween` /
`findByBingeIdAndBlockedDateBetween`, `deleteByBlockedDate` / `deleteByBingeIdAndBlockedDate`,
`findByBingeId`.

### `BlockedSlotRepository.java`
Same dual-flavor pattern for slots: `findBySlotDate` / `findByBingeIdAndSlotDate`, the
`existsBy...StartHour` pair, the `deleteBy...StartHour` pair, the `...Between` range pair, and
`findByBingeId`.

---

## service/

### `AvailabilityService.java` (376 lines) — the engine
Holds two short-TTL caches (`OPERATING_WINDOW_TTL_MS = 30s`): `operatingWindowCache`
(`bingeId → [openMin, closeMin, expiry]`) and `venueTimezoneCache` (`bingeId → IANA tz`).

- **`getAvailability(from, to, clientToday)`** — the public date-range view. Uses
  `clientToday` when supplied (the client knows its own clock), else `venueLocalToday()`.
  Clamps `from` up to today (no past dates), rejects inverted ranges. Loads blocked dates +
  blocked slots for the range (binge-scoped when context is set), then builds a
  `DayAvailabilityDto` per day: fully-blocked days short-circuit to empty slot lists; others go
  through `buildDayAvailability`.
- **`getSlotsForDate(date)`** — single-day view; fully-blocked → empty, else
  `buildDayAvailability`.
- **`blockDate` / `unblockDate`** (`@Transactional`) — admin whole-day block; rejects duplicates
  with `DuplicateResourceException`; stamps `blockedBy` and the scoped `bingeId`.
- **`blockSlot` / `unblockSlot`** — admin slot block; validates `endMinute > startMinute`;
  duplicate guard on `(date, startMinute)`.
- **`getAllBlockedDates` / `getAllBlockedSlots`** — admin lists, **return empty when no binge is
  selected** (defensive — never leak another tenant's calendar).
- **`isSlotAvailable(date, startMinute, durationMinutes)`** — the internal check booking-service
  calls. Order: date-block check → operating-window check (start ≥ open, start+duration ≤ close)
  → load blocked slots → build a set of blocked **30-minute half-hour indices** (using
  **ceiling division** on `endHour` so a partial half-hour is treated as fully blocked) → walk
  the requested span in 30-min steps and reject if any index is blocked.
- **`resolveOperatingWindow()`** — returns `[openMin, closeMin)` for the scoped binge. Cache-hit
  fast path; on miss it calls `bookingBingeClient.getBinge(bid)` (Feign) to read the binge's
  `openTime`/`closeTime`/`timezone`, falling back to the global `app.theater.opening-hour`/
  `closing-hour` when the binge has no override **or booking-service is unreachable**. Rate-
  limited WARN (only on first miss). Validates `closeMin > openMin` (else falls back). Caches
  with the 30s TTL — this is what prevents an N-call Feign storm on every slot-grid render.
- **`evictOperatingWindowCache(bingeId)`** — manual cache invalidation (null = clear all).
- **`venueLocalToday()`** — current date in the venue's IANA timezone; warms the tz cache via
  `resolveOperatingWindow()` if needed; falls back to UTC if the binge is unknown, the tz is
  unavailable, or the IANA id is invalid (`ZoneRulesException` → WARN + UTC). This is the
  venue-local-time discipline applied to the calendar.
- **`buildDayAvailability(date, blockedSlots)`** — generates the 30-min grid within the
  operating window. The loop bound `minute + 30 <= window[1]` ensures a slot whose **end**
  exceeds closing time is never emitted (e.g. close=21:45 must not render 21:30–22:00). Each
  slot becomes a `SlotDto` (start/end hour+minute, `HH:mm - HH:mm` label, available flag),
  partitioned into available vs blocked lists.
- `toBlockedDateDto` / `toBlockedSlotDto` — entity→DTO mappers (note the minute-field rename).

### `AvailabilityBingeScopeService.java`
Tenancy enforcement.
- `requireSelectedBinge(action)` — throws 400 "Select a binge before <action>" if no context.
- `requireManagedBinge(adminId, role, action)` — calls booking-service for the binge, 404s if
  missing, and **enforces ownership**: a non-SUPER_ADMIN must match `binge.adminId` or gets 403
  "you do not own this binge". Feign `NotFound` → 404; other Feign errors → 503 "unable to
  validate ownership right now" (fail-closed). This is why one admin can't block another venue's
  calendar.

---

## controller/

### `AvailabilityController.java`
`/api/v1/availability`, `@Validated`. A single `@ModelAttribute validateBingeScope` runs before
every handler: admin paths (`/admin/`) require `requireManagedBinge` (ownership), public paths
require `requireSelectedBinge`. Endpoints:
- `GET /dates?from&to&clientDate` and `GET /slots?date` — public, `Cache-Control: no-store`
  (availability must never be cached by a proxy/browser — it changes the moment a slot is
  booked).
- `GET /internal/check?date&startMinute&durationMinutes` — the booking-service callback,
  returns a raw `Boolean`.
- `GET /admin/blocked-dates` / `blocked-slots`; `POST /admin/block-date` / `block-slot`;
  `DELETE /admin/unblock-date` / `unblock-slot` — admin calendar management, taking `X-User-Id`
  as the acting admin.

---

## client/

### `BookingBingeClient.java`
`@FeignClient(name = "booking-service", fallback = BookingBingeClientFallback.class)` — one
method, `getBinge(id)` → `GET /api/v1/bookings/binges/{id}`, used to read per-binge hours/tz and
to validate ownership.

### `BookingBingeClientFallback.java`
Circuit-breaker fallback: logs `Circuit breaker OPEN` and returns `ApiResponse.error(...)` with
null data so callers degrade gracefully (the service then uses its global fallback window).

---

## config/

### `BingeContextFilter.java`
`@Order(1)` servlet filter: reads `bingeId` from the request param (or `X-Binge-Id` header),
parses to `Long` (logs + ignores a malformed value), sets `BingeContext`, and **clears it in
`finally`** — the per-request tenancy population that everything above relies on.

### `SecurityConfig.java`
Stateless `SecurityFilterChain`: CSRF disabled (stateless API behind the gateway), session
`STATELESS`, registers `InternalApiAuthFilter` (the `/internal/**` shared-secret guard) and
`GatewayHeaderAuthFilter` (turns gateway `X-User-*` into a Spring auth) before the username/
password filter. Authorization: `/admin/**` → ADMIN/SUPER_ADMIN; `/dates`,`/slots`,`/event-types`
→ permitAll; `/internal/**` → `ROLE_SYSTEM` (only the shared-secret callers); `/actuator/health`
permitAll, other actuator → SYSTEM; swagger → ADMIN/SUPER_ADMIN; everything else authenticated.

### `OpenApiConfig.java`
Springdoc/OpenAPI bean (API title, version, the JWT/header security scheme) for the swagger UI.

---

## dto/
Lombok builders: `DayAvailabilityDto` (date, `fullyBlocked`, `availableSlots`, `blockedSlots`),
`SlotDto` (startHour/endHour/startMinute/endMinute/label/available), `BlockedDateDto`,
`BlockedSlotDto` (both with the minute-field rename), `BlockDateRequest`/`BlockSlotRequest`
(validated request bodies), and `BookingBingeDto` (the slice of the binge this service reads:
adminId, openTime, closeTime, timezone).

---

## Tests (`src/test/`)
`AvailabilityServiceTest` (date/slot blocking, half-hour grid math, operating-window clamping)
and `AvailabilityBingeScopeServiceTest` (ownership enforcement, 403/404/503 paths).
