# 09 — `frontend` (React 18 + Vite SPA, :3000)

The single-page app. ~75 lazy-loaded routes, Zustand state behind three contexts, an Axios
client with proactive token refresh + idempotency + Sentry tracing, a PWA shell, and a shared UI
kit. Routing/guards and the booking wizard are traced in
[ARCHITECTURE.md §15 + §8.1](../../ARCHITECTURE.md); this doc walks the structure file-group by
file-group.

Source root: `frontend/src/`

---

## Entry & shell

### `main.jsx` / `App.jsx`
`main.jsx` bootstraps React, Sentry, i18n, and the PWA service worker. `App.jsx` (294 lines) is
the router: 75 `lazy()` route chunks behind `<Suspense>`, the provider stack
(`ErrorBoundary → AuthProvider → BingeProvider → ConfirmProvider → AppFrame`), and the **route
guards** — `PublicOnlyRoute`, `CompleteProfileRoute`, `ProtectedRoute`, `AdminRoute`,
`SuperAdminRoute` (native or delegated-with-scope), `BingeRequired`, `AdminBingeRequired`.
`AppFrame` renders the Navbar, delegation banner, PWA update prompt, and the `<Routes>` table.
Full walk in [ARCHITECTURE.md §15](../../ARCHITECTURE.md).

---

## State (`stores/` + `context/`)

### `stores/authStore.ts`
The Zustand auth store. State: `user`, `isAuthenticated`, `loading`, `isAdmin`, `isSuperAdmin`,
`effectiveAuthority` (delegation scopes). Actions: `login`/`adminLogin` (handle the `mfaRequired`
challenge response), `logout`, and a bootstrap that hydrates from `localStorage` and validates the
session on load (keeping the local session on a 5xx so a backend blip doesn't log everyone out).
Derives `isAdmin`/`isSuperAdmin` from `user.role`.

### `stores/bingeStore.ts`
The selected-venue store: `selectedBinge` (persisted to `localStorage`), `setBinge`, `clearBinge`.
Drives `X-Binge-Id` injection and the `BingeRequired` guards.

### `context/AuthContext.jsx` / `BingeContext.jsx`
Thin bridges that expose the Zustand stores via React context (`useAuth`, `useBinge` with a
"must be used within Provider" guard) so components consume a stable hook.

### `context/CurrencyContext.jsx` (159 lines)
Multi-currency UI state: loads active currencies (with a hardcoded fallback list), tracks
`selectedCode` (auto-detected default, persisted), exposes `refresh`, conversion helpers, and a
formatter — backing the `CurrencySwitcher`.

---

## API layer (`services/`)

### `services/api.js` (293 lines)
The Axios instance (`baseURL:/api/v1`, `withCredentials:true` for the httpOnly cookie). Request
interceptor does four things: (1) attaches an **`Idempotency-Key`** UUID on mutating verbs
(preserving caller-supplied keys from `useIdempotencyKey`); (2) injects **`X-Binge-Id`** from the
binge store; (3) propagates **`X-Sentry-Trace-Id`** from the active Sentry span so a frontend
error links to the backend Zipkin trace, and sets Sentry user/binge tags; (4) **proactive token
refresh** — if the JWT (`token_exp`, epoch seconds) expires within 60s, it silently refreshes
first and **queues concurrent requests** until the new token lands (prevents mid-form 401s). The
response interceptor handles 401 (redirect to login), 429 (surfaces retry-after to the toast/UI),
and normalizes error messages onto `err.userMessage`.

### `services/endpoints.js` (553 lines)
The typed API surface — one object per domain: `authService`, `authorityService`, `bookingService`
(incl. blob invoice PDF download), `slotHoldService`, `taxService`, `currencyService`,
`checkoutService`, `notificationService`, `availabilityService`, `paymentService`. Plus the
`toArray` helper that defends against the Page-vs-PagedResponse shape difference (extract
`.content` so lists never silently render empty).

### Other services
- `loyaltyV2.js` — the `/api/v2/loyalty` client (member/admin/super-admin).
- `analytics.js` — `trackPageView` / `trackBookingStepCompleted` / funnel-beacon ingestion
  (fired via `sendBeacon`, `credentials:'omit'`).
- `bookingEvents.js` — a tiny in-app pub/sub the customer hub pages subscribe to for cross-tab
  refresh.
- `i18n.js` — i18next setup (translation resources + language detection).
- `timeFormat.js` — venue-local time/duration formatting helpers.
- `exportUtils.js` — client-side CSV/PDF export for admin reports/tables.
- `aboutExperience.js` / `customerExperience.js` / `dashboardExperience.js` — the CMS config
  schemas + option lists powering the binge "about"/dashboard editors and customer hub.

---

## Hooks (`hooks/`)
- `useRealtimeUpdates.js` — subscribes to the admin **SSE** stream (`/admin/events/stream`) and
  dispatches live booking/payment/lifecycle updates into the dashboards.
- `usePageTracking.js` — fires `trackPageView` on route change (used by `AppFrame`).

---

## Components (`components/`)

### Top-level
- `Navbar.jsx` + `NavDropdownGroup.jsx` + `NavOverflowBar.jsx` — responsive nav with role-aware
  menus and overflow handling.
- `BookingWizard.jsx` / `BookingFormCore.jsx` — re-export shims to the refactored
  `components/booking/` module.
- `NotificationsBell.jsx` — the in-app admin notification bell (polls `unread-count`).
- `CurrencySwitcher.jsx` — currency dropdown bound to `CurrencyContext`.
- `CustomerReviewsDrawer.jsx` — slide-over of a customer's review history.
- `ErrorBoundary.jsx` — React error boundary reporting to Sentry.
- `PWAUpdatePrompt.jsx` — "new version available, reload" prompt from `vite-plugin-pwa`.
- `ThemeToggle.jsx` — dark/light toggle. `SEO.jsx` — `react-helmet-async` meta tags.

### `components/booking/` — the wizard
`BookingWizard.jsx` (816 lines, the step machine + `handleSubmit` — traced in
[ARCHITECTURE.md §5/§8.1](../../ARCHITECTURE.md)), the five steps (`StepCustomer`, `StepEvent`,
`StepDateTime`, `StepAddOns`, `StepReview`), `SlotSuggestionsPanel` (alternatives when a slot is
taken), `BookingTimelinePanel` (event-log timeline), `ImagePopup`.

### `components/checkout/`
`BillingAddressForm.jsx` — B2C/B2B billing capture for invoices.

### `components/form/`
`PhoneField.jsx` (E.164 with country code via `react-phone-number-input`), `AddressFields.jsx`
(country/state/city cascading via `country-state-city`).

### `components/authority/`
`DelegationBanner.jsx` (shows the active Authority-Handover grant + scope to a delegated admin),
`LockBadge.jsx` (resource-lock "X is editing" indicator).

### `components/admin/`
`BingeLoyaltySection.jsx` — the per-binge loyalty config panel embedded in binge management.

### `components/ui/` — the design-system kit
`Button`, `Card`, `Modal`, `ConfirmDialog` + `ConfirmProvider` (promise-based confirm via
context), `FormField`, `Pagination`, `Skeleton`, `Spinner`, `PageHeader`, `LazyImage`
(intersection-observer lazy loading), and `index.js` barrel. DOMPurify is used where user/CMS
HTML is rendered.

---

## Pages (`pages/`, ~75 files)

### Auth & onboarding
`Home`, `Login`, `AdminLogin`, `Register`, `AdminRegister`, `ForgotPassword`, `ResetPassword`,
`VerifyEmail`, `CompleteProfile`, `MfaSetup`, `Entrance`/`AdminEntranceDashboard`.

### Customer
`PlatformDashboard`, `BingeSelector`, `Dashboard`, `BookingPage`, `BookingConfirmation`,
`MyBookings`, `Membership` (loyalty), `CustomerPayments`, `PaymentPage`, `AboutBinge`,
`AccountCenter`, `CustomerSettings`, `CustomerNotifications`, `MySessions`, `CustomerHub` (shared
layout/CSS).

### Admin (~35)
`AdminDashboard`, `AdminBookings`, `AdminBookingCreate`, `AdminBlockedDates`, `AdminEventTypes`,
`AdminRateCodes`, `AdminCustomerPricing`, `AdminVenueRooms`, `AdminSurgeRules`, `AdminWaitlist`,
`AdminCustomerFreezes`, `AdminRiskFlags`, `AdminSupportConsole`, `AdminRecoveryQueues`,
`AdminApprovals`, `AdminDisputes`, `AdminFailedRefunds`, `AdminSlotHolds`, `AdminTaxes`,
`AdminReports`, `AdminAccount`, `BingeManagement`, `AdminUsersConfig`, `AdminPages` (CSS).

### Super-admin / delegated (scope-gated)
`SuperAdminDashboard`, `AuthorityHandover`, `AdminAllUsers`, `AdminCustomerEdit`,
`AdminCurrencies` (scope CURRENCIES), `AdminNotificationTemplates` (NOTIFICATIONS),
`AdminLoyaltyCenter` (LOYALTY), `AdminOps` (OPS), `AdminHomeEditor` (HOME_CMS),
`AdminAccountPageEditor` (ACCOUNT_CMS).

### Fallback
`NotFound`.

Each page is a route target in `App.jsx`, wrapped by the appropriate guard, and talks to the
backend only through the `services/` clients (so auth/idempotency/binge/Sentry headers are applied
uniformly).

---

## Build & PWA config
- `vite.config.js` — Vite + React plugin, PWA (`vite-plugin-pwa`), Sentry source-map upload, the
  `/api` dev proxy to the gateway, manual chunking.
- `index.html` — the SPA shell + CSP meta. `public/` — PWA manifest, icons, robots.
- `nginx.conf` + `Dockerfile` — production static serving (SPA fallback, gzip, cache headers).
- `tsconfig.json` — TS config for the `.ts` stores + typecheck.

## Tests (`e2e/`, `*.test.jsx`)
Playwright e2e (`playwright.config.js`) covering login → book → pay flows, plus Vitest component/
unit tests. `playwright-report/` + `test-results/` hold the latest run artifacts.
