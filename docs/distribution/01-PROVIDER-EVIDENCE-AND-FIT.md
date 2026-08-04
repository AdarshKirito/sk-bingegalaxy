# Provider Evidence, Product Fit and Scored Decision

> Research date **2026-07-31**. Every row is sourced from the provider's own documentation unless labelled otherwise. Where official evidence could not be obtained, the row is marked `UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR`.

---

## 1. API-product disambiguation (read this before the matrix)

The single most expensive mistake available here is integrating an **affiliate/demand** API believing it distributes SK Binge inventory. It does the opposite.

| Provider | **Supply-connectivity API** (sends SK Binge inventory *out*) | **Affiliate / demand API** (sells *their* inventory) | Verdict |
|---|---|---|---|
| Viator (Tripadvisor) | **Viator Supplier API** — Viator *calls* the supplier's reservation system (pull) | **Viator Partner API** — affiliate, resell Viator products | Only the **Supplier API** is relevant |
| GetYourGuide | **Supplier API** via the Integrator Portal | **Affiliate Partner Program** — explicitly offered as the alternative to rejected aggregators | Only the **Supplier API** is relevant |
| Klook | **Klook OpenAPI / Merchant API** — *"for merchants, reservation systems & channel managers"*, Klook pulls products/pricing/availability | Klook affiliate/distribution partner programme | Only the **Merchant API** is relevant |
| Headout | Not publicly documented | **Headout distribution/affiliate programme** — partners *sell Headout inventory* via API or portal | The public programme is **demand-side**. Do not mistake it for supply. |
| Tiqets | **Tiqets Supplier API v2** — public OpenAPI spec on GitHub, *"allows Tour Operators and Venues to sell their tickets on Tiqets.com"* | Tiqets affiliate/reseller programme | Only the **Supplier API** is relevant |
| Booking.com | **Connectivity APIs** (Rates & Availability, Reservations, Content, Photos, Promotions, Property Settings, …) | **Demand API** — search & book Booking.com inventory | Only **Connectivity** is relevant — and it is closed (§5) |
| Expedia Group | **EG Connectivity Hub / lodging supply APIs** (property management, ARI, booking retrieval) | **EPS Rapid** — demand API, book Expedia inventory | Only **Connectivity Hub** is relevant — and it excludes individual properties |
| Google | **Actions Center booking server** (Google calls *your* server: availability + create/update booking) · **Things to Do** product feed (SFTP JSON) | Google Ads / Things-to-do ads | Both are supply-side; different mechanics |
| Bókun / Rezdy | **Channel Manager API** — connect *your own* reservation system, they fan out to resellers | Their marketplaces also let you *act as* a reseller | Only the **Channel Manager API** direction is relevant |

---

## 2. Provider evidence matrix

Legend for **Access**: `OPEN` = documented self-serve or published route · `GATED` = application + approval · `CLOSED` = not accepting new partners of this type · `UNKNOWN` = no official statement found.

### 2.1 The standard

| Field | **OCTO (Open Connectivity for Tours, Activities & Attractions)** |
|---|---|
| Official source | `octo.travel`, `docs.octo.travel` |
| Researched | 2026-07-31 |
| Version | 1.0 stable; **2.0 in community review** (enhanced pricing/booking flows) |
| Direction | **Supply.** Supplier hosts the endpoint; resellers connect to it |
| Business model | Free, open, no membership required — *"No license, no strings, no catch."* Governed by OCTO Standards NP Inc. (not-for-profit) |
| Supported product types | Tours, activities, attractions, in-destination experiences |
| Countries / currencies | Unrestricted; `defaultCurrency` + `availableCurrencies` per product; integer minor units with `currencyPrecision` |
| Inventory model | `Product` → `Option` → `Unit`; `availabilityType` = `START_TIME` \| `OPENING_HOURS` |
| Time granularity | **Arbitrary datetimes** — `localDateTimeStart` / `localDateTimeEnd` in the product's IANA timezone |
| Capacity | `capacity`, `vacancies`, `maxUnits`; status `AVAILABLE` \| `FREESALE` \| `LIMITED` \| `SOLD_OUT` \| `CLOSED` |
| Reservation delivery | **Pull.** Reseller calls `POST /bookings` (→ `ON_HOLD`, `utcExpiresAt`, `expirationMinutes`) then `POST /bookings/{uuid}/confirm`; also `/cancel`, `GET /bookings` |
| Booking statuses | `ON_HOLD`, `CONFIRMED`, `PENDING`, `EXPIRED`, `CANCELLED`, `REDEEMED`, `REJECTED` |
| Idempotency | Reseller-supplied `uuid` — *"an optional idempotency key … to prevent duplicate bookings in case of retries"* |
| Auth | Bearer token; **API key issued per reseller↔supplier pair** |
| Pricing | Optional capability: `original`/`retail`/`net`, itemised `includedTaxes`, `pricingPer` = `UNIT` \| **`BOOKING`** |
| Optional capabilities | Pricing, Content, Pickups/Dropoffs, Questions, **Extras**, Notifications (webhooks), Promotions (draft) |
| Certification | None mandated by the standard; each reseller certifies its own connection |
| Access | **OPEN** |
| Adoption | 130+ implementations; consumed by Viator, GetYourGuide, Expedia, Groupon, Klook, Headout, Go City, TUI Musement, Tiqets |
| Known limitations | Duration is a product property, not a booking input; no native "setup/cleanup buffer"; no venue-hire-specific vocabulary |

### 2.2 Experiences resellers (demand for SK Binge inventory)

| Provider | API product | Direction | Time model | Reservation delivery | Access | Key evidence |
|---|---|---|---|---|---|---|
| **Viator** | Supplier API (v2) | **Pull** — Viator calls the supplier's system | Dates + `TourDepartureTime` start times; capacity-checked | Batch availability, real-time availability, **booking hold endpoint (v2)** that holds inventory *and price*, booking insert, cancel, amend | **GATED** — dev → API account manager testing → pilot product → launch | Viator Supplier API connectivity overview |
| **GetYourGuide** | Supplier API (Integrator Portal) | Pull, scheduled | Time slots; per-slot pricing supported | Availability + price fetched on a schedule (default ~every 8 days for 365 days); price-over-API optional | **GATED + RESTRICTED** — *"resellers, aggregators, online travel agencies, destination management companies … are not accepted"* as supply partners | GYG Supply Partner Help Center |
| **Klook** | OpenAPI / Merchant API | Pull | Product/option/schedule with real-time availability & dynamic pricing | Bulk + real-time retrieval; booking APIs | **GATED** — *"for merchants, reservation systems & channel managers"* | klook.gitbook.io/openapi |
| **Tiqets** | Supplier API v2 | Pull | Timed-entry / time slots | Reservation → confirmation | **GATED**, but spec is **public open source** on GitHub | Tiqets/supplier-api |
| **Expedia Local Expert** | Activities connectivity (distinct from lodging) | Pull | Activity schedules | Via Bókun live integration among others | **GATED** | Bókun channel list |
| **Headout** | — | — | — | Public programme is **demand-side distribution/affiliate** | n/a | partner.headout.com/distribution |

### 2.3 Aggregators / channel managers (the "partner" option)

| Provider | Product | What it does for SK Binge | Access | Evidence |
|---|---|---|---|---|
| **Bókun** (Tripadvisor) | **Channel Manager API** — *"you must build your own plug-in which acts as a channel between Bókun's Channel Manager and the other system"* | Fan-out to 2,600+ resellers from one integration. **Live direct-availability sync exists for exactly three channels: Viator, GetYourGuide, Expedia Local Expert**; everything else (Klook, Trip.com, Civitatis, Tiqets…) runs through the Bókun marketplace on contracted reseller terms | **GATED** — custom plugin, tested with the API team | bokun.dev channel-manager-api; also publishes a **Bókun OCTO API** |
| **Rezdy** | **RezdyConnect** — *"an API designed specifically for online ticketing and reservation systems"* | Two-way sync of availability, pricing, bookings, cancellations into a network of **25,000+ resellers**; two-step reservation & confirmation flow; barcodes/QR | **GATED** — discovery call → technical spec review → integration plan. **Fees and certification terms not published.** | rezdy.com/channel-manager-for-ticketing-software |
| **Ventrata / Peek / Zaui / Magpie / Prioticket** | OCTO-native platforms | Not integration targets — they are **evidence** that OCTO is the ecosystem's lingua franca, and their public OCTO docs are the best available reference implementations | n/a | docs.ventrata.com, octodocs.peek.com, docs.zaui.com |

### 2.4 Lodging OTAs — evidence for the rejection

| Provider | API product | Inventory model | Time granularity | Access statement | Verdict |
|---|---|---|---|---|---|
| **Booking.com** | Connectivity APIs (Rates & Availability, Reservations, Content, Photos, Guest Messages, Promotions, Property Settings, Reporting, Payments onboarding) | `roomrate` = room type × rate plan | **Per-night calendar date.** `<date value>` or `<date from…to>` (to-date exclusive). Restrictions: `minimumstay`, `maximumstay`, `exactstay_arrival`, `closed`, `closedonarrival`, `closedondeparture`, `min_advance_res`, `max_advance_res` — referenced to *"24:00 in the hotel timezone"*. **No time-of-day field.** | *"we are pausing integrations with new connectivity providers until further notice"* | **CLOSED + UNSUPPORTED** |
| **Expedia Group** | EG Connectivity Hub lodging supply APIs | Property → room type → rate plan | Per-night | *"we're not accepting direct connections from individual properties"* — designed for PMS / channel manager / CRS / lodging connectivity applications. PCI compliance required. Chain agreement needs 5+ properties, 75+ sellable units. | **CLOSED to properties + UNSUPPORTED** |
| **Agoda** | YCS / YCS5 API (+ Push BookingHint for near-real-time reservation notification) | Property → room → rate | Per-night | Contact Agoda; certification tests with an account manager | **GATED + UNSUPPORTED** |
| **Airbnb** | Partner APIs (no public access) | Listing / calendar-day | Per-night | Approved partners only; NDA, API Terms, partner-specific terms, **data-security review**, mandatory features within 6 months. Selection weighs profitability, technical strength, support capability. | **CLOSED + UNSUPPORTED** |
| **Priceline / other Booking Holdings** | — | — | — | No independent supply-connectivity product for hourly venue inventory located | `UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR` |

### 2.5 Google surfaces

| Product | Direction | Model | Access | Evidence |
|---|---|---|---|---|
| **Google Actions Center — Reservations end-to-end** | **Google calls your booking server** (HTTPS + basic auth, credentials rotated every 6 months; REST recommended over gRPC for new partners) | Merchant feed + Services feed + **Availability feed**; server implements `HealthCheck`, `BatchAvailabilityLookup`, `CreateBooking`, `UpdateBooking`, `SetMarketingPreference`; sandbox environment before production | **GATED.** Requires *"a direct contractual relationship with all the merchants included in their integration feed"* and merchant matching to Google Maps locations. Current e2e docs are **restaurant**-centric; venue-hire eligibility `UNVERIFIED` | developers.google.com/actions-center (last updated 2026-04-01) |
| **Google Things to Do** | Feed out (SFTP, JSON, full replacement, ≥ every 30 days or products are taken down) | Products with `id`, `title`, `options`; non-OTA partners set `operator` + `inventory_types`; deep-link/URL templates to your booking flow | **GATED.** *"Google doesn't currently allow individual operators to upload listings directly"* — eligible partner types are OTAs, reservation technology companies, and connectivity partners. Requires interest form + **content licence agreement** + technical contact | developers.google.com/actions-center/verticals/things-to-do/overview (last updated 2026-04-01) |

### 2.6 Venue / space marketplaces — closest product fit, no connectivity

| Provider | Product fit | API | Verdict |
|---|---|---|---|
| **Peerspace** (~20% commission), **Tagvenue**, **Giggster** (~19%), **Splacer** (~15%, 30+ countries) | **Excellent** — hourly private space rental is literally their product | **No public supplier/connectivity API located** | `UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR.` Treat as **manual listing channels**: high commercial value, zero integration surface. Worth pursuing as a business action, not an engineering one. |
| **Regional consumer ticketing platforms** (e.g. BookMyShow/District in South Asia, Fever, Dice, Eventbrite-class) | Partial — consumer event ticketing, not venue hire | No public supply-connectivity API located for venue-hire inventory | `UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR` |

---

## 3. Product-fit matrix

Fit codes: `NATIVE_FIT` · `SAFE_MAPPING` · `LOSSY_MAPPING` · `UNSUPPORTED` · `COMMERCIAL_ACCESS_REQUIRED` · `TECHNICAL_ACCESS_REQUIRED` · `UNVERIFIED`.

### 3.1 Capability-by-capability, against the OCTO model

| SK Binge capability | OCTO representation | Fit | What is lost |
|---|---|---|---|
| Hourly start & end times | `localDateTimeStart` / `localDateTimeEnd`, `availabilityType: START_TIME` | **NATIVE_FIT** | — |
| Fixed-duration sessions | `Option` + `durationMinutesFrom/To` | **NATIVE_FIT** | — |
| Multi-hour reservations | Availability slot spans the duration | **NATIVE_FIT** | — |
| **Customer-chosen variable duration (30 min–12 h, 30-min steps)** | Must become a **finite set of `Option`s** | **LOSSY_MAPPING** | Free choice of duration. A 24-value lattice × start times explodes the calendar (DIST-R8). **Loss: customers on-channel pick from e.g. {2 h, 3 h, 4 h}, not any duration.** |
| Physical room assignment | Room = `Option`, or assigned post-booking by SK Binge | **SAFE_MAPPING** | Channel guest cannot pick a specific room unless rooms are exposed as options |
| Any-room / auto-assign inventory | `vacancies` = free room count at that slot | **NATIVE_FIT** | — |
| Guest capacity (priced, non-inventory) | `Unit` with `paxCount` + `Option.restrictions.minUnits/maxUnits` | **SAFE_MAPPING** | Guests consume no inventory in SK Binge but *look* like units on-channel; needs careful `pricingPer` choice |
| Exclusive-use / whole-venue pricing | `pricingPer: BOOKING` | **NATIVE_FIT** | — |
| `base + hourly×h + perGuest×g + room + addons` composite | Must be **pre-resolved** into a single `retail` per option/unit | **SAFE_MAPPING** | The formula is not transmitted; only the resolved price. Correct — pricing stays server-authoritative. |
| Add-ons | **Extras** capability | **SAFE_MAPPING** | Add-on *categories* and per-rate-code add-on pricing flatten |
| **Setup / cleanup buffer** | No OCTO concept | **UNSUPPORTED (both sides)** | **Must be absorbed into the availability projection** — publish slots that already exclude turnover time. This is the single most important mapping rule. |
| Venue timezone | `Product.timeZone` (IANA) | **NATIVE_FIT** | — |
| Date-level closure (`BlockedDate`) | `status: CLOSED` for the day | **NATIVE_FIT** | — |
| Time-level closure (`BlockedSlot`, `RoomBlock`) | slot `status: CLOSED` / reduced `vacancies` | **NATIVE_FIT** | — |
| Stop-sell (channel-specific) | Per-connection suppression | **SAFE_MAPPING** | Requires a Distribution-owned concept (G4); not derivable from current entities |
| Same-day booking | `utcCutoffAt` on availability | **SAFE_MAPPING** | — |
| Minimum notice | `utcCutoffAt` | **SAFE_MAPPING** | No source field exists yet (G5) |
| Maximum advance booking | Calendar horizon | **SAFE_MAPPING** | Global 365-day config, not per-binge |
| Request-to-book | Booking status `PENDING` | **SAFE_MAPPING** | Needs an operator SLA + auto-decline that do not exist |
| Instant booking | `instantConfirmation: true` | **NATIVE_FIT** | — |
| Slot holds | `ON_HOLD` + `expirationMinutes` + `utcExpiresAt` → `confirm` | **NATIVE_FIT** | — literally the same lifecycle as `SlotHold` |
| Deposits / partial payment | Not modelled by OCTO | **UNSUPPORTED** | Channel bookings are all-or-nothing. Deposit flows stay direct-only. |
| Venue-collected payment (agency) | Supplier collects; reseller commissions | **SAFE_MAPPING** | — |
| OTA-collected payment (merchant) | `net` pricing + reseller settlement | **TECHNICAL_ACCESS_REQUIRED** | Needs G7 (net/commission model) **and** PR-PAY-01 closed |
| Cancellation tiers (% by hours-before) | Single `cancellationCutoff` per option | **LOSSY_MAPPING** | **Loss: the graduated 100/50/0 % ladder collapses to one cutoff.** Publish the most conservative tier; reconcile refunds server-side from `CancellationTier`, never from the channel's view. |
| Rescheduling | Cancel + rebook (Viator has `amend`) | **LOSSY_MAPPING** | Reschedule history (`rescheduleCount`, `originalBookingRef`) does not survive the round trip |
| Booking transfer (magic link) | No concept | **UNSUPPORTED** | Direct-only feature. Suppress on channel bookings. |
| Loyalty earn/redeem | No concept | **UNSUPPORTED** | **Deliberate:** channel bookings must not earn or redeem points. Needs an explicit rule, not silence. |
| Taxes & fees | `includedTaxes[]` itemised | **NATIVE_FIT** | Maps directly to `Booking.taxBreakdownJson` |
| Currency & FX | Integer minor units + `currencyPrecision` | **NATIVE_FIT** | Matches the existing minor-unit money contract exactly |
| Channel commission | `retail` vs `net` | **TECHNICAL_ACCESS_REQUIRED** | No source model (G7) |

### 3.2 Per-provider verdict

| Provider-product | Verdict |
|---|---|
| **OCTO supplier API (SK Binge implements)** | **SAFE_MAPPING** — native on time, capacity, holds, tax, currency; lossy on variable duration + cancellation tiers |
| Viator Supplier API | **SAFE_MAPPING + COMMERCIAL_ACCESS_REQUIRED** (product-category eligibility unresolved, see B/P2) |
| Klook Merchant API | **SAFE_MAPPING + COMMERCIAL_ACCESS_REQUIRED** |
| Tiqets Supplier API | **SAFE_MAPPING + COMMERCIAL_ACCESS_REQUIRED** |
| GetYourGuide Supplier API | **SAFE_MAPPING technically + COMMERCIAL_ACCESS_REQUIRED** — SK Binge cannot sign as supplier; venues must, with SK Binge as their system |
| Bókun Channel Manager API | **SAFE_MAPPING + COMMERCIAL_ACCESS_REQUIRED** |
| Rezdy RezdyConnect | **SAFE_MAPPING + COMMERCIAL_ACCESS_REQUIRED** |
| Google Actions Center (Reservations e2e) | **SAFE_MAPPING technically, UNVERIFIED for this vertical** |
| Google Things to Do | **SAFE_MAPPING** (feed + deep link; no availability truth leaves the platform) **+ COMMERCIAL_ACCESS_REQUIRED** |
| Booking.com Connectivity | **UNSUPPORTED** (nightly) **+ CLOSED** |
| Expedia lodging connectivity | **UNSUPPORTED** (nightly) **+ CLOSED to properties** |
| Agoda YCS | **UNSUPPORTED** (nightly) **+ COMMERCIAL_ACCESS_REQUIRED** |
| Airbnb | **UNSUPPORTED** (nightly) **+ CLOSED** |
| Peerspace / Tagvenue / Giggster / Splacer | **UNVERIFIED** — best product fit, no API. Manual listing only. |
| Regional consumer ticketing platforms | **UNVERIFIED** |

---

## 4. Global & regional channel analysis

`[BUSINESS DECISION — B1 resolved 2026-07-31: **global, no lead market**]` SK Binge Galaxy targets venues worldwide. `Binge.country` is already required and load-bearing (currency derived, timezone seeded, tax rules and payment methods resolved from it), so the platform is structurally ready for this. **Channel strategy must therefore be country-agnostic at the architecture layer and country-resolved at the connection layer** — see §4.2.

### 4.1 Channel reach is a per-region fact, and the architecture must not encode it

| Region | Realistic experiences channels | Notes |
|---|---|---|
| **Europe** | GetYourGuide (DACH-strong), Tiqets, Civitatis, Regiondo, TUI Musement | Densest OCTO-reseller coverage of any region |
| **North America** | Viator, Peek, Peerspace, Giggster, Google | Peerspace/Giggster are the closest *product-category* match anywhere — manual listing, no API |
| **APAC** | Klook (strongest), Trip.com, Traveloka | Klook's Merchant API is explicitly aimed at "merchants, reservation systems & channel managers" |
| **South Asia / Middle East / LATAM / Africa** | Thin OTA coverage for this product category; Google surfaces and local venue marketplaces dominate discovery | **No API-integrated distribution layer exists for private-venue hire in most of these markets.** `[ARCHITECTURAL INFERENCE]` |
| **Global, geography-neutral** | **OCTO** · Bókun (2,600+ resellers) · Rezdy (25,000+ resellers) · Google Things to Do | One integration, every market |

### 4.2 What "global" changes about the decision `[ARCHITECTURAL INFERENCE]`

Going worldwide **strengthens** the OCTO-first recommendation rather than complicating it:

1. **OCTO is geography-neutral by construction** — no per-country contract, no regional variant, no country-scoped certification. One endpoint serves a venue in Berlin and a venue in São Paulo identically.
2. **The lodging route gets worse, not better, at global scale.** Booking.com requires an accommodation-partner contract *per country the properties are located in*. A worldwide venue platform would need a contract matrix, not a contract. Combined with the new-provider pause, this is now a compounding objection.
3. **Aggregators become more attractive for Phase 2, not less** — Bókun and Rezdy already carry the per-region reseller relationships that SK Binge would otherwise negotiate country by country.
4. **Channel eligibility becomes a first-class Distribution concern.** A `ChannelConnection` must be validated against `Binge.country`: Klook for an APAC venue, GetYourGuide for a European one. **The UI must never offer a channel that does not operate in the venue's country** — this is a data-driven `Channel.supportedCountries` check, not hardcoded logic.
5. **Currency and settlement are already solved** — per-binge `country → currency`, `CurrencyRate` FX, minor-unit longs, and OCTO's `defaultCurrency`/`availableCurrencies` with `currencyPrecision`. No new money model is needed for global reach.
6. **One gap gets promoted by this decision:** the UI is English-only (`GLB-01`, previously P3 *"unless target markets require it"*). A worldwide venue platform selling through European and APAC channels **does require it** — **`GLB-01` should be re-rated P2**. Channel-facing content (product titles, descriptions) is separately affected: OCTO carries `locale` per product and an `Accept-Language` header on availability, so multi-language product content is a real Phase-6 requirement, not a nicety.

---

## 5. Compliance & commercial-access analysis

| Area | Finding | Implication |
|---|---|---|
| **Aggregator exclusion** | GetYourGuide: *"resellers, aggregators, online travel agencies, destination management companies, and unregistered private guides are explicitly not accepted"*; requires *"a legally operating business and a valid insurance policy where applicable"* | **SK Binge must be the reservation system, each Binge the supplier of record.** Changes contracting, not just code. |
| **Expedia property exclusion** | *"we're not accepting direct connections from individual properties"* | Confirms the restech seat is the correct one — and that lodging is the wrong ecosystem regardless |
| **PCI** | PCI compliance is a stated Expedia precondition; merchant-model channels imply cardholder data or virtual cards | **Stay agency-model in Phase 1–2.** SK Binge never touches PAN; Razorpay/Stripe remain the money boundary. |
| **PII** | Channel reservations carry guest name/email/phone. The `user.anonymized` erasure fan-out exists but has **no channel-side leg** | Add Distribution to the erasure fan-out **before** the first live reservation. DPDP/GDPR exposure otherwise. |
| **Credentials** | Per-venue × per-channel secrets. Repo history already has a secrets incident (`admin_token.txt`, P0-2 still open) | Envelope encryption, write-only API surface, masked UI, rotation from day one. Non-negotiable (DIST-R5). |
| **Rate limits** | Reseller calendar polling is heavy by design (GYG default: availability+prices every ~8 days for 365 days per product) | Per-reseller quotas at the gateway; the existing `RateLimitFilter`/`UserRateLimitFilter` pattern extends to API keys |
| **Contractual** | Google Actions Center requires *"a direct contractual relationship with all the merchants included in their integration feed"* | SK Binge's admin/Binge relationship plausibly satisfies this — legal review needed |
| **Money gate** | **PR-PAY-01 (P0)** — no end-to-end payment/refund has been proven against a provider sandbox | **Distribution must not ship channel-collected money before this closes.** |

---

## 6. Scored decision matrix

Scoring **0–5**, higher is better. Weights reflect what actually constrains SK Binge today. Scores are **relative judgements from the evidence above**, not measured data — no false precision is implied.

| Criterion | W | OCTO Supplier API (self-hosted) | Bókun / Rezdy aggregator | Google surfaces | Direct experiences OTA connector | Lodging OTA (BKG/EXP/Agoda) |
|---|---:|---:|---:|---:|---:|---:|
| Compatibility with hourly Binge inventory | **5** | 5 | 4 | 4 | 4 | **0** |
| Compatibility with accommodation inventory | 1 | 1 | 1 | 1 | 1 | 5 |
| Country coverage | 3 | 5 | 5 | 5 | 2 | 4 |
| Customer reach | 4 | 3 | **5** | 4 | 3 | 4 |
| Official API availability | **5** | 5 | 4 | 4 | 4 | 3 |
| Partnership accessibility | **5** | **5** | 3 | 2 | 2 | **0** |
| Certification effort (higher = less) | 4 | **5** | 3 | 2 | 2 | 1 |
| Development complexity (higher = simpler) | 4 | 3 | 4 | 3 | 2 | 1 |
| Operational complexity (higher = simpler) | 4 | 3 | 4 | 3 | 2 | 1 |
| Payment complexity (higher = simpler) | **5** | 4 | 3 | 4 | 3 | 1 |
| Security & compliance burden (higher = lighter) | 4 | 4 | 3 | 3 | 3 | 1 |
| Cost (higher = cheaper) | 4 | **5** | 3 | 4 | 3 | 2 |
| Time to market (higher = faster) | **5** | 4 | 4 | 3 | 2 | 1 |
| Vendor lock-in (higher = less) | 4 | **5** | 2 | 3 | 4 | 3 |
| Long-term strategic value | **5** | **5** | 3 | 4 | 3 | 1 |
| **Weighted total (max 310)** | | **⭐ 254** | **207** | **203** | **164** | **78** |

**Reading:** OCTO wins on the two criteria weighted highest *and* uniquely — partnership accessibility (it needs nobody's permission) and long-term strategic value (it is the same server every future reseller uses). Aggregators win on reach, which is exactly why they are Phase 2 rather than Phase 1. Lodging OTAs score 78/310 and fail the two hard gates outright.

---

## 7. Evidence bibliography

All retrieved **2026-07-31** unless noted.

**Standard**
- OCTO — [octo.travel](https://octo.travel/) · [specification](https://octo.travel/specification) · [Developer Hub](https://docs.octo.travel/) · [Products](https://docs.octo.travel/octo-api-core/products) · [Availability](https://docs.octo.travel/octo-api-core/availability) · [Bookings](https://docs.octo.travel/octo-api-core/bookings) · [Supplier](https://docs.octo.travel/octo-api-core/supplier) · [Pricing capability](https://docs.octo.travel/capabilities-optional/pricing)
- Reference implementations: [Ventrata OCTO](https://docs.ventrata.com/) · [Peek Reseller API](https://octodocs.peek.com/) · [Bókun OCTO API](https://bokun.dev/octo-api/)

**Experiences resellers**
- Viator — [Supplier API](https://docs.viator.com/supplier-api/technical/index.html) · [Connectivity overview](https://docs.viator.com/supplier-api/technical/connectivity-overview/index.html) · [Partner API (affiliate — not supply)](https://docs.viator.com/partner-api/) · [Product standards](https://partnerresources.viator.com/blog/updates-to-viators-product-standards/) *(Product Acceptance Criteria page returned **HTTP 403** — unread)*
- GetYourGuide — [Who can apply to be a Supply Partner?](https://supply.getyourguide.support/hc/en-us/articles/13980993520925-Who-can-apply-to-be-a-Supply-Partner) · [API features & functionalities](https://supply.getyourguide.support/hc/en-us/articles/14150246193181-API-Features-and-Functionalities) · [Price over API](https://supply.getyourguide.support/hc/en-us/articles/14150365685661-Price-over-API) · [Restricted activities](https://supply.getyourguide.support/hc/en-us/articles/19787988730525-Restricted-Activities-on-GetYourGuide)
- Klook — [API specification](https://klook.gitbook.io/openapi)
- Tiqets — [Supplier API v2](https://tiqets.github.io/supplier-api/) · [GitHub spec](https://github.com/Tiqets/supplier-api)
- Headout — [distribution programme (demand-side)](https://partner.headout.com/distribution/)

**Aggregators / channel managers**
- Bókun — [developer docs](https://bokun.dev/) · [Channel Manager API](https://bokun.dev/channel-manager-api/rGmzgGe66zjtEQ8FUvS8dd) · [channel list](https://www.bokun.io/channel-manager-for-tour-operators)
- Rezdy — [Channel manager for ticketing/reservation systems](https://rezdy.com/channel-manager-for-ticketing-software/) · [Channel manager for suppliers](https://rezdy.com/channel-manager-for-suppliers/)

**Lodging OTAs**
- Booking.com — [Connectivity API docs](https://developers.booking.com/connectivity/docs) *(page last updated ~2026-07-29)* · [Rates & Availability overview](https://developers.booking.com/connectivity/docs/ari) · [Create/update inventory, rates and restrictions](https://developers.booking.com/connectivity/docs/b_xml-availability) · [Connectivity Portal — partner pause notice](https://connect.booking.com/)
- Expedia Group — [EG Connectivity Hub — lodging supply](https://developers.expediagroup.com/supply/lodging)
- Agoda — [YCS API](https://developer.agoda.com/supply/docs/ycs-api) · [Agoda APIs](https://developer.agoda.com/supply/reference/where-to-start)
- Airbnb — [API Terms of Service](https://www.airbnb.com/help/article/3418) · [2025 Preferred Software Partners](https://news.airbnb.com/announcing-our-2025-preferred-software-partners/)

**Google**
- [Actions Center — Things to Do overview & eligibility](https://developers.google.com/actions-center/verticals/things-to-do/overview) *(last updated 2026-04-01)*
- [Actions Center — Things to Do required fields](https://developers.google.com/actions-center/verticals/things-to-do/guides/partner-integration/required-fields)
- [Actions Center — Reservations end-to-end](https://developers.google.com/actions-center/verticals/reservations/e2e/overview) *(last updated 2026-04-01)*
- [Actions Center rebrand FAQ](https://developers.google.com/actions-center/faqs/actions-center-rebrand)

**Secondary context only (never used to establish a capability)**
- Arival ("130+ OCTO implementations"), PhocusWire, Bókun/Rezdy comparison blogs, Tagvenue/Peerspace marketplace comparisons.
