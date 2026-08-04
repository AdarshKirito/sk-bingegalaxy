# Recommended Target Architecture and Sequencing

> Research date **2026-07-31**. Depends on [00-RESEARCH-DOSSIER.md](00-RESEARCH-DOSSIER.md) and [01-PROVIDER-EVIDENCE-AND-FIT.md](01-PROVIDER-EVIDENCE-AND-FIT.md). **Nothing here has been implemented.**
>
> **Accepted 2026-07-31:** strategy = **HYBRID, OCTO-first** · first connector = **OCTO simulator + conformance suite** · scope = **global, worldwide from the start**. Business decisions B1–B5 resolved in [00](00-RESEARCH-DOSSIER.md) §6.1.

---

## 1. Direct integration vs aggregator

### 1.1 Direct provider connectors

| Dimension | Assessment |
|---|---|
| Certification effort | High and **per provider**. Viator: dev → API-account-manager testing → pilot product → launch. Agoda: certification tests with an account manager. Each is weeks of calendar time you do not control. |
| Partner approval | The binding constraint, not the code. GetYourGuide will not accept SK Binge as a supplier at all; venues must sign individually. |
| Development effort | Moderate per connector — but the *ecosystem is pull-based*, so most of the work (product catalogue, availability, hold/confirm) is **the same work repeated in different dialects**. |
| Maintenance & API-change burden | Linear in connector count, forever. |
| Provider-specific operations | Each channel brings its own reconciliation, dispute and support workflow. |
| Country coverage | Narrow per connector. |
| Control | Highest — own the guest data, the terms, the rate. |
| Cost | Highest total; lowest marginal revenue share. |
| Time to market | Slowest. |
| Long-term strategic value | High **only** for a channel that drives real volume. Unknowable before the first one runs. |

### 1.2 Certified aggregator / channel manager

| Dimension | Bókun (Tripadvisor) | Rezdy |
|---|---|---|
| Supported providers | 2,600+ resellers; **live direct availability sync for exactly three: Viator, GetYourGuide, Expedia Local Expert**. Everything else via its marketplace on contracted terms. | 25,000+ resellers via marketplace + Distribution Manager |
| Hourly / event inventory | Yes — the tours & activities model is time-slot native | Yes — sessions with start times |
| API quality | Channel Manager API with gRPC **and** REST transports; documented plugin lifecycle (registration, definition, mapping, shallow & deep availability, reservation, booking, cancel, amend, failure handling, security). Also publishes an **OCTO API**. | RezdyConnect: two-step reservation & confirmation, availability pull/push, content, barcodes/QR |
| Mapping flexibility | Plugin-defined product mapping | Distribution Manager or API |
| Webhooks / reconciliation | Covered by the plugin lifecycle | Two-way live sync |
| Pricing | Not published for restech plugins; operator plans from ~$49/mo, 0.5–1.5% channel-manager fee on reservations | Not published |
| **Vendor lock-in** | **Real.** Bókun becomes the system of record for channel mappings and reseller relationships. | Same |
| Data & credential ownership | Aggregator holds the reseller relationships | Same |
| Certification inherited | **Yes — the main prize.** Their certification with Viator/GYG/Expedia substitutes for yours. | Yes |
| Payment / virtual cards | Handled by the aggregator's contracted terms | Same |
| Exit strategy | Painful if SK Binge's canonical model was never independent | Same — **mitigated entirely by owning a canonical model first** |

### 1.3 Hybrid

Aggregator for reach, direct for strategic channels, **one canonical model underneath both**, and no provider vocabulary anywhere inside booking-service. This is the standard shape and it is the right one here — but the ordering matters, and the obvious ordering is wrong.

### 1.4 Decision

`[RECOMMENDATION]` **HYBRID, but sequenced OCTO-first — not aggregator-first and not direct-first.**

The reasoning that decides it: **in a pull ecosystem, the "connector" and the "canonical model" are the same artifact.** An OCTO-compliant supplier API *is* the anti-corruption layer, *is* the reseller-facing connector, and *is* the thing an aggregator would otherwise wrap. Building it first means:

- It is the **cheapest possible aggregator on-ramp** — Bókun already speaks OCTO; Rezdy's RezdyConnect maps to the same reserve/confirm shape.
- It is the **only** distribution asset that requires **nobody's approval to build** — the spec is free and open, so the whole of Phase 1 can complete while commercial conversations (B2, B3, P1, P2) run in parallel instead of blocking.
- It **eliminates the lock-in objection to Phase 2**. Signing with Bókun after owning an OCTO surface is a commercial decision that can be reversed. Signing before is an architecture decision that cannot.

Choosing `AGGREGATOR_FIRST` would mean paying for reach before knowing whether any reseller will list private venue hire (unresolved P2), and doing it on top of a canonical model that does not exist yet. Choosing `DIRECT_FIRST` would mean N× the work for 1× the reach. Choosing `EVENT_MARKETPLACE_FIRST` is the correct *commercial* move for Peerspace-class marketplaces — but they have no API, so it is a business action, not an engineering phase.

---

## 2. Patterns worth borrowing from mature systems — and the ones to skip

`[ARCHITECTURAL INFERENCE]` Patterns, not implementations.

### Apply

| Pattern | How it lands here |
|---|---|
| **Canonical inventory model** | One internal representation; provider dialects are edge translations only. Already 80% present in booking-service. |
| **Provider anti-corruption layer** | Zero provider types in booking-service. The OCTO surface *is* the ACL. |
| **Mapping system** | `(bingeId, eventTypeId, channelId) → externalProductId/optionId/unitId`, versioned, with a mapping status and a "never auto-remap" rule. |
| **Reservation inbox** | Inbound channel reservations land in an inbox row **first**, then attempt canonical booking creation. Never lose a reservation because a guard rejected it. Mirrors the existing `ProcessedEvent`/inbox pattern. |
| **Outbox delivery + version ordering + dedup** | Already built (`OutboxEvent` with unique `eventId`, attempts, `failedPermanent`, DLQ). Reuse verbatim for outbound channel sync. |
| **Delivery attempts & provider health** | Per-connection success rate, last sync, last error, latency — an ops surface, not a log grep. |
| **Reconciliation** | Nightly compare of channel-side bookings vs canonical bookings; discrepancies to a queue, not an alert email. Copy the shape of `PaymentReconciliationScheduler`'s receipt-first pattern. |
| **Recovery queues** | Copy `/admin/recovery` and `/admin/failed-refunds` exactly. Operators already know the interaction. |
| **Stop-sell & safety inventory** | Per-connection kill switch + hold-back of N concurrent slots from channels. Fills gap G4. |
| **Tenant isolation** | API key → `(bingeId, channelId)`, re-scoped on every query. The `requireManagedBinge` seam already models this. |
| **Idempotent connector operations** | Reseller `uuid` → existing `IdempotencyKey` table. |

### Skip (unnecessary or actively harmful here)

| Pattern | Why not |
|---|---|
| Nightly ARI push pipelines with delta computation | The ecosystem **pulls**. Building push infrastructure would be inventing work. |
| Allotments (contracted room blocks per channel) | A lodging wholesale concept. SK Binge's exclusive-use inventory has nothing to allot. Safety inventory covers the real need. |
| GDS / OpenTravel / HTNG connectivity | Airline/hotel-era XML for nightly inventory. **No fit whatsoever** for 30-minute venue slots. |
| A separate distribution inventory store | The dual-truth bug the V75 trigger exists to prevent (DIST-R2). |
| Length-of-stay pricing, occupancy-based rates, CTA/CTD restrictions | Lodging vocabulary with no product meaning here. |
| A full CRS | SK Binge already *is* the CRS for its venues. |

---

## 3. Recommended target architecture

`[RECOMMENDATION]` **A standalone `distribution-service` (a 10th Maven module), as a true bounded context — not a package inside booking-service.**

Four reasons, in order of weight:
1. **Blast radius.** `BookingService.java` is 5,189 lines and the audit names it the top source of regressions. Distribution must not add to it.
2. **A different trust boundary.** Reseller traffic is machine-to-machine Bearer-API-key, not user JWT. It needs its own authentication, its own rate limits, and its own CSRF exemption. Mixing that into a JWT-only service is how boundary bugs happen.
3. **A different failure posture.** A channel outage must never degrade direct booking. Separate process, separate pool, separate deploy.
4. **The pattern is already established** — 9 modules, Eureka, config-server, per-service Postgres, Feign + Kafka. This is the 10th, not a novelty.

```
                      resellers (Viator · Klook · Bókun · Tiqets · …)
                                   │  Bearer API key (per reseller↔binge pair)
                                   ▼
   ┌──────────────  API Gateway :8090  ──────────────┐
   │  NEW route: /api/v1/octo/**  →  distribution     │
   │   · exempt from JwtAuthenticationFilter          │
   │   · exempt from CsrfProtectionFilter (no cookies)│
   │   · dedicated per-API-key rate limit bucket      │
   └───────────────────────┬──────────────────────────┘
                           ▼
        ┌──────────  distribution-service :8086  ──────────┐
        │  OCTO surface   /octo/supplier /products         │
        │                 /availability /availability/calendar
        │                 /bookings  /bookings/{uuid}/confirm|cancel
        │  ── owns ONLY ──────────────────────────────────  │
        │  Channel (+ supportedCountries) · ChannelConnection│
        │            (encrypted creds, per venue × channel) │
        │  ProductMapping · ReservationInbox                │
        │  SyncState · DeliveryAttempt · ProviderHealth     │
        │  ChannelBookingRef · ReconciliationRun            │
        │  StopSell / SafetyInventory                       │
        └───┬──────────────────────┬──────────────────┬─────┘
            │ Feign /internal      │ Feign /internal  │ Kafka (consume)
            ▼                      ▼                  ▼
   availability-service      booking-service    booking.created / .confirmed
   (the 30-min grid,         (canonical         .cancelled / .rescheduled
    blocks, closures)         reservations,      → invalidate + push updates
                              pricing, holds)
```

**Non-negotiable invariants**
1. `distribution_db` contains **no availability rows and no booking rows.** Every availability answer is derived at read time; every reservation is a canonical booking-service booking with an external reference attached.
2. **Zero provider vocabulary in booking-service.** No `octoProductId`, no `viatorRef`. The only booking-service additions are provider-neutral: an origin discriminator and an external-reference pair.
3. **Fail-closed ordering.** Approval status → `Binge.active` → module matrix → distribution classification → channel connection state. A venue frozen for direct customers is frozen for channels first.

### 3.1 New booking-service concepts required (provider-neutral)

`[RECOMMENDATION]` The minimum vocabulary that must exist before any channel can be connected. Migrations are Phase-1 work, **not** part of this research phase. Next free heads per project memory: **booking V79+** *(note: entities already reference V80 for the loyalty config lock — confirm the true head before writing any migration)*, auth V20, payment V16.

| Concept | Shape | Fills |
|---|---|---|
| **Turnover buffers** | `EventType.setupMinutes`, `EventType.cleanupMinutes` (or per-room); consumed by availability + the advisory-locked conflict check **and** by the channel projection | **G1 / DIST-R1 — the P0 blocker** |
| **Booking origin** | `Booking.origin` ∈ `DIRECT` \| `ADMIN` \| `CHANNEL`, + `externalSource`, `externalRef` | G8, G3 |
| **Channel guest identity** | A first-class non-login guest customer record, so channel bookings need no fake user | G2 |
| **Anti-abuse policy object** | Guards declared per origin: capacity/approval/hours guards **always**; unpaid-limit, pending-duplicate, freeze, risk-flag guards **direct-only** | G3 / DIST-R3 |
| **Minimum notice & per-binge horizon** | `Binge.minNoticeMinutes`, `Binge.maxAdvanceDays` | G5 |
| **Permitted durations** | Allow-list per EventType (e.g. `[120, 180, 240]`) | DIST-R8 |
| **Duration normalisation** | Retire `durationHours`; `durationMinutes` becomes the single truth | G9 |
| **Net rate / commission** *(Phase 3 only)* | Per-connection commission %, `netAmount` on the price snapshot | G7 |

### 3.2 Gateway changes — the known seam

`[SOURCE-CODE EVIDENCE]` Adding a service touches **three** places, and missing any one produces the classic 403 storm:
1. `config-server/.../configurations/api-gateway.yml` — a new `- id: distribution-service` route.
2. `JwtAuthenticationFilter` — `/api/v1/octo/**` must be reachable **without** a JWT and must not be caught by `isAdminPath`/`isSuperAdminPath`; the admin console paths `/api/v1/distribution/admin/**` must be.
3. `CsrfProtectionFilter` — exempt the OCTO path. It is Bearer-authenticated with no cookies, so double-submit CSRF is both inapplicable and breaking.

---

## 4. Source-of-truth map

| Owns | Service | Distribution may |
|---|---|---|
| **Canonical reservations** — creation, state machine, cancellation, reschedule, transfer, check-in | **booking-service** | Request creation via an internal API; read; attach an external reference. **Never write booking state directly.** |
| **Inventory & availability** — 30-min grid, operating hours, blocked dates/slots, room blocks, room concurrency, turnover buffers | **availability-service + booking-service** | Read and project. **Never cache as truth. Never hold a second copy.** |
| **Pricing** — base/rate code/customer profile/surge/FX/tax → `BookingPriceSnapshot` | **booking-service** | Read resolved prices. **Never compute a price.** Channel net-rate arithmetic is applied *by booking-service* from a Distribution-supplied commission rate. |
| **SK-controlled money movement** — orders, callbacks, refunds, disputes, reconciliation | **payment-service** | Record who is responsible for collection. **Never move money.** |
| **Identity & access** — users, roles, sessions, delegation, gateway trust boundary | **auth-service + api-gateway** | Authenticate *resellers* by API key within its own boundary. **Never mint a user JWT.** |
| **Notifications** | **notification-service** | Emit events; never send directly. |
| **Connections, mappings, external references, sync state, delivery attempts, reconciliation, provider health, stop-sell, safety inventory** | **distribution-service** | Sole owner. |

**Two rules stated as absolutes, because they are:** *No second inventory truth. No second booking truth.*

---

## 5. Recommended first connector

`[RECOMMENDATION]` **A self-hosted OCTO provider simulator, validated against the public OCTO specification and at least one public reference implementation (Ventrata / Peek / Bókun docs) — then Bókun as the first *real* counterparty.**

Chosen against the brief's criteria:

| Criterion | Why the simulator wins |
|---|---|
| Actual official access | The only option requiring **no** approval. Everything else is blocked on P1/P2. |
| Product compatibility | Proves the hardest mapping questions (variable duration, buffers, exclusive-use capacity, tiered cancellation) **before** anyone commercial sees them. |
| Sandbox availability | It *is* the sandbox — deterministic, offline, CI-runnable, no rate limits. |
| Certification feasibility | Nothing to certify; and its passing test suite becomes the evidence pack for every later certification. |
| Country coverage | Neutral. |
| Ability to test safely | **Decisive.** SK Binge has never proven an end-to-end money path against any provider sandbox (PR-PAY-01). Practising oversell and double-book scenarios against a live reseller is not an option. |
| Value to customers | Zero directly — and this is the honest trade. It buys correctness before exposure. |
| Operational burden | Lowest. |

The simulator is **not** a fake connector in the sense the brief prohibits. It does not pretend a provider relationship exists; it is a conformance harness that exercises the real OCTO surface, and it must never be reachable in production.

**Second connector, once P1/P2 answer:** **Bókun**, because it is the only counterparty that (a) already speaks OCTO, (b) carries inherited certification to Viator + GetYourGuide + Expedia Local Expert in one step, and (c) is reversible given an OCTO surface already owned. **Rezdy is the fallback** if Bókun's restech terms are unfavourable — its RezdyConnect two-step reservation flow maps to the same domain.

**Run in parallel, as a business track, not an engineering one:** Google Things to Do (interest form + content licence) and manual listings on Peerspace/Tagvenue/Giggster-class marketplaces. **In any market where experiences-OTA coverage of private-venue hire is thin — which is most markets outside Europe and North America — these plausibly out-earn every OTA in the list.** Things to Do needs only a product feed and a deep link, so no availability truth leaves the platform; the space marketplaces are the closest product-category match found anywhere, and cost engineering nothing because they have no API.

---

## 6. Estimated implementation phases

Sizes are **relative effort**, not calendar commitments.

| Phase | Scope | Exit criteria | Size |
|---|---|---|---|
| **0 — Prerequisites** *(blocking)* | Commit the 80-file working tree. Close **PR-PAY-01** with sandbox evidence. Correct the memory index (X-1) and the `docs/audit/` double-numbering (X-2). ~~Answer B1, B2, B3~~ — **resolved 2026-07-31**, see [00](00-RESEARCH-DOSSIER.md) §6.1. | `git status` clean; payment sandbox proof recorded | S |
| **1 — Domain readiness** *(no provider code)* | ✅ **COMPLETE.** Turnover buffers G1 (V81) · duration normalisation G9 (V82) · safe buffer defaults + review prompt (V83) · min-notice & per-binge horizon G5 + permitted-duration allow-list B5 (V84) · `BookingOrigin` + external references G8 (V85) · **G3 origin-scoped guards genuinely branching** · **G2 channel ingestion** over the existing internal service-principal seam. **TEST-01 closed** — `OccupancyBackstopIT` runs the real Flyway chain + trigger against real PostgreSQL, verified passing 14/14. Plus two defects found and fixed on the way: a **live CI-breaking** `.mvn/jvm.config`, and **internal service endpoints being reachable from the internet** (gateway now 404s `/internal/**` and strips `X-Internal-Secret`). | Direct booking behaviour unchanged ✅ · buffers provably prevent back-to-back sales ✅ · channel reservations bypass funnel guards but no venue guard ✅ · ⚠️ advisory-lock **contention** coverage still outstanding (the IT proves the trigger, not concurrent writers) | **L** — done |
| **2 — Distribution service skeleton** | New module + `distribution_db` + Eureka + config + the three gateway seams. Entities: `Channel`, `ChannelConnection`, `ProductMapping`, `ReservationInbox`, `SyncState`, `DeliveryAttempt`, `ProviderHealth`, `StopSell`. Encrypted credential store. `distributionClassification` on `Binge`, defaulted `DIRECT_ONLY`. New `DISTRIBUTION` module key in the V71 matrix + `AuthorityScope`. | Service boots, health-green, gateway routes correct, zero customer-visible change | M |
| **3 — OCTO surface + simulator** | `/octo/supplier`, `/products`, `/availability`, `/availability/calendar`, `/bookings`, `/confirm`, `/cancel`. Capabilities: **pricing** (`pricingPer: BOOKING`, itemised `includedTaxes`), **content**, **extras** (add-ons). Reservation → `SlotHold`; confirm → canonical booking. Simulator + conformance suite in CI. | Full reserve→confirm→cancel against the simulator; oversell impossible under concurrent simulated resellers; **no booking row in `distribution_db`** | **L** |
| **4 — Admin console** | See [03-ADMIN-UX-DESIGN.md](03-ADMIN-UX-DESIGN.md). Ships **with** Phase 3, not after — an unobservable connector is an unoperatable one. | An admin can classify, connect, map, publish, stop-sell and diagnose without a developer | M |
| **5 — First real counterparty** | Bókun (or Rezdy). Mapping UI hardened. Reconciliation scheduler. Provider health + alerting. **Agency model only.** | One real venue live on one real channel; reconciliation clean for 14 consecutive days | M |
| **6 — Reach & optional depth** | Additional OCTO resellers (near-zero marginal cost). Google Things to Do feed. Merchant-model money + commission (G7) **only if** PR-PAY-01 is long closed and a channel demands it. Direct connectors only where a channel refuses OCTO **and** has proven volume. | Per-channel decision, each with its own business case | Variable |

**Two ordering rules that should not be negotiated:** Phase 1 before Phase 3 — publishing availability that ignores turnover buffers is how a venue ends up double-staffed on a Saturday night. Phase 4 with Phase 3 — a channel you cannot see is a channel you cannot switch off.

---

## 7. Certification & partnership prerequisites

| Counterparty | Prerequisite | Status |
|---|---|---|
| **OCTO** | None. Free, open, no membership. Optionally register as an implementation for visibility. | ✅ Available now |
| **Bókun** | Custom Channel Manager API plugin, tested with their API team; commercial terms for a **restech** (not operator) integration | ❓ **P1 — terms not published, must ask** |
| **Rezdy** | Discovery call → technical spec review → agreed integration plan | ❓ **P1 — terms not published** |
| **Viator** | Supplier API dev → account-manager testing → **pilot product** → launch. Product Acceptance Criteria compliance. Venue is the supplier of record. | ❓ **P2 — venue-hire eligibility unresolved (page returned HTTP 403)** |
| **GetYourGuide** | **Venue signs, not SK Binge.** Legally operating business + valid insurance. Restricted-activities + quality/safety/animal-welfare compliance. | ⛔ SK Binge ineligible as supplier; venue-level route open |
| **Klook** | Merchant/reservation-system integration; contact required | ❓ Not started |
| **Tiqets** | Supplier API implementation (spec is public); commercial agreement | ❓ Not started |
| **Google Things to Do** | Meet development requirements → interest form → **content licence agreement** → technical contact. Feed ≥ every 30 days or products are removed. | ❓ **P5 — eligibility as a multi-venue restech unconfirmed** |
| **Google Actions Center (Reservations)** | Direct contractual relationship with every merchant in the feed; Maps location matching; sandbox booking server before production; basic-auth credentials rotated 6-monthly | ❓ **P4 — venue-hire vertical eligibility unverified** |
| **Booking.com / Expedia / Agoda / Airbnb** | — | ⛔ **Rejected.** See [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §2.4. |
| **Internal** | PR-PAY-01 closed · secrets purged from git history (P0-2 open) · working tree committed · DPDP/GDPR erasure fan-out extended to Distribution | ⛔ **Phase-0 blockers** |

---

## 8. Research quality gate — self-assessment

| Gate requirement | Met | Evidence |
|---|---|---|
| Current repository behavior inspected | ✅ | Entities, availability slot generation, `createBooking` guard chain, outbox, idempotency, gateway filters, gateway route table, admin nav & CSS system all read from the working tree |
| Authoritative project documentation reconciled | ✅ | `docs/audit/` (AUD-2026-07-25-01) adopted as authoritative over the superseded `00-08` set; **4 contradictions logged** (X-1…X-4) |
| Official provider documentation used | ✅ | OCTO, Viator, GetYourGuide, Klook, Tiqets, Bókun, Rezdy, Booking.com, Expedia, Agoda, Airbnb, Google — all primary sources, cited with retrieval dates |
| Supply APIs distinguished from affiliate APIs | ✅ | [01](01-PROVIDER-EVIDENCE-AND-FIT.md) §1 — Viator Supplier vs Partner; GYG Supply vs Affiliate; Headout's public programme identified as **demand-side** |
| Event inventory distinguished from lodging inventory | ✅ | Per-night ARI (`<date from…to>`, `minimumstay`, `closedonarrival`, "24:00 in the hotel timezone") vs OCTO `localDateTimeStart/End` |
| Commercial & certification access investigated | ✅ | Booking.com pause · Expedia property exclusion · GYG aggregator exclusion · Airbnb approval gate · Agoda certification · Google contractual requirement |
| Direct and aggregator strategies compared | ✅ | §1 + scored matrix |
| Product compatibility demonstrated | ✅ | 30-row capability-level fit matrix with explicit losses named |
| Unknowns explicitly identified | ✅ | 11 unknowns (B1–B5, P1–P6) in [00](00-RESEARCH-DOSSIER.md) §6 |
| No undocumented API invented | ✅ | Everything unfound is marked `UNVERIFIED — DO NOT IMPLEMENT AS A REAL CONNECTOR` |
| No provider marked production-ready without evidence | ✅ | The recommended first connector is a **simulator**, precisely because no provider relationship is proven |

**Gate: PASSED**, with two honest caveats — Viator's Product Acceptance Criteria page was unreadable (HTTP 403) and Expedia's/Agoda's deep docs sit behind JS portals, so their ARI granularity is evidenced via Booking.com's equivalent public spec plus each provider's own access statements rather than their own field lists.
