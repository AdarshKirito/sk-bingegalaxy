# 08 — Events & Asynchronous Messaging

Evidence: census (common-lib `KafkaTopics`), `evidence/specialist-03` (outbox/consumers), live Kafka reachable.

## Topics (from `common-lib/.../constants/KafkaTopics`)

booking.created, booking.confirmed, booking.cancelled, booking.rescheduled, booking.transferred, booking.checked-in, booking.completed, booking.cash-payment, waitlist.promoted, payment.success, payment.failed, payment.refunded, notification.send, user.registered, password.reset, room.approved, room.rejected, room.blocked, room.unblocked.

## Producer → consumer matrix

| Topic | Producer | Consumer(s) | Delivery/dedup |
|---|---|---|---|
| booking.created/confirmed/cancelled/… | booking-service (transactional outbox) | notification-service (`EventListener`), payment-service (`BookingCancelledEventListener` on cancelled), booking (`WaitlistPromotionListener` on cancelled) | at-least-once; consumer dedup on `ProcessedEvent.event_key` |
| booking.cash-payment | booking-service | payment-service (`CashPaymentEventListener`) | dedup |
| payment.success/failed/refunded | payment-service (transactional outbox) | booking-service (`PaymentEventListener`) | dedup + DB-unique `processed_event.event_key`; order-tolerant |
| notification.send | auth-service, booking-service | notification-service | — |
| user.registered, password.reset | auth-service | notification-service | — |
| room.* | booking-service | availability-service (config-driven) | — |
| waitlist.promoted | booking-service | notification-service | — |

## Runtime confirmation (2026-07-12)

The event backbone is **runtime-confirmed working end-to-end** (`21-...LOG.md` R7.4): test bookings produced `BOOKING_CREATED` notifications and test registrations produced `USER_REGISTERED`/`EMAIL_VERIFICATION` notifications in `notification_db` — proving outbox → Kafka → consumer → persist for both booking-service and auth-service producers. Notifications queue at `deliveryStatus: PENDING` in dev (no SMTP).

## Patterns (CONFIRMED)

- **Transactional outbox (booking + payment):** `OutboxEvent` row written in the same DB transaction as the domain change (`BookingEventPublisher` `@Transactional(MANDATORY)`); `OutboxPublisher` drains every 2s under ShedLock and marks `sent` after each successful send. No dual-write. `outbox_event.event_id` UNIQUE (live-confirmed).
- **Idempotent consumers:** every handler computes a natural key, early-returns on `existsByEventKey`, and writes `ProcessedEvent` in the same transaction; `processed_event.event_key` UNIQUE backstops races. Same-key events land on the same partition → sequential.
- **Ordering:** keyed by `bookingRef` → per-booking ordering preserved; cross-booking ordering not assumed.
- **DLQ:** common-lib `KafkaDlqErrorHandlerConfig` + `KafkaDlqProperties`. Known past defect (per project memory) around JSON type headers / `__TypeId__` stamping was addressed with per-topic stamping + DLQ deserializer.

## Issues

- **REL-001 (Medium, CONFIRMED):** `OutboxPublisher` marks `failedPermanent` after 10 attempts **except** serializer/class-cast "code bugs", which keep retrying indefinitely → a poison event can loop forever. Route all failure classes to a DLQ/parking table with alerting.
- **BOOK-002 (Medium):** `waitlist.promoted` OFFER does not reserve the slot; `markEntryConverted` caller unverified.

## Messaging / notifications (notification-service)

Single Kafka fan-in (`EventListener`, group `notification-service`) consuming 7 topics → renders Thymeleaf email templates + Web Push (VAPID) + WhatsApp/SMS (config-driven). Delivery webhook controller records provider callbacks. Templates + preferences exist (`NotificationPreference`, opt-out).

**Delivery reliability (CONFIRMED positive, 2026-07-12 direct read):** `NotificationService` has a real failure story — try/catch around dispatch with `failureReason`/`deliveryStatus` recording (`:283-298`), a scheduled `retryFailedNotifications` with `maxRetries` exclusion and **exponential backoff** (`computeNextRetryAt`, `:194-273`), and an operator-initiated per-row `retryById` from the support console (`:242-264`). Better than census-level suggested.

**DATA-003 (High) still stands:** Mongo TTL/unique indexes are inert (auto-index-creation off) → notification PII never expires and reminder `@CompoundIndex(unique)` dedup is unenforced (possible double-send), and the retry query relies on indexes that may not exist for efficiency.
