# Loyalty overhaul — flexible redemption, country-driven point value, per-binge governance

**Date:** 2026-07-24
**Scope:** booking-service (loyalty v2) + frontend (customer booking + admin/super-admin loyalty)
**Migration:** `V80__loyalty_binge_admin_config_lock.sql`
**Tests:** booking-service full suite green — 439 passed, 0 failed, 6 skipped.

This delivers four things the customer/admin experience was missing, following how
production loyalty programs (Marriott Bonvoy, airline award charts) actually work:
a **flexible redemption slider**, **country-authoritative point value**, a
**super-admin governance lock** on per-binge loyalty, and **inline field help**.

---

## 1. Flexible point redemption at booking (customer)

**Before:** a bare number box + "Use all" button — no sense of how many points were
actually usable, no live value.

**Now:** a **slider** the customer drags to choose exactly how many points to apply,
bounded by what's genuinely redeemable on that booking, with a synced number input,
"Use max" / "Clear", and a live "−₹X off · N pts applied" readout. Below the max it
shows the venue's per-point value ("100 pts = ₹1 at this venue · min 100 pts").

- New endpoint `GET /api/v2/loyalty/me/redeem-max?bingeId&bookingAmount` →
  `{ eligible, maxPoints, maxDiscount, pointsPerCurrencyUnit, minRedemptionPoints, reason }`.
  The ceiling is `min(wallet balance, points to hit the booking's max-redeemable cap)`,
  so the slider can never request more than is redeemable.
- `RedeemEngine.maxRedeemable(...)` computes it (read-only, no ledger write).
- Frontend: `BookingWizard` fetches the ceiling when the **bill** changes (not when the
  chosen points change, so dragging never refetches) and clamps the chosen points if the
  bill shrinks. `StepReview` renders the slider (`BookingPage.css` — themed track/thumb).

## 2. Country-authoritative, live point value (super-admin)

**Deep-research model:** *award chart + property override*. A point is valued by the
**venue's country**, not the customer's — book a US venue, burn at the US rate; an Indian
venue, the INR rate — regardless of where the customer is from.

- New resolver `LoyaltyConfigService.resolveEffectiveRedemption(bindingId, bingeId, at)`
  returns `EffectiveRedemptionTerms` in priority order:
  1. **per-binge override** (`LoyaltyBingeRedemptionRule`) — an admin explicitly set a rate;
  2. **venue-country config** (`LoyaltyCountryEarnConfig`, live) — the default;
  3. **program default** (100 pts / unit).
- `RedeemEngine.compute(...)` now consumes those terms (both quote and burn, so checkout
  matches the preview). `NO_REDEEM_RULE` rejection is gone — redemption always resolves a value.
- **Live, not copied:** the auto-seeder **no longer stamps a per-binge redemption rule** on
  new binges. A venue with no override tracks its country value automatically, so editing a
  country rate in the Loyalty Center **instantly re-prices** every inheriting venue. (Earn
  rules are still seeded — the earn engine has no country fallback.)
- Existing binges keep their rules untouched; an admin can **"Reset to country default"**
  (`POST /admin/bindings/{id}/redeem-rule/reset`) to opt a venue into live inheritance.
- Super-admin **Countries** tab: copy updated to reflect the live/authoritative behavior
  and a per-1,000-points value readout added.

## 3. Super-admin governance lock on per-binge loyalty (super-admin)

**Before:** any binge admin could enable/disable loyalty for their venue and freely change
its earn/redeem economics — no oversight.

**Now:** a super-admin lock, mirroring the goodwill-budget pattern.

- New column `loyalty_binge_binding.admin_config_locked` (default **FALSE** — existing
  behavior preserved). Entity field `adminConfigLocked`.
- Super-admin toggle `POST /super-admin/bindings/{id}/config-lock {locked}`, surfaced as a
  **Config lock** column in Loyalty Center → Binges (🔒 Locked / Self-service).
- Fail-closed enforcement in `LoyaltyV2AdminController.assertMayConfigure(...)` on
  **enable / disable / earn-rule / redeem-rule / reset / perk-override** — a locked binding
  403s a regular admin; super-admins always pass.
- The per-binge panel shows a read-only **"Managed by the super admin"** banner and disables
  every write control when locked.

## 4. Inline field help + gaps (per-binge loyalty panel)

- Every earn/redeem field now has a **"?" help circle** (hover/focus/tap tooltip, theme-aware,
  `BingeLoyaltySection.css`) explaining exactly what it does, in the venue's own currency.
- The redemption section shows the **effective terms + source** ("Inheriting IN default",
  "Custom rate for this venue", "Using the program default") via
  `GET /admin/bindings/{id}/effective-redeem`, plus the Reset action.

---

## Files

**Backend**
- `loyalty/v2/service/LoyaltyConfigService.java` — `resolveEffectiveRedemption` + `EffectiveRedemptionTerms` (country/default fallback).
- `loyalty/v2/engine/RedeemEngine.java` — consume effective terms; `maxRedeemable` + `RedeemMax`.
- `loyalty/v2/controller/LoyaltyV2CustomerController.java` — `GET /me/redeem-max`.
- `loyalty/v2/controller/LoyaltyV2AdminController.java` — config-lock enforcement; `redeem-rule/reset`; `effective-redeem`.
- `loyalty/v2/controller/LoyaltyV2SuperAdminController.java` — `POST /bindings/{id}/config-lock`.
- `loyalty/v2/service/LoyaltyAdminService.java` — `retireRedemptionRule`.
- `loyalty/v2/entity/LoyaltyBingeBinding.java` — `adminConfigLocked`.
- `loyalty/v2/config/LoyaltyBindingAutoSeeder.java` — stop seeding redemption rules (inherit live country config).
- `repository/BingeRepository.java` — `findCountryById`.
- `db/migration/V80__loyalty_binge_admin_config_lock.sql`.
- `test/.../RedeemEngineTest.java` — effective-terms stubbing, country-fallback + maxRedeemable cases.

**Frontend**
- `components/booking/StepReview.jsx` + `pages/BookingPage.css` — redemption slider.
- `components/booking/BookingWizard.jsx` — redeem-max fetch + clamp.
- `components/admin/BingeLoyaltySection.jsx` + `.css` — help tips, lock banner, effective terms, reset.
- `pages/AdminLoyaltyCenter.jsx` — Config-lock cell, live-country copy, per-1,000-pt value.
- `services/loyaltyV2.js` — `getRedeemMax`, `getEffectiveRedeem`, `resetRedeemRule`, `setBindingConfigLock`.
