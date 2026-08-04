# Admin UI/UX Design — Distribution Console

> Research date **2026-07-31**. Design only; nothing built. Grounded in the **existing** frontend so it looks and behaves like the console operators already use.
>
> **Reflects the accepted decisions:** global scope (B1) · venue is the supplier of record (B2) · agency payment model only (B3) · instant-book only (B4) · ≤4 venue-configured durations per event type (B5).

---

## 1. Who this is for, and the one design problem

Two very different operators:

| Persona | Mental model | What they must never be asked |
|---|---|---|
| **Venue ADMIN** — runs one Binge, is not technical | *"I want more bookings. Put my venue on the internet."* | To understand API keys, product mapping, sync cursors, or the word "connectivity" |
| **SUPER_ADMIN** — runs the platform | *"Which venue/channel pairs are broken, and why?"* | To read logs to answer that |

**The one design problem:** channel distribution has a large, genuinely complex state space — connection health, mapping status, publish state, sync lag, reservation inbox, reconciliation drift — and the venue admin must operate it without learning any of it.

**The answer used throughout:** *the admin declares intent; the system reports truth.* The admin says "sell this event type on this channel." Everything else — mapping, publishing, syncing, retrying — is machinery that reports its own status in plain language and asks for help only when a human decision is genuinely required.

This is the same posture the existing consoles already take (`/admin/recovery` surfaces failure modes as named tabs with a one-click fix, not as raw queue rows), so it will feel native rather than bolted on.

---

## 2. Design constraints inherited from the codebase

`[SOURCE-CODE EVIDENCE]` These are not preferences — building against them is what makes the feature cheap and consistent.

| Constraint | Consequence for this design |
|---|---|
| Two CSS vocabularies: `.adm-*` (AdminPages.css) and `.admin-*` / `.modal-*` / `.form-row` (styles/admin-system.css, imported globally in `main.jsx`) | **Use the `.admin-*` vocabulary + `.adm-table-wrap`.** Do not introduce a third. |
| `components/ui/` primitives: `Button`, `Card`, `ConfirmDialog`, `FormField`, `LazyImage`, `Modal`, `PageHeader`, `Pagination`, `Spinner`, `Skeleton*` | Compose from these. Zero new primitives needed. |
| `Navbar.jsx` uses `NavDropdownGroup` for grouped nav, with items hidden by the V71 module matrix | Distribution is a **new nav group**, gated by a new `DISTRIBUTION` module key |
| `/admin/recovery`, `/admin/failed-refunds`, `/admin/approvals` establish the queue → inspect → act pattern | Copy it exactly for the reservation inbox and failed-sync queue |
| `AdminSseController` already streams admin events (`/api/v1/bookings/admin/events/stream`) | **Reuse SSE for live sync status.** No polling loop. |
| `ConfirmProvider` + `ConfirmDialog` exist | Every destructive action (disconnect, unpublish, force-resync) goes through it |
| Dual sign-off exists for RATE_CODES, SURGE_RULES, DISPUTES, FAILED_REFUNDS | **Distribution belongs in this set** — see §7 |
| The audit names `AdminBookings.jsx` (2,385 lines) and `BingeManagement.jsx` (2,029) as regression sources | **Hard cap: no distribution page over ~500 lines.** Split by route, not by conditional rendering. |
| UI is English-only (GLB-01) | Use the `t('key', 'Default')` pattern already in `Navbar.jsx` so strings are extractable later |

---

## 3. Information architecture

A new nav group, **five routes**, ordered by how often they are opened:

```
Distribution  ▾                                  [module: DISTRIBUTION]
├── /admin/distribution              Overview      ← the daily screen
├── /admin/distribution/channels     Channels      ← connect / disconnect
├── /admin/distribution/listings     Listings      ← what is on sale where
├── /admin/distribution/reservations Reservations  ← inbound channel bookings
└── /admin/distribution/health       Diagnostics   ← super-admin, cross-binge
```

**Why five and not one:** the recovery console proves tabs work for *variations on one task*. These are five different tasks with different audiences and different urgency. Splitting by route also enforces the 500-line cap and keeps deep-links usable in support threads.

Venue admins live in Overview and Reservations. Channels and Listings are setup screens, visited rarely. Diagnostics is super-admin-only and cross-binge.

---

## 4. Screen designs

### 4.1 Overview — the daily screen

Answers three questions in under two seconds: *Is anything broken? Is anything selling? Is anything waiting for me?*

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Distribution                                        [ Connect a channel ]│
│  Sell Moonlight Theatre on partner sites. 2 channels live.               │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ⚠  Bókun stopped syncing 3 hours ago                       [ Fix this ] │  ← only when true
│     Your listings are still on sale but availability may be stale.       │
│                                                                          │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐        │
│  │ Live        │ │ Bookings    │ │ Revenue     │ │ Needs you   │        │
│  │ 2 channels  │ │ 14 this wk  │ │ ₹48,200     │ │ 1 item      │        │
│  │ 6 listings  │ │ ▲ 3 vs last │ │ this month  │ │ 1 unmapped  │        │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘        │
│                                                                          │
│  Channels                                                                │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │ ● Bókun          Syncing…      6 listings   9 bookings   [Manage]  │ │
│  │ ● Google         Healthy       4 listings   5 bookings   [Manage]  │ │
│  │ ○ Viator         Not connected                           [Connect] │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  Recent channel bookings                              [ View all → ]     │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │ Bókun   BK-8842   Birthday · 4 Aug 19:00–22:00 · 12 guests  ₹8,400 │ │
│  │ Google  GG-1190   Proposal · 4 Aug 20:00–22:00 ·  2 guests  ₹6,200 │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

**Rules that make it work**
- **The banner is the whole design.** When nothing is wrong there is no banner and the page is calm. When something is wrong it is the first thing read, written as a consequence ("availability may be stale") not a cause ("SYNC_ERROR 502"), with one button that starts the fix.
- Status is a **word plus a dot**, never colour alone: `Healthy` · `Syncing…` · `Needs attention` · `Paused` · `Not connected`.
- Stat tiles are outcomes (bookings, revenue), not machinery (API calls, queue depth). Machinery lives in Diagnostics.
- Live updates arrive over the existing **SSE** stream. On reconnect, refetch once — never render a stale "Healthy".

### 4.2 Channels — connect and manage

The list is a card grid, not a table: a venue admin has 2–5 channels, and cards carry logo, status and a primary action better than rows do.

**The connect wizard is where the "easy way" is won.** Four steps, one decision each, nothing else on screen.

```
  ①  Choose channel   ──  ②  Connect   ──  ③  Choose listings  ──  ④  Review

┌──────────────────────────────────────────────────────────────────────────┐
│  Step 2 of 4 — Connect to Bókun                                          │
│                                                                          │
│  Paste the API key from your Bókun account.                              │
│  Where do I find this? ▸                       ← inline, expands in place│
│                                                                          │
│  API key    [ ••••••••••••••••••••••••••••••••••••••• ]                  │
│  Vendor ID  [ 12345                                   ]                  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │ ✓ Connected. We found 3 products in your Bókun account.            │ │  ← live test
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│                                          [ Back ]  [ Continue → ]        │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Test the credential before advancing.** A failed test shows the provider's own error translated into an action ("That key doesn't have permission to read products — ask Bókun to enable Inventory access"), never a raw 401.
- **Credentials are write-only.** After save the field renders `••••••••1234` with `[ Replace ]` and `[ Rotate ]`. There is no read endpoint — the API must never return a secret (DIST-R5).
- **Step 1 only offers channels that operate in this venue's country.** `Channel.supportedCountries` ∩ `Binge.country`. A venue in Brazil must not be offered a channel that cannot sell it — and channels filtered out should say so (*"Klook doesn't currently cover BR"*) rather than silently vanishing. This is the single most important consequence of the global scope decision showing up in the UI.
- **Step 3 is an opt-in checklist of event types**, defaulting to *nothing selected*. Distribution is never silently switched on.
- **Step 4 states plainly what will happen** — which listings go live, the resolved channel price, the cancellation cutoff being advertised, and that availability syncs every N minutes. Two things it must say in plain words, because they are the accepted commercial model and operators will otherwise assume the opposite:
  - **"You are connecting *your venue's* account to Bókun. The contract is between your venue and Bókun; SK Binge is your reservation system."** (B2)
  - **"You collect payment as normal. Bókun takes its commission separately. SK Binge does not handle channel payments."** (B3)

  This is the consent moment. Make it readable, not legalistic.

Ineligible event types stay visible but disabled with the reason inline — *"Needs a cleanup buffer before it can be sold on a channel"* with a link to fix it. Hiding them produces a support ticket; explaining them produces a self-serve fix.

### 4.3 Listings — what is on sale where

The one screen that needs a table, because it is a matrix. Rows are event types, columns are channels.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Listings                        [ All ▾ ] [ Live ▾ ]     [ Sync now ]   │
├──────────────────────────────────────────────────────────────────────────┤
│  EVENT TYPE            DURATIONS      BÓKUN         GOOGLE       ACTIONS │
│  ────────────────────────────────────────────────────────────────────────│
│  Birthday Celebration  2h · 3h · 4h   ● Live        ● Live        ⋯      │
│  Proposal Setup        2h             ● Live        ○ Off         ⋯      │
│  Corporate Offsite     4h · 6h        ⚠ Unmapped    ○ Off         ⋯      │
│  Private Screening     3h             ⛔ Blocked     ⛔ Blocked     ⋯      │
│     └ No cleanup buffer set. Channels could sell back-to-back slots.     │
│       [ Set buffer → ]                                                   │
└──────────────────────────────────────────────────────────────────────────┘
```

- Four cell states only: **Live · Off · Unmapped · Blocked**. Anything more granular belongs in the row drawer.
- The **`DURATIONS` column is editable** and is where B5 lives: the venue picks up to **4** bookable durations per event type from the range its `minHours`/`maxHours` allow. Attempting a fifth explains why (*"Channels show these as separate options — more than four makes the picker unusable"*) rather than silently disabling the control.
- **`Blocked` always states its reason inline and links to the fix.** This is the surface where gap G1 (turnover buffers) becomes visible to the person who can actually resolve it.
- Clicking a cell opens a drawer: which durations are published, the resolved channel price with commission shown, the cancellation cutoff being advertised, last sync time, and a **read-only preview of the exact payload** the channel receives. That preview is worth more than any log — it turns "why is the price wrong on Bókun" into a ten-second answer.
- `[ Sync now ]` is a manual override with a visible cooldown, not a button that silently does nothing when rate-limited.

### 4.4 Reservations — inbound channel bookings

Deliberately **not** a second bookings list. Canonical bookings live in `/admin/bookings`; this is the *inbox and its exceptions*.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Channel reservations                                                    │
│  [ Needs attention (1) ] [ Accepted ] [ Cancelled ] [ All ]              │
├──────────────────────────────────────────────────────────────────────────┤
│  ⚠ Could not accept 1 reservation                                        │
│                                                                          │
│  Viator · VT-77120 · Birthday · 9 Aug 18:00–21:00 · 8 guests · ₹7,100    │
│  Rejected: the 18:00 slot was taken 40 seconds earlier by a direct       │
│  booking. The channel has been told it is unavailable.                   │
│                     [ View the direct booking ]  [ Offer 19:00 instead ] │
└──────────────────────────────────────────────────────────────────────────┘
```

- Every inbound reservation is persisted in the inbox **before** canonical creation is attempted, so a rejection is a visible, explainable row rather than a lost booking.
- Accepted reservations show a one-line summary and **deep-link into `/admin/bookings`** — one booking, one place, always.
- The rejection copy names the *cause* and offers the *next action*. This is the screen that decides whether operators trust distribution at all.

### 4.5 Diagnostics — super-admin, cross-binge

The only screen allowed to use engineering vocabulary, because its audience is the platform operator.

Tabs mirroring `/admin/recovery`: **Connection health** (per binge×channel: last success, error rate, p95 latency, consecutive failures) · **Failed syncs** (retryable queue, one-click and bulk retry) · **Reservation inbox errors** · **Reconciliation drift** (channel-side vs canonical, with a diff) · **Rate limits & quotas**.

Two controls that must exist from day one:
- **Per-connection kill switch** — pause one venue×channel pair without touching the venue.
- **Global channel pause** — stop all traffic to one channel platform-wide. When a provider misbehaves at 2am, this is the control that saves the night.

---

## 5. The five interaction rules that make this "easy"

1. **Never show a state without a next action.** Every warning, every `Blocked`, every rejection carries a button. A status with no affordance is a support ticket.
2. **Translate provider errors at the boundary.** The Distribution service maps provider error codes to operator-readable causes; the UI renders only the translation, with the raw code behind a `Details ▸` disclosure for super-admins.
3. **Default to off, always.** New Binges are `DIRECT_ONLY`. New listings start unselected. Distribution is opt-in at every level.
4. **Optimistic UI is banned here.** A channel operation either completed on the provider or it did not. Show `Syncing…` and settle from SSE. A green tick that later turns out to be false destroys trust permanently.
5. **One booking, one place.** Distribution never renders a booking detail view. It links to `/admin/bookings`.

---

## 6. States, accessibility, responsiveness

| Concern | Treatment |
|---|---|
| **Loading** | `SkeletonStatCard` for tiles, `SkeletonLine` for rows — already in `components/ui/Skeleton`. Never a full-page spinner on a screen with a warning banner. |
| **Empty** | Overview with no channels shows one illustrated card: *"Your venue is only bookable on SK Binge right now."* + `[ Connect a channel ]`. Not an empty table. |
| **Error** | Section-scoped. A failing channels list must not blank the reservations list. |
| **Permission-denied** | The module gate hides the nav item entirely (existing V71 behaviour). Direct navigation renders an explanatory page, **not** a raw 403 — this is the exact failure that produced the notification-bell incident. |
| **Colour** | Status is always icon + word + colour. `Live` ● green, `Syncing` ◐ blue, `Needs attention` ⚠ amber, `Blocked` ⛔ red, `Off` ○ grey. Passes without colour perception. |
| **Keyboard** | Wizard steps are a `role="tablist"`-free linear flow with focus moved to the step heading on advance. Drawers trap focus and restore it on close (`Modal` already does this). |
| **Screen readers** | Sync status is an `aria-live="polite"` region. SSE updates announce once, not per keystroke of change. |
| **Responsive** | The listings matrix is the only wide surface — it scrolls inside `.admin-table-wrap` (`overflow-x: auto`), never the page body. Below 768px it collapses to per-event cards with channel chips. |
| **Dark mode** | Uses the existing `var(--bg-card)`, `var(--border)`, `var(--text-muted)` tokens; no new colour literals. |

---

## 7. Permissions

| Capability | ADMIN (own binge) | SUPER_ADMIN |
|---|---|---|
| View Overview / Listings / Reservations | ✅ | ✅ cross-binge |
| Connect / disconnect a channel | ✅ | ✅ |
| Publish / unpublish a listing | ✅ | ✅ |
| Rotate credentials | ✅ | ✅ |
| **Set `distributionClassification`** | ❌ | ✅ **only** |
| **Set commission / net rate** *(Phase 6 — not built while B3 holds)* | ❌ | ✅ **only** |
| Global channel pause, Diagnostics | ❌ | ✅ |

`[RECOMMENDATION]` Add a `DISTRIBUTION` key to the V71 module matrix, and put it in the **dual sign-off** set alongside `RATE_CODES` and `SURGE_RULES`. Connecting a sales channel changes who can sell the venue's inventory and at what price — it is at least as consequential as a rate code. `AuthorityScope` gains a matching `DISTRIBUTION` scope so a super-admin can delegate it time-boxed.

---

## 8. What not to build

| Tempting | Why not |
|---|---|
| A distribution-specific booking calendar | `/admin/bookings` already exists. Two calendars will disagree, and users will believe the wrong one. |
| A channel-performance BI dashboard | `/admin/reports` is the home for analytics. Ship a link, not a second charting stack. |
| Inline editing of channel prices | Pricing is server-authoritative through the rate-code → surge → FX → tax pipeline. An editable price field in Distribution is a second pricing truth. Show the **resolved** price, read-only, and link to `/admin/rate-codes`. |
| A raw log viewer in the admin UI | Diagnostics shows *translated* errors. Raw logs belong in the observability stack. |
| A generic "add any channel" form | Every channel needs a real connector. A generic form invites operators to configure something that cannot work. |

---

## 9. Build order

Ships **alongside** Phase 3, not after it.

| Step | Deliverable | Why here |
|---|---|---|
| 1 | Nav group, routes, `DISTRIBUTION` module gate, permission-denied page | Skeleton first — proves the gateway and module seams before any feature depends on them |
| 2 | **Channels list + connect wizard** | Nothing else is reachable without a connection |
| 3 | **Listings matrix + row drawer** | The screen that makes the mapping model comprehensible — and the one that surfaces `Blocked`/G1 to the person who can fix it |
| 4 | **Reservations inbox** | The trust screen. Must exist before the first real reservation. |
| 5 | **Overview** | Composed from data the first four already return — cheap once they exist, and only meaningful once they do |
| 6 | **Diagnostics** (super-admin) | Needed before the first real counterparty (Phase 5), not before the simulator |

Each step is one route, under ~500 lines, reusing `components/ui/` and the `.admin-*` vocabulary. No new CSS system, no new state library — `useState` + the existing `endpoints.js` service pattern, matching `AdminRecoveryQueues.jsx`.
