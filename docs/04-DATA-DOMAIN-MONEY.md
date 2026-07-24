# 04 — Data, Domain & Money

## Aggregates (source-of-truth per service)

### auth_db (Postgres, Flyway V20)
`User`, `UserSession`, `AuthAuditLog`, `AuthorityGrant` (delegation), `ResourceLock` (authority locks), `RevokedToken`, `PasswordHistoryEntry`, `PasswordResetToken`, `EmailVerificationToken`, `SiteContent` (CMS).

### availability_db (Postgres, Flyway V2 — small/stable)
`BlockedDate`, `BlockedSlot`.

### booking_db (Postgres, Flyway V79 — the big one, ~70 entities)
- **Inventory:** `Binge`, `VenueRoom`, `RoomBlock`, `RoomApprovalStatus`, `BingeApprovalStatus`, `BingeChangeRequest`, `BingeSiteContent`, `AddOn`, `AddOnCategory`, `EventType`, `EventCategory`, `BookingEventType`.
- **Booking lifecycle:** `Booking`, `BookingAddOn`, `BookingNote`, `BookingReview`, `BookingEventLog`, `BookingReadModel` (CQRS read side), `BookingRiskFlag`, `BookingTransfer`, `CheckInToken`, `WaitlistEntry`, `SlotHold`, `CustomerBingeFreeze`.
- **Pricing engine:** `RateCode` (+`RateCodeEventPricing`/`RateCodeAddonPricing`/`RateCodeChangeLog`), `CustomerPricingProfile` (+`CustomerEventPricing`/`CustomerAddonPricing`), `SurgePricingRule`, `TaxRule`, `CancellationTier`, `CurrencyRate`, `BookingPriceSnapshot`.
- **Financial docs:** `Invoice`/`InvoiceLine`, `CreditNote`, `LedgerEntry`.
- **Loyalty v2 (~19 entities):** `LoyaltyProgram`, `LoyaltyTierDefinition`/`LoyaltyTierPerk`, `LoyaltyPerkCatalog`, `LoyaltyMembership`(+`Event`), `LoyaltyPointsWallet`/`LoyaltyPointsLot`, `LoyaltyLedgerEntry`, `LoyaltyRewardClaim`, `LoyaltyBingeBinding`(+earning/redemption/perk-override/reward-item), `LoyaltyCountryEarnConfig`, `LoyaltyGuestShadow`, `LoyaltyQualificationEvent`, `LoyaltyStatusMatchRequest`.
- **Plumbing:** `OutboxEvent`, `ProcessedEvent`, `IdempotencyKey`, `SagaState`, `SystemSettings`, `AdminNotification`, `BillingAddress`.

### payment_db (Postgres, Flyway V16)
`Payment`, `PaymentStatusHistory`, `Refund`(+`RefundStatus`), `PaymentDispute`, `AdminApprovalRequest`, `ProcessedWebhookEvent` (dedup), `OutboxEvent`, `IdempotencyKey`, `AuditLog`, `PaymentConnectedAccount`.

### notification_db (Mongo)
`Notification`, `NotificationTemplate`, `NotificationPreference`, `PushSubscription`, `BookingReminder`, `WhatsAppTemplate`, `DeliveryStatus`.

## Schema-management model (verified) — the operational footgun

- **Flyway owns the schema; `ddl-auto=validate`** in all four Postgres services (set in the config-server baked YAML via `${SPRING_JPA_HIBERNATE_DDL_AUTO:validate}`). Hibernate never mutates DDL.
- **Consequence:** any entity/column drift from the migrated schema **fails service startup**. This is a deliberate integrity gate and the most common self-inflicted outage: add a field → must ship the migration in the same change, or the service won't boot. It also means migrations are effectively append-only and must be forward-safe.
- 234 migration files total; test profile uses `create-drop` (booking test yml) so tests don't need the migration chain.
- **Known entity/schema trap:** `@Lob` on a Postgres `TEXT` column breaks *every* read of that entity ("Bad value for type long") — the fix is plain `@Column(columnDefinition="TEXT")`. Watch for this on any new large-text field.

## Cross-service integrity (the seam)

- `binge_id` (and `booking` reference / customer id) are shared **by value** across four databases. **No cross-DB foreign keys.** Integrity is enforced *only* by internal API contracts + event-payload validation.
- The **public Binge DTO strips `adminId`**; ownership decisions in payment/availability **must** use the internal snapshot `/internal/binges/{id}`. Using the public DTO for ownership ⇒ 403 storm / wrong-tenant binding.
- Payment binds writes/events to the authoritative booking owner/binge/remaining-balance via the extended internal snapshot (`/internal/amount/{ref}`), and the payment-event consumer has a tenant fence (`PaymentEvent.bingeId`).

## Money & rounding contract (code-verified: `common-lib/money/MoneyUtil`)

**All money is `BigDecimal` end-to-end — no float/double in any money path** (verified: the only `double`/`float` in booking/payment are geo lat/lng/radius and rating averages).

- Internal computation scale `CALC_SCALE = 8`; final rounding **HALF_UP** to the currency's ISO-4217 minor units (JPY→0, USD/INR→2; unknown codes fall back to 2).
- Helpers: null-safe `add/sub/mul/div`, `nonNegative` (discounts can't push below zero), `convertWithRate` (`converted = base × rate`, rounded to target minor units), basis-point tax (`applyBps`) and inclusive-tax extraction (`extractInclusiveTax = gross × bps/(10000+bps)`).
- The platform-wide rule: **every monetary computation must go through `MoneyUtil`.** Server is authoritative for pricing; the client never computes final money. See the preserved [`MONEY_SCALE_AND_ROUNDING_CONTRACT.md`](MONEY_SCALE_AND_ROUNDING_CONTRACT.md).

## Domain lifecycles (high level)

- **Binge lifecycle:** create → approval workflow (`BingeApprovalStatus`, 24h grace) → active; a `BingeApprovalInterceptor` freezes REJECTED venues fail-closed. Room-level approval mirrors this (`RoomApprovalStatus`, `room.approved/rejected` events).
- **Booking lifecycle:** hold (`SlotHold`, Redis-backed) → create (advisory lock + DB occupancy backstop, optimistic version) → confirm/pay → check-in (`CheckInToken`) → complete; or cancel → policy-based refund via `booking.cancelled`→refund-intent saga; or transfer (`BookingTransfer`, magic-link) ; waitlist promotion on cancellation.
- **Pricing resolution (layered):** base event/add-on price → rate code overrides → customer pricing profile overrides → surge rules → FX (currency of the *venue's* country) → tax (flat + `TaxBreakdown`) → final snapshot (`BookingPriceSnapshot`). Payment methods follow the **venue's** country via catalog ∩ provider capability.
- **Refund correctness:** durable refund *intents* with stable provider receipts; provider I/O never inside a rollback-able transaction; ambiguity resolved receipt-first by reconciliation; at-most-once movement; disputes monotonic with real chargeback ledger rows; revenue uses captured-ever gross (not mutable status).

Detail and current fragilities are in [05-SERVICE-DEEP-DIVES.md](05-SERVICE-DEEP-DIVES.md) and [07-ISSUE-REGISTER.md](07-ISSUE-REGISTER.md).
