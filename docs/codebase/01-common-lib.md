# 01 — `common-lib`

The shared Maven module every service depends on. No Spring Boot app of its own — a library
JAR of cross-cutting contracts: enums, Kafka event payloads, response DTOs, the exception
hierarchy + global handler, money math, the gateway/internal security filters, PII-masking
log infrastructure, the binge-context thread-local, and the Kafka DLQ config.

Source root: `backend/common-lib/src/main/java/com/skbingegalaxy/common/`

---

## enums/

### `BookingStatus.java`
Six-value lifecycle enum: `PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW`.
This is the authority for booking state; the booking-service `BookingStateMachine` is the
only thing allowed to move a booking between these.

### `PaymentStatus.java`
`PENDING, INITIATED, SUCCESS, FAILED, REFUNDED, PARTIALLY_REFUNDED, PARTIALLY_PAID, DISPUTED`.
The `DISPUTED` value carries a doc comment: it is set on a Razorpay `dispute.created` webhook;
the money is held by the gateway; **the booking is intentionally left confirmed** — do not
auto-cancel, wait for `dispute.won`/`dispute.lost`. `PARTIALLY_PAID` exists because the
platform supports split/partial collection (multiple payments toward one booking total).

### `PaymentMethod.java`
`UPI, CARD, BANK_TRANSFER, WALLET, CASH`. `CASH` is the admin-recorded walk-in method
(`PaymentService.recordCashPayment`); the rest are Razorpay rails.

### `NotificationChannel.java`
`EMAIL, SMS, WHATSAPP, PUSH` — the four delivery channels the `ChannelRouter` cascades through.

### `UserRole.java`
`CUSTOMER, ADMIN, SUPER_ADMIN`. Note SUPER_ADMIN is a real role here even though much of the
elevation is delegated per-path at the gateway.

### `AuthorityScope.java`
The ten per-page delegation scopes for Authority Handover: `CURRENCIES, NOTIFICATIONS,
LOYALTY, OPS, ALL_USERS, CUSTOMER_EDIT, ADMIN_REGISTER, HOME_CMS, ACCOUNT_CMS,
SUPER_DASHBOARD`. Each maps to one super-admin page and a family of endpoints. The class
header states the invariant: **there is no implicit catch-all** — adding a super-admin page
requires adding a value here *and* a path mapping in the gateway's `SCOPE_MAP`.
`fromString(s)` is a null-safe, case-insensitive, trim-then-`valueOf` parse that returns
`null` (rather than throwing) on an unknown string — so a malformed scope claim degrades to
"no scope" instead of erroring.

---

## constants/

### `KafkaTopics.java`
A `final` class of `public static final String` topic names — the single source of truth
producers and consumers share so a topic typo can't silently mis-route. Topics:
`booking.created/confirmed/cancelled/rescheduled/transferred/checked-in/completed/cash-payment`,
`waitlist.promoted`, `payment.success/failed/refunded`, `notification.send`,
`user.registered`, `password.reset`, and the V56/V57 admin-lifecycle topics
`room.approved/rejected/blocked/unblocked` (payload = `AdminLifecycleEvent`). Private
constructor prevents instantiation.

---

## context/

### `BingeContext.java`
The multi-tenancy thread-local. A `final` class wrapping `ThreadLocal<Long> BINGE_ID`.
- `setBingeId` / `getBingeId` / `clear` — raw accessors; `clear()` calls `remove()` to avoid
  thread-pool leakage.
- `requireBingeId()` — returns the id or **throws `IllegalStateException`** with a precise
  message ("X-Binge-Id header missing or BingeContextFilter not applied"). Used by every
  tenant-scoped write so a missing context fails loudly rather than reading another tenant's data.
- `execute(bingeId, Runnable)` / `supply(bingeId, Supplier<T>)` — set-then-try/finally-clear
  wrappers for **non-servlet** code paths (Kafka listeners, async tasks, scheduler ticks)
  that `BingeContextFilter` doesn't cover. The finally guarantees cleanup even on exception —
  the critical detail that prevents a pooled thread from carrying a stale binge id into the
  next job.

---

## dto/

### `ApiResponse<T>.java`
The universal envelope. Lombok `@Data/@Builder`, `@JsonInclude(NON_NULL)` so absent fields
don't bloat the wire. Fields: `success`, `message`, `data`, `errors`, and `captchaRequired`
(set true when the client must solve a CAPTCHA before retrying). Static factories: `ok(data)`,
`ok(message, data)`, `error(message)`, `error(message, errors)`. Every controller returns this
shape, which is why the frontend can branch uniformly on `.success`/`.data`/`.message`.

### `PagedResponse<T>.java`
The booking-service pagination shape: `content`, `page`, `size`, `totalElements`,
`totalPages`, `last`. **Note** (per project memory): booking returns this `PagedResponse`
(content under `.content`), whereas auth/payment return a raw Spring `Page` — the frontend
must extract `.content` in both cases or lists render empty.

### `ErrorCodes.java`
A `final` constants class of stable, machine-readable error identifiers, **append-only** with a
`DOMAIN_REASON` convention. The header rule: *never rename* a code; introduce a new one and
deprecate. Groups: generic (`VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `UNAUTHORIZED`,
`FORBIDDEN`, `OPTIMISTIC_LOCK_FAILURE`, `RATE_LIMITED`…), CSRF (`CSRF_TOKEN_MISSING/MISMATCH/
BAD_ORIGIN`), auth/abuse (`ACCOUNT_LOCKED`, `OTP_LOCKED/INVALID`, `SUSPICIOUS_LOGIN_BLOCKED`,
`INVALID_CREDENTIALS`), idempotency (`IDEMPOTENCY_MISMATCH` — "same key, different payload,
never retryable"), and gateway circuit-breaker fallbacks (`{AUTH,BOOKING,AVAILABILITY,PAYMENT,
NOTIFICATION}_SERVICE_UNAVAILABLE`).

---

## event/

### `EventEnvelope.java`
The abstract base every Kafka payload extends. Lombok `@SuperBuilder(toBuilder=true)`,
`@JsonInclude(NON_NULL)`, `Serializable`. The header is a small design essay: the first
generation of events was a flat POJO whose only "type" signal was the topic; the envelope adds
five metadata fields:
- `eventId` — globally-unique idempotency key for consumer dedup.
- `eventVersion` — schema version so a payload can evolve without a lockstep all-services deploy.
- `eventType` — domain discriminator (duplicates the topic today; supports multiplexing later).
- `occurredAt` — **producer emit time** (outbox write), explicitly distinguished from domain
  timestamps like `paidAt`/`bookingDate`.
- `correlationId` — distributed-trace id threaded from the originating HTTP request via MDC;
  null for scheduler-emitted events.

`ensureDefaults()` populates `eventId`/`occurredAt`/`eventVersion` when the producer left them
blank — **idempotent**, never overwrites a non-null. Backward compat is explicit: old flat
payloads still deserialize (envelope fields become null), and old consumers ignore the unknown
JSON props because the shared Jackson config sets `FAIL_ON_UNKNOWN_PROPERTIES=false`.

### `BookingEvent.java`
Extends `EventEnvelope`. The booking payload: `bookingRef` (also the Kafka partition key),
`bingeId`, customer identity (`customerId/Name/Email/Phone/PhoneCountryCode` — the E.164 dial
prefix added Apr 2026 so SMS/WhatsApp can target international numbers), `eventTypeName`,
`bookingDate/startTime/durationHours/durationMinutes`, `totalAmount`, `status`, `specialNotes`,
and `customerCancellationCutoffMinutes` (snapshot of the binge's cutoff so notification-service
can schedule a "deadline approaching" reminder; nullable for old wire events).
`@JsonIgnoreProperties(ignoreUnknown=true)` for forward compat.

### `PaymentEvent.java`
Payment payload: `bookingRef`, `transactionId`, `amount`, `currency`, `paymentMethod`,
`status`, customer contact fields, `paidAt` (the **domain** timestamp), plus refund-only fields
`refundId`/`refundAmount`/`refundReason` (populated only for `payment.refunded`). This single
class serves success, failed, and refunded topics — consumers branch on `status`/topic.

### `NotificationEvent.java`
The `notification.send` payload: `recipientEmail/Phone/PhoneCountryCode/Name`, the requested
`channel` (a `NotificationChannel`), `templateName` + `templateData` map (for templated sends),
`type`, raw `subject`/`body` (for non-templated), `bookingRef`, and a free-form `metadata` map
(carries e.g. `deviceToken` for push routing).

### `AdminLifecycleEvent.java`
A deliberately **generic** admin-action event so the platform doesn't need a new topic+class for
every micro-change. `entityType` (ROOM, ROOM_BLOCK, EVENT_CATEGORY, ADDON_CATEGORY) + `action`
(APPROVED/REJECTED/BLOCKED/UNBLOCKED/CREATED/UPDATED/DELETED) together identify what happened;
`entityId`, `bingeId` (nullable for global entities), `actorAdminId`, `name`, `reason`, and
`startAt`/`endAt` (for room-block windows) are optional context. Emitted via the booking-service
outbox so notifications/analytics/audit can react without coupling to the controller.

### `CashPaymentRequestedEvent.java`
Small event for the cash-collection flow: `bookingRef`, `customerId`, `amount`, `notes`.
Published by booking-service, consumed by payment-service's `CashPaymentEventListener` to record
an offline payment against the booking.

---

## exception/

### `BusinessException.java`
The base domain exception: a `RuntimeException` carrying an `HttpStatus` (Lombok `@Getter`).
One-arg constructor defaults to `400 BAD_REQUEST`; two-arg lets callers pick the status. This
status is what `GlobalExceptionHandler` maps to the HTTP response — so a service can throw
`new BusinessException("...", HttpStatus.CONFLICT)` and get a clean 409 without controller code.

### `CaptchaRequiredException.java`
Extends `BusinessException` with `429 TOO_MANY_REQUESTS` and a fixed message. Thrown by the auth
flow once the failure threshold is crossed; the handler turns it into a response with
`captchaRequired=true`.

### `DuplicateResourceException.java`
Extends `BusinessException` with `409 CONFLICT`; formats `"%s already exists with %s: %s"`.

### `ResourceNotFoundException.java`
Extends `BusinessException` with `404 NOT_FOUND`; formats `"%s not found with %s: %s"`. Used
pervasively (e.g. `new ResourceNotFoundException("Booking", "ref", bookingRef)`).

### `GlobalExceptionHandler.java`
`@RestControllerAdvice` — the single place HTTP error shapes are produced, imported by every
service. Handlers, in order:
- `CaptchaRequiredException` → status from the exception, body with `captchaRequired=true`.
- `BusinessException` → its own status + `error(message)`.
- `ResourceNotFoundException` → 404; `DuplicateResourceException` → 409;
  `NoResourceFoundException` (Spring static-resource miss) → 404 generic.
- `MethodArgumentNotValidException` / `ConstraintViolationException` → 400 with a
  `{field: message}` map (field extracted from the property path), logged at WARN.
- `HttpMessageNotReadableException` → 400 "Malformed request body".
- `DataIntegrityViolationException` → 409 (logs the most-specific cause only — avoids leaking
  the full SQL).
- `OptimisticLockingFailureException` → 409 "modified by another request, refresh and try again"
  (this is what surfaces `@Version` conflicts to the user).
- `HttpRequestMethodNotSupportedException` → 405.
- `MissingRequestHeaderException` → **401 if the header is `X-User-*`** (a missing gateway
  identity header means unauthenticated), else 400.
- `MissingServletRequestParameterException` / `ServletRequestBindingException` → 400.
- Catch-all `Exception` → 500 with a generic message; logs the real message server-side only
  (never leaks internals to the client).

---

## money/

### `MoneyUtil.java`
The mandatory money-math gateway — *all* financial computation must route through it. `final`,
stateless. `CALC_SCALE = 8` (intermediate precision), `ROUND = HALF_UP` (invoicing standard for
IN/EU).
- `decimalDigits(currencyCode)` — ISO-4217 minor units via `Currency.getInstance`, falling back
  to 2 for unresolvable codes (so JPY rounds to 0, USD to 2).
- `isZeroOrNull` / `zeroIfNull` — null-safe helpers used everywhere to avoid NPEs in pipelines.
- `round(v, currencyCode)` and `round(v, decimals)` — HALF_UP to currency or explicit scale.
- `add/sub/mul/div` — null-safe; `mul` sets `CALC_SCALE`; `div` **returns ZERO on null/zero
  divisor** (safe FX/ratio math, no `ArithmeticException`).
- `nonNegative(v)` — `max(0, v)`; used so a discount can't push a total below zero.
- `convertWithRate(base, rate, targetCurrency)` — `base × rate`, rounded to target minor units;
  the canonical FX conversion.
- `applyBps(value, bps)` — basis-point tax application (1800 bps = 18%).
- `extractInclusiveTax(gross, bps)` — pulls the embedded tax out of a tax-inclusive gross via
  `gross × bps/(10000+bps)`.

---

## security/

### `GatewayHeaderAuthFilter.java`
An `HttpFilter` (servlet, used by the downstream services — not the gateway). Reads the
gateway-injected `X-User-Id` + `X-User-Role`, validates the role against
`{CUSTOMER, ADMIN, SUPER_ADMIN}`, and builds a Spring Security
`UsernamePasswordAuthenticationToken` with `ROLE_<role>` authority — so each service's
`SecurityFilterChain` `hasRole(...)` matchers work off the trusted headers. It also resolves
`bingeId` (from `X-Binge-Id` header or `bingeId` request param) and puts `userId`/`bingeId`
into **MDC** so every log line in the request carries them (picked up by the Logstash encoder
for structured JSON, searchable in Loki/Grafana). The `finally` clears both the security
context and MDC keys — preventing identity/tenant bleed across pooled threads.

### `InternalApiAuthFilter.java`
Guards `/internal/**` endpoints (service-to-service Feign calls) with a shared-secret header
`X-Internal-Secret`. Constructed with the expected secret. For any path containing
`/internal/`, it requires a present, non-blank secret that **constant-time-matches**
(`MessageDigest.isEqual`, defeating timing attacks) — else `403 Forbidden`. On success it sets a
`SYSTEM`/`ROLE_SYSTEM` authentication so `.authenticated()` matchers pass for internal calls.
This is the mechanism behind e.g. availability-service's `/internal/check` being reachable by
booking-service but not by the public.

---

## util/

### `LogSanitizer.java`
PII-masking helpers for log lines and error responses; never returns null, always a low-
cardinality token. The header explains the why: raw PII in Loki/OpenSearch violates GDPR/DPDP/
CCPA minimization, creates a second access-controlled data source, and widens the blast radius
of a stolen log-shipping credential.
- `maskEmail` — `john.doe@x.com` → `jo***@x.com` (keeps domain for routing diagnostics).
- `maskPhone` — `+919876543210` → `+91******3210` (country code + last 4, the fraud-investigation tail).
- `maskToken` — first 4 + last 4 of JWTs/reset tokens (returns `***` if < 12 chars).
- `maskCardNumber` — last-4 only (`****1111`).

---

## logging/

### `CardNumberMaskingConverter.java`
A Logback `ClassicConverter` that redacts card PANs **in the log pipeline** before any appender
writes. Registered as a `conversionRule` overriding the `msg` word so *all* pattern appenders
(console, file) mask automatically with zero app-code changes. The `CARD_PATTERN` regex matches
16-digit groups-of-4 (Visa/MC/Discover), Amex 4-6-5, 13-digit Visa, 14-digit Diners, 16-digit
JCB. Design choice stated in the comment: the pattern is **intentionally broad** — false
positives (a phone number) are acceptable, false negatives (a real PAN leaking) are not.
`mask(input)` is a static reusable entry point; `convert(event)` masks the formatted message.
Satisfies PCI-DSS 3.4 for the log-transport path.

### `MaskingMessageJsonProvider.java`
The JSON-profile counterpart. Extends Logstash's `MessageJsonProvider`; overrides `writeTo` to
run the same `CardNumberMaskingConverter.mask()` on the message before it's written to the
structured `message` field. Needed because `LogstashEncoder` bypasses `PatternLayout` entirely,
so the converter above wouldn't fire on the JSON/Loki profile — this closes that gap.

---

## config/

### `KafkaDlqErrorHandlerConfig.java`
The shared consumer error handler (documented in detail in `ARCHITECTURE.md §9.3`). A
`@Configuration` exposing a `DefaultErrorHandler` bean: `FixedBackOff(1s, 3 retries)` → on
exhaustion a `DeadLetterPublishingRecoverer` routes the record to `<topic>-dlt` (same partition,
cause in headers) and commits the offset to keep the partition moving. Never-retryable
exceptions (`DeserializationException`, `IllegalArgumentException`, `NullPointerException`,
`ClassCastException`) skip straight to DLT. The `-dlt` suffix (not Spring's default `.DLT`) is
deliberate so it matches the pre-created topics and the `AdminOpsController` replay allow-list.
Prevents poison-pill head-of-line blocking.

---

## Tests (`src/test/`)
`KafkaDlqErrorHandlerConfigTest`, `BingeContextTest`, `ApiResponseTest`,
`GlobalExceptionHandlerTest`, `MoneyUtilTest`, `LogSanitizerTest` — unit coverage for the DLQ
routing rules, thread-local cleanup, response factories, exception→status mapping, money
rounding/FX/bps math, and PII masking shapes respectively.
