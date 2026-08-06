# Distribution Console — Corrected Design (v2)

> **Supersedes [03-ADMIN-UX-DESIGN.md](03-ADMIN-UX-DESIGN.md).** Research date **2026-08-02**.
> Design only — no production code. Returned for approval before implementation.

**Evidence labels:** `[SOURCE-CODE EVIDENCE]` `[OFFICIAL PROVIDER EVIDENCE]` `[ARCHITECTURAL INFERENCE]` `[BUSINESS ASSUMPTION]` `[UNVERIFIED]` `[RECOMMENDATION]`

---

## 0. What was wrong with v1, and what was not

The critique is **substantially correct**. Three of its findings are not stylistic — they are contradicted by evidence that was already in my own research file, which makes them design errors rather than differences of opinion.

### 0.1 Errors — conceded, with the evidence that proves them

| # | v1 said | Evidence it contradicts | Consequence |
|---|---|---|---|
| **E1** | One flat "channel" concept; Bókun, Viator and Google shown as peers in one list | Bókun is a **channel manager** whose live sync covers *Viator, GetYourGuide, Expedia Local Expert*, with others via its marketplace `[OFFICIAL PROVIDER EVIDENCE]` | A booking arrives **through** Bókun but is **sold on** Viator. v1 could not express that, so commission, support ownership and attribution were all unrepresentable |
| **E2** | Google shown in "Recent channel bookings" as `GG-1190 … ₹6,200` | Things to Do is **SFTP JSON feed + deep link**; *"users complete transactions independently on partner sites"*; **no reservation flows back** `[OFFICIAL PROVIDER EVIDENCE]` | **That reservation does not exist.** A Google conversion is a canonical SK Binge booking with an attribution source. v1 invented an inbound object |
| **E3** | Consent copy: *"You collect payment as normal. Bókun takes its commission separately."* | **Viator is merchant of record** — collects the full traveller payment, holds it as *deferred merchant payables*, pays the operator the **net rate after the experience**. **GetYourGuide is Merchant of Record** as commercial agent, paying **retail − 20–35% commission** monthly `[OFFICIAL PROVIDER EVIDENCE]` | v1 would have told venue operators to expect cash at checkout **that never arrives**. This is the most damaging error: it misstates a venue's cash flow |

Also conceded, less severe:

- **E4 — "syncs every N minutes" is wrong for every provider researched.** Viator/OCTO are **pull** (real-time availability check); GetYourGuide fetches on a schedule (default ~every 8 days for 365 days); Google is a feed (≥ every 30 days or products are removed). `[OFFICIAL PROVIDER EVIDENCE]` Periodic push is a *fallback*, not the model.
- **E5 — capability-blind UI.** v1's "Offer 19:00 instead" button assumes amendment/counteroffer support. Viator's Supplier API has *booking amendment*; Google has no reservation at all. Offering an action a connector cannot perform is a defect.
- **E6 — listing readiness too narrow.** Validating buffers/durations/price ignores the content each destination actually requires to publish.
- **E7 — "paste an API key" as the universal connection screen.** Google Things to Do requires an **interest form + content licence agreement + SFTP**, not a key paste. Actions Center uses **basic auth rotated 6-monthly**. `[OFFICIAL PROVIDER EVIDENCE]`

### 0.2 Where the critique is only partly right — and what I am keeping

I am not conceding these wholesale, because the evidence supports them:

| Assumption | Verdict |
|---|---|
| **Venue is supplier of record (B2)** | **KEEP — evidence is strong.** GetYourGuide: *"resellers, aggregators, online travel agencies … are not accepted"* as supply partners `[OFFICIAL PROVIDER EVIDENCE]`. SK Binge is an aggregator of third-party venues; it cannot sign as supplier. It occupies the **restech** seat, exactly as Bókun/Ventrata/Peek do. This is not a limiting assumption — it is the only lawful structure |
| **Instant-book only (B4)** | **KEEP as a rollout scope.** `REQUEST_TO_BOOK` needs an operator response SLA and auto-decline timer that do not exist `[SOURCE-CODE EVIDENCE]`. Shipping on-request inventory without them produces expired reservations and channel penalties. The capability model below *represents* it; the platform does not *enable* it yet |
| **≤4 durations (B5)** | **DEMOTE, don't delete.** Correct as an **SK Binge UX default**; wrong as a domain maximum. Now `min(SK Binge default, provider.maxProductOptions)` |
| **Agency-payment-only (B3)** | **OVERTURNED.** Not a conservative scope choice — it describes a model Viator and GYG do not offer. The *domain* must be capability-driven; the *rollout* may still restrict which settlement modes are enabled |

**The honest summary:** v1 was a competent UI over an under-modelled domain. The UI conventions survive; the domain beneath them is rebuilt.

---

## 1. Corrected domain terminology

Four distinct concepts, never collapsed:

| Concept | Definition | Examples | Owns |
|---|---|---|---|
| **Connectivity Provider** (`Connection`) | The technical system SK Binge exchanges data with. One credential/authorization. | Bókun account · direct Viator Supplier API · Google Things-to-Do feed · direct OCTO reseller | Credentials, auth state, capabilities, health, rate limits |
| **Sales Destination** (`Destination`) | Where a traveller actually sees and buys the listing. | Viator · GetYourGuide · Klook · Google Things to Do · Civitatis | Commission, content rules, cancellation policy shown to traveller, settlement terms |
| **Listing** (`ProductMapping`) | An SK Binge `EventType` published to one destination via one connection. | "Birthday Celebration" → Viator (via Bókun) | Readiness, external product id, publish state |
| **Booking Source** (`attribution` on the canonical booking) | Where the booking is *attributed*, for reporting and commission. | `SK_BINGE_DIRECT` · `VIATOR` · `GOOGLE_THINGS_TO_DO` · `ADMIN` · `TRAVEL_AGENT` | Revenue attribution |

**The relationship that v1 could not express:**

```
Connection (Bókun)  ──┬──► Destination (Viator)          ──► Listing ──► Reservation
                      ├──► Destination (GetYourGuide)    ──► Listing
                      └──► Destination (Google TTD)      ──► Listing (feed only, no reservation)

Connection (Direct Viator Supplier API) ──► Destination (Viator) ──► Listing ──► Reservation
```

A reservation therefore records **both**: `deliveredVia = bokun` and `soldOn = viator`. `[ARCHITECTURAL INFERENCE]` Without both, you cannot answer "which commission applies", "who does the traveller phone", or "which connection do I pause".

**Booking-source truth:** `[RECOMMENDATION]` a canonical booking carries `origin` (already built, V85: `DIRECT|ADMIN|CHANNEL`) **plus** `attributionSource`. Google conversions are `origin = DIRECT` with `attributionSource = GOOGLE_THINGS_TO_DO` — *not* `CHANNEL`.

---

## 2. Provider capability model

`[RECOMMENDATION]` Every connector declares capabilities. **The UI renders from these; it never hardcodes provider names.**

```
ConnectorCapabilities
  ── Reservation delivery ─────────────────────────
     deliversReservations            bool
     supportsModification            bool
     supportsCancellation            bool
     supportsCounterOffer            bool     // "offer 19:00 instead"
     requiresAcknowledgement         bool
  ── Inventory & rates ────────────────────────────
     realTimeAvailabilityCheck       bool     // provider pulls on demand
     availabilityPush                bool
     ratePush                        bool
     inventoryDeltaPush              bool
     feedBased                       bool     // full-replacement feed
     feedMaxStalenessDays            int?
  ── Content ──────────────────────────────────────
     listingContent | images | promotions | messaging | reviews   bool
  ── Commerce ─────────────────────────────────────
     paymentResponsibility           [enum]   // which modes this provider can do
     settlementModel                 [enum]
     supportsVirtualCard             bool
  ── Product shape ────────────────────────────────
     maxProductOptions               int?
     maxDurations                    int?
     supportsVariableDuration        bool
     requiresFixedStartTimes         bool
     inventoryModel                  CAPACITY | UNIT | FREESALE
  ── Access ───────────────────────────────────────
     authMethod    OAUTH | API_KEY | SFTP_FEED | PLATFORM_MANAGED | CONTRACT_ONLY
     sandbox       bool
     certification NONE | SELF | PROVIDER_REVIEWED | PILOT_REQUIRED
```

### Provider differences this makes visible `[OFFICIAL PROVIDER EVIDENCE]`

| | **Bókun** | **Viator (direct)** | **GetYourGuide** | **Google Things to Do** |
|---|---|---|---|---|
| Role | Connectivity provider → many destinations | Connectivity **and** destination | Connectivity **and** destination | Destination, **feed only** |
| Delivers reservations | Yes | Yes (pull: Viator calls us) | Yes (scheduled pull) | **No** |
| Modification / cancellation | Plugin lifecycle: amend, cancel | **Booking amendment**, cancellation | Cancellation | **n/a** |
| Counter-offer | `[UNVERIFIED]` | `[UNVERIFIED]` | `[UNVERIFIED]` | **No** |
| Availability model | Live sync (3 channels) + marketplace | Batch + real-time availability, **booking hold** | Scheduled fetch ~8 days / 365 days | Full feed replace, ≥30 days |
| **Who collects money** | Per contracted destination | **Viator (merchant of record)** — net rate paid **after** experience | **GYG (Merchant of Record)** — retail − 20–35%, monthly | **SK Binge** (traveller lands on our checkout) |
| Auth | API key / custom plugin | API account + **pilot product** | Integrator portal | **SFTP + content licence agreement** |
| Certification | Plugin tested with API team | Dev → account-manager testing → pilot → launch | Gated; **aggregators refused as supplier** | Interest form + licence |

**This table is the whole argument for capability-driven UI.** Four "channels", four different behaviours in every row that matters.

---

## 3. Payment and settlement model

`[RECOMMENDATION]` Replaces the single agency assumption.

**Payment responsibility** (per reservation, resolved from connection + destination contract):
`VENUE_COLLECTS` · `SK_BINGE_COLLECTS` · `CHANNEL_COLLECTS` · `PAY_AT_VENUE` · `VIRTUAL_CARD` · `MIXED_OR_PARTIAL`

**Settlement model:** `COMMISSION_SETTLEMENT` · `NET_RATE_SETTLEMENT` · `DIRECT_SETTLEMENT` *(no third party)*

**Per-reservation financial record** — every field the critique listed, and it is not padding: without them you cannot reconcile a Viator payout, which arrives *after the experience* in a batch:

```
grossBookingValue · taxes · fees
channelCommission · skBingePlatformFee · venueNetAmount
collectedBy (enum) · depositAmount · outstandingBalance
expectedPayoutDate · actualPayoutDate · actualPayoutAmount
refundResponsibility (VENUE|CHANNEL|SK_BINGE)
cancellationChargeResponsibility
settlementStatus     PENDING|EXPECTED|PAID|SHORT_PAID|DISPUTED
reconciliationStatus MATCHED|UNMATCHED|VARIANCE
```

**Hard rule.** `[ARCHITECTURAL INFERENCE]` A `CHANNEL_COLLECTS` reservation must **never** create a Razorpay/Stripe `PaymentIntent`. Payment-service stays the authority for *SK-controlled* money only. Channel money is **recorded, not moved** — a receivable, reconciled against the provider's remittance. Faking it through the existing payment flow would corrupt reconciliation, ledger and refund logic simultaneously.

**Interaction with PR-PAY-01.** `[BUSINESS ASSUMPTION]` The open P0 (no provider-sandbox proof for payment/refund) applies to **SK-collected** money. `CHANNEL_COLLECTS` does not touch it — which, usefully, means Viator/GYG can be integrated **without** waiting on PR-PAY-01, the opposite of what v1's agency model implied.

---

## 4. Synchronisation states

Replace "syncs every N minutes" with named, separately displayed states: `[RECOMMENDATION]`

| State | Meaning | Shown as |
|---|---|---|
| `REALTIME_PULL` | Provider queries us on demand | "Viator checks availability live" |
| `DELTA_PUSHED` | Inventory change pushed after a booking | "Updated 12s ago" |
| `ACK_RECEIVED` | Provider confirmed acceptance | "Confirmed by Bókun" |
| `FEED_PUBLISHED` | Full feed uploaded | "Feed uploaded 4h ago · next due in 26d" |
| `RECONCILED` | Scheduled comparison completed clean | "Reconciled 02:00" |
| `FULL_RESYNC` | Everything re-sent | operator-triggered |
| `STALE` | Beyond the provider's tolerance | ⚠ "Feed is 28 days old — products are removed at 30" |

Displayed per connection: last real-time event · last accepted inventory version · last full reconciliation · **current lag** · staleness warning.

---

## 5. Revised information architecture

Eight surfaces (v1 had five and no room for connections vs destinations):

```
Distribution ▾                                       [module: DISTRIBUTION]
├── /admin/distribution                  Overview
├── /admin/distribution/connections      Connections     ← technical providers
├── /admin/distribution/destinations     Sales Channels  ← where it sells
├── /admin/distribution/listings         Listings        ← mapping + readiness
├── /admin/distribution/reservations     Reservations    ← inbound only
├── /admin/distribution/settlements      Settlements     ← NEW: money
├── /admin/distribution/health           Health & Recovery
└── /admin/distribution/providers        Provider Governance  [SUPER_ADMIN]
```

---

## 6. Screens

### 6.1 Overview

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Distribution — Moonlight Theatre              [ Add a connection ]        │
├────────────────────────────────────────────────────────────────────────────┤
│  ⚠ Google feed is 28 days old. Products are removed at 30.   [ Publish ]   │
├────────────────────────────────────────────────────────────────────────────┤
│  ┌───────────┐ ┌───────────┐ ┌────────────┐ ┌────────────┐                │
│  │ Selling on│ │ Bookings  │ │ Awaiting   │ │ Needs you  │                │
│  │ 3 places  │ │ 14 this wk│ │ payout     │ │ 2 items    │                │
│  │ 2 conns   │ │           │ │ ₹41,300    │ │            │                │
│  └───────────┘ └───────────┘ └────────────┘ └────────────┘                │
│                                                                            │
│  CONNECTIONS                          reaching                             │
│  ● Bókun            Healthy           Viator · GetYourGuide   [ Manage ]   │
│  ● Google feed      ⚠ Stale 28d       Google Things to Do     [ Manage ]   │
│                                                                            │
│  Note: "Awaiting payout" is money Viator/GetYourGuide are holding on your  │
│  behalf. It is not in your bank yet.                     [ Settlements → ] │
└────────────────────────────────────────────────────────────────────────────┘
```

Connections and the destinations they reach are shown as a **hierarchy**, not a flat list. The payout note exists because E3 is the error most likely to cost a venue real money.

### 6.2 Connections — authorization is connector-specific

```
Step 2 of 4 — Connect                          [ Bókun ]

  Bókun uses an API key.        ← rendered ONLY when authMethod = API_KEY
  API key   [ ••••••••••••••• ]   Vendor ID [ 12345 ]
  ✓ Connected. Found 3 products.

──────────────────────────────────────────────────────────────
Step 2 of 4 — Connect                          [ Google Things to Do ]

  Google does not use an API key.     ← authMethod = SFTP_FEED
  Before connecting you need:
    1. An approved Things-to-Do partnership (interest form)
    2. A signed content licence agreement with Google
    3. SFTP credentials issued by Google
  ⓘ SK Binge publishes the feed for you once these exist.
  Status: ⏳ Awaiting Google approval        [ How to apply ▸ ]
```

**Never a raw key box by default.** Credentials remain **write-only** — no read endpoint, masked as `••••1234`, with `[ Replace ]` / `[ Rotate ]`, plus **expiry monitoring** (Actions Center basic-auth rotates 6-monthly) and **sandbox vs production** separation.

### 6.3 Listings — readiness, per destination

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Listings                                                                  │
├────────────────────────────────────────────────────────────────────────────┤
│  EVENT TYPE           VIATOR (Bókun)   GETYOURGUIDE     GOOGLE TTD         │
│  ────────────────────────────────────────────────────────────────────────  │
│  Birthday Celebration ● Live           ◑ 82% ready      ● Live             │
│  Proposal Setup       ◑ 60% ready      ○ Off            ● Live             │
│  Private Screening    ⛔ Blocked        ⛔ Blocked        ⛔ Blocked          │
│     └ No cleanup buffer set — channels could sell back-to-back slots.      │
│       [ Set buffer → ]                                                     │
│                                                                            │
│  Birthday Celebration → GetYourGuide  (82%)                                │
│    ✓ title · descriptions · images(4) · geolocation · durations · capacity │
│    ✗ Meeting point instructions          [ Add ]                           │
│    ✗ Cancellation terms not mapped       [ Map ]                           │
│    ⓘ Cannot go LIVE until every mandatory field for GetYourGuide passes.   │
└────────────────────────────────────────────────────────────────────────────┘
```

Readiness is **per (listing × destination)** because requirements differ per destination. A listing cannot be published below 100% of that destination's mandatory set.

### 6.4 Reservations — actions are capability-gated

```
⚠ Could not accept 1 reservation

Viator · VT-77120 · via Bókun · Birthday · 9 Aug 18:00–21:00 · 8 guests
Rejected: the 18:00 slot was taken 40 seconds earlier by a direct booking.

  [ Reject & notify channel ]  [ Contact customer ]  [ Escalate ]
  ⓘ "Offer an alternative time" is unavailable — this connector does not
     support counter-offers.
```

The disabled action **states why**. v1 showed "Offer 19:00 instead" unconditionally; that button is now rendered only when `supportsCounterOffer`.

**Google never appears here** — it delivers no reservations. Google conversions surface as canonical bookings in `/admin/bookings` tagged `Source: Google Things to Do`.

### 6.5 Settlements — new

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Settlements                        [ Viator ▾ ] [ This month ▾ ]         │
├────────────────────────────────────────────────────────────────────────────┤
│  Collected by Viator     ₹128,400   Commission −₹25,680   Your net ₹102,720│
│  Expected 15 Sep · Viator pays after the experience completes              │
│                                                                            │
│  BOOKING     GROSS    COMM    NET      COLLECTED BY  STATUS               │
│  VT-77120  ₹8,400  ₹1,680  ₹6,720     Viator        Expected 15 Sep       │
│  VT-77004  ₹6,200  ₹1,240  ₹4,960     Viator        ✓ Paid 15 Aug         │
│  VT-76988  ₹7,100  ₹1,420  ₹5,680     Viator        ⚠ Short-paid −₹340    │
│                                                    [ Investigate ]         │
└────────────────────────────────────────────────────────────────────────────┘
```

### 6.6 Health & Recovery / 6.7 Provider Governance

Health mirrors `/admin/recovery`: failed delivery · stale inventory/feeds · reconciliation drift · unmapped products · credential expiry · provider incidents. Governance (super-admin) holds the provider catalogue, declared capabilities, supported countries, certification state, **global provider pause** and cross-binge connection monitoring.

---

## 7. Permissions

| Capability | ADMIN | SUPER_ADMIN |
|---|---|---|
| View Overview / Listings / Reservations / Settlements | ✅ own binge | ✅ cross-binge |
| Create/remove a connection, publish a listing, rotate credentials | ✅ | ✅ |
| Set `distributionClassification`, commission/net rate, provider catalogue, global pause | ❌ | ✅ |

`DISTRIBUTION` joins the V71 module matrix **and the dual-sign-off set** alongside `RATE_CODES`/`SURGE_RULES` — connecting a sales channel changes who may sell the venue's inventory and at what price.

---

## 8. Preserved from v1

Admin declares intent, system reports truth · one canonical booking, one detail page · no second calendar or pricing truth · off by default · banners carry a next action · provider errors translated · **no optimistic success** · per-connection kill switch · global pause · inbox persisted before canonical creation · deep-link to `/admin/bookings` · existing `components/ui` + `.admin-*` CSS · ≤500-line pages, split by route · accessibility and responsive rules.

---

## 9. Migration from v1 · 10. Implementation order

| v1 | v2 |
|---|---|
| `/channels` | split → `/connections` + `/destinations` |
| Google in reservations inbox | removed → attribution on canonical booking |
| Agency-only consent copy | replaced by resolved payment-responsibility per destination |
| "syncs every N minutes" | named sync states |
| Listings = buffers/durations | per-destination readiness checklist |
| — | **Settlements (new)** |

**Order:** 1 provider catalogue + capability model → 2 Connections + connector-specific auth → 3 Listings + readiness → 4 Reservations (capability-gated) → 5 Settlements → 6 Overview → 7 Health → 8 Governance. Capability model first: every later screen renders from it.

---

## 11. Acceptance criteria

1. UI never offers an action whose capability flag is false; disabled actions state why.
2. A reservation records **both** `deliveredVia` and `soldOn`.
3. A `CHANNEL_COLLECTS` reservation creates **no** Razorpay/Stripe intent.
4. Google produces **zero** rows in the reservations inbox; its conversions appear in `/admin/bookings` with `Source: Google Things to Do`.
5. A listing cannot be `LIVE` below 100% of that destination's mandatory fields.
6. Credentials are never returned by any API; expiry is monitored.
7. Sync status shows a named state and lag, never a bare "synced".
8. Settlements reconcile provider remittance against expected net; variances are actionable.
9. Distribution is off by default; every enable is explicit.
10. No second inventory, booking or pricing truth.

---

## 12. Unverified items and commercial blockers

| Item | Status |
|---|---|
| Counter-offer / alternative-time support at Bókun, Viator, GYG | `[UNVERIFIED]` — assume **false** until proven; the UI degrades safely |
| Whether any experiences reseller lists **private venue hire** | `[UNVERIFIED]` — **the single biggest commercial blocker.** Viator's acceptance criteria page returns HTTP 403; GYG's restricted list is silent. **Ask before building connectors** |
| Bókun / Rezdy **restech** commercial terms | `[UNVERIFIED]` — neither publishes them |
| Google Things-to-Do eligibility for a multi-venue restech | `[UNVERIFIED]` — *"Google doesn't currently allow individual operators to upload listings directly"*; SK Binge may qualify as a connectivity partner |
| Virtual-card handling | `[UNVERIFIED]` for these providers; model it, don't implement it |
| **PR-PAY-01** | Open **P0** — blocks `SK_BINGE_COLLECTS` only, **not** `CHANNEL_COLLECTS` |
| GYG supplier eligibility | ⛔ **Confirmed blocker** — SK Binge cannot sign as supplier; the **venue** must, with SK Binge as its reservation system |

---

---

# Pass 2 — adversarial review of the above (2026-08-02)

v2 corrected the domain *vocabulary* but was still a design document, not a buildable
one. A second pass, grounded by re-inspecting the repository, found **ten** gaps. Three
were material enough that implementing v2 as written would have produced a broken
feature.

## 13. Gaps found and closed

### G-A · No data model — **the gap that blocks implementation** `[SOURCE-CODE EVIDENCE]`

v2 named concepts and drew screens but specified no schema. Closing it:

```
provider                 catalogue row, platform-owned, super-admin managed
  code · displayName · providerKind(CONNECTIVITY|DESTINATION|BOTH)
  authMethod · certificationState · supportedCountries[] · active

provider_capability      one row per (provider, capability) — NOT a bitmask;
  provider_id · capability_key · enabled · notes   an unknown key must read as
                                                  false, and notes carry evidence

destination              a sellable marketplace
  code · displayName · operatedByProviderCode · supportedCountries[]

connection               a venue's authorization to ONE provider
  binge_id · provider_code · status · environment(SANDBOX|PRODUCTION)
  credential_ref (secrets-manager pointer, NEVER the secret)
  credential_expires_at · last_verified_at · paused_at · paused_reason

connection_destination   which destinations this connection reaches
  connection_id · destination_code · enabled
  commission_bps · payment_responsibility · settlement_model

listing_mapping          EventType published to one destination
  connection_destination_id · event_type_id
  external_product_id · external_option_ids[] · publish_state
  readiness_pct · blocking_reasons[]

reservation_inbox        raw inbound, persisted BEFORE canonical creation
  connection_id · destination_code · external_ref · message_type
  external_sequence · payload_json · received_at
  status(RECEIVED|APPLIED|REJECTED|SUPERSEDED) · booking_ref · reject_reason

settlement_record        money a third party holds or owes
  booking_ref · destination_code · currency
  gross · taxes · fees · commission · platform_fee · venue_net
  collected_by · expected_payout_at · actual_payout_at · actual_amount
  settlement_status · reconciliation_status · variance_minor

sync_state               per (connection, destination)
  last_realtime_at · last_delta_ack_at · last_feed_published_at
  last_reconciled_at · accepted_inventory_version · stale_after
```

**Where it lives:** `distribution_db`, owned by `distribution-service`. It holds
**no availability rows and no booking rows** — the invariant from the original dossier
survives untouched.

### G-B · Attribution had no capture mechanism — **the Google channel's entire value** `[SOURCE-CODE EVIDENCE]`

v2 said a Google conversion is `origin=DIRECT, attributionSource=GOOGLE_THINGS_TO_DO`
and never said how that value arrives. Grepping the frontend for `utm_`/`attribution`
returns **nothing** — no capture exists. Without this, Google is unmeasurable and the
business case for it is unprovable.

```
Google TTD deep link
  https://skbinge.example/binges/12/book?utm_source=google&utm_medium=ttd
      &sk_click=<opaque-id>
        │
        ▼  landing: capture into sessionStorage (NOT a cookie — no consent banner
        │  needed for first-party, session-scoped attribution data)
        ▼  carried through the wizard, sent on POST /bookings as attributionRef
        ▼  booking-service stores attribution_source + attribution_ref
```

Rules `[RECOMMENDATION]`: **last non-direct touch wins**, 30-day window, session-scoped;
attribution is **never** allowed to alter price, availability or eligibility — it is a
reporting dimension only. A booking with an unrecognised source records it verbatim
rather than discarding it.

### G-C · No ordering model for modifications and cancellations `[ARCHITECTURAL INFERENCE]`

V85's `(external_source, external_ref)` unique index makes *creation* idempotent. It says
nothing about a **cancel arriving before the modify it supersedes** — ordinary with
at-least-once delivery and retries.

`reservation_inbox.external_sequence` + rule: **apply only if
`external_sequence > last_applied_sequence`**, otherwise mark `SUPERSEDED` and keep the
row. Where a provider supplies no sequence, fall back to provider timestamp, then
receipt order, and **record which** was used — a reconciliation run must be able to tell
"ordered by provider" from "ordered by luck".

### G-D · Connection tenancy was ambiguous `[BUSINESS ASSUMPTION → decided]`

Is a Bókun connection per-venue or platform-wide? v2 never said, and it determines the
whole credential model. **Decision: per-venue** (`connection.binge_id` NOT NULL),
because B2 makes the venue the supplier of record and GetYourGuide refuses aggregators
as supply partners. A platform-level connection would contradict the contracting model
the whole strategy rests on. `provider` and `destination` stay platform-owned.

### G-E · Settlement had no currency `[SOURCE-CODE EVIDENCE]`

Fatal under global scope (B1): Viator may remit in one currency while the venue banks in
another. Every settlement figure is a **minor-unit long plus an ISO currency**, matching
the existing money contract, with `fx_rate_at_payout` captured on receipt. Two currencies
are tracked — **destination settlement currency** and **venue payout currency** — because
the variance between them is a real reconciliation category, not an error.

### G-F · Stop-sell and safety inventory were dropped — **a regression I introduced**

They were G4/DIST-R6 in the original dossier; v2 lost them. Restored:
`connection_destination.safety_inventory` (hold back N concurrent slots from channels)
and a per-connection **stop-sell** distinct from pause — stop-sell stops *new* sales while
honouring existing reservations; pause stops *all* traffic.

### G-G · Cancellation authority was unstated `[ARCHITECTURAL INFERENCE]`

If Viator advertises one cutoff and `CancellationTier` says another, whose refund math
runs? **Decision: SK Binge's `CancellationTier` is always the authority for what the
venue is owed.** The destination's published policy governs what the *traveller* is
charged by that destination. Where they differ, the variance is a
`settlement_record.reconciliation_status = VARIANCE` — surfaced, never silently absorbed.
Publishing therefore advertises the **most conservative representable** cutoff, as the
original product-fit matrix required.

### G-H · No backpressure design `[OFFICIAL PROVIDER EVIDENCE]`

GetYourGuide fetches availability + prices for **365 days per product** on a schedule;
Viator batch-polls. Naive implementation melts booking-service. Closing it: a dedicated
read path with short-TTL cache, per-connection rate-limit buckets at the gateway, honest
`Retry-After`, and the OCTO split honoured — coarse `availability/calendar` vs fine
`availability`.

### G-I · Nobody owned the Google feed `[OFFICIAL PROVIDER EVIDENCE]`

Things to Do requires a **full-replacement** SFTP JSON upload, recommended daily and
**mandatory within 30 days or products are removed**. Assigned: a
`GoogleFeedPublisher` scheduled job in distribution-service, ShedLock-guarded like the
existing schedulers, writing `sync_state.last_feed_published_at`, with a staleness alarm
at **21 days** — comfortably before the 30-day takedown, not at it.

### G-J · Acceptance criteria were not mechanically checkable `[RECOMMENDATION]`

"UI never offers an action whose capability flag is false" is unenforceable prose. Made
testable: every capability-gated control renders through a single
`<CapabilityGate capability="...">` component, and a unit test asserts that **no
distribution page contains an action element outside a `CapabilityGate`**. A rule a test
can enforce is worth more than a rule in a document.

## 14. Pass-3 review — no material findings

A third pass produced only preferences (naming, screen ordering), not defects. **Stopping
here**: further iteration without new provider evidence or user feedback is theatre, and
the two genuinely unresolved items are commercial questions no amount of redesign
answers — see §12.

## 15. Implementation order (revised after pass 2)

G-A moved to the front: everything renders from the schema and the capability rows.

| # | Slice | Why here |
|---|---|---|
| **1** | **Schema + provider capability model** + seeded provider catalogue — **DONE** | Every screen and every connector reads it |
| **2** | **Attribution capture (G-B)** — **DONE** | Independent of connectors; makes Google measurable **before** any connector exists, so the channel can be justified on data |
| **3** | **Connections + connector-specific auth** — **DONE** | Nothing is reachable without one |
| **4** | **Listings + per-destination readiness** — **DONE** | Surfaces `Blocked` to whoever can fix it |
| **5** | **Reservation inbox + ordering (G-C)** — **DONE** (service + recovery console) | Trust screen; must precede the first real reservation |
| **6** | **Settlements (G-E)** — **DONE** (service + console) | Correct from the first channel-collected booking |
| **7** | **Overview · Health** — **DONE**; governance not built | Composed from 1–6 |

**Slice 2 before slice 3 is deliberate.** Attribution needs no provider approval, no
credentials and no certification — it is the one piece of real distribution value
obtainable while the commercial questions in §12 are still open.

### Slice 1 — what actually shipped

Landed in `backend/distribution-service` as its own bounded context, not as a package
in booking-service (different trust boundary, different failure posture, and
`BookingService` is already the audit's named source of regressions).

| Piece | Where |
|---|---|
| 9-table schema + seeded catalogue | `db/migration/V1__init_distribution_schema.sql` — numbered **V1** in the new service's own history, not V87; it is a separate database |
| Schema invariants | `DistributionSchemaIT` — 14 tests, each pinning a decision that was **wrong in v1** |
| Entities + repositories | `entity/`, `repository/` |
| Entity/schema parity | `EntitySchemaParityIT` — Hibernate `validate` against the migrated schema |
| Runtime wiring | `distribution-service.yml`, `Dockerfile`, `postgres-init` one-shot, compose service |

Every real provider is seeded `active = FALSE`. Nothing is connectable until a
super-admin turns one on, which should follow the commercial question in §12 that is
still open: whether any experiences reseller will list **private venue hire** at all.
The five wiring gaps found while landing this — including one regression I introduced
— are written up as WIR-1…WIR-5 in `04-SECURITY-AND-VERIFICATION-LOG.md`.

**Not built, deliberately:** no controllers, no gateway route, no k8s manifests. An
API with no caller is a guess about the caller. Slice 2 defines the first real one.

### Slice 2 — what actually shipped

Attribution now flows end to end, and deliberately lands in **booking-service**, not the
distribution context: a Google conversion is an ordinary DIRECT booking, so the
attribution belongs on the booking row next to the origin it qualifies.

| Piece | Where |
|---|---|
| Columns + two CHECKs + partial reporting index | `V88__booking_attribution.sql` |
| Domain rules (canonical form, 30-day window, never-throws) | `domain/BookingAttribution.java` (13 tests) |
| Capture, storage, last-non-direct-touch | `frontend/src/utils/attribution.js` (12 tests) |
| Wiring | `main.jsx` before the router mounts; merged centrally in `endpoints.createBooking` |

**`attribution_*` is not `external_source`.** The latter means the reservation *arrived
from* a channel and carries `origin = CHANNEL`. Attribution means a customer booked
here after *following a link*, and the booking stays `origin = DIRECT`. Collapsing them
would let a referral inherit CHANNEL's guards, skip the customer-funnel checks, and be
counted as channel-collected revenue nobody is going to remit. A CHANNEL booking
records no attribution at all, so nothing is double-counted.

**Reporting dimension only, enforced structurally.** Attribution is resolved *after*
every price, tax and eligibility decision is final and immediately before persistence —
a value that only exists after the decisions are made cannot have influenced one. This
matters because attribution arrives as query parameters on a public URL: if it could
reach the pricing path, a customer could choose their own discount.

Three smaller decisions worth keeping:

* **An unrecognised source is recorded verbatim.** Discarding sources we have no name
  for yet would throw away the first data about a new channel — exactly the data needed
  to decide whether to build it.
* **Malformed input returns null, never throws.** The customer is trying to buy
  something; losing an analytics dimension is acceptable, losing the sale is not.
* **A capture timestamp in the future is treated as expired**, not as valid forever. The
  clock belongs to the visitor's browser, so failing closed costs one reporting row
  while trusting it would hold attribution open indefinitely.

Verified: constraints proven to fail closed on the live database (mixed-case source and
a ref-without-source are both rejected), `booking_db` at **V88**, full reactor green,
374/374 frontend tests.
