# 10 — Domain Workflows (end-to-end)

> **Original workflow traces with historical payment/cancellation conclusions.** Current blockers: [`../13-BOOKING-AND-AVAILABILITY.md`](../13-BOOKING-AND-AVAILABILITY.md) and [`../14-PAYMENT-REFUND-DISPUTE.md`](../14-PAYMENT-REFUND-DISPUTE.md).

Evidence: `evidence/specialist-03-booking-availability-concurrency.md`, `-01`, `-04`, runtime log. Where a step is app-logic-only or has a gap, it is flagged with the issue ID.

## Identity & administration

Register/login → auth-service issues HS256 JWT (cookie, httpOnly) + sets CSRF cookie. Gateway validates on every request, injects trusted identity headers. Refresh is proactive (token near expiry) + reactive (401), concurrency-guarded in `frontend/src/services/api.js`. Sessions are revocable via a Redis list checked at the gateway. Admin login is a separate endpoint; super-admin can delegate **global scopes** (≤24h, `AuthorityGrant`) which the gateway honors only on scope-mapped global paths. Runtime-confirmed: register/login/profile/refresh behave correctly; anonymous→401, customer→admin→403.

## Binge / venue / room / event-type configuration

Implemented hierarchy (as built): **Binge** (owned by `adminId`, has timezone + currency + per-day opening hours + module-permission matrix) → **VenueRoom** (bookable inventory, capacity) → **EventType** (occasion/package, priced). Add-ons, rate codes, surge rules, blocked dates/slots, and taxes attach at the binge level. Super-admin approves new binges (`/admin/binges/{id}/approve`); change-requests workflow exists (V69). This matches the intended product model. **QUESTION:** "Venue" as a distinct layer above Room is thin — rooms attach to a binge directly (`venue_rooms.binge_id`); there is no separate `venues` table, so "Venue" in the UI is effectively the binge/room grouping.

## Availability & holds

Availability is **computed live** (30-min slots across venue-local opening hours minus blocked half-hours), never stored as inventory (`AvailabilityService`). Booking-service calls availability `/internal/check` before booking, but the **authoritative** conflict decision is the DB advisory-lock check at write time. Slot holds exist (`SlotHold`, TTL expiry scheduler) **but the hold→booking hand-off is dead code (BOOK-001)** — a held slot is not actually protected from a direct booking.

## Pricing

Server-side pricing chain (booking-service `PricingService`/`CheckoutQuoteService`/`TaxService`/surge): base event-type price → rate codes / customer pricing profiles → surge multiplier (V74) → add-ons → loyalty redemption → tax (basis-points, V72). **Currency is native per-binge** — the booking is priced and charged in the binge's own currency (derived from its country); there is **no** server-side FX conversion and no customer currency choice (`BookingService.java:399-444`: `fxRate=1`, `fxLockedUntil=null`). The `FxLockService`/`/checkout/lock-fx` machinery is present but **dormant/unreachable — see PRICE-002** (corrects a prior claim). Money is `NUMERIC` throughout; a `booking_price_snapshots` table (immutable, trigger-enforced) preserves the breakdown per booking. **DATA-006 (Low):** scale differs across snapshot/invoice/ledger tables. Frontend recomputes for display only; the charged amount is server-authoritative (payment re-validates against booking balance — specialist-01).

**Positive controls CONFIRMED (2026-07-12 direct read):** negative/zero total is prevented — `if (afterDiscount.signum() < 0) afterDiscount = ZERO` and loyalty redemption gated on positive subtotal (`CheckoutQuoteService.java:120,217`). Tax flows through a single choke point (`TaxService.compute` → `InternalTaxProvider`) with a per-binge master switch, so every entry path (preview/create/update/reschedule/recurring) is consistent; flat taxes can't be inclusive (validated). **FX-lock correction (2026-07-12):** an earlier revision credited `FxLockService.consume` with rejecting expired locks "so a stale rate can never be charged." That method is correct in isolation but **has zero callers** — `BookingService` never consumes `fxLockToken`, the frontend never locks, and the `fx_rate_locks` table has 0 rows (all bookings `fx_rate=1`). No stale-rate risk actually exists because native per-binge pricing performs no conversion. Tracked as **PRICE-002**.

> **PRICE-001 (Medium):** while the *primitives* (`resolveEventPrice`/`resolveSurge`/`taxService.compute`) are shared, the ~15-line pricing **assembly** (base + hourly + per-guest + surge) is copy-pasted inline across 5 paths — `CheckoutQuoteService.preview` and `BookingService` create/update/reschedule/recurring (`:290,851,1212,1467`). Display-vs-charge parity is by convention, not a shared method → divergence risk. (Full surge-rule-matrix + loyalty earn/redeem trace remains a spot-check, not exhaustive.)

## Customer booking (happy path)

Discover binge → select event-type/date/time → availability check → (hold, currently non-binding) → checkout preview (server pricing) → `POST /api/v1/bookings` (with `Idempotency-Key`) → PENDING booking → payment initiation → on PAYMENT_SUCCESS (full) the state machine advances PENDING→CONFIRMED → invoice + notification. Booking status machine: PENDING/CONFIRMED/CHECKED_IN/COMPLETED/CANCELLED/NO_SHOW, single-authority `BookingStateMachine`, idempotent for Kafka replays. Abandoned PENDING auto-cancel after 30 min. No-show automation past venue-local midpoint. **Runtime NOT VERIFIED end-to-end** (CSRF Secure-cookie harness limit — R4).

## Payment

`payment-service` creates a Razorpay order (server-computed amount, FX-validated), customer completes on Razorpay, provider webhook (HMAC-verified) drives the payment state; `payment.success/failed/refunded` events flow to booking via Kafka. Dedup: `processed_webhook_event(event_id,provider)` UNIQUE + `idempotency_key`. Out-of-order/late webhooks handled explicitly (money recorded, lifecycle untouched, `MANUAL_REVIEW_FLAGGED`). Simulation mode exists (`PAYMENT_SIMULATION_ENABLED`) — dangerous in prod because the guard that would block it is inert (SEC-003). Cash payments via `BOOKING_CASH_PAYMENT` consumer. **PAY-001:** dedicated deep pass incomplete.

## Cancellation & refund

Cancel is state-machine-guarded; availability auto-restores (COUNT-based); `collectedAmount` reversed transactionally; loyalty reversed AFTER_COMMIT (idempotent, clamped, balance-aware clawback — see LOYALTY-001 for the earn→spend→cancel edge). Refund lifecycle (partial/failed/duplicate) has `PaymentStatusHistory` + admin "Failed Refunds" queue. Over-refund IS guarded at the app layer (pessimistic `findByIdForUpdate` + DB-authoritative `sumCompletedRefundsByPaymentId`, `PaymentService.java:571-603`); the missing DB constraint is defense-in-depth only (DATA-002, downgraded to Medium). Maker-checker (`AdminApprovalService.approve`) enforces the 4-eyes principle — no self-approval (`:126-130`). **Disputes (`DisputeWebhookService`, V12) are sound:** fail-closed HMAC webhook verification (unconfigured secret → reject), dedup by `dispute:<id>:<event>`, and the invariant that **dispute events never change booking status** (funds handled by the gateway, ops informed via audit log) — a positive control.

> **PAY-002 (Critical):** the refund itself is **book-keeping only** — `initiateRefund`, the late-capture auto-refund, and `retryFailedRefund` all mint a local fake `RFD-<uuid>`, mark the refund SUCCEEDED, and publish a refund event (customer "refunded" email) **without any Razorpay refund API call** (`RazorpayGatewayClient` has no refund method; the provider's `refund()` is `NOT_IMPLEMENTED`). No money actually moves. **PAY-003:** the late-capture branch acts before signature verification.

## Failure-scenario matrix (current behavior)

| Scenario | Behavior | Safe? |
|---|---|---|
| Double-click WITH Idempotency-Key | cached replay; true-simultaneous both run, one wins via advisory lock, other errors | Integrity ✔, UX imperfect |
| Double-click no key, room-less venue | 2nd rejected by post-lock conflict | ✔ |
| Double-click no key, multi-room, same customer | pre-lock TOCTOU → two PENDING different rooms | ✖ DATA-005 |
| Two customers, same room/slot | 2nd rejected (capacity) | ✔ |
| Hold expires during payment | booking still confirms (holds never consulted) | Integrity ✔, hold illusory (BOOK-001) |
| Payment webhook before browser redirect | webhook is authoritative; booking confirms regardless of redirect | ✔ |
| Duplicate webhook | dedup on (event_id,provider) | ✔ |
| Delayed / out-of-order webhook (success after cancel) | money recorded, lifecycle untouched, MANUAL_REVIEW_FLAGGED | ✔ (flagged) |
| DB commit ok, Kafka publish fails | transactional outbox retries; at-least-once | ✔ |
| Consumer receives event twice / out of order | ProcessedEvent dedup + unique index; order-tolerant handlers | ✔ |
| Refund duplicated | app SUM guard only (no DB constraint) | ⚠ DATA-002 |
| Service restart mid-flow | outbox + ShedLock resume; idempotent consumers | ✔ |
| Kafka unavailable | outbox buffers in DB, drains on recovery | ✔ |
| Venue timezone change with future bookings | naive times reinterpreted under new zone | ⚠ QUESTION (no re-anchoring logic found) |
| Cross-binge admin read (recovery/invoices) | returns other binges' data | ✖ SEC-001/002 |

## Operator recovery

For most workflows an operator can detect (recovery queues, `MANUAL_REVIEW_FLAGGED`, DLT), trace (Zipkin, `booking_event_log`, `payment audit_log`), and repair (admin ops DLT replay / outbox retry, maker-checker refunds). Gaps: poison messages retry forever (REL-001); notification failures have no TTL/replay story documented; cross-service PII erasure has no mechanism (DATA-004).
