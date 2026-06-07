# 08 — `notification-service` (:8085, MongoDB)

The only MongoDB-backed service. A Kafka consumer that turns domain events into multi-channel
notifications (email/SMS/WhatsApp/push), routes to the best available channel, templates the
content, respects user preferences and quiet hours, schedules booking reminders, tracks delivery
status via gateway webhooks, and retries failures. The `ChannelRouter` cascade is in
[ARCHITECTURE.md §10/§13](../../ARCHITECTURE.md); this doc walks every file.

Source root: `backend/notification-service/src/main/java/com/skbingegalaxy/notification/`

---

## model/ (MongoDB documents)

### `Notification.java`
`notifications` collection — the record of every notification. Recipient fields (email/phone/
countryCode/name), `type`, `channel`, `subject`/`body`, `metadata` map (carries `deviceToken`
etc.), `bookingRef`, `sent`/`failureReason`/`retryCount`/`sentAt`, and the **delivery-tracking**
fields `deliveryStatus` (enum), `deliveredAt`/`openedAt`/`clickedAt` updated by provider webhooks.

### `NotificationTemplate.java`
`notification_templates` — versioned, channel-specific templates. `name`, `channel`, `version`,
`content`, `subject`, `active`. Only one version per name is active at a time (activation flips
the flag); supports content evolution without code changes.

### `NotificationPreference.java`
`notification_preferences` per `recipientEmail`. `mutedTypes` + `mutedChannels` (the sets the
`ChannelRouter` consults), `globalOptOut`, **quiet hours** (`quietHoursEnabled`/`Start`/`End`/
`Timezone` — suppress non-urgent sends overnight in the user's tz), `marketingFrequency`
(IMMEDIATE/digest), `primaryChannel`.

### `BookingReminder.java`
`booking_reminders` — a scheduled future reminder. Recipient + booking snapshot (eventTypeName,
date, startTime, durationHours), `reminderType`, `fireAt` (when to send), `fired`/`cancelled`
flags. The reminder scheduler scans these.

### `WhatsAppTemplate.java`
`whatsapp_templates` — Twilio-approved WhatsApp templates (`templateName`, `contentSid`,
`description`, `active`). WhatsApp requires pre-approved templates outside the 24h session window,
so these map a logical name to Twilio's `contentSid`.

### `DeliveryStatus.java`
Enum: `PENDING, SENT, DELIVERED, OPENED, CLICKED, BOUNCED, FAILED, …` — the delivery lifecycle
updated by `DeliveryWebhookController`.

---

## listener/

### `EventListener.java` (608 lines) — the Kafka entry point
Seven `@KafkaListener` handlers (group `notification-service`), each idempotent:
- `BOOKING_CREATED` → booking-confirmation notification + schedules reminders.
- `BOOKING_CANCELLED` → cancellation notice.
- `PAYMENT_SUCCESS` → receipt; `PAYMENT_FAILED` → payment-failed notice.
- `NOTIFICATION_SEND` → the generic templated-send path (used by auth for verification/reset).
- `USER_REGISTERED` → welcome.
- `PASSWORD_RESET` → reset OTP (forced EMAIL-only by the router).
Each builds a `Notification`, resolves the channel, renders the template, sends, and records the
result — wrapped by the shared DLQ handler so a poison event can't wedge the consumer.

---

## service/

### `NotificationService.java` (491 lines) — the orchestrator
The send pipeline: resolve channel (`ChannelRouter`) → check preferences/quiet-hours/opt-out →
render template (`TemplateService`) → rate-limit (`EmailRateLimiter`) → dispatch to the provider
→ persist the `Notification` with status → emit metrics. Also: list-by-recipient/booking, the
admin retry-failed path, and reminder materialization.

### `ChannelRouter.java`
Cascades **PUSH → WHATSAPP → SMS → EMAIL**, gated per channel by provider-configured + contact-
present + not-muted; security types (`PASSWORD_RESET`) are EMAIL-only. Providers are
`@Autowired(required=false)` → graceful degradation to email when Twilio/FCM creds are absent.
Documented in full in [ARCHITECTURE.md §10](../../ARCHITECTURE.md).

### `TemplateService.java`
Loads the active template for a (name, channel), renders it by substituting `templateData`
placeholders, and falls back to a sensible default body when no template exists. Activation/
versioning support.

### `NotificationPreferenceService.java`
`isSuppressed(email, type, channel)` (the router's mute check), quiet-hours evaluation in the
user's timezone, get/update preferences. Backs the customer notification-settings page.

### `EmailRateLimiter.java`
A `Semaphore`-based per-minute email cap with a `@Scheduled(60s)` permit refill — protects the
SMTP relay/reputation from a burst (e.g. a mass digest) tripping spam thresholds.

### `NotificationMetrics.java`
Observability counters (sent/failed/retried per channel, delivery-status transitions) for the
notification on-call dashboard.

---

## provider/

### Interfaces
- `SmsProvider` — `String send(toPhone, body)`.
- `WhatsAppProvider` — `String send(toPhone, body)`.
- `PushProvider` — `String send(deviceToken, title, body, data)`.
Each returns a provider message id (for delivery correlation). The abstraction is what lets the
router degrade gracefully and what lets a backend be swapped (Twilio→SNS, FCM→APNs).

### Implementations
- `TwilioSmsProvider` / `MockSmsProvider` — live Twilio SMS vs a logging stub.
- `TwilioWhatsAppProvider` / `MockWhatsAppProvider` — Twilio WhatsApp (using `contentSid`
  templates) vs stub.
- `FcmPushProvider` / `MockPushProvider` — Firebase Cloud Messaging vs stub.
The mock variants are wired when creds are absent so dev/test runs without external accounts.

---

## scheduler/

### `BookingReminderScheduler.java`
`@Scheduled` (default 60s) — scans `booking_reminders` for due, un-fired, un-cancelled rows
(`fireAt <= now`), sends each, marks `fired`. The "your booking is tomorrow / starts soon" nudge.

### `DigestScheduler.java`
`@Scheduled` (default hourly) — batches notifications for users on a digest `marketingFrequency`
into a single periodic summary instead of immediate sends.

### `NotificationRetryScheduler.java`
`@Scheduled` — re-attempts `sent=false` notifications up to a retry cap, backing the admin
"retry failed" action and transient-provider-error recovery.

---

## controller/

### `NotificationController.java`
`/api/v1/notifications`. `GET /my` (customer history), `GET /booking/{ref}`, `POST
/admin/retry-failed`, `POST /admin/{id}/retry`.

### `DeliveryWebhookController.java`
`POST /api/v1/notifications/webhooks/delivery` — receives delivery-status callbacks from the
providers (sent/delivered/opened/clicked/bounced/failed) and updates the `Notification`'s
`deliveryStatus` + timestamps. Public (provider-called); the body is validated/correlated by
message id.

### `TemplateController.java`
`/api/v1/notifications/admin/templates` (SUPER_ADMIN). `GET` list, `POST` upsert, `POST
/activate` (flip the active version).

### `NotificationPreferenceController.java`
`/api/v1/notifications/preferences`. `GET` (read mine), `PUT` (update) — the customer settings.

### `WhatsAppTemplateController.java`
`/api/v1/notifications/admin/whatsapp-templates` (SUPER_ADMIN). Full CRUD over the Twilio
WhatsApp template registry.

---

## config/

### `ProviderConfig.java`
Conditionally wires the live vs mock providers based on whether the relevant creds
(`TWILIO_*`, FCM) are configured — the source of the `@Autowired(required=false)` graceful
degradation.

### `SecurityConfig.java`
Stateless chain. `/admin/templates/**` + `/admin/whatsapp-templates/**` → SUPER_ADMIN; other
`/admin/**` → ADMIN/SUPER_ADMIN; `/webhooks/**` → permitAll (provider-called); actuator health
permitAll, rest SYSTEM; swagger ADMIN/SUPER_ADMIN; else authenticated. Plus the gateway/internal
filters.

### `MongoConfig.java`, `KafkaConfig.java`, `ShedLockConfig.java`, `OpenApiConfig.java`
Mongo client/template + index setup, Kafka consumer/DLQ wiring, ShedLock (Mongo-backed lock
provider so schedulers run once cluster-wide), springdoc.

---

## health/
`KafkaHealthIndicator` — surfaces Kafka consumer connectivity in actuator health.

## repository/
Spring Data Mongo: `NotificationRepository`, `NotificationTemplateRepository`,
`NotificationPreferenceRepository`, `BookingReminderRepository`, `WhatsAppTemplateRepository`.

## dto/
`NotificationDto`, `NotificationTemplateDto`, `NotificationPreferenceDto`, `DeliveryEventDto`.

## Tests (`src/test/`)
`EventListenerTest` (event→notification mapping, idempotency), `ChannelRouterTest` (cascade +
mute + EMAIL-only), `NotificationServiceTest`, `TemplateServiceTest`, `DigestSchedulerTest`,
`DeliveryWebhookControllerTest`.
