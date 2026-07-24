# 04 — Frontend

> **Original catalog plus dated findings.** Current assessment: [`../06-FRONTEND.md`](../06-FRONTEND.md) and `evidence/specialist-08-current-frontend-ux-accessibility.md`. A11Y-001/A11Y-002 were remediated; SEC-009/012, A11Y-003/004 and PERF-002 are current.

React 18 + Vite 5 + react-router 6 + Zustand + axios, PWA (vite-plugin-pwa autoUpdate), i18n (en/hi/ta/te), Sentry. 66 lazy-loaded page components, 60 component files, ~369 endpoint calls, 40 Vitest + 7 Playwright specs. Depth = Level B (structural); no dedicated per-component logic / UX / a11y pass this run.

## Routing (70 routes)

`App.jsx`: BrowserRouter → AuthProvider → BingeProvider → ConfirmProvider → AppFrame. All pages `React.lazy`. **0 orphan pages** (all 66 imported).

**Guard wrappers (auth/role/scope enforced client-side; the gateway + services enforce independently — client guards are UX, not security):**
- `PublicOnlyRoute` — redirects authenticated users away (login/register/forgot/reset).
- `CompleteProfileRoute` — forces profile completion before use.
- `ProtectedRoute` — requires an authenticated customer (+ phone).
- `BingeRequired` — `ProtectedRoute` + a selected binge (customer binge context).
- `AdminRoute` — requires an admin role.
- `AdminBingeRequired` — `AdminRoute` + a selected binge (binge operational context).
- `SuperAdminRoute(scope?)` — requires super-admin, or a delegated authority grant for the named `scope` (V-authority).

### Route catalog (70 routes)

| Path | Params | Page | Guard | Notes / key APIs |
|---|---|---|---|---|
| `/` | — | Home | public | landing; `siteContent.getPublic` |
| `/login` | — | Login | PublicOnly | `auth/login` |
| `/register` | — | Register | PublicOnly | `auth/register` (strict DTO — clean) |
| `/forgot-password` | — | ForgotPassword | PublicOnly | `auth/forgot-password` |
| `/reset-password` | `?token` | ResetPassword | PublicOnly | `auth/reset-password` |
| `/verify-email` | `?token` | VerifyEmail | public | `auth/verify-email` |
| `/terms` | — | Terms | public | `siteContent.getPublic('terms')` |
| `/transfers/:token` | `token` | TransferAccept | public (magic-link) | `booking-transfers/by-token/**` (SEC-006) |
| `/complete-profile` | — | CompleteProfile | CompleteProfile | `auth/complete-profile` |
| `/platform` | — | PlatformDashboard | Protected | multi-binge landing |
| `/binges` | — | BingeSelector | Protected | `bookings/binges`, nearby |
| `/dashboard` | — | Dashboard | BingeRequired | binge customer dashboard |
| `/book` | — | BookingPage | BingeRequired | `bookings` create (BookingWizard) |
| `/booking/:ref` | `ref` | BookingConfirmation | BingeRequired | reschedule/cancel/transfer/timeline |
| `/my-bookings` | — | MyBookings | BingeRequired | `bookings/my/**` |
| `/membership` | — | Membership | Protected | loyalty v2 |
| `/payments` | — | CustomerPayments | BingeRequired | `payments/my` |
| `/about` | — | AboutBinge | BingeRequired | binge about experience |
| `/account` | — | AccountCenter | Protected | profile, preferences, CMS |
| `/account/notifications` | — | CustomerNotifications | Protected | `notifications/my` |
| `/messages` | — | CustomerMessages | Protected | `bookings/notifications/**` |
| `/account/sessions` | — | MySessions | Protected | `auth/sessions` (self revoke) |
| `/settings` | — | CustomerSettings | Protected | account prefs |
| `/account/security/mfa` | — | MfaSetup | Protected | `auth/mfa/**` (TOTP) |
| `/payment/:ref` | `ref` | PaymentPage | BingeRequired | `payments/initiate`+Razorpay+`callback` |
| `/admin/login` | — | AdminLogin | PublicOnly | `auth/admin/login` |
| `/admin/register` | — | AdminRegister | SuperAdmin(ADMIN_REGISTER) | `auth/admin/register` |
| `/admin/platform` | — | AdminEntranceDashboard | Admin | admin entrance |
| `/admin/messages` | — | AdminMessages | Admin | admin inbox |
| `/admin/account` | — | AdminAccount | Admin | admin profile |
| `/admin/all-users` | — | AdminAllUsers | SuperAdmin(ALL_USERS) | user mgmt |
| `/admin/customers/:id/edit` | `id` | AdminCustomerEdit | SuperAdmin(CUSTOMER_EDIT) | customer edit |
| `/admin/super` | — | SuperAdminDashboard | SuperAdmin(SUPER_DASHBOARD) | platform stats |
| `/admin/super/authority` | — | AuthorityHandover | SuperAdmin | grants + locks |
| `/admin/home-editor` | — | AdminHomeEditor | SuperAdmin(HOME_CMS) | landing CMS |
| `/admin/terms-editor` | — | AdminTermsEditor | SuperAdmin(HOME_CMS) | terms CMS |
| `/admin/sessions` | — | MySessions | Admin | admin sessions |
| `/admin/security/mfa` | — | MfaSetup | Admin | admin TOTP |
| `/admin/binges` | — | BingeManagement | Admin | binge CRUD/approval |
| `/admin/dashboard` | — | AdminDashboard | AdminBingeRequired | `bookings/admin/dashboard-stats` |
| `/admin/bookings` | — | AdminBookings | AdminBingeRequired | booking ops, refund, add-payment |
| `/admin/blocked-dates` | — | AdminBlockedDates | AdminBingeRequired | `availability/admin/**` |
| `/admin/about-binge` | — | AdminBingeAbout | AdminBingeRequired | about editor |
| `/admin/event-types` | — | AdminEventTypes | AdminBingeRequired | event-type CRUD |
| `/admin/rate-codes` | — | AdminRateCodes | AdminBingeRequired | pricing rate codes |
| `/admin/loyalty-center` | — | AdminLoyaltyCenter | SuperAdmin(LOYALTY) | loyalty configs |
| `/admin/customer-pricing` | — | AdminCustomerPricing | AdminBingeRequired | per-customer pricing |
| `/admin/venue-rooms` | — | AdminVenueRooms | AdminBingeRequired | rooms CRUD + blocks |
| `/admin/surge-rules` | — | AdminSurgeRules | AdminBingeRequired | surge CRUD |
| `/admin/waitlist` | — | AdminWaitlist | AdminBingeRequired | waitlist ops (BOOK-002) |
| `/admin/customer-freezes` | — | AdminCustomerFreezes | AdminBingeRequired | freeze CRUD |
| `/admin/risk-flags` | — | AdminRiskFlags | AdminBingeRequired | risk flags |
| `/admin/support` | — | AdminSupportConsole | Admin | support console |
| `/admin/recovery` | — | AdminRecoveryQueues | AdminBingeRequired | recovery queues (**SEC-001**) |
| `/admin/approvals` | — | AdminApprovals | AdminBingeRequired | maker-checker approvals |
| `/admin/disputes` | — | AdminDisputes | AdminBingeRequired | `payments/admin/disputes` |
| `/admin/failed-refunds` | — | AdminFailedRefunds | AdminBingeRequired | failed-refund queue (**PAY-002**) |
| `/admin/slot-holds` | — | AdminSlotHolds | AdminBingeRequired | slot holds (**BOOK-001**) |
| `/admin/taxes` | — | AdminTaxes | SuperAdmin+AdminBingeRequired | tax rules |
| `/admin/currencies` | — | AdminCurrencies | SuperAdmin(CURRENCIES) | FX/currencies |
| `/admin/venue-timezones` | — | AdminVenueTimezones | SuperAdmin | bulk tz assign |
| `/admin/account-page-editor` | — | AdminAccountPageEditor | SuperAdmin(ACCOUNT_CMS) | account CMS |
| `/admin/binges/:bingeId/account-page-editor` | `bingeId` | AdminAccountPageEditor | Admin | per-binge account CMS |
| `/admin/notification-templates` | — | AdminNotificationTemplates | SuperAdmin(NOTIFICATIONS) | templates |
| `/admin/ops` | — | AdminOps | SuperAdmin(OPS) | DLT replay/outbox (**SEC-005**) |
| `/admin/reports` | — | AdminReports | AdminBingeRequired | reports |
| `/admin/book` | — | AdminBookingCreate | AdminBingeRequired | admin create booking |
| `/admin/users-config` | — | AdminUsersConfig | AdminBingeRequired | binge users |
| `/admin/users-config/:userId` | `userId` | AdminUsersConfig | AdminBingeRequired | user detail |
| `*` | — | NotFound | public | catch-all 404 |

Per-route loading/empty/error/unauthorized state grading and mobile/desktop rendering are **NOT VERIFIED** (no browser automation) — states exist structurally (route guards render redirects; pages use toasts + ErrorBoundary), but were not visually exercised. Route groups: public/auth 9, customer 16, admin/super-admin ~44, catch-all 1.

## State & API layer

- Zustand stores: `authStore.ts` (user, roles, delegation), `bingeStore.ts` (selected binge, persisted to `localStorage.selectedBinge`). Thin React-Context bridges (`AuthContext`, `BingeContext`).
- `services/api.js` (axios): cookie JWT (`withCredentials`), no Bearer header. Proactive + reactive refresh, concurrency-guarded queue, `forceLogout` on refresh failure. Injects `X-Binge-Id` from localStorage, auto `Idempotency-Key` on writes, CSRF `X-XSRF-TOKEN` from cookie, Sentry trace id. Error normalization unwraps ApiResponse/Spring shapes; toasts 403/429/5xx/network; 429 retry-after countdown.
- `services/endpoints.js`: 18 service groups (auth, authority, booking, slotHold, tax, currency, checkout, notification, availability, message, payment, siteContent, admin, adminSupport, dispute, adminRisk).
- Hooks: `useModuleAccess` (per-binge module gating — **cosmetic only; server enforces**), `useRealtimeUpdates` (admin SSE scoped to binge), `useVenueLocale` (currency+tz), `usePageTracking`, `useGeolocation`.

## PWA / service workers

`vite.config.js` VitePWA generateSW, `autoUpdate` (skipWaiting+clientsClaim). Runtime caching: NetworkFirst for read-only GETs (bookings/availability/event-types/binges, 2-min TTL); **NetworkOnly for auth + payments** (correct — no caching of sensitive/mutating calls). `push-sw.js` handles Web Push. `navigateFallback:/index.html` with `/api` denylist. Note: the project's own memory flags that a stale SW previously masked frontend fixes; `autoUpdate` mitigates.

## Observations / findings (structural)

- **Guards mirror backend roles** but are **not** the security boundary — backend enforces (runtime-confirmed customer→admin 403). `useModuleAccess` is explicitly cosmetic. Good separation.
- **CSRF over localhost:** the `XSRF-TOKEN` cookie is `Secure` — browsers accept it on `http://localhost` (secure context), so the real frontend works; non-browser HTTP clients cannot (audit harness limitation, not a bug).
- **Design tokens present** (`index.css` ~60 CSS custom properties, light + `[data-theme="dark"]`), shared `styles/admin-system.css`. Two historical admin CSS vocabularies were unified (per project memory).
- **Accessibility (2026-07-12 direct read):** the shared `Modal` uses native `<dialog>` + `showModal()` — correct focus trap, focus restoration, and `aria-modal` for free, with accessible names on the dialog + close button (positive). **A11Y-001 (Medium):** but it requires a **double-press of Escape** and **double-tap on the backdrop** to close (`Modal.jsx:22-52`), with no `aria-live` hint — a non-standard dialog keyboard contract that strands keyboard/screen-reader users. `ConfirmProvider` (`await confirm({...})`) is a proper accessible destructive-action guard replacing `window.confirm` (positive). `ErrorBoundary` exists.
- **A11Y-002 (Medium, whole-frontend attribute survey 2026-07-12):** form validation errors are **not programmatically associated** with their inputs. Across ~40+ forms: `aria-describedby` = **0** uses, `aria-invalid` = **6**, `aria-required` = **0**. The prevailing pattern (`Register.jsx:180-188`: `<div class="input-group has-error"> <input/> <span class="field-error">{error}</span>`) is visual-only — a screen reader gets no link between field and error, and no invalid state. Label association is inconsistent (`htmlFor` = 13; wrapping-label forms are OK, many sibling-label admin forms are not). **Positives that offset it:** `aria-label` = 162 (icon buttons labeled), native `<dialog>` with `aria-modal`/`role="dialog"` (8 uses), `role="alert"` on some error regions (6). Fix = per-field `id` + `aria-describedby` + `aria-invalid`. Full contrast / keyboard-nav / responsive / visual grading still NOT VERIFIED (no browser automation on host).
- **Still NOT deeply audited (NOT VERIFIED):** per-form validation-parity vs backend DTOs across all forms, error/empty/loading-state completeness on every page, colour-contrast, full keyboard-nav sweep, responsive/mobile layout, visual rendering (no browser-automation). The Modal + Confirm + ErrorBoundary spot-checks are representative, not exhaustive.
- TypeScript is minimal (5 TS files; `tsc --noEmit` not enforced at build) — most code is JSX with runtime-only typing.
