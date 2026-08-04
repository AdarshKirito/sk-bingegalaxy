# 06 — Workflows (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · 12 core flows traced end-to-end, VERIFIED-STATIC

## 1. Registration
Register.jsx → `POST /auth/register` → AuthController#register → AuthService → `User` row → publishes `user.registered` (no in-repo consumer). Email verification token issued; VerifyEmail.jsx completes.

## 2. Login + MFA
Login.jsx → `POST /auth/login` → AuthController#login → AuthService (+ MfaService TOTP when enabled). Session row + httpOnly cookie pair (access/refresh). `SUPER_ADMIN_REQUIRE_MFA` code default "true" (AuthService.java L464-480). Frontend refresh is single-flight; gateway consults the Redis denylist per request.

## 3. Binge create → approve
BingeManagement.jsx → `POST /admin/binges` (PENDING_APPROVAL, 24 h grace, hidden) → super-admin `POST /admin/binges/{id}/approve` (may pre-set module restrictions/disabled tax rules) or `/reject`. Approval is workflow state, not an event.

## 4. Slot hold → booking create (the concurrency spine)
BookingPage.jsx wizard → hold slot (SlotHold, Redis-tracked, `@Version` optimistic, expiry scheduler) → `POST /bookings` → BookingController#createBooking → BookingService L261 takes `pg_advisory_xact_lock` (BookingRepository.java:433) → occupancy re-check → **V75 trigger backstop** rejects oversell at DB layer → BookingPriceSnapshot frozen → outbox `booking.created`.

## 5. Payment (Razorpay)
PaymentPage.jsx → `POST /payments/initiate` → durable PaymentIntent → Razorpay order → callback/webhook (HMAC-verified, deduped) → PaymentService transitions → outbox `payment.success|failed` → booking state machine confirms/releases.

## 6. Payment (Stripe Connect)
Admin onboarding `POST /payments/admin/connect/onboard` → PaymentConnectedAccount. Customer flow via StripeGatewayClient; `POST /payments/webhooks/stripe` (signature-verified, deduped) marks success. Venue country drives method choice (PaymentMethodResolver).

## 7. Cancellation + refund saga
MyBookings.jsx / AdminBookings.jsx → `POST /bookings/{ref}/cancel` → BookingStateMachine validates transition → CancellationTier policy math (minor units) → refund intent persisted (saga) → payment-service executes provider refund (Razorpay/Stripe API) → RefundWebhookService confirms via webhook → receipt-first reconciliation in PaymentReconciliationScheduler (L158-208) resolves ambiguous outcomes → V14 partial unique index on `gateway_refund_id` prevents double-refund rows → `refund.*` events → notification.

## 8. Check-in (QR/OTP)
Dashboard → `POST /checkins/{ref}/check-in/qr/issue` or `/otp/issue` → CheckInToken (TTL) → scan/enter → booking → CHECKED_IN → `booking.checked-in` (no in-repo consumer).

## 9. No-show audit (batch)
NoShowAuditService (daily scheduler, ShedLock) sweeps past-due CONFIRMED bookings → marks NO_SHOW → CustomerFreezeService may create CustomerBingeFreeze (NO_SHOW_PATTERN trigger).

## 10. Waitlist join → promote
BookingPage.jsx (slot full) → `POST /waitlist` → WaitlistEntry. On `booking.cancelled`, WaitlistPromotionListener promotes head-of-line → `waitlist.promoted` → notification invite.

## 11. Transfer (magic link)
MyBookings.jsx → `POST /bookings/{ref}/transfers` → BookingTransfer with token → recipient opens `/transfers/{token}` (TransferAccept.jsx) → `POST /booking-transfers/by-token/{token}/accept` → ownership move → `booking.transferred` (no in-repo consumer).

## 12. Loyalty v2 earn + redeem
`booking.created` → EarnEngine (skips ENABLED_LEGACY frozen bindings) → PointsWallet lots (expiry-aware). Redemption: Membership.jsx → `POST /loyalty/redemptions` → RedeemEngine burn with wallet locking. Config writes are super-admin only (`@PreAuthorize("hasRole('SUPER_ADMIN')")`, LoyaltyV2SuperAdminController.java:49) — the 26-Apr-2026 stress-test CRITICAL finding (customer could write loyalty config) is **FIXED**.

---

### Cross-cutting behaviors verified in the traces

- **Idempotency:** axios attaches `Idempotency-Key` on mutations; booking/payment persist IdempotencyKey rows; webhooks dedupe by provider event id.
- **Tenancy:** every admin flow resolves scope via `requireManagedBinge` (the SEC-001 cross-binge recovery-queue leak is FIXED — AdminRecoveryQueueController + AdminRecoveryQueueScopeTest).
- **Money:** all pricing math in minor-unit longs; snapshots freeze price at booking; refunds computed off snapshots.
- **NOT-VERIFIED:** no end-to-end runtime execution of any flow occurred in this audit (static only); provider sandbox proof for flows 5–7 remains an open launch gate.
