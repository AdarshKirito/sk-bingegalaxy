# 07b — MongoDB Collection Catalog (live introspection)

> **Historical live snapshot captured before current Mongo index remediation.** It proves the earlier deployment had `_id_`-only indexes, not that a current deployment does. See [`../12-DATABASE.md`](../12-DATABASE.md).

`notification_db` (owned by notification-service), **introspected from the running stack** (2026-07-12) via `mongosh` `getCollectionNames()` / `findOne()` / `getIndexes()`. MongoDB has no migration tool here; document shape is inferred from a live sample document per collection (field: BSON-type). PII fields are named but no values are shown (Rule 7). See [07-DATABASE.md](07-DATABASE.md) for narrative and **DATA-003** (inert TTL/unique indexes) + **DATA-004** (cross-service PII).

## Collections

### `notifications` — docs: **84**
Every email/SMS/push notification record.

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | PK |
| `recipientEmail` | string | **PII** |
| `recipientPhone` | string | **PII** |
| `recipientPhoneCountryCode` | string | |
| `recipientName` | string | **PII** |
| `type` | string | e.g. `BOOKING_CREATED`, `USER_REGISTERED`, `EMAIL_VERIFICATION` |
| `channel` | string | EMAIL / SMS / PUSH / WHATSAPP |
| `subject` | string | |
| `body` | string | rendered content |
| `sent` | boolean | |
| `failureReason` | string | |
| `retryCount` | number | drives exponential-backoff retry |
| `createdAt` | date | |
| `deliveryStatus` | string | PENDING / SENT / FAILED (PENDING in dev — no SMTP) |
| `bouncedAt` | date | |
| `digested` | boolean | |
| `_class` | string | Spring type hint |

**Indexes:** `_id_` **only** — the `@Indexed(expireAfter="P90D")` TTL declared on the entity is **NOT created** (auto-index-creation off). → PII never expires (**DATA-003**).

### `booking_reminders` — docs: **86**
Scheduled pre-event reminders.

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | PK |
| `bookingRef` | string | |
| `recipientEmail` | string | **PII** |
| `recipientPhone` | string | **PII** |
| `recipientPhoneCountryCode` | string | |
| `recipientName` | string | **PII** |
| `eventTypeName` | string | |
| `bookingDate` | date | |
| `startTime` | date | |
| `durationHours` | number | |
| `reminderType` | string | |
| `fireAt` | date | scheduler trigger |
| `fired` | boolean | |
| `cancelled` | boolean | |
| `_class` | string | Spring type hint |

**Indexes:** `_id_` **only** — the `@CompoundIndex(unique)` dedup index declared on the entity is **NOT created** → duplicate reminders / double-sends possible (**DATA-003**).

### `push_subscriptions` — docs: **0**
Web-Push (VAPID) browser subscriptions. Empty in dev. **Indexes:** `_id_` only.

### `shedLock` — docs: **3**
Distributed-lock bookkeeping (ShedLock). Fields: `_id` (string lock name), `lockUntil` (date), `lockedAt` (date), `lockedBy` (string). **Indexes:** `_id_` only. (Infra, not domain data.)

## Runtime confirmation

This introspection (2026-07-12) re-confirms **DATA-003**: on the live store — 84 notifications + 86 reminders — **every collection has only the default `_id_` index**; no `expireAfterSeconds`, no unique compound. Consistent with the R7.2 finding in [21-RUNTIME-VERIFICATION-LOG.md](21-RUNTIME-VERIFICATION-LOG.md). The PII fields above (`recipientEmail`/`recipientPhone`/`recipientName`) are the ones over-retained (DATA-003) and unreachable by auth's anonymization (DATA-004).
