# Change record — 2026-07-21

Country-aware payment methods, Stripe Connect, MFA hardening, mandatory venue country,
and binge-form default fixes.

> **Verification status.**
>
> Verified:
> * Backend compiles and **all tests pass** in a `maven:3.9-eclipse-temurin-17-alpine`
>   container across `common-lib`, `auth-service`, `payment-service`,
>   `booking-service`, `api-gateway` — booking 430 passed / 6 skipped, payment 93
>   passed, 0 failures, `BUILD SUCCESS`.
> * Frontend: **41 test files / 359 tests pass** (incl. the new
>   `bookings.test.js`), and `npm run build` produces a clean production bundle
>   (tests alone do not prove the bundle compiles).
> * All three migrations **executed against a real PostgreSQL 16**, with the V79
>   backfill result asserted (USD→US, blank→IN, ambiguous EUR→IN fallback,
>   lowercase `in`→`IN`, existing `AE` untouched) and the NOT NULL + CHECK
>   constraints confirmed to reject bad rows.
> * Changed frontend files parse under esbuild; hook order, imports and the
>   `adminService` endpoint wiring checked by hand.
>
> NOT verified — do this before trusting the payment paths:
> * **No end-to-end payment has ever run**, not even against Stripe test keys.
> * No browser test of the Payment Element or the Connect onboarding panel.
> * Migrations have not run against production-shaped data (only stub tables).
>
> Two test failures were found and fixed during this work: `BingeRepositoryGeoTest`
> (the only booking test that truly persists a `Binge`, broken by `country NOT NULL`)
> and `PaymentControllerAuthzTest` (a `@WebMvcTest` slice needing `@MockBean`s for
> the two new controller collaborators).

---

## 1. Payment methods now follow the VENUE's country

**Problem.** `PaymentPage.jsx` rendered a hardcoded four-option `<select>`
(defaulting to UPI) for every venue on earth. A US customer paying a Mumbai venue
and an Indian customer paying a New York venue saw the identical list, and the
backend never validated the choice. Separately,
`PaymentProviderRegistry.resolveForCurrency` — the existing multi-gateway seam —
was **dead code**: `PaymentService` called `RazorpayGatewayClient` directly.

**Design.** Resolution is an intersection of two independent facts:

| Side | Source | Question it answers |
|---|---|---|
| Demand | `payment/method/PaymentMethodCatalog` | What do customers in this market expect? |
| Supply | `PaymentProvider.supportedMethods(country)` | What can this gateway actually charge? |

`PaymentMethodResolver` intersects them (catalog order preserved, so the first
survivor is the UI default) and picks the gateway via `resolveForCurrency`.
Intersecting is what prevents both failure modes: offering UPI to a US venue (the
gateway would reject it) and offering nothing because a market's preferred rail is
unsupported.

**Why venue-country and not customer-country:** the charge settles in the venue's
country and currency, so the venue's rails are the real constraint. This is also
why Stripe uses *direct charges* (below).

**Wiring.** `bingeCountry` added to booking-service's
`GET /internal/bookings/internal/amount/{ref}` → `BookingAmountClient.BookingSnapshot`
→ new `GET /api/v1/payments/methods/{bookingRef}` → `PaymentPage.jsx` renders from
it. `PaymentService.reserveInitiatedPayment` re-enforces the same resolution, so
the UI and the guard cannot disagree.

**Enforcement is deliberately scoped** to customer checkout with a *known* venue
country. Admin/offline CASH is exempt, and legacy null-country venues stay
permissive — the catalog fallback there is a guess, not an authority.

**Regression guarded:** a venue with a null `country` would have resolved to
card-only, silently stripping UPI from existing Indian venues. The resolver now
infers country from the settlement currency (always populated). Covered by
`PaymentMethodResolverTest`.

## 2. Stripe Connect (direct charges)

Hand-rolled over `RestClient` in the same style as `RazorpayGatewayClient` — **no
`stripe-java` SDK**, keeping one HTTP idiom and no extra supply-chain surface.

- `StripeGatewayClient` — payment intents, refunds, account creation, onboarding
  links, webhook signature verification (HMAC over the **raw** body, with a replay
  tolerance window).
- `StripePaymentProvider` — implements the existing `PaymentProvider` contract.
- `PaymentConnectedAccount` + repository + migration **payment V16**.
- `ConnectedAccountService` — onboarding lifecycle and capability sync.
- Endpoints: `POST /payments/admin/connect/onboard`, `GET /payments/admin/connect/status`,
  `POST /payments/webhooks/stripe` (already public at the gateway via the
  `/api/v1/payments/webhooks/` prefix).

**Direct charges on the connected account** is the load-bearing choice: the charge
happens on an account domiciled in the *venue's* country, which is the only way
local rails (UPI for an Indian venue) are available to a customer sitting
elsewhere. A platform-account charge could only ever offer the platform country's
methods. The platform's cut rides along as `application_fee_amount`.

**Dormant unless configured.** With `STRIPE_SECRET_KEY` empty,
`StripePaymentProvider.supportedCurrencies()` returns empty, so the registry never
routes to it and behaviour is byte-for-byte the previous Razorpay path.

**`chargesEnabled` gates checkout** — an account exists the moment it is created
but cannot take money until KYC completes; routing to it earlier would fail at the
final confirm.

### Checkout, settlement and admin UI (completed in a second pass)

- **Stripe Payment Element** in `PaymentPage.jsx`. Stripe.js loads from Stripe's
  CDN rather than npm — self-hosting or bundling it breaks PCI compliance, and it
  conveniently avoids a lockfile change. The Element is initialised against the
  venue's connected account (`stripeAccount`), without which it would neither find
  the intent nor offer the venue's rails.
- **Webhook now settles payments** (`PaymentService.settleStripeIntent`). This is
  the authoritative path: Stripe's browser redirect carries no signature, so a
  customer closing the tab mid-redirect would otherwise leave a paid booking stuck
  `INITIATED`. Deduplicated (Stripe retries aggressively) and idempotent on
  terminal states. `confirmPayment` in the browser only drives the UI.
- **Connect onboarding panel** in the binge edit form, showing connected /
  onboarding-incomplete / ready, since an account can exist yet still be unable to
  charge.
- **Refunds route by provider.** `Payment.providerName` now records which gateway
  took the money (the `provider_name` column has existed since V9 but was never
  mapped or written). Stripe refunds go back through Stripe, naming the connected
  account — a platform-scoped refund would not find a direct charge at all.

**Two live bugs found and fixed while wiring this up:**

1. `initiatePayment` gated real gateway calls on `razorpayKeyId != null`. A
   Stripe-only deployment would have skipped the gateway entirely and returned a
   fabricated local order id — bookings would look initiated while no money was
   ever requested. Now gates on *any* configured gateway.
2. `MfaThrottleService` had to become a separate bean: the failure counter was
   being incremented inside the caller's transaction, and since a failed attempt
   ends with login throwing, the rollback would have discarded every increment and
   left the throttle permanently disarmed.

## 3. MFA hardening (auth **V20**)

Three real defects fixed:

1. **TOTP secret was stored in plaintext.** Any read of `users` (dump, backup,
   SQL injection, a broad support query) let an attacker mint valid 2FA codes for
   every enrolled admin, indefinitely and invisibly. Now AES-256-GCM via
   `SecretCipher`. Legacy unprefixed values still decrypt as plaintext and are
   re-encrypted on next write, so no downtime migration is needed.
2. **No rate limiting on verification.** A 6-digit code with a ±1-step window
   leaves 3 valid codes per million at any instant — brute-forceable given
   unlimited attempts. Now capped via `MfaThrottleService`.
   *Subtlety:* the counter must commit in its **own** transaction
   (`REQUIRES_NEW`). A failed attempt ends with the caller throwing, which would
   otherwise roll the increment back and leave the throttle permanently at zero.
3. **Recovery codes were echoed back by the client for storage** — whatever the
   client sent became the account's recovery codes, so an XSS or MITM could plant
   a known set and keep permanent access. They are now generated, hashed and
   stored server-side at enrolment; `MfaConfirmRequest.recoveryCodes` is
   `@Deprecated` and ignored.

Also: **disabling 2FA now requires the account password** as well as a valid code.
A live session (stolen cookie, unlocked laptop) must not by itself be enough to
strip the second factor.

## 4. Venue country is mandatory (booking **V79**)

Country is load-bearing, not descriptive: it derives currency, seeds the timezone,
selects tax rules, and now decides payment methods. It was optional, so a venue
could silently inherit INR / Asia-Kolkata / card-only.

- `BingeSaveRequest.country` → `@NotBlank` + ISO-3166 pattern; entity column
  `nullable = false`; frontend validates before submit.
- **V79** backfills legacy NULLs from currency (unambiguous 1:1 inversions only —
  EUR is excluded because it maps to twelve countries and guessing would misfile a
  tax jurisdiction), defaults the remainder to `IN` (their existing behaviour),
  then enforces `NOT NULL` + a `CHECK` constraint.

**Operator action:** review rows the fallback touched —
`SELECT id, name, currency FROM binges WHERE country = 'IN';`

## 5. Binge create/edit form fixes

- **Payment currency no longer shows INR by default.** `currencyForCountry(undefined)`
  returned the `FALLBACK`; it now shows "Not set yet" until a country is entered.
- **Timezone no longer defaults to Asia/Kolkata.** It was hardcoded in `emptyForm`
  *and* silently re-applied in the submit payload (`form.timezone || 'Asia/Kolkata'`).
  Both removed; now auto-derived from the address and validated as required.
- **WhatsApp space reclaimed** — the always-visible full-width "Support WhatsApp
  (if different)" field renders only when the support phone isn't already
  WhatsApp; both checkboxes are compact 16px inline controls.

---

## Migrations added

| Service | Version | Purpose |
|---|---|---|
| booking | `V79__binge_country_required.sql` | Backfill + enforce venue country |
| auth | `V20__mfa_hardening.sql` | Widen `mfa_secret` for ciphertext; MFA throttle columns |
| payment | `V16__stripe_connected_accounts.sql` | Stripe Connect account per venue |

`mfa_secret` **must** be widened before any new enrolment: ciphertext is ~71 chars
and the old `VARCHAR(64)` would silently truncate and corrupt it.

## New configuration

| Variable | Default | Notes |
|---|---|---|
| `CRYPTO_SECRET_KEY` | derived from `JWT_SECRET` | **Set explicitly before enrolling users.** With the derived fallback, rotating `JWT_SECRET` makes every enrolled TOTP secret undecryptable. |
| `TOTP_MAX_FAILED_ATTEMPTS` / `TOTP_LOCK_MINUTES` | 5 / 15 | MFA brute-force throttle |
| `STRIPE_SECRET_KEY` | empty (dormant) | Enables the Stripe provider |
| `STRIPE_WEBHOOK_SECRET` | empty | **Required when Stripe is on** — without it every webhook is 403'd and accounts never flip to chargeable after KYC |
| `STRIPE_APPLICATION_FEE_BPS` | `0` | Platform commission, basis points |
| `STRIPE_ONBOARDING_RETURN_URL` / `_REFRESH_URL` | `/admin/binges` | Where Stripe returns the owner |

## "Past Visits" counted cancelled / failed bookings (reported bug)

The customer dashboard's **Past Visits** and **Completed visits** tiles — and the
same badge in the Account Centre — counted *every* row from `/bookings/my/past`,
which includes CANCELLED, NO_SHOW and lapsed/unpaid past-dated bookings. A
cancelled booking or one whose payment failed was being counted as a "visit".

Root cause is a semantic mismatch, not a query bug: `/my/past` is a booking-HISTORY
feed and *must* return those rows — My Bookings and the Payments page rely on it.
So the fix belongs in the consumers, not the endpoint. New `utils/bookings.js`
holds one authoritative definition — a past visit is `COMPLETED`, or `CHECKED_IN`
with a past date (attended but not formally closed) — and Dashboard + AccountCenter
both use it. Total Spend now also requires a visit AND a successful payment (it
previously summed any past booking with a successful payment, so a cancelled-but-
charged or refunded booking inflated it). Covered by `src/test/bookings.test.js`.

Backend admin stats were checked and are already correct — `DashboardStatsDto.completedBookings`
counts `BookingStatus.COMPLETED` explicitly.

## Stripe `charge.refunded` now settles refunds

Previously listed as a gap. Stripe refunds can settle asynchronously (bank rails),
so the immediate API response may say "pending"; the webhook is the authoritative
signal. `charge.refunded` / `refund.updated` now route each carried refund through
the SAME `settleRefundFromGateway` path Razorpay uses (`succeeded`→processed,
`failed`/`canceled`→failed, `pending` ignored until the next event), deduplicated
per refund id. Refunds that settle late no longer sit in PROCESSING until
reconciliation.

## CRITICAL: a REJECTED venue was fully operable

`getManagedBinge` authorized admin binge operations on **ownership only** — it never
checked approval status — and the frontend showed every operational button on a
rejected venue's card unconditionally. So a rejected venue's admin could create
events, edit its dashboard/about page, set cancellation tiers, configure loyalty,
etc. — operate a venue a super-admin had refused. Ownership and the per-module
matrix existed; a **lifecycle-state gate did not**.

Fix — `BingeApprovalInterceptor`, a central, **fail-closed** control: every admin
WRITE targeting a REJECTED binge is refused unless its path is on a small lifecycle
allow-list (edit / delete / re-request / country+timezone requests). Fail-closed is
the point — a *future* operational endpoint is frozen automatically without anyone
remembering to guard it. The binge is resolved from the path (`.../binges/{id}/...`)
or the selected-binge context (event-type create), so both routing styles are
covered. SUPER_ADMIN bypasses (may repair a rejected venue); reads pass (the SPA
needs them to show the edit form + rejection reason); only REJECTED is frozen
(PENDING is still being set up, APPROVED is live). Backed by `BingeApprovalInterceptorTest`
(7 cases). Frontend hides operational buttons on rejected cards — presentation only;
the interceptor is the real boundary.

Verified NOT affected: the customer side already filters to APPROVED+active;
rejection only happens from PENDING (so a rejected venue never had live
events/bookings); admin manual bookings route through `/admin/**` and are covered.

## Super-admin approval: review-and-correct gate

Approval is a marketplace's fraud/quality gate — the one moment a human verifies a
new venue — so the dialog is now review + correct, not a rubber stamp:

- **Review (read-only genuineness signals):** full address + whether it is geocoded,
  public contact (email/phone), owner contact (private), operating hours, submission
  time. All already on `BingeDto`, so no new endpoint.
- **Correctable at approval:** country (with live payment-currency preview),
  timezone (live local-time), tax system on/off, per-admin module access, access
  remarks.

A **country change at approval** re-derives the payment currency and re-seeds the
tax jurisdiction (`ensureDefaultTaxRule`, applied AFTER the country is set so the
derived currency is correct). Doing this at approval is the safe moment: a new
venue's prices/events are created after approval (the grace window), so there are no
existing amounts to rescale. Country + timezone are validated on both sides
(ISO-code + real `ZoneId`), so an approval can never persist a bad value.

Still deferred (stated, not faked): **loyalty participation** at approval — it is a
`LoyaltyBingeBinding` (program + tenant), not a Binge flag, so it needs the program
resolved rather than a boolean toggle. The existing post-approval Loyalty panel
covers it for now.

`approveBinge(id, superAdminId, role, taxesEnabled, disabledModules, accessRemarks,
timezoneOverride, countryOverride)`.

## Binge form: dark mode + timezone permission model

**Dark mode** — the scoped `BingeForm.css` referenced a `--surface` token that does
not exist in this app, so it fell back to white in dark mode, and used a
`prefers-color-scheme` media query the app never triggers (theme is toggled via
`[data-theme="dark"]`). Rewritten to use the app's real tokens (`--bg-input`,
`--bg-card`, `--border`, `--text`, `--text-muted`, `--primary`) which flip with the
theme automatically — no per-theme overrides.

**Timezone is admin-locked, super-admin-resolved.** Per product requirement, a
regular admin can no longer set a venue's timezone by hand:
- The picker is **read-only for admins**; the zone is auto-derived from the address
  (country → state → city). Auto-derivation now runs for *everyone* (it is how a
  locked admin's zone is set at all) and only defers to a super-admin's explicit
  manual pick.
- If the derived zone looks wrong, the admin raises a **timezone review** — a
  mandatory reason (≥5 chars, enforced server-side) plus an optional suggested
  zone. This reuses the existing binge-change-request machinery: a new
  `BingeChangeRequest.Type.TIMEZONE_CHANGE`, a `requestTimezoneChange` service
  method, and a branch in `approveChangeRequest` that applies the super-admin's
  chosen zone (validated as a real IANA `ZoneId`).
- **Super-admins** keep full edit access, and resolve reviews from the "Venue
  change requests" panel with a timezone picker in the approve modal. A delegated
  admin holding a `TIMEZONE_CHANGE` Authority-Handover grant is treated as a
  super-admin for this.

No migration needed — `request_type` is a VARCHAR storing the enum name, so the new
value is just a new string. Endpoints: `POST /admin/binges/{id}/timezone-request`;
the existing approve endpoint now also accepts a `timezone` to resolve a review.

Deliberately used the **address**, not browser geolocation, as the derivation
source: an admin often sets up a venue they are not physically at, so "current
location" would frequently be wrong.

## Self-audit findings (fixed)

A review pass over the above found seven defects, five of them introduced by this
work. Recorded because each is a class of mistake worth recognising, not just a
line to patch.

1. **CSRF blocked the Stripe webhook.** `CsrfProtectionFilter.WEBHOOK_PATHS` is an
   explicit allow-list and contained only `…/webhooks/razorpay`. Symptom would have
   been invisible from the customer side: Stripe accepts the payment, every webhook
   is rejected, connected accounts never become chargeable and nothing ever
   settles. *Lesson: a new endpoint has to be traced through existing middleware,
   not just its own handler.*
2. **Connect onboarding was decorative.** Razorpay is the default provider and
   settles INR/USD, so `resolveForCurrency` never chose Stripe — a venue could
   complete Connect onboarding and still have every charge routed to the platform's
   Razorpay account, with money never reaching its bank. Provider preference is now
   `"stripe"` whenever the venue has a chargeable account, applied identically at
   display, enforcement and charge so the rails shown always match the gateway that
   charges.
3. **Google accounts were locked out of disabling 2FA.** Password re-auth was
   applied unconditionally, but `googleLogin` seeds a random password the user
   cannot know. `lastPasswordChangeAt` (set by `register`, never by `googleLogin`)
   now distinguishes them; for password-less accounts the authenticator code stands
   alone and the event is logged.
4. **Stripe idempotency keyed on `bookingRef`.** A second legitimate attempt at a
   different amount (partial payment, balance changed after a refund) reuses the
   key, and Stripe rejects a reused key with different parameters — checkout would
   hard-fail. Now keyed on the per-attempt `transactionId`.
5. **Country-change approval wrote the requested value raw** — no uppercasing.
   Creation normalises, but a legacy row would fail the new V79 CHECK and surface
   as a 500 on an admin's approval click. Now re-normalised and validated locally.
6. **Decrypt failure surfaced as a bare 500.** A CRYPTO_SECRET_KEY mismatch affects
   every enrolled user; as a 500 it looked like "wrong code" and users would retype
   forever. Now a 503 naming the configuration cause.
7. Stuck spinner if the Stripe panel unmounted between render and effect.
8. **IDOR on `GET /payments/methods/{bookingRef}`.** The endpoint this work added
   took no caller identity at all, so any authenticated user could probe arbitrary
   booking references — confirming which exist and reading the venue's country and
   currency for bookings that were not theirs. It now applies the same SEC-011
   owner check as initiation (customers restricted to their own bookings; staff
   exempt). *Lesson: a new read endpoint inherits none of the authorisation its
   neighbours have — the check has to be added deliberately.*

A second audit round found three more:

9. **`payment-service` would not have started.** V16 declared `country CHAR(2)` while
   the entity maps `@Column(length = 2)` → VARCHAR. Hibernate runs with
   `ddl-auto=validate`, which reports a CHAR column as `bpchar` and fails the
   mismatch, so the service refuses to boot. It was the only `CHAR` in any payment
   migration and contradicted the existing `binges.country VARCHAR(2)` convention.
   Verified by applying the migration to real Postgres and diffing all ten columns
   against the entity mapping.
10. **No duplicate-capture guard on the Stripe settlement path.** The Razorpay
    callback detects a booking that was already fully collected and auto-refunds
    the surplus; `settleStripeIntent` simply marked SUCCESS. This became reachable
    *because of* fix #4 above: moving idempotency from per-booking to per-attempt
    (needed for partial payments) also allows two intents to succeed for one
    booking, and the balance check at initiation cannot see money still in flight.
    Stripe now has parity — the capture is recorded for ledger truth and refunded.
11. **New secrets missing from the K8s External Secrets manifest.** Both degrade
    gracefully (Stripe dormant, crypto key derived from JWT_SECRET), so nothing
    breaks — but Stripe would silently never work in production. Deliberately
    documented as `vault kv patch` commands rather than added as `remoteRef`
    entries: External Secrets fails the ENTIRE sync when a referenced property is
    absent from the store, which would take out every service sharing that Secret.
    No per-service manifest change is needed because deployments use
    `envFrom: secretRef`.

Checked and confirmed NOT affected: binge seeders (`DataSeeder` already sets
`country`), other binge-save callers (only the two `@Valid` endpoints),
`PaymentMethodResolver` (returns empty rather than throwing, so adding it to the
charge path introduced no new failure mode), `BookingAmountClient.fetchSnapshot`
(catches internally), gateway role-gating for `/payments/admin/connect/**`, and
provider selection for existing INR venues (unchanged — enabling Stripe does not
hijack them).

## Known gaps / follow-ups

1. **No end-to-end payment test.** Stripe has never been exercised against real
   (even test-mode) credentials. Before trusting it: set test keys, onboard a
   venue, run a booking through with Stripe's `4242…` card, and confirm the
   webhook flips the payment to SUCCESS.
2. **Webhook endpoint must be reachable** for Connect to work at all — accounts
   only become chargeable via `account.updated`. Locally that means
   `stripe listen --forward-to localhost:8090/api/v1/payments/webhooks/stripe`.
3. **`charge.refunded` is not handled** — asynchronous refund settlement for
   Stripe relies on the immediate API response. Refunds that settle later stay
   PROCESSING until reconciliation.
4. **Session revocation** is correct in source but was never deployed — the
   running containers predate it. Rebuild, then verify.
5. **Wallet rails are omitted** from the Stripe method mapping (market-specific
   and needing extra activation); sending an unsupported
   `payment_method_types` value is a hard 400 at checkout, so they were left out
   rather than guessed.
