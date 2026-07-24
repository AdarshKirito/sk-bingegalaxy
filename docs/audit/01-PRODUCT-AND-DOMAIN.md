# 01 — Product & Domain Model (as implemented)

## Purpose

SK Binge Galaxy is a private venue/event-booking platform. A **Binge** is an owned/managed operational business context (a venue business). Customers discover a binge, pick an event type and a date/time, optionally add-ons, and book & pay; admins operate their binge (inventory, pricing, bookings, support). The platform is multi-tenant with a super-admin over all binges.

## Implemented domain model

| Concept | Implemented as | Notes |
|---|---|---|
| Binge | `binges` table, owned by `adminId`; has timezone, currency, per-day opening hours, module-permission matrix, geo lat/long, support contacts | Super-admin approves creation; change-request workflow (V69) |
| Venue | **thin** — no separate `venues` table; rooms attach to a binge | "Venue" in UI ≈ binge/room grouping (QUESTION for product) |
| Room | `venue_rooms` (binge-scoped, capacity) | bookable inventory; capacity drives overlap rules |
| Event Type | `event_types` (binge-scoped, priced) | occasion/package (Birthday, Anniversary, HD Screening, Corporate, …) |
| Add-ons | `add_ons` + categories, `stockPerDay` | live-COUNT inventory |
| Availability | computed live (not stored) | 30-min slots, venue-local hours minus blocked half-hours |
| Holds | `slot_holds` (TTL) | **hand-off is dead code — hold not binding (BOOK-001)** |
| Pricing | base + rate codes + surge + add-ons + loyalty + tax + FX | server-authoritative; immutable price snapshot per booking |
| Booking | `bookings` + state machine | PENDING→CONFIRMED→CHECKED_IN→COMPLETED / CANCELLED / NO_SHOW |
| Payment / Refund | separate `payment_db` | Razorpay, webhook-driven, event-linked to booking |
| Loyalty | full v2 engine (V21+) | tiers, perks, ledger, country configs (V73), goodwill budgets |

## Administrative hierarchy (intended vs implemented)

**Super Admin** — platform authority: all binges, approve/suspend binges, manage admins, global config (currencies, taxes, loyalty, CMS, notifications), delegate **global scopes** (≤24h). Implemented via native SUPER_ADMIN role + `AuthorityGrant` delegation, gateway scope-elevation on global paths only.

**Binge Admin / Owner** — scoped to owned binges (`Binge.adminId`). Enters a binge context; operates within it. Implemented via `AdminBingeScopeService.requireManagedBinge/requireBingeOwnership` — **but enforcement is per-endpoint and two endpoints skip it (SEC-001/002).**

Intended admin menu (Dashboard/Binges/Messages/Account; then per-binge Reports/Messages/Venue/Rooms/Event Types/Rate Codes/Surge Rules/Blocked Dates/Slot Holds/People/Users/Waitlist/Customer Freezes/Risk Flags/Support Console/Disputes/Failed Refunds) is largely realized as routes (see `04-FRONTEND.md` + `11-OPERATIONAL-MODULES.md`). Module visibility is governed server-side by the V71 permission matrix.

## Product QUESTIONs (need product decision, not derivable from code)

1. **Slot holds** (BOOK-001): should a hold actually reserve a slot? Currently it does not. Fix or remove.
2. **Venue layer**: is a first-class Venue entity (above Room) intended, or is the binge/room grouping sufficient? Code has no `venues` table.
3. **Loyalty scope**: are loyalty programs global (super-admin) or per-binge? Cache keys are program-scoped, bindings are per-binge (V73) — the boundary is ambiguous.
4. **Venue timezone change** with existing future bookings: no re-anchoring logic — is the intended behavior "times move with the zone" or "times are pinned"?
5. **Multi-currency caps**: `NUMERIC(10,2)` caps a single amount ~100M — acceptable for current currencies; revisit if high-inflation currencies are added.

## OTA / third-party channels

No OTA/channel-manager/CRS/PMS integration exists today (only Razorpay + mail + Web Push + WhatsApp/SMS config). The data model *could* support channel publishing (binge→property, room-type, availability, rate plans) but none is implemented. See `12-INTEGRATIONS-AND-OTA-READINESS.md`. Recommendation: at the current product stage, no OTA integration is required; if added later, configuration belongs inside existing operational responsibilities (Rooms/Rate Codes/Blocked Dates), not a new user-facing module, unless a dedicated channel-reconciliation surface proves necessary.
