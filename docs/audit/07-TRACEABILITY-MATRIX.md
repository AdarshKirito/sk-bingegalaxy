# 07 — Traceability Matrix (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58`
> Machine-generated inputs: [evidence/frontend-routes-current.tsv](evidence/frontend-routes-current.tsv) (71 routes), [evidence/frontend-api-pairs-current.tsv](evidence/frontend-api-pairs-current.tsv) (372 call sites), [evidence/endpoint-inventory-current.tsv](evidence/endpoint-inventory-current.tsv) (429 endpoints)

## Coverage summary

| Layer | Count | Notes |
|---|---:|---|
| Pages | 67 | All routed in App.jsx; **no orphaned pages** |
| Routes | 71 | Guards: SuperAdminRoute (9), AdminBingeRequired (31), BingeRequired (18), public (8), utility (5) |
| Static API call sites | 372 | Template-literal dynamic paths under-counted by design (regex limitation, noted) |
| Backend endpoints | 429 | 47 controllers across 5 domain services + gateway actuators |

## Representative page → table traces (12 core flows)

| Page | Route | API (method path) | Controller#method | Service | Tables | Event |
|---|---|---|---|---|---|---|
| Register.jsx | /register | POST /auth/register | AuthController#register | AuthService | users | user.registered |
| Login.jsx (+MfaSetup) | /login | POST /auth/login, /auth/verify-otp | AuthController#login/#verifyOtp | AuthService, MfaService | users, user_sessions | — |
| BingeManagement.jsx | /admin/binges | POST /admin/binges; POST …/{id}/approve | BingeController#createBinge/#approveBinge | BingeService | binges, binge_module_permissions | — |
| BookingPage.jsx | /book | POST /bookings | BookingController#createBooking | BookingService (advisory lock) | bookings, booking_add_ons, booking_price_snapshots, slot_holds | booking.created |
| PaymentPage.jsx | /payment | POST /payments/initiate (+webhooks) | PaymentController#initiate | PaymentService + gateway clients | payments, payment_status_history, payment_intents | payment.success/failed |
| MyBookings.jsx | /my-bookings | POST /bookings/{ref}/cancel | BookingController#cancelBooking | BookingStateMachine → refund saga | bookings, refunds, ledger_entries | booking.cancelled → refund.* |
| Dashboard.jsx | /dashboard | POST /checkins/{ref}/check-in/qr/issue | CheckInController#issueQr | CheckInService | check_in_tokens | booking.checked-in |
| (batch) | — | — | NoShowAuditService (@Scheduled) | CustomerFreezeService | bookings, customer_binge_freezes | — |
| BookingPage.jsx (full) | /book | POST /waitlist | WaitlistController#join | WaitlistService | waitlist_entries | waitlist.promoted (on promote) |
| MyBookings.jsx | /my-bookings | POST /bookings/{ref}/transfers | BookingTransferController#createTransfer | BookingTransferService | booking_transfers | booking.transferred |
| TransferAccept.jsx | /transfers/:token | POST /booking-transfers/by-token/{token}/accept | BookingTransferController#acceptTransfer | BookingTransferService | booking_transfers, bookings | booking.transferred |
| Membership.jsx | /membership | POST /loyalty/redemptions | LoyaltyV2CustomerController | RedeemEngine, PointsWalletService | loyalty_points_wallets, loyalty_ledger_entries | internal |
| AdminLoyaltyCenter.jsx | /admin/loyalty-center | /api/v2/loyalty/super-admin/** | LoyaltyV2SuperAdminController (@PreAuthorize SUPER_ADMIN) | loyalty config services | loyalty_* config tables | internal |

## Guard → backend enforcement pairing (defense in depth)

| Frontend guard | Backend counterpart | Verified at |
|---|---|---|
| SuperAdminRoute | `hasRole('SUPER_ADMIN')` / gateway path rules | LoyaltyV2SuperAdminController.java:47-49 |
| AdminBingeRequired | `requireManagedBinge(bingeId)` | AdminRecoveryQueueController (SEC-001 fix), BingeController |
| BingeRequired (customer) | ownership checks on booking/payment reads | BookingController owner scoping |
| ProtectedRoute | gateway JWT + denylist | api-gateway filters |

## Mismatch scan (FE calls vs BE endpoints)

Static join of the two TSVs found **no orphaned frontend calls** against missing endpoints for literal paths (dynamic template-literal paths excluded from the static join — the residual risk is noted in [14-API-AND-EVENT-COMPATIBILITY.md](14-API-AND-EVENT-COMPATIBILITY.md)). AdminApprovals.jsx intentionally posts only REFUND_RETRY executions (other action types unwired by design — product gap PG-03 in the register).
