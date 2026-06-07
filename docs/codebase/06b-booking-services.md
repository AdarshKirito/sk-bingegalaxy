# 06b — `booking-service` · Services

The business logic layer — 28 files in `service/` plus the `statemachine/`, `tax/provider/`,
`metrics/`, and `web/` subpackages. The hot paths (createBooking, pricing precedence, cancel/
refund, saga, state machine) are traced line-by-line in
[ARCHITECTURE.md §8/§9](../../ARCHITECTURE.md); this doc walks every service and its public API.

Source root: `backend/booking-service/src/main/java/com/skbingegalaxy/booking/`

---

## Core orchestration

### `service/BookingService.java` (4,483 lines)
The domain monolith. Public surface (grouped): **create** (`createBooking` 5/6-arg,
`createRecurringBookings`), **read** (`getByRef`, `getMyBookings`/`current`/`past`, admin
list/search/by-date/by-status, dashboard-stats), **mutate** (`updateBooking`, `cancelBooking`
×3, `cancelBookingByCustomer`, `cancelBookingForSystem`, `rescheduleBooking`, `transferBooking`,
`confirmBooking`, check-in/checkout), and the Kafka-driven `updatePaymentStatus` /
`addToCollectedAmount` / `subtractFromCollectedAmount`. `createBooking`, `cancelBooking`, and
`evaluateCustomerCancellation` are documented line-by-line in
[ARCHITECTURE.md §8.1/§8.5](../../ARCHITECTURE.md). Every status mutation routes through
`BookingStateMachine`; every create writes to the outbox + starts a saga.

### `service/statemachine/BookingStateMachine.java`
The **single** mutator of `Booking.status`. `transition(booking, event, actor, reason)` (table-
driven, role-checked, idempotent, audited), `override(...)` (super-admin terminal-state
recovery), `canTransition(...)` (UI button gating). Full transition table in
[ARCHITECTURE.md §9.1](../../ARCHITECTURE.md). Companion `BookingTransitionEvent` (the event
enum) and `InvalidTransitionException` (→ 409).

### `service/statemachine/TransitionActor.java`
Value object identifying who performed a transition. Role constants (`ROLE_SYSTEM/CUSTOMER/
ADMIN/SUPER_ADMIN`) and factories `system()`, `customer(id,name)`, `admin(id,name)`,
`superAdmin(id,name)`. Carries the display name and `isSuperAdmin()` used by `override`.

### `service/SagaOrchestrator.java`
The booking saga state machine (`STARTED→…→COMPLETED`, `COMPENSATING→COMPENSATED/FAILED`).
`startSaga`, `advanceTo` (re-verifies `collected ≥ total` before CONFIRMED), `markCompensating`,
`markFailed`, `getFailedSagas`/`getCompensatingSagas`. Documented in
[ARCHITECTURE.md §9.4](../../ARCHITECTURE.md).

---

## Pricing / tax / FX / checkout

### `service/PricingService.java` (1,147 lines)
Rate-code + customer-pricing CRUD, the **precedence resolution** (`resolveEventPrice`/
`resolveAddonPrice`: CUSTOMER → override RATE_CODE → profile RATE_CODE → DEFAULT), `resolveSurge`
(max matching multiplier), `resolveCustomerPricing` (bulk profile resolve with `overallSource`),
surge-rule CRUD, and `bulkAssignRateCode`. Traced in
[ARCHITECTURE.md §8.2](../../ARCHITECTURE.md). Holds a thread-local current-admin-id for scoping.

### `service/TaxService.java`
`compute(bingeId, …)` / `compute(TaxContext, total, base, addon, guest)` → a
`TaxComputationResult` (per-rule breakdown + total tax, with inclusive/exclusive handling), plus
tax-rule CRUD (`listRulesForCurrentBinge`, `listGlobalRules`, `createRule(global?)`, `updateRule`,
`deleteRule`, `isGlobalRule`). Delegates the actual math to the tax provider.

### `tax/provider/TaxProvider.java` + `InternalTaxProvider.java`
Strategy interface (`computeTaxes(ctx, …)`) and the built-in impl `INTERNAL` — applies matching
rules in basis points via `MoneyUtil.applyBps`/`extractInclusiveTax`, honoring `appliesTo` and
the inclusive flag, producing the per-rule breakdown serialized onto the booking.

### `tax/provider/JurisdictionResolver.java`
`score(rule, ctx)` ranks how specifically a `TaxRule` matches the billing jurisdiction
(country/region/state); `filterApplicable(candidates, ctx)` keeps the best-matching rules — so a
state-specific rule wins over a country-wide one. `tax/provider/TaxContext.java` is the input
(jurisdiction codes + amounts).

### `service/CurrencyService.java`
Multi-currency. `BASE_CURRENCY="INR"`, `listActive`/`listAll`, `findOrThrow`, `getRate`,
`convertFromBase`/`convertToBase` (via `MoneyUtil`), `symbolMap`, `upsert`, `delete`. Backs the
currency switcher and FX conversion.

### `service/FxLockService.java`
Checkout FX quote locks. `DEFAULT_TTL_MINUTES=15`. `lockFx(from,to,…)` (mint a token + freeze a
rate), **`consumeLock(token)`** (atomic validate-and-mark-CONSUMED, throws on
expired/already-used — the booking-time guard), `findActive`, `expireStaleLocks` (scheduler hook).

### `service/CheckoutQuoteService.java`
`preview(CheckoutPreviewRequest, customerId)` → `CheckoutPreviewResponse`: the full pre-commit
total (pricing precedence → surge → room → loyalty → tax → FX) so the wizard shows exactly what
will be charged. Mirrors the createBooking math without persisting.

### `service/RefundCalculationService.java`
Snapshot-based refund math + credit-note issuance (`quote`, `issue`) with proportional tax
reversal and double-entry ledger rows. Documented in
[ARCHITECTURE.md §8.5](../../ARCHITECTURE.md).

### `service/CancellationTierService.java`
`getTiers(bingeId)`, `getActiveBingeTiers`, `saveTiers(bingeId, request)` — manage the tiered
cancellation/refund schedule the cancel policy evaluates.

---

## Lifecycle & ops

### `service/SlotHoldService.java`
Temporary checkout reservations. `createHold`, `getByToken`, `releaseByToken`,
`listMyLiveHolds`/`listActiveHoldsForCurrentBinge`, **`consumeHold`** (convert a hold into a
booking), `findLiveHoldsForSlot`, `releaseQuietly`, `expireStaleHolds`/`purgeOldTerminalHolds`
(scheduler hooks).

### `service/WaitlistService.java`
`joinWaitlist`, `leaveWaitlist`, customer/admin listing + count, `adminCancelEntry`/
`adminOfferEntry`/`adminSetPriority`, and **`promoteWaitlistOnCancellation`** (offer the slot to
the next entry when a booking frees up — race-tested). Backs the `CAPACITY_FULL` waitlist CTA.

### `service/CustomerFreezeService.java`
Anti-abuse freezes. **`assertNotFrozen(customerId, bingeId)`** (raises 423 — called first in
createBooking), the trigger recorders `recordCustomerCancellation`/`recordPendingPaymentTimeout`/
`recordNoShow` (which apply a freeze once the per-binge threshold is crossed), customer/admin
listing, `liftFreeze`, `createManualFreeze`.

### `service/CheckInService.java`
QR/OTP check-in. `issueQrToken`/`issueOtp` (mint a `CheckInToken`), `verifyQr`/`verifyOtp`
(consume token → state-machine CHECK_IN, detect late arrival vs venue-local start time). Returns
`IssueQrResult`/`IssueOtpResult` records.

### `service/BookingTransferService.java`
Magic-link transfers. `requestTransfer` (mint `acceptToken`, email recipient), `acceptTransfer`
(recipient claims — rewrites booking ownership), `declineTransfer`, `revokeTransfer`,
`listForBooking`, `findByToken`, `expireStalePending` (scheduler hook).

---

## CQRS / events / idempotency

### `service/BookingProjectionService.java`
The CQRS write side. `applyEvent(BookingEventLog)` (incrementally update the read model),
`replayBooking(ref)` (rebuild one), `replayAll()` (full rebuild — used by the reconciliation job).

### `service/BookingEventLogService.java`
Writes the immutable audit log. `logEvent(...)` (×2 overloads) and `logEventFull(...)` (with IP/
UA), plus `getEventHistory(ref)` (list + paged) — the booking timeline.

### `service/BookingEventPublisher.java`
`publish(topic, aggregateKey, EventEnvelope)` — serializes an event and writes it to the
**outbox table within the current transaction** (the transactional-outbox producer side); also
emits `AdminLifecycleEvent`s for room/category changes.

### `service/IdempotencyService.java`
`execute(key, …)` — runs an operation under HTTP idempotency (cache response, replay on repeat,
raise mismatch on changed payload), `purgeExpired`. Used to wrap booking creation.

---

## Invoicing / ledger

### `service/InvoiceService.java`
`generate(bookingRef)` (build an `Invoice` + lines from the price snapshot), `linesFor`,
`listForCustomer`/`listForBinge`, `resend`.

### `service/InvoicePdfService.java`
`generateInvoice(bookingRef)` → `byte[]` — renders the invoice to a downloadable PDF.

### `service/LedgerService.java`
The double-entry ledger. `record(entry)` (idempotent on `entryUuid`), `recordPair(debit, credit)`,
`historyForBooking(ref)`, `newUuid()`.

---

## Tenancy / venue / settings

### `service/BingeService.java` (894 lines)
Venue management. `GRACE_PERIOD_HOURS=24`/`GRACE_WARNING_AT_HOURS_REMAINING=12`. Admin/customer
binge listing (scoped), `getPendingBinges`, `getBingeById`, the customer dashboard/about
experience builders (from the CMS config JSON), create/update/approve/reject/toggle/delete,
cancellation-policy + tiers, support contacts, review summaries. The approval lifecycle +
grace-period logic lives here.

### `service/VenueClockService.java`
The venue-local-time authority. `zoneOf(bingeId)` (cached IANA zone), `today`/`now` (venue-local),
`defaultZone`, `evict`. Every date/time comparison in the service layer goes through this.

### `service/SystemSettingsService.java`
Operational-date management: `getOperationalDate`/`advanceOperationalDate` (global + per-binge),
`setOperationalDate`. The per-venue business date that advances only after a successful audit.

### `service/AdminBingeScopeService.java`
Tenancy enforcement: `requireSelectedBinge(action)`, `requireManagedBinge(adminId, role[, action])`
(ownership unless SUPER_ADMIN), `requireBingeOwnership(...)`. The 403 gate behind every admin
binge action.

### `service/AdminNotificationService.java`
In-app admin notifications. `notifyUser`, `broadcastToRole`, `list` (paged), `unreadCount`,
`markRead`, `markAllRead` — surfaced via the SSE bell.

---

## Risk / notes / metrics

### `service/BookingRiskEvaluator.java`
`evaluate(booking)` (runs heuristics in `REQUIRES_NEW` so it can't roll back a booking; writes
`BookingRiskFlag`s), `listForBinge`/`listForBooking`, `acknowledge`, `createManual`.

### `service/BookingNoteService.java`
Support notes CRUD: `list`, `add`, `edit`, `softDelete`, `pin` — with visibility + author/role
checks.

### `service/BookingAnalyticsMetrics.java` + `metrics/BookingFunnelMetrics.java`
Micrometer counters: the analytics funnel (`funnelStarted`/`Step1..3`/`Created`) + lifecycle
(`lifecycleConfirmed`/`Cancelled`), and the ops funnel (`holdCreated`/`Expired`/`Converted`/
`Released`, `waitlistJoined`/`Offered`/`Converted`). Power the admin dashboards.

### `web/RequestContext.java`
Static accessors over the current request (`currentIp`, `currentUserAgent`, `currentRole`,
`currentUserId`, `currentUserName`, `currentCorrelationId`) — used by the state machine and
audit log to stamp who/where without threading the request through every method.

---

## Repositories (`repository/`)
~55 Spring Data JPA interfaces, one per entity. Notable custom queries:
- `BookingRepository` — `acquireSlotLock` (Postgres advisory lock), `addToCollectedAmount`
  (`@Modifying`, `clearAutomatically`), `existsPendingDuplicate`, the anti-abuse counts
  (`countPendingByCustomerIdAndBingeId`, `countRecentTimeoutCancellationsByBinge`), `hasTimeConflict`
  helpers, `findByBookingRefForUpdate`.
- `OutboxEventRepository.findPendingBatchWithLock` (skip-locked batch).
- `SagaStateRepository.findBySagaStatus`, `SlotHoldRepository`/`WaitlistRepository` expiry scans,
  `SurgePricingRuleRepository.findMatchingRules`, `CreditNoteRepository`/`LedgerEntryRepository`
  idempotent sums, `BookingReadModelRepository`.

Each is wired to its service above. Controllers consuming these services are in
[06c](06c-booking-controllers.md); the schedulers driving the async work are in
[06d](06d-booking-schedulers.md).
