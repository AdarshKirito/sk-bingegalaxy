# Phase −1 — Distribution Research Dossier

> **Research date:** 2026-07-31
> **Repo state inspected:** real git root `D:\sk-binge-galaxy\sk-binge-galaxy`, branch tip `6440f58`, **plus 80 uncommitted paths in the working tree** (mostly `docs/`, plus `backend/.../config/DataSeeder.java`). All source claims below were read from the **working tree**, which is what runs.
> **Status:** research phase complete. **Phase-1 implementation has since begun** — gap **G1 (turnover buffers) is closed** by `V81__turnover_buffers.sql` (2026-07-31). No distribution service, provider DTO or connector exists yet; §2.2 records what remains.
>
> **── DECISION GATE PASSED, 2026-07-31 ──**
> **Strategy accepted:** `HYBRID`, sequenced **OCTO-first**.
> **First connector accepted:** self-hosted **OCTO provider simulator + conformance suite**.
> **Market scope:** **global — worldwide from the start, no lead market** (see §6.1 B1).
> Remaining business unknowns B2–B5 were resolved on the same date and are recorded in §6.1.

| # | Required output | Where |
|---|---|---|
| 1 | Executive research conclusion | this doc §1 |
| 2 | Distribution-readiness assessment | this doc §2 |
| 3 | Inventory classification model | this doc §3 |
| 4 | Provider evidence matrix | [01-PROVIDER-EVIDENCE-AND-FIT.md](01-PROVIDER-EVIDENCE-AND-FIT.md) §1–2 |
| 5 | Product-fit matrix | [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §3 |
| 6 | Direct-vs-aggregator comparison | [02-ARCHITECTURE-DECISION.md](02-ARCHITECTURE-DECISION.md) §1 |
| 7 | Global & regional channel analysis | [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §4 |
| 8 | Compliance & commercial-access analysis | [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §5 |
| 9 | Recommended target architecture | [02](02-ARCHITECTURE-DECISION.md) §3 |
| 10 | Source-of-truth map | [02](02-ARCHITECTURE-DECISION.md) §4 |
| 11 | Recommended first connector | [02](02-ARCHITECTURE-DECISION.md) §5 |
| 12 | Rejected alternatives | this doc §4 |
| 13 | Risk register | this doc §5 |
| 14 | Implementation phases | [02](02-ARCHITECTURE-DECISION.md) §6 |
| 15 | Certification & partnership prerequisites | [02](02-ARCHITECTURE-DECISION.md) §7 |
| 16 | Evidence bibliography | [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §7 |
| 17 | Unknowns requiring confirmation | this doc §6 |
| — | Admin UI/UX design | [03-ADMIN-UX-DESIGN.md](03-ADMIN-UX-DESIGN.md) |
| — | **Security findings & verification log** (Phase 1) | [04-SECURITY-AND-VERIFICATION-LOG.md](04-SECURITY-AND-VERIFICATION-LOG.md) |

**Evidence labels used throughout:** `[VERIFIED FACT]` `[SOURCE-CODE EVIDENCE]` `[OFFICIAL PROVIDER EVIDENCE]` `[ARCHITECTURAL INFERENCE]` `[BUSINESS ASSUMPTION]` `[UNVERIFIED]` `[RECOMMENDATION]`.

---

## 1. Executive research conclusion

**SK Binge Galaxy is not a lodging product, and the entire lodging-OTA route is both technically lossy and commercially closed to it today. It is an in-destination *experiences* product, and that ecosystem has an open, free, time-slot-native standard — OCTO — that maps onto SK Binge's existing domain model with unusual precision.**

Five findings drive the recommendation:

**F1. The inventory is time-of-day, not per-night.** `[SOURCE-CODE EVIDENCE]` A booking is `(bingeId, eventTypeId, bookingDate, startTime, durationMinutes)` on a **30-minute grid**, 30 min – 12 h, optionally assigned to a `VenueRoom` whose `capacity` means *max concurrent bookings*, not headcount ([Booking.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/Booking.java), [VenueRoom.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/VenueRoom.java), [AvailabilityService.java:456-525](../../backend/availability-service/src/main/java/com/skbingegalaxy/availability/service/AvailabilityService.java)).

**F2. Lodging OTA ARI is calendar-date/per-night with no time-of-day field at all.** `[OFFICIAL PROVIDER EVIDENCE]` Booking.com's availability message takes `<date value>` / `<date from…to>` at **per-night granularity**, and its restriction vocabulary is `minimumstay`, `maximumstay`, `closedonarrival`, `closedondeparture`, `min_advance_res` — all referenced to *"24:00 in the hotel timezone"*. There is no start-time concept. Mapping a 19:00–22:00 celebration slot into this model destroys the product.

**F3. The famous lodging channels are commercially shut, independently of fit.** `[OFFICIAL PROVIDER EVIDENCE]`
- Booking.com: *"we are pausing integrations with new connectivity providers until further notice."* (connect.booking.com)
- Expedia Group: *"we're not accepting direct connections from individual properties"* — supply connectivity is for PMS/channel-manager/CRS companies only.
- Airbnb: no public API; access is limited to approved partners subject to a data-security review and demonstrated scale.
- Agoda YCS: partners must contact Agoda and pass certification with an assigned account manager.

Chasing any of these first would burn quarters on a route that cannot represent the product anyway.

**F4. The experiences ecosystem is a near-native fit, and it is a *pull* architecture.** `[OFFICIAL PROVIDER EVIDENCE]` In OCTO, **the supplier hosts the endpoint and each reseller connects to it with an API key issued per reseller↔supplier pair**. Viator's Supplier API works the same way (Viator calls the supplier's reservation system). This inverts the cost model: SK Binge builds *one* server, not *N* push pipelines. OCTO is free and open ("No license, no strings, no catch"), has 130+ implementations, and is already consumed by Viator, GetYourGuide, Expedia, Klook, Headout, Tiqets, Go City, TUI Musement and Groupon.

**F5. The OCTO object model lines up with entities SK Binge already has.** `[OFFICIAL PROVIDER EVIDENCE + ARCHITECTURAL INFERENCE]`

| OCTO concept | SK Binge equivalent that already exists |
|---|---|
| `Product` (`availabilityType: START_TIME`, `timeZone`, `durationMinutesFrom/To`) | `EventType` × `Binge` (`Binge.timezone`, `EventType.minHours/maxHours`) |
| `Option` (`availabilityLocalStartTimes`, `cancellationCutoff`, `restrictions.minUnits/maxUnits`) | duration/room variant + `CancellationTier` + `EventType.minGuests/maxGuests` |
| `Unit` (`paxCount`, age restrictions) | guests (`Booking.numberOfGuests`, `EventType.pricePerGuest`) |
| `Availability` (`localDateTimeStart/End`, `capacity`, `vacancies`, `status`) | the 30-min availability grid + `VenueRoom.capacity` + `BlockedDate`/`BlockedSlot` |
| Booking `ON_HOLD` + `expirationMinutes` + `utcExpiresAt` → `confirm` | **`SlotHold`** (`ACTIVE → CONVERTED/RELEASED/EXPIRED`, `expiresAt`, `@Version`) |
| Reseller-supplied `uuid` as idempotency key | **`IdempotencyKey`** table (Stripe-style, request-hash guarded) |
| `pricingPer: BOOKING`, integer minor units + `currencyPrecision`, itemised `includedTaxes` | whole-venue pricing, the minor-unit money contract, `Booking.taxBreakdownJson` |

That is not a coincidence to be admired — it is the reason the recommendation is cheap.

### The recommendation in one line

`[RECOMMENDATION]` **HYBRID, sequenced as: expose an OCTO-compliant Supplier API from a new Distribution bounded context, prove it against a self-hosted provider simulator + the public OCTO validator, then take reseller reach through a certified aggregator (Bókun or Rezdy) before writing a single direct OTA connector.** Classify every Binge with an explicit distribution type, and default all existing Binges to `DIRECT_ONLY` so nothing leaks to a channel by accident. Formally reject Booking.com / Expedia / Agoda / Priceline for this product.

The strategic posture this implies, and it should be stated out loud because it changes contracts as much as code: **SK Binge Galaxy is the *reservation system*; each Binge is the *supplier of record*.** GetYourGuide states plainly that *"resellers, aggregators, online travel agencies, destination management companies … are not accepted"* as supply partners — but a restech whose customers are the operators is exactly what Bókun, Ventrata, Peek and Rezdy are. SK Binge should occupy that seat, not try to sign as a supplier itself.

---

## 2. Distribution-readiness assessment

### 2.1 What is genuinely ready

| Capability | Evidence | Why it matters for distribution |
|---|---|---|
| Three-layer oversell defence | `SlotHold` `@Version` → `pg_advisory_xact_lock` → **V75 DB trigger** `[SOURCE-CODE EVIDENCE]` | Channel traffic is *concurrent, unfriendly* traffic. This is the single hardest thing to retrofit, and it is already done. |
| Provisional holds with TTL | [SlotHold.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/SlotHold.java) | OCTO/Viator both require reserve-then-confirm. Already modelled. |
| Transactional outbox | [OutboxEvent.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/OutboxEvent.java) — unique `eventId`, `attempts`, `failedPermanent`, `correlationId`, DLQ | Outbound channel sync is an outbox problem. The pattern and the poller already exist. |
| Idempotency | [IdempotencyKey.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/IdempotencyKey.java), composite PK + SHA-256 request hash | Resellers retry. Duplicate-suppression semantics already correct. |
| Immutable price snapshots | `BookingPriceSnapshot`, `taxBreakdownJson`, `fxRate`, `calculationVersion` | Commission reconciliation needs the price *as sold*, forever. |
| Per-venue timezone | `Binge.timezone` (IANA, change-request governed) | OCTO `Product.timeZone` is mandatory. Already correct. |
| Per-venue country/currency | `Binge.country` NOT NULL → derived `currency` | Channel currency + settlement resolution. |
| Tenancy + module gating | `binge_id` everywhere, 17-module `ModulePermissionInterceptor`, `BingeApprovalInterceptor` | A `DISTRIBUTION` module drops into an existing, enforced pattern. |
| Recovery queues + DLQ + admin consoles | `/admin/recovery`, `/admin/failed-refunds`, `AdminRecoveryQueueController` | A "failed channel sync" queue has a precedent to copy verbatim. |

**Verdict:** the plumbing is materially better than typical pre-distribution systems. The gaps are all in the *domain vocabulary*, not the infrastructure.

### 2.2 Blocking gaps — things distribution needs that do not exist

| Gap | Evidence | Consequence |
|---|---|---|
| ~~**G1. No setup/cleanup buffer (turnover time)**~~ | ✅ **CLOSED 2026-07-31 by migration `V81__turnover_buffers.sql`.** Occupancy is now `[start − setup, start + duration + cleanup)` end-to-end: `OccupancyWindow` + `TurnoverPolicy`, every conflict/capacity path in `BookingService`, `SlotHold`, the V81 database backstop, and the customer slot grid. Buffers are configured per venue (`Binge.defaultSetup/CleanupMinutes`) and overridable per event type, and are **snapshotted onto each booking and hold** so history stays reproducible. | Was: a channel could sell 19:00–21:00 and 21:00–23:00 back-to-back with zero reset time. **This was also a live defect for direct bookings, not only a distribution gap.** |
| ~~**G2. No machine/service-principal booking path**~~ | ✅ **CLOSED 2026-08-01.** `POST /api/v1/bookings/internal/reservations` ingests a channel reservation using the platform's **existing service-principal seam** — `X-Internal-Secret` → `InternalApiAuthFilter` → `ROLE_SYSTEM`, gated by `SecurityConfig` — rather than inventing a new identity system. Provider-neutral `ChannelReservationRequest`; guest identity travels in the payload; `customerId = 0` reuses the existing "no known customer" convention so loyalty and per-customer pricing already treat it as anonymous. **Idempotent** on `(externalSource, externalRef)`, so a channel retry converges instead of duplicating. Implemented as a thin adapter over `createBooking` so the channel path can never become a second booking truth. | — |
| ~~**G3. Customer-abuse guards would misfire on channel bookings**~~ | ✅ **CLOSED 2026-08-01.** The guards are extracted into `applyCustomerFunnelGuards(origin, …)` and genuinely branch on origin — pre-lock *and* post-lock, plus the pending-duplicate check. Now exercised by the real channel entry point (G2), so it is live code, not a dormant flag. Tests assert the guards are **never consulted** for CHANNEL (`verify(never())`, not stub-and-ignore) and still reject for DIRECT. | The distinction is *"is there a customer with a funnel to abuse?"* — never a blanket bypass. Approval, slot locking, occupancy windows, room capacity, operating hours, booking window and the DB backstop apply to **every** origin. Why it matters: every channel reservation shares `customerId = 0`, so an unpaid-limit or freeze check would begin rejecting unrelated *paid* reservations as soon as two were pending — silently, with no error a venue could see. |
| **G4. No stop-sell / allotment / safety-inventory concept** | Only `BlockedDate` + `BlockedSlot` + `RoomBlock` exist `[SOURCE-CODE EVIDENCE]` | No way to hold back N rooms from channels, or emergency-close a single channel without closing the venue. |
| ~~**G5. No minimum-notice or per-binge advance window**~~ | ✅ **CLOSED 2026-08-01 by `V84__booking_window_rules.sql`.** `Binge.minNoticeMinutes` + `Binge.maxAdvanceDays` (NULL inherits the platform default), enforced by `BookingWindowPolicy` on **both** the booking and the hold path, evaluated on the **venue's own clock** so the rule means the same thing in every country. | Was: channels sell at 23:58 for 00:30. |
| **G6. Rate codes are not date-ranged rate plans** | `RateCode` = named price list with per-event/per-addon overrides; no validity window `[SOURCE-CODE EVIDENCE]` | Channel rate plans are date-effective. Net-rate/commission-adjusted pricing has nowhere to live. |
| **G7. No commission, net-rate or payment-responsibility model** | grep: no `commission`, no `netRate`, no `paymentResponsibility` `[SOURCE-CODE EVIDENCE]` | Every channel deal is either merchant-of-record (channel collects) or agency (venue collects). Neither is representable. |
| ~~**G8. No external-reference or channel-origin field on `Booking`**~~ | ✅ **CLOSED 2026-08-01 by `V85__booking_origin.sql`.** `Booking.origin` (`DIRECT`/`ADMIN`/`CHANNEL`) + `externalSource`/`externalRef`, with DB CHECKs enforcing the pairing (a CHANNEL row without a reference, or a DIRECT row carrying one, is structurally impossible) and a partial unique index so a redelivered channel webhook cannot create a duplicate reservation. Provider-neutral: booking-service never learns a channel's name. | Reconciliation, cancellation matching and support triage all need it. |
| ~~**G9. Dual duration representation**~~ | ✅ **CLOSED 2026-08-01 by `V82__duration_minutes_canonical.sql`.** `duration_minutes` is NOT NULL with a CHECK (30–720, 30-minute steps); `Booking#getScheduledDurationMinutes()` is the single canonical accessor. **The audit found the rule duplicated six times and already drifted** — five copies guarded `> 0`, `ExportController` did not, so a zero-duration row exported as 0 minutes while every other subsystem read `durationHours * 60`. `durationHours` is retained, deprecated and documented as lossy, purely for the `BookingEvent` wire contract. | Was: two sources of truth for the most important field in a time-slot mapping. |
| **G10. No provider-sandbox proof for money** | `PR-PAY-01` is an open **P0 launch gate**: no end-to-end payment/refund has ever run against a provider sandbox `[docs/audit/11]` | Distribution multiplies money paths (channel-collect, virtual cards, commission). Shipping distribution before PR-PAY-01 closes stacks unproven money on unproven money. |

### 2.3 Contradictions found while reconciling documentation

| ID | Contradiction | Resolution used in this dossier |
|---|---|---|
| **X-1** | `docs/00-AUDIT-INDEX.md` declares itself **superseded (2026-07-25)** by `docs/audit/`, while the project memory index describes the `00-08` set as the fresh canonical audit. | `docs/audit/` (run `AUD-2026-07-25-01`, commit `6440f58`) treated as authoritative; `00-08` treated as historical. **The memory index entry is stale and should be corrected.** |
| **X-2** | `docs/audit/` contains **two overlapping generations under one numbering scheme** (two `01-`, two `02-`, two `05-`, two `06-`, two `07-`…). A reader cannot tell which `05-` is current from the filename. | Used the `*-CURRENT.md` files and the `AUD-2026-07-25-01`-stamped documents. Flagged as a documentation-hygiene defect. |
| **X-3** | Session-start git snapshot reported the tree **clean**; the real (nested) git root has **80 modified/untracked paths**. | Two git roots exist. All source statements taken from the nested working tree. This is the recurring "stale worktree" failure mode. |
| **X-4** | `docs/audit/21` says *"OTA/channel managers: none present … not claimed anywhere, no gap vs docs."* | **No contradiction** — source agrees. Confirmed by grep: zero channel/OTA/commission constructs in the codebase. |

---

## 3. Inventory classification model

### 3.1 What SK Binge actually sells `[SOURCE-CODE EVIDENCE]`

A sellable unit is `EventType @ Binge`, priced as:

```
base = EventType.basePrice
     + EventType.hourlyRate  × hours
     + EventType.pricePerGuest × guests
     + VenueRoom.priceAddition
     + Σ add-ons
→ RateCode override → CustomerPricingProfile → Surge → FX → Tax → BookingPriceSnapshot
```

Capacity is **two-dimensional and independent**:
- **Concurrency:** `VenueRoom.capacity` = simultaneous bookings that room supports; `Binge.maxConcurrentBookings` caps venue-wide.
- **Headcount:** `EventType.minGuests/maxGuests`, `Booking.numberOfGuests` — priced, but does **not** consume inventory.

This is the signature of a **private-hire / exclusive-use** product, not a per-seat product. It is closest to a *private charter* or *private tour* in OTA vocabulary — which is a well-supported shape in the experiences ecosystem (`pricingPer: BOOKING`) and an unsupported shape in the lodging ecosystem.

### 3.2 Proposed `distributionClassification` on `Binge` `[RECOMMENDATION]`

Not every Binge belongs to the same category, and the classification must be **explicit and defaulted closed**.

| Value | Definition | Channel eligibility | Mapping / pricing / reservation consequence |
|---|---|---|---|
| `EVENT_VENUE` | Whole-space private hire by the hour (private theatre, celebration room, proposal venue). **The core product.** | Experiences resellers via OCTO; venue/space marketplaces; Google Things-to-Do links | Product = EventType; `pricingPer: BOOKING`; availability = 30-min starts × permitted durations; exclusive-use → `vacancies` from room concurrency, **not** guest count |
| `EXPERIENCE` | Fixed-duration, fixed-start session with a defined headcount (screening, workshop, tasting). | Best fit of all — this is the native OCTO shape | Product = EventType, Option = start time, Unit = pax; `pricingPer: UNIT`; instant-confirm |
| `HYBRID` | A Binge selling both of the above from shared rooms. | Eligible, but **each EventType** must be classified, not the Binge | Two product families sharing one availability pool; the pool is the truth, per-product views are projections |
| `ACCOMMODATION` | Overnight stay inventory. **Does not exist in the current model** — no nights, no LOS, no check-in/check-out dates, no occupancy pricing. | None until the domain gains a nightly model | Reserved value. **Do not implement any lodging connector against it today.** |
| `REQUEST_TO_BOOK` | Venue requires manual acceptance before a reservation is firm. | Only channels supporting `PENDING` / on-request (OCTO `PENDING` status) | Suppresses `instantConfirmation`; needs an operator SLA + auto-decline timer, neither of which exists today |
| `DIRECT_ONLY` | Deliberately not distributed. | None | **The default for every existing Binge.** |
| `NOT_DISTRIBUTABLE` | Structurally ineligible — `PENDING_APPROVAL`/`REJECTED`, no approved rooms, no active EventType, missing country/timezone/geo, or a locked module matrix. | None | Computed, not chosen. Must be re-evaluated on every binge/room/event change. |

**Classification interacts with three existing gates and must not bypass any of them:** `BingeApprovalStatus`, the V71 module permission matrix, and `Binge.active`/`autoDeactivatedAt`. `[ARCHITECTURAL INFERENCE]` A venue that is frozen for direct customers must be frozen for channels *first* — fail-closed, in that order.

---

## 4. Rejected alternatives, and why

| Rejected | Reason | Evidence class |
|---|---|---|
| **Booking.com Connectivity API** | (a) per-night ARI cannot express a 3-hour evening slot; (b) *"pausing integrations with new connectivity providers until further notice"*; (c) accommodation-partner contract required per country. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Expedia Group lodging connectivity** | (a) same nightly model; (b) *"we're not accepting direct connections from individual properties"*; (c) chain agreements need 5+ properties / 75+ sellable rooms; (d) PCI compliance is a precondition — and PR-PAY-01 is still open. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Agoda YCS** | Nightly ARI; contact-and-certify gate with an assigned account manager; no self-serve route. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Priceline / other Booking Holdings routes** | Reached via the same Booking Holdings supply rails as Booking.com; inherits every objection above. No independent supply-connectivity product was found for hourly venue inventory. | `[UNVERIFIED — no official supply-side API for this product type located]` |
| **Airbnb (stays or Experiences)** | No public API; approved partners only, gated on data-security review and demonstrated scale; mandatory-feature compliance within 6 months of each release. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **SK Binge signing directly as a GetYourGuide / Viator supplier** | GYG: *"resellers, aggregators, online travel agencies, destination management companies … are not accepted."* SK Binge is an aggregator of third-party venues. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Headout "partner" programme as a distribution route** | The public programme is **demand-side distribution/affiliate** — resellers *selling Headout inventory*. It is not a supply API for pushing SK Binge inventory in. Exactly the confusion §5 of the brief warns about. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Viator Partner API** | Affiliate/demand API — lets you *sell Viator's* products. The supply-side product is the separate **Viator Supplier API**. Not interchangeable. | `[OFFICIAL PROVIDER EVIDENCE]` |
| **Building N direct OTA connectors first** | In a *pull* ecosystem this is strictly dominated: one OCTO server serves every OCTO-consuming reseller. Direct connectors are a Phase-3 optimisation for a channel that refuses OCTO, not a starting point. | `[ARCHITECTURAL INFERENCE]` |
| **Peerspace / Tagvenue / Giggster / Splacer as *integrations*** | Closest product-fit of any consumer marketplace (hourly space rental), but **no public supplier/connectivity API was located for any of them.** They are manual-listing marketplaces. | `[UNVERIFIED — treat as manual listing channels, DO NOT IMPLEMENT AS A REAL CONNECTOR]` |
| **Regional consumer ticketing platforms** (BookMyShow/District, Fever, Dice, Eventbrite-class) | No public supply-connectivity API located for venue-hire inventory. Consumer event ticketing, not distribution. | `[UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR]` |
| **A second inventory or booking store inside Distribution** | Violates the brief's non-negotiable and would create the classic dual-truth oversell bug the V75 trigger exists to prevent. | `[ARCHITECTURAL INFERENCE]` |

---

## 5. Risk register

| ID | Risk | Sev | Likelihood | Mitigation |
|---|---|---|---|---|
| **DIST-R1** | ~~Turnover oversell (G1)~~ — **CLOSED.** | ~~P0~~ → P3 | — | Buffers land on the **shared** availability pool (conflict detection, capacity counting, holds, the DB backstop and the customer grid), not a channel-only projection, so a future connector inherits them for free. The "defaults to 0" residual is closed by **V83**: new venues default to 30 min cleanup, existing venues are **deliberately left unchanged** (backfilling would retroactively widen the occupancy of bookings already sold and could make them overlap), and `turnover_policy_reviewed_at` drives an admin prompt until an operator decides. Choosing zero is a valid answer — the point is that it was chosen. |
| **DIST-R2** | **Dual-truth drift.** Distribution caches availability "for speed" and diverges from booking-service. | **P0** | Medium | Distribution stores *sync state and external references only*. Every availability answer is derived at read time from availability-service + booking-service. Enforce by review and by an architecture test. |
| **DIST-R3** | **Channel-origin bookings rejected by anti-abuse guards (G3).** | P1 | High | Explicit `BookingOrigin` policy object; channel-origin bookings skip *customer-abuse* guards but keep **every** capacity, approval, operating-hours and lock guard. Never a blanket bypass. |
| **DIST-R4** | **Money paths multiply before PR-PAY-01 closes (G10).** | **P0 (gate)** | Certain if ignored | Distribution Phase 1 ships **agency model only** (venue collects, channel commissions). No channel-collect / virtual-card handling until PR-PAY-01 is closed with sandbox evidence. |
| **DIST-R5** | **Credential sprawl.** Per-venue × per-channel API keys stored plaintext, repeating the `admin_token.txt`-in-git failure. | **P0** | Medium | Envelope-encrypted at rest, decrypted only in the Distribution service, never returned by any API (write-only fields, masked in UI), never logged. Rotation is a first-class admin action from day one. |
| **DIST-R6** | **Availability fan-out load.** Resellers poll calendars for 365 days × every product; naive implementation melts booking-service. | P1 | High | Dedicated read path with short-TTL cache + `Cache-Control`, per-reseller rate limits at the gateway, and OCTO's `availability/calendar` (coarse) vs `availability` (fine) split honoured properly. |
| **DIST-R7** | **Cancellation-semantics mismatch.** SK Binge uses `CancellationTier` (% by hours-before); OCTO/Viator express a single `cancellationCutoff`. | P1 | High | Publish the *most conservative* representable cutoff, and treat any channel cancellation as authoritative-but-reconciled. Never let a channel-side policy overwrite the venue's tier table. |
| **DIST-R8** | ~~Duration combinatorics~~ — **CLOSED 2026-08-01.** | ~~P1~~ → P3 | — | `EventType.permittedDurationsCsv` (V84, decision B5): a venue-configured allow-list of **at most 4** durations, enforced in `BookingWindowPolicy`, shape-guarded by a DB CHECK, and surfaced as toggle chips in the admin form. NULL restores free choice, so unconfigured venues behave exactly as before. |
| **DIST-R9** | **Tenant leakage across channels.** A reseller API key resolves to the wrong Binge. | **P0** | Low | API key → `(bingeId, channelId)` binding resolved server-side only; every query re-scoped by `bingeId`; contract tests that assert a key can never read another Binge's product. Mirrors the existing `requireManagedBinge` seam. |
| **DIST-R10** | **Provider spec drift** (OCTO 2.0 is in community review). | P2 | Medium | Version the connector surface; pin to OCTO 1.0 stable; treat 2.0 as an additive migration with a compatibility window. |
| **DIST-R11** | **Commercial dead end.** Venues sign channel contracts SK Binge cannot service, or resellers decline a multi-supplier restech. | P2 | Medium | Validate with **one** real venue + **one** real reseller before Phase 2 spend. Do not build 5 connectors on an unvalidated commercial premise. |
| **DIST-R12** | **Stale-worktree deployment (X-3).** Distribution work lands in an uncommitted tree and the deployed build diverges. | P1 | High (has already recurred) | Commit before implementation starts; verify `git status` clean at each phase boundary. |

---

## 6. Unknowns requiring provider or business confirmation

Recorded honestly rather than guessed. **None of the still-open items blocks Phases 0–4** — that is a deliberate property of the OCTO-first sequencing, which is why it was chosen.

### 6.1 RESOLVED — business decisions taken 2026-07-31

| ID | Decision | Rationale | Consequence |
|---|---|---|---|
| **B1** | ✅ **Global. No lead market.** SK Binge targets venues worldwide; there is no India-first phase. | Owner decision, 2026-07-31. The platform is already structurally ready: `Binge.country` is required and load-bearing (currency derived, timezone seeded, tax rules and payment methods resolved from it). | Channel strategy must be **country-agnostic at the architecture layer and country-resolved at the connection layer** — see [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §4.2. Adds `Channel.supportedCountries` validation. **Promotes `GLB-01` (English-only UI) from P3 to P2.** |
| **B2** | ✅ **The venue is the supplier of record; SK Binge is the reservation system (restech).** Channel contracts are signed per venue, not centrally. | The only structure that works: GetYourGuide explicitly excludes *"resellers, aggregators, online travel agencies"* from being supply partners, while Expedia's connectivity route is built precisely for *"a property management software company, a channel manager, a central reservation system, or another lodging connectivity application."* **Global scope makes this decisive rather than merely preferable** — SK Binge cannot plausibly be merchant of record across every jurisdiction it will operate in. | Credentials are stored **per venue × per channel**, never platform-wide. Liability, insurance and local licensing sit with the venue. The admin UI must make it unmistakable that the venue is contracting with the channel, not SK Binge. |
| **B3** | ✅ **Agency model only through Phase 5** — the venue collects payment, the channel takes commission. No channel-collected money, no virtual cards. | `PR-PAY-01` (no provider-sandbox proof of any payment or refund) is still an open **P0**. Taking on merchant-model settlement across many countries before the single-country direct path is proven would stack unproven money on unproven money. Keeping PAN out of scope also keeps PCI out of scope. | Gap **G7** (net rate / commission / payment responsibility) is deferred to **Phase 6**, and only if a channel demands it. Payment-service is untouched by Phases 1–5. |
| **B4** | ✅ **Instant-book only. `REQUEST_TO_BOOK` is reserved, not built.** | It requires an operator response SLA and an auto-decline timer, neither of which exists. Shipping on-request inventory without them produces expired reservations and channel penalties. | The enum value exists in the classification model so the door stays open; no code path implements it. |
| **B5** | ✅ **Venue-configured allow-list per EventType, capped at 4 durations**, defaulting to a platform-suggested set derived from `minHours`/`maxHours`. | Resolves DIST-R8 without taking the choice away from venues. Four options is the point where a channel's option picker stays usable and the availability calendar stays finite. | Phase-1 work. Surfaced in the Listings screen ([03](03-ADMIN-UX-DESIGN.md) §4.3) as the `DURATIONS` column. |

### 6.2 STILL OPEN — provider/technical questions requiring contact, not research

| ID | Open question | Blocks | Who answers |
|---|---|---|---|
| **P1** | Bókun vs Rezdy commercial terms for a **restech / channel-manager-API** integration (not an operator plan): fees, certification duration, whether a multi-supplier reservation system is accepted. Both publish a "connect your own reservation system" route; **neither publishes terms.** | Phase 5 | Direct contact |
| **P2** | Whether any experiences reseller will accept **private venue hire** as a listable product category. Viator's Product Acceptance Criteria page returned **HTTP 403** to automated retrieval; GetYourGuide's restricted-activities list is silent either way. **Genuinely unresolved — must be asked, not researched.** | Phase 5 | Direct contact — **ask first, it is the cheapest question here** |
| **P3** | Whether resellers accept OCTO `availabilityType: START_TIME` for *variable-duration* products, or require one product per duration. B5 caps this at 4 durations, which makes either answer survivable. | Phase 3 refinement | Reseller onboarding |
| **P4** | Google Actions Center: the Reservations e2e vertical documents **restaurants**; appointment verticals were reorganised after the 2024 Reserve-with-Google sunset. Whether a venue-hire merchant category is accepted is **unverified**. Note it also requires *"a direct contractual relationship with all the merchants included in their integration feed"* — which B2's per-venue contracting model plausibly satisfies. | Phase 6 | Google partnerships |
| **P5** | Google Things to Do: *"Google doesn't currently allow individual operators to upload listings directly"* — but SK Binge as a multi-venue reservation technology company may qualify as a **direct integration partner**. Requires interest form + content licence agreement. | Phase 6 | Google partnerships |
| **P6** | Whether Peerspace / Tagvenue / Giggster / Splacer have **private** partner APIs. Nothing public exists. `[UNVERIFIED]` | Never blocks — manual listing is a business action | Direct contact |

**Do not implement a connector for any item above while it remains unverified.**
