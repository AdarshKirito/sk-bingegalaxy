# 16 — Traceability Matrix

Capability → role → route → frontend → API → service → data/event → tests → issues. Condensed to the load-bearing capabilities; "—" = not applicable, "gap" = missing link.

| Capability | Role | Route | Frontend | API | Service | Data / Event | Tests | Issues |
|---|---|---|---|---|---|---|---|---|
| Register / login | anon | `/login`,`/register` | Login/Register + api.js | `/auth/register`,`/login` | auth-service | `users`, Redis session | auth tests | — |
| Discover binge | anon/customer | `/binges`,`/` | BingeSelector/Home | `/bookings/binges` | booking-service | `binges` | — | — |
| Availability | customer | `/book` | BookingWizard/StepDateTime | `/availability/*`,`/bookings/booked-slots` | availability + booking | `blocked_*`, live compute | availability tests | DATA-008, BOOK-003 |
| Slot hold | customer | `/book` | StepDateTime | `/bookings/slot-holds` | booking-service | `slot_holds` | hold tests | **BOOK-001** (dead hand-off) |
| Pricing quote | customer | `/book` | StepReview | `/bookings/checkout/preview` | booking pricing/tax/FX | `booking_price_snapshots` | checkout tests | DATA-006 |
| Create booking | customer | `/book` | BookingWizard | `POST /bookings` | booking-service | `bookings` + outbox | lifecycle tests | **DATA-001**, DATA-005 |
| Pay | customer | `/payment/:ref` | PaymentPage | `/payments/*` + Razorpay | payment-service | `payments`, webhook dedup | PaymentEventListenerTest | SEC-003, PAY-001 |
| Confirm booking | system | — | — | Kafka `payment.success` | booking state machine | `bookings`, `processed_event` | state tests | — |
| Cancel / refund | customer/admin | `/my-bookings`,`/admin/failed-refunds` | MyBookings/AdminFailedRefunds | `/bookings/{ref}/cancel`,`/payments` | booking + payment | `refunds`, `payment_status_history` | lifecycle tests | **DATA-002** |
| Waitlist | customer/admin | `/admin/waitlist` | AdminWaitlist | `/bookings/waitlist/*` | booking-service | `waitlist_entries`, `waitlist.promoted` | — | BOOK-002 |
| Admin recovery | admin | `/admin/recovery` | AdminRecoveryQueues | `/bookings/admin/recovery/*` | booking-service | `bookings`,`slot_holds` | gap | **SEC-001** |
| Admin invoices | admin | (invoice list) | — | `/bookings/admin/invoices` | booking-service | `invoices` | gap | **SEC-002** |
| Notifications | customer | `/account/notifications` | CustomerNotifications | `/notifications/*` | notification-service | Mongo `notifications` | notification tests | **DATA-003** |
| Loyalty | customer/admin | `/membership`,`/admin/loyalty-center` | Membership/AdminLoyaltyCenter | `/api/v2/loyalty/*` | booking loyalty v2 | loyalty tables | — | QUESTION (scope) |
| Module gating | super-admin | (per module) | useModuleAccess | `/bookings/admin/**` | booking permission | `binge_module_permissions` (V71) | — | SEC-005 (unmapped paths) |
| User anonymization | customer/system | `/settings` | CustomerSettings | `/auth/privacy` | auth-service | `users` (V14) | — | **DATA-004** (no cross-service propagation) |

## Missing links (highlights)

- **Endpoint exists, isolation missing:** recovery queue + invoice list (SEC-001/002).
- **Feature has UI + backend but the reserving mechanism is dead code:** slot holds (BOOK-001).
- **Requirement with no test:** cross-binge access, over-refund, DST, concurrent double-booking (TEST-001).
- **Capability spanning services with no propagation:** anonymization (DATA-004).
- **Data written but retention/TTL absent:** Mongo notifications (DATA-003).
- Full API orphan sweep (call-without-endpoint / endpoint-without-caller) is outstanding (`06-API-CONTRACTS.md`).
