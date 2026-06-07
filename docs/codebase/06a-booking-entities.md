# 06a — `booking-service` · Entities

The core domain's persistence layer: 47 JPA entities in `entity/` (loyalty-v2 has its own ~20
entities, documented in [06e](06e-booking-loyalty.md)). Grouped here by concern. Booking and
Binge are covered field-by-field in [ARCHITECTURE.md §4/§16](../../ARCHITECTURE.md); this doc
walks all of them.

Source root: `backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/`

---

## Core booking

### `Booking.java` (354 lines)
The central row (`bookings`), `@Version` optimistic lock. Identity + venue (`bingeId`,
`customerId`, contact fields incl. `customerPhoneCountryCode`), `eventType` (lazy `@ManyToOne`),
`bookingDate`/`startTime`/`durationHours`/`durationMinutes` (venue-local), `addOns` (cascade
children), and a large **financial snapshot**: base/addOn/guest/subtotal/tax/total amounts,
`taxBreakdownJson`, currency + FX (`currencyCode`/`fxRate`/`displayAmount` + base/display/payment/
settlement codes), `pricingSource`/`rateCodeName`, surge multiplier/label, venue-room snapshot,
loyalty earned/redeemed/discount, check-in/checkout tracking, reschedule/transfer/recurring
linkage, support fields (escalation, goodwill, cancellation reason/actor), and
`priceSnapshotId`/`billingAddressId`/`fxLockedUntil`. Composite indexes for the common query
shapes; a `@PrePersist` defaults the NOT-NULL finance columns. Full field walk in
[ARCHITECTURE.md §16](../../ARCHITECTURE.md).

### `BookingAddOn.java`
`booking_add_ons` — a line item: FK `booking`, FK `addOn`, `quantity`, `price` (the resolved
line total snapshotted at booking time). Cascade-managed by the parent `Booking`.

### `BookingEventLog.java`
The **immutable audit trail** (`booking_event_log`). Per state change: `bookingRef`, `eventType`
(a `BookingEventType`), `previousStatus`/`newStatus`, actor (`triggeredBy`/`Role`/`Name`),
`description`, `snapshot` (JSON), `reason`, **`ipAddress`/`userAgent`** (captured by the state
machine), `bingeId`, `eventVersion`, `createdAt`. Append-only.

### `BookingEventType.java`
Enum of audit event types: `CREATED, CONFIRMED, CANCELLED, CHECKED_IN, CHECK_IN_REVERTED,
COMPLETED, NO_SHOW, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_UPDATED, MANUAL_REVIEW_FLAGGED,
RESCHEDULED, TRANSFERRED, …`.

### `BookingReadModel.java`
The **CQRS read projection** (`booking_read_model`) — a denormalized, query-optimized view:
`bookingRef`, `customerId`, `status`/`paymentStatus`, total/collected amounts, date/time/
duration/guests, `checkedIn`, `eventTypeId`, plus projection metadata (`eventCount`,
`lastEventId`, `projectedAt`). Rebuilt by `BookingProjectionService` + reconciled by
`CqrsReconciliationJob`.

### `BookingPriceSnapshot.java` (138 lines)
The **immutable pricing record** (`booking_price_snapshots`) that refunds read from (so they're
deterministic after FX/tax moves). All four currency codes, then base-currency component
amounts: `subtotalBase`, `surgeAmountBase`, `discountAmountBase`, `loyaltyRedemptionBase`,
`platformFeeBase`, `taxAmountBase`, `totalBase`, plus `displayTotal` and FX context. One per
calculation version.

### `BookingNote.java`
Support-console notes (`booking_notes`): `authorAdminId`/`authorName`, `body`, `Visibility`
(INTERNAL/CUSTOMER_VISIBLE), `pinned`, `edited`, `deleted` (soft), timestamps.

### `BookingReview.java`
Post-stay reviews — both customer- and admin-authored (`reviewerRole`, `rating`, `comment`,
`skipped`, `visibleToCustomer`). Feeds the binge review summary.

### `BookingRiskFlag.java`
Abuse/risk signals (`booking_risk_flags`): `RuleCode` (the heuristic that fired), `Severity`,
`Source` (SYSTEM/MANUAL), `reason`, `evidence` (JSON), and acknowledgement fields
(`acknowledged`/`...ByAdminId`/`...At`/`...Note`). Produced by `BookingRiskEvaluator`.

### `BookingTransfer.java`
Magic-link booking transfer (`booking_transfers`): from-customer snapshot, to-recipient
(`toName/Email/Phone`), `toCustomerId` (set on accept), `Status` (PENDING/ACCEPTED/DECLINED/
REVOKED/EXPIRED), `acceptToken` (the bearer), `expiresAt`, lifecycle timestamps.

---

## Catalog

### `EventType.java`
A bookable experience (`event_types`, binge-scoped): `name`, `description`, `basePrice`/
`hourlyRate`/`pricePerGuest`, `minHours`/`maxHours`, `minGuests`/`maxGuests`, `categoryId`,
`imageUrls` (element collection), `active`. The default tier of the pricing precedence chain.

### `EventCategory.java` / `AddOnCategory.java`
Catalog grouping (`event_categories` / `addon_categories`): `name`, `description`, `imageUrl`,
`sortOrder`, `active`, timestamps. Can be binge-scoped or global (a null/global variant exists).

### `AddOn.java`
A purchasable extra (`add_ons`, binge-scoped): `name`, `description`, `price`, `categoryId`,
`imageUrls`, `active`, plus inventory controls `stockPerDay` and `advanceNoticeMinutes`
(enforced by `enforceAddOnAvailability` at booking time).

---

## Pricing / tax / FX

### `RateCode.java` + `RateCodeEventPricing` + `RateCodeAddonPricing`
A named pricing plan (`rate_codes`) holding per-event and per-addon override prices (child
collections). Tier 2/3 of the precedence chain.

### `RateCodeChangeLog.java`
Audit of rate-code assignment changes per customer (`changeType` = ASSIGN/REASSIGN/UNASSIGN/
BULK_ASSIGN, previous/new code, admin, timestamp).

### `CustomerPricingProfile.java` + `CustomerEventPricing` + `CustomerAddonPricing`
A customer's personal deal (`customer_pricing_profiles`): an optional attached `RateCode`, a
`memberLabel`, and customer-specific per-event/per-addon overrides (the top tier — "CUSTOMER
always wins").

### `SurgePricingRule.java`
Time/demand multiplier (`surge_pricing_rules`): `dayOfWeek`, `startMinute`/`endMinute`,
`multiplier`, `label`, `active`. Resolved by `PricingService.resolveSurge` (max matching rule).

### `TaxRule.java` (116 lines)
A tax rule (`tax_rules`): `rateBps` (basis points), `AppliesTo` (TOTAL/SUBTOTAL/…), `inclusive`
flag, and jurisdiction matchers (`countryCode`/`regionCode`/`stateCode`) used by
`JurisdictionResolver`. Effective-date bounds make historical bookings reproducible.

### `CurrencyRate.java`
A display/settlement currency (`currency_rates`): `code`/`name`/`symbol`, `rateToBase`,
`decimalDigits`, `active`, `base` flag (the one base currency), `manualOverride`,
`supportsDisplay`, `lastUpdated`.

### `FxRateLock.java`
A checkout FX quote lock (`fx_rate_locks`): `lockToken`, from/to currency, `fxRate`, `fxSource`,
base/converted amounts, `lockedUntil`, consumed flag. Atomically consumed by `FxLockService` at
booking creation so the total can't drift.

### `CancellationTier.java`
A refund tier (`cancellation_tiers`, binge-scoped): `hoursBeforeStart` + `refundPercentage` +
`label`. Evaluated by `evaluateCustomerCancellation`.

### `SystemSettings.java`
Singleton (`id` always 1) holding the global `operationalDate` fallback.

---

## Reliability / infra

### `OutboxEvent.java`
The transactional outbox row (`outbox_event`): `topic`, `aggregateKey` (partition key),
`payload`, `sent`/`sentAt`, retry tracking (`attempts`/`lastAttemptAt`/`lastError`),
`failedPermanent`. Polled by `OutboxPublisher` (see [06d](06d-booking-schedulers.md)).

### `ProcessedEvent.java`
Consumer dedup (`processed_event`): unique `eventKey` + `processedAt`. The idempotency guard for
Kafka consumers (`PaymentEventListener`, etc.).

### `IdempotencyKey.java`
HTTP request dedup (composite PK), `requestHash`, cached `responseStatus`/`responseBody`,
`expiresAt` — same shape as payment's; raises `IDEMPOTENCY_MISMATCH` on payload change.

### `SagaState.java`
The booking saga row (`saga_state`), `@Version`: `bookingRef`, `sagaStatus` (the
`SagaStatus` enum STARTED→…→COMPLETED / COMPENSATING→COMPENSATED/FAILED), `lastCompletedStep`,
`failureReason`, `compensationAttempts`, timestamps. Driven by `SagaOrchestrator`.

---

## Venue / tenancy

### `Binge.java` (224 lines)
The tenant/venue. Address, IANA `timezone`, open/close times, `operationalDate`, support
contacts, cancellation + freeze policy knobs, approval lifecycle (`status`/`approvalDecidedBy`/
`...At`/`rejectionReason`), grace-period fields, `maxConcurrentBookings`, `roomSelectionRequired`,
CMS config JSON. Full walk in [ARCHITECTURE.md §4](../../ARCHITECTURE.md).

### `BingeSiteContent.java`
Per-binge CMS doc (`binge_site_content`, composite key `bingeId`+`slug`), `contentJson`,
`updatedAt`/`updatedBy`.

### `BingeApprovalStatus.java`
Enum: `PENDING_APPROVAL, APPROVED, REJECTED`.

### `VenueRoom.java`
A bookable room within a binge (`venue_rooms`): `name`, `roomType`, `capacity`, `priceAddition`
(the flat surcharge), `sortOrder`, `active`, and a `RoomApprovalStatus`.

### `RoomBlock.java`
A room-unavailability window (`room_blocks`): `roomId`, `startAt`/`endAt`, `reason`, `createdBy`.

### `RoomApprovalStatus.java`
Enum: `PENDING_APPROVAL, APPROVED, REJECTED`.

---

## Ops / lifecycle

### `SlotHold.java`
A temporary checkout reservation (`slot_holds`): `holdToken`, binge/customer, event/date/time,
`expiresAt`. Released by `SlotHoldExpiryScheduler`.

### `WaitlistEntry.java`
A waitlist row (`waitlist_entries`): customer + preferred date/time/event, priority, offer state,
`expiresAt`. Promoted by `WaitlistService`/`WaitlistOfferExpiryScheduler`.

### `CustomerBingeFreeze.java`
An anti-abuse freeze (`customer_binge_freezes`): `freezeUntil`, `reason`, `Status` (ACTIVE/
LIFTED/EXPIRED), `TriggerType` (PENDING_CANCEL/PAYMENT_TIMEOUT/NO_SHOW_PATTERN), actor fields.
Enforced by `CustomerFreezeService.assertNotFrozen` (HTTP 423).

### `CheckInToken.java`
A QR/OTP check-in token (`check_in_tokens`): `TokenType` (QR/OTP), `tokenValue`, `issuedBy`,
`issuedAt`/`expiresAt`/`consumedAt`. Issued and verified by `CheckInService`.

### `AdminNotification.java`
In-app admin notification (`admin_notifications`): recipient, `type`, `severity`, `title`/
`message`, `relatedBingeId`, `actionUrl`, read state. Surfaced via the SSE bell.

---

## Finance documents

### `Invoice.java` + `InvoiceLine.java`
A generated invoice (`invoices`) with `invoiceNumber`, FK to snapshot + billing address,
currency, subtotal/tax/total, and line items (`invoice_lines`: `LineType`, description,
quantity, unit amount, tax bps/type, amount). Rendered to PDF by `InvoicePdfService`.

### `LedgerEntry.java` (102 lines)
Double-entry ledger row (`ledger_entries`): `entryUuid` (deterministic, for idempotency),
`bookingRef`, optional `invoiceId`/`creditNoteId`, `EntryType` (CHARGE/TAX_COLLECTED/REFUND/
TAX_REVERSAL/CANCELLATION_FEE/…), `Direction` (DEBIT/CREDIT), amount, currency, `recordedBy`.

### `CreditNote.java`
A refund credit note (`credit_notes`): `creditNoteNumber`, FK invoice, `amount` + `taxAmount`,
`Reason` (CUSTOMER_CANCELLATION/…), `Status` (ISSUED/…). Issued by `RefundCalculationService`.

### `BillingAddress.java`
Invoice billing details (`billing_addresses`): `fullName`/`email`/`phone`, `companyName`,
`taxId`, `customerType` (B2C/B2B), full postal address.

---

## Repositories
Each entity above has a Spring Data JPA repository in `repository/` (~55 interfaces). Notable
custom queries: `BookingRepository` (the advisory-lock `acquireSlotLock`, `addToCollectedAmount`
`@Modifying`, the anti-abuse counts, `existsPendingDuplicate`), `OutboxEventRepository`
(`findPendingBatchWithLock`), `SlotHoldRepository`/`WaitlistRepository` (expiry scans),
`SurgePricingRuleRepository.findMatchingRules`, `CreditNoteRepository`/`LedgerEntryRepository`
(idempotent sums). These are covered alongside their services in [06b](06b-booking-services.md).
