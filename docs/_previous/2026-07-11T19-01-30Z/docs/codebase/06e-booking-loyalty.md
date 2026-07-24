# 06e — `booking-service` · Loyalty v2

A complete points-and-tiers loyalty engine living inside booking-service under
`loyalty/v2/**`: 5 engines, 7 services, 18 entities, 18 repositories, a pluggable perk registry
with 10 handlers, 3 controllers, an `AFTER_COMMIT` booking listener, an auto-seeder, and the
constants. Exposed at `/api/v2/loyalty/**`. The earn/redeem/expiry/tier mechanics are traced in
[ARCHITECTURE.md §8.4/§11](../../ARCHITECTURE.md); this doc walks every file.

Source root: `backend/booking-service/src/main/java/com/skbingegalaxy/booking/loyalty/v2/`

The model is Marriott-Bonvoy-style: **redeemable points** (spendable) are separate from
**qualifying credits** (status/tier progress), so a premium venue can reward status faster than
points.

---

## engine/ — the math core

### `EarnEngine.java` (259 lines)
`earnForBooking(EarnRequest)` → `EarnResult` (awarded/skipped). On a completed booking: resolve
the binge binding + earn rule, compute `floor(amount × num/den)` (rounds against the customer),
apply tier + cap, derive qualifying credits with a separate multiplier, credit a FIFO lot, write
a `LoyaltyQualificationEvent` (de-duped per membership+booking), and hand off to `TierEngine`.

### `RedeemEngine.java` (266 lines)
`quote(QuoteRequest)` (read-only preview) and `burn(BurnRequest)` (transactional). Resolves the
redemption rule + tier bonus, caps by `maxRedemptionPercent` of the booking (scaling points down
so the member is never overcharged, with customer-favorable asymmetric rounding), checks balance,
and debits via the wallet.

### `ExpiryEngine.java`
`runDailyExpiry()` / `expireAsOf(cutoff)` — nightly per-lot expiry (one bad row can't crash the
run; orphaned lots throw + ERROR for manual cleanup), then `TierEngine.recalculateTier`.

### `TierEngine.java` (287 lines)
`recalculateTier(membershipId, at)` — recomputes rolling-window + lifetime QC, finds the highest
qualified tier, **promotes immediately**, **defers demotions** to the annual rollover, extends
validity on re-qualification. `runAnnualRolloverJob()` / `runAnnualRollover(at)` — the *only*
place demotions happen, after `tierEffectiveUntil` passes.

### `PointsWalletService.java` (374 lines)
The ledgered wallet. `credit(CreditRequest)` (new FIFO lot + ledger entry), `debit(DebitRequest)`
(locked, idempotent at two granularities, drains lots oldest-first, one ledger row per lot +
aggregate marker — see [ARCHITECTURE.md §8.4](../../ARCHITECTURE.md)), `expireLot(...)`, and the
`InsufficientPointsException`.

---

## service/

### `EnrollmentService.java`
Membership creation. `enrollFromBooking`/`enrollExplicit`/`enrollFromBackfill` (different sources),
`findForCustomer`, `ensureEnrolledForBooking` (lazy-enroll at earn time).

### `LoyaltyMemberService.java` (288 lines)
The member-facing facade. `getMemberProfile` (tier, balances, perks), **`redeemForBooking`**
(called from `createBooking` — the `RedemptionResult` carrying points + discount), `adjustPoints`
(admin manual credit/debit). Returns `MemberProfile`/`RedemptionResult` records.

### `LoyaltyAdminService.java` (339 lines)
Admin/super-admin config. Tier CRUD (`upsertTier`/`retireTier`), perk catalog (`savePerk`),
tier-perk mapping (`assignPerkToTier`/`removePerkFromTier`), and binge bindings
(`enableBingeForLoyalty`/`disableBingeForLoyalty`).

### `LoyaltyConfigService.java` (211 lines)
Read-side config resolver: `requireDefaultProgram`, `findProgramByCode`, `activeLadder` (the tier
ladder effective at a time), `activeTier`, `activePerks`, `findPerk`, `tierPerks`, plus the
binding/earn-rule/redeem-rule resolvers the engines depend on (effective-date filtered).

### `StatusMatchService.java` (249 lines)
Competitor status-match challenges. `submit`, `approve`/`reject`, `runChallengeExpiryJob`/
`expireChallengesAsOf` — a member proves status elsewhere, an admin grants a matching tier (often
as a time-boxed challenge).

### `GuestShadowService.java` (213 lines)
Pre-account accrual. `accrueForGuest(email, phone, deviceFingerprint, …)` (track points for a
guest who hasn't registered), **`mergeOnSignup(customerId, email, phone)`** (fold the shadow into
a real membership when they sign up), and an expiry/purge job.

### `MemberNumberGenerator.java`
`generate()` — produces unique member numbers.

---

## entity/ (18 documents)
- **Program/tier**: `LoyaltyProgram` (the program, default `SK_MEMBERSHIP`), `LoyaltyTierDefinition`
  (a tier in the ladder with QC thresholds + validity), `LoyaltyTierPerk` (tier→perk mapping),
  `LoyaltyPerkCatalog` (a perk definition).
- **Membership/wallet**: `LoyaltyMembership` (the member: tier, QC window/lifetime, validity),
  `LoyaltyPointsWallet` (balance + lifetime counters), `LoyaltyPointsLot` (a dated FIFO lot),
  `LoyaltyLedgerEntry` (immutable EARN/REDEEM/EXPIRE/ADJUST rows), `LoyaltyQualificationEvent`
  (a QC accrual with rolling-window expiry), `LoyaltyMembershipEvent` (tier-change audit).
- **Per-binge config**: `LoyaltyBingeBinding` (binge↔program link + state), `LoyaltyBingeEarningRule`,
  `LoyaltyBingeRedemptionRule`, `LoyaltyBingePerkOverride`, `LoyaltyBingeRewardItem`.
- **Other**: `LoyaltyGuestShadow` (pre-account accrual), `LoyaltyStatusMatchRequest`,
  `LoyaltyRewardClaim` (a redeemed catalog reward).

Each has a matching Spring Data JPA repository in `repository/` (18 interfaces) — notably
`LoyaltyPointsLotRepository.findFifoOpen` (oldest-first drain), `findExpiringLots`,
`LoyaltyQualificationEventRepository.sumActiveCredits`/`sumLifetimeCredits`,
`LoyaltyLedgerEntryRepository` idempotency lookups, and `LoyaltyMembershipRepository.findByIdForUpdate`.

---

## perk/ — pluggable perk delivery

### `PerkDeliveryHandler.java`
The strategy interface: `handlerKey()` + `evaluate(EvaluationContext)` → a `PerkOutcome`
(factory methods `none()`, `discount(amount, note)`, `bonus(points, note)`, `priority(boost,
note)`). Lets a perk's effect be computed uniformly at booking/earn time.

### `PerkRegistry.java`
Discovers every `PerkDeliveryHandler` bean and indexes by `handlerKey()`; `find(key)` dispatches
to the right handler — the seam that makes perks pluggable without touching the engines.

### `perk/handlers/` (10 handlers)
`WelcomeBonusPointsHandler`, `BirthdayBonusPointsHandler`, `BonusPointsMultiplierHandler`,
`TierDiscountPercentHandler` (e.g. Gold = X% off), `EarlyAccessBookingWindowHandler` (book before
general availability), `FreeCancellationExtendedHandler` (longer free-cancel window),
`PriorityWaitlistHandler` (queue boost), `RewardCatalogClaimHandler` (redeem points for a catalog
item), `StatusExtensionGrantHandler` (extend tier validity), `SurpriseDelightBudgetHandler`
(ad-hoc goodwill budget). Each maps its `handlerKey()` to a `LoyaltyV2Constants.PERK_*` code.

---

## controller/

### `LoyaltyV2CustomerController.java` — `/api/v2/loyalty/me`
`GET /ledger` (my points history), `GET /redeem-quote` (preview a redemption), `GET/POST
/status-match` (view/submit a status-match request).

### `LoyaltyV2AdminController.java` — `/api/v2/loyalty/admin`
Per-binge config (scope `LOYALTY`): `GET /bindings/{bingeId}`, `POST /bindings/{bingeId}/enable`,
`POST /bindings/{bindingId}/disable`, earn-rules GET/POST, redeem-rule GET/POST, `POST
/bindings/{bindingId}/perks`, and status-match review (`status-match/pending`, `{id}/approve`,
`{id}/reject`).

### `LoyaltyV2SuperAdminController.java` — `/api/v2/loyalty/super-admin`
Program-wide config (SUPER_ADMIN): program GET/PUT, tier CRUD, perk catalog GET/POST, tier-perk
mapping POST/DELETE + GET, bindings list + `bindings/bulk`, and member ops (`customers/{id}` view,
`/adjust` manual points, `/ledger`).

---

## event/

### `LoyaltyV2BookingListener.java`
`@TransactionalEventListener(AFTER_COMMIT)` handlers: `onBookingCompleted` (→ `EarnEngine`) and
`onBookingCancelled` (→ proportional **reversal** of earned + redeemed points using the cancel's
refund ratio). Running AFTER_COMMIT means loyalty side effects can never roll back a booking and
only fire on a committed booking — the zero-risk coupling described in
[ARCHITECTURE.md §8.5](../../ARCHITECTURE.md).

---

## config/ & constants

### `config/LoyaltyBindingAutoSeeder.java`
`ApplicationRunner` — at startup, ensures the default program, tier ladder, and binge bindings
exist (auto-binds new binges) so loyalty works out of the box.

### `LoyaltyV2Constants.java`
Central codes: `DEFAULT_PROGRAM_CODE="SK_MEMBERSHIP"`, `DEFAULT_REDEMPTION_RATE=100`, the tier
codes (`BRONZE/SILVER/GOLD/PLATINUM/LIFETIME_PLATINUM/...`), the ledger entry-type constants
(`LEDGER_EARN/REDEEM/...`), and the `PERK_*` handler keys.

## Tests (`src/test/loyalty/v2/`)
`EarnEngineTest`, `RedeemEngineTest`, `ExpiryEngineTest`, `TierEngineTest`,
`PointsWalletServiceTest` (FIFO + idempotency), `GuestShadowServiceTest` — covering the earn math,
redemption caps, lot expiry, tier promote/demote, and guest merge.

---

> **Booking-service module complete** — entities ([06a](06a-booking-entities.md)), services
> ([06b](06b-booking-services.md)), controllers ([06c](06c-booking-controllers.md)), schedulers/
> listeners/config ([06d](06d-booking-schedulers.md)), and loyalty-v2 (this doc).
