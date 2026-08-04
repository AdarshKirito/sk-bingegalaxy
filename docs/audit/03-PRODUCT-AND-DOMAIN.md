# 03 — Product and Domain (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · all statements VERIFIED-STATIC unless labeled

## Role model

| Concept | Implementation |
|---|---|
| Native roles | `CUSTOMER`, `ADMIN`, `SUPER_ADMIN` — [UserRole.java](../../backend/common-lib/src/main/java/com/skbingegalaxy/common/enums/UserRole.java) |
| Authority handover | Super-admin grants **time-bounded scoped elevation** (1–24 h, default 4 h) to admins via `AuthorityGrant` ([AuthorityGrant.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/entity/AuthorityGrant.java)). JWT native role is unchanged; the gateway elevates `X-User-Role` only for matched paths |
| Delegation scopes (10) | CURRENCIES, NOTIFICATIONS, LOYALTY, OPS, ALL_USERS, CUSTOMER_EDIT, ADMIN_REGISTER, HOME_CMS, ACCOUNT_CMS, SUPER_DASHBOARD — [AuthorityScope.java](../../backend/common-lib/src/main/java/com/skbingegalaxy/common/enums/AuthorityScope.java) |
| Per-binge modules (17) | REPORTS, MESSAGES, VENUE, ROOMS, EVENT_TYPES, RATE_CODES, SURGE_RULES, BLOCKED_DATES, SLOT_HOLDS, PEOPLE, USERS, WAITLIST, CUSTOMER_FREEZES, RISK_FLAGS, SUPPORT_CONSOLE, DISPUTES, FAILED_REFUNDS — enforced by `ModulePermissionInterceptor`; deny-list model (absence = allowed) via `BingeModulePermission(bingeId, userId, moduleKey, actionKey)` |
| Dual sign-off modules | RATE_CODES, SURGE_RULES, CUSTOMER_FREEZES, RISK_FLAGS, DISPUTES, FAILED_REFUNDS require binge-admin **and** super-admin approval ([PermissionModules.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/permission/PermissionModules.java)) |
| Staff separation of duties | Admin/super-admin identities cannot transact as customers; staff need separate customer accounts ([App.jsx](../../frontend/src/App.jsx) guard logic) |
| Risk & freezes | `BookingRiskFlag` (LOW/MEDIUM/HIGH) + `CustomerBingeFreeze` (triggers: CUSTOMER_CANCELLATIONS, PAYMENT_TIMEOUTS, NO_SHOW_PATTERN, MANUAL) |

## Domain clusters (booking-service is the domain heart)

| Cluster | Key entities | Frontend |
|---|---|---|
| Venue/Binge lifecycle | Binge (PENDING_APPROVAL/APPROVED/REJECTED), BingeChangeRequest, BingeSiteContent | /admin/binges |
| Rooms & approvals | VenueRoom, RoomBlock, RoomApprovalStatus (mirrors binge approval) | /admin/venue-rooms |
| Event types & add-ons | EventType, EventCategory, AddOn, AddOnCategory, BookingEventType | /admin/event-types, /admin/add-ons |
| Pricing engine (layered) | base → RateCode (+event/addon pricing +change log) → CustomerPricingProfile → SurgePricingRule → FX (CurrencyRate) → TaxRule → **BookingPriceSnapshot** | /admin/rate-codes, /admin/surge-rules, /admin/customer-pricing, /admin/currencies |
| Booking lifecycle | Booking, BookingAddOn, BookingNote, BookingEventLog, BookingReadModel (CQRS), BookingReview | /book, /admin/bookings |
| Check-in / transfer / holds | CheckInToken (QR/OTP), BookingTransfer (magic link), SlotHold (@Version optimistic) | dashboards + accept links |
| Waitlist | WaitlistEntry, auto-promotion on cancellation (Kafka listener) | /admin/waitlist |
| Risk/freeze/disputes | BookingRiskFlag, CustomerBingeFreeze (disputes live in payment-service) | /admin/risk-flags, /admin/customer-freezes |
| Support console | BookingNote threads, escalation NONE/L1/L2/L3, goodwill credits | /admin/support |
| Financial docs | Invoice, InvoiceLine, CreditNote, LedgerEntry | mostly backend reporting |
| Loyalty v2 (19 entities) | LoyaltyProgram, TierDefinition, Membership(+Event), PointsWallet(+Lot), BingeBinding(+EarningRule/RedemptionRule/PerkOverride/RewardItem), CountryEarnConfig, QualificationEvent | /admin/loyalty-center (super-admin), /membership (customer) |
| Plumbing | OutboxEvent, ProcessedEvent, SagaState, IdempotencyKey, SystemSettings, AdminNotification | internal only |

## Binge lifecycle

Create (ADMIN, `POST /admin/binges`) → PENDING_APPROVAL with 24 h grace (hidden from customers, no bookings) → super-admin approve (can pre-set module restrictions + disabled tax rules) or reject (retained for audit) → APPROVED venues bookable. Country/timezone changes go through `BingeChangeRequest` resubmission. Rooms carry their own mirrored approval status with `room.approved`/`room.rejected` events.

## Half-wired / dormant features (product decisions needed)

| Feature | State | Decision needed |
|---|---|---|
| Loyalty v1 | Deprecated; V28 dropped v1 tables; legacy bindings frozen as ENABLED_LEGACY (V21/V22); EarnEngine skips them | Migration/thaw UI, or backfill-only forever? |
| Admin approval queue | `/admin/approvals` executes only REFUND_RETRY; other actionTypes log "not yet wired" ([AdminApprovals.jsx:111](../../frontend/src/pages/AdminApprovals.jsx)) | Which action types to wire next? |
| Authority locks | `ResourceLock` + `AuthorityLockGuard` enforced server-side; DelegationBanner displays locks; **no lock-management UI** | Where should operators manage locks? |
| Disputes | Ingested via Razorpay webhook only; ops triage; no customer filing UI | By design (ops-gate) or incomplete? |
| Binge change requests | Endpoints exist; approve/reject/cancel UX sparse | Complete the operator flow? |
| Cross-binge risk view | Risk flags are customerId-keyed and survive across binges but no cross-binge dashboard | Product call |

## Embedded product rules worth knowing

1. **Venue-driven payment methods** — methods follow the venue's country, not customer locale (PaymentMethodResolver; CHANGELOG-2026-07-21).
2. **Room-selection requirement** — per-binge V56 flag mandates room choice before checkout ([Binge.java](../../backend/booking-service/src/main/java/com/skbingegalaxy/booking/entity/Binge.java)).
3. **Loyalty point value by country** — LoyaltyCountryEarnConfig with V80 config lock (CHANGELOG-2026-07-24).
4. **Module-scoped navbar** — items hide when super-admin locked/disabled a module (V71; [Navbar.jsx](../../frontend/src/components/Navbar.jsx)).
