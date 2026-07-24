# Money Scale & Rounding Contract (DATA-006)

**Status:** Authoritative. Any new finance column, DTO, or calculation MUST conform to
this contract. Supersedes ad-hoc per-table scale choices.

## Why this exists

Finance tables were authored at different times and carry mixed numeric scales
(`NUMERIC(10,2)`/`(12,2)` on `bookings` vs `NUMERIC(14,4)` on snapshots / invoices /
ledgers; FX at `(18,8)` vs `(20,10)`). Mixed scales are not a bug by themselves —
but without a written contract they invite sub-cent reconciliation drift and
display-vs-charge divergence. This document pins the intended precision and the
rounding rule **at each boundary**, so the scales are deliberate and verifiable.

## The three boundaries

Money flows through three boundaries, each with a different precision requirement:

| Boundary | Purpose | Scale | Rounding rule |
|---|---|---|---|
| **1. Computation** | Intermediate pricing math (base × hours, guest add-ons, surge multipliers, tax, discounts) | Full `BigDecimal` precision in memory; **no premature rounding** | Round only where the canonical formula says to (see below) |
| **2. Charge / customer-facing** | The amount actually charged, refunded, displayed, and stored on `bookings` and payment rows | **Currency minor units** — 2 dp for INR/USD/EUR, 0 dp for JPY, 3 dp for KWD/BHD | `HALF_UP` to the currency's minor-unit scale |
| **3. Ledger / audit** | Immutable financial records: price snapshots, invoices, credit notes, `ledger_entries`, loyalty points ledger | `NUMERIC(14,4)` (4 dp) — carries sub-cent components (per-unit tax, proportional splits) without loss | Store the computed value at 4 dp; never re-round a persisted ledger row |

**Rule of thumb:** round **once**, at the charge boundary (2), using the currency's
minor-unit scale. Everything upstream (1) stays full-precision; everything downstream
(3) preserves ≥ the charge precision so audit never disagrees with what was charged.

## Canonical computation rounding (PRICE-001)

The single source of truth for assembling a charge is
`PricingService.computeBaseAmount / computeGuestAmount / applySurge`. These already
encode the intended intermediate rounding:

- **Base amount:** the hourly component is rounded to 2 dp `HALF_UP` **before** the
  flat base is added (matches `PricingMathTest`).
- **Guest amount / surge:** applied on the rounded base; the final charge is rounded
  to the currency minor unit at boundary (2).
- Currency minor-unit conversion for the gateway (e.g. paise, cents, or 3-dp fils)
  lives in the payment layer and uses the currency's exponent — never a hardcoded
  `× 100`.

Do **not** re-inline this formula anywhere; call the canonical methods so the rounding
stays identical across all five pricing paths (customer create, admin create,
reschedule, quote/display, snapshot).

## FX rates

| Use | Scale | Rounding |
|---|---|---|
| Stored FX rate (reference/lock) | `NUMERIC(18,8)` minimum | store as provided by the FX source, no rounding |
| Legacy `(20,10)` columns | acceptable — higher precision than the floor; do not down-scale | — |
| Converted **amount** | round the *result* to the target currency's minor unit at boundary (2) | `HALF_UP` |

Rates are never the charge; only a **converted amount** is rounded, and only once, at
the charge boundary. (Note: the FX-lock/checkout-preview surface was removed under
PRICE-002 — pricing is native per-binge currency. These FX rules apply to any future
multi-currency reporting, not to an active checkout path.)

## Reconciliation tolerance

Cross-table reconciliation (e.g. sum of ledger lines vs the charged total) MUST agree
to **the currency minor unit** (0.00 for 2-dp currencies). Any residual larger than
half a minor unit indicates a rounding-rule violation — treat as a defect, not noise.
A reconciliation-delta alert at this threshold is the recommended monitor.

## Checklist for new finance code

1. Compute in full `BigDecimal` precision; do not round intermediates except per the
   canonical PricingService methods.
2. Round to the **currency minor unit** (`HALF_UP`) exactly once, at the charge boundary.
3. Persist audit/ledger rows at `NUMERIC(14,4)`; never re-round an existing ledger row.
4. New columns: 2 dp for a customer-facing charge, 4 dp for a ledger/snapshot value,
   ≥ 8 dp for an FX rate.
5. Add a rounding-boundary test (see `PricingMathTest`) for any new formula.
