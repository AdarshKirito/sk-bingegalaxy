# 21 — Integrations, OTA and Global Readiness (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; **zero provider calls executed this run**

## Payment providers

| Provider | Integration depth | Proof status |
|---|---|---|
| Razorpay | Orders, callbacks, webhooks (HMAC), refunds, disputes | Code-complete; **sandbox proof outstanding (PR-PAY-01, P0 gate)** |
| Stripe Connect | Onboarding, connected accounts, webhooks, refunds | Code-complete; same gate |
| Simulation | Dev-only; production fail-fast (@PostConstruct) | ✅ safe by default |

Method selection is **venue-country driven** (PaymentMethodResolver; V77-V79 era) — correct model for a multi-country venue platform.

## Notification providers

| Channel | Status |
|---|---|
| Email (SMTP) | Wired; production SMTP creds required at deploy |
| WebPush | VAPID keypair (rotated 2026-07-13); wired |
| Webhooks | Outbound with retry |
| SMS / WhatsApp | **Mocks** — launch checklist mandates integrate-or-hide (INT-01, P2) |

## Global readiness

| Concern | Status |
|---|---|
| Multi-currency | CurrencyRate FX + super-admin /admin/currencies console; minor-unit longs make FX rounding deterministic ✅ |
| Timezones | Venue-timezone driven scheduling; change-request governance; /admin/venue-timezones console ✅ |
| Payment methods per country | ✅ (above) |
| Loyalty per country | LoyaltyCountryEarnConfig (V80 lock) ✅ |
| i18n/l10n | 🔴 **UI is English-only** — no i18n framework in frontend (GLB-01, P3 unless target markets require it) |
| Tax rules | TaxRule engine per venue; disabled-tax-rule set at approval ✅ |

## OTA / channel managers

None present (no Booking.com/Expedia-style channel integration) — not claimed anywhere, no gap vs docs. If OTA distribution is on the roadmap, the event fabric (producer-only lifecycle topics) is the natural attach point — see doc 12.

## PWA update path ("OTA" for the frontend)

vite-plugin-pwa `autoUpdate`: new SW activates on next visit; `/api` NetworkOnly prevents stale authenticated data. No forced-refresh UX for long-lived tabs (GLB-02, P3 — add "new version available" toast).

## Risks (register refs)

PR-PAY-01 (P0 gate) · INT-01 (P2) · GLB-01/02 (P3)
