# 06 — Frontend UI/UX

React 18.3 + Vite 5 SPA/PWA. ~200 source files, ~70 page components (admin-weighted), 72 routes, 16k lines CSS, i18n in **en/hi/ta/te** (English, Hindi, Tamil, Telugu).

## Routing & guards (verified: `App.jsx`, 72 `<Route>`)

Five guard wrappers compose the access model on the client (mirrored server-side — the client guard is UX, the gateway/service is the real enforcement):
- `PublicOnlyRoute` (login/register — redirect if already authed)
- `ProtectedRoute` (any authenticated user)
- `AdminRoute` (ADMIN/SUPER_ADMIN)
- `SuperAdminRoute` (SUPER_ADMIN only — the largest guarded set, 14 routes)
- `CompleteProfileRoute` (forces profile completion / temp-password change)

Admin routes are additionally filtered at runtime by `useModuleAccess` against the **per-binge module matrix**, and by `BingeContext` (which Binge is selected). A route the module matrix disables is hidden/blocked client-side and 403'd server-side — belt and suspenders.

## State model (verified) — a split worth noting

Two state systems coexist:
- **Zustand** — `stores/authStore.ts`, `stores/bingeStore.ts`, `stores/index.ts`.
- **React Context** — `context/AuthContext.jsx`, `context/BingeContext.jsx`.

Both model auth and binge selection. This duplication is a real maintainability smell: two sources of truth for "who am I" and "which binge," kept in sync by convention. Plus `localStorage` holds `user`, `token_exp`, and `selectedBinge` (read directly by the axios client). Consolidating onto one store is a recommended cleanup (see 08). The token itself is **not** in localStorage — it rides in an httpOnly cookie (good).

## The API client (verified: `services/api.js`, 312 lines)

A single axios instance with rich interceptors — this is the best-engineered part of the frontend:
- Auto-attaches an **Idempotency-Key** (UUID) to every POST/PUT/PATCH/DELETE so retries hit the same server slot; caller keys preserved.
- **CSRF double-submit**: echoes the `XSRF-TOKEN` cookie as `X-XSRF-TOKEN`.
- **X-Binge-Id** tenancy header from `localStorage.selectedBinge`.
- **Sentry** user/trace context (`X-Sentry-Trace-Id`) linking React errors ↔ backend Zipkin spans.
- **Proactive refresh** (<60s to expiry) + **reactive 401 refresh** with a queued-request drain (cap 50) so a long admin form doesn't 401 mid-flow.
- Friendly, PII-stripped error extraction; 403 temp-password → forced re-login; 429 → precise retry-after countdown; 5xx/network toasts.

**Architecture note:** endpoint URLs are called **inline in pages** (there's no per-resource API module layer beyond this instance). For ~400 endpoint call-sites across 70 pages this scatters the contract; a thin typed `api/` module per resource would reduce drift and duplication.

## PWA (verified: `vite.config.js` VitePWA)

- `generateSW` + `registerType: 'autoUpdate'` — a freshly deployed build is picked up and activated (this is why the older "SW serves stale bundle" class of bugs was tamed; still, **suspect a stale service worker before suspecting your code** when a frontend fix "doesn't reach the user").
- **API is NetworkOnly** (no `api-cache`) — authenticated booking/admin responses are deliberately *not* cached by Workbox (closed the SEC-009 authenticated-response-caching hole).
- The large **address dataset is excluded from precache** (its own on-demand chunk) — precache dropped ~80%.
- `PWAUpdatePrompt.jsx` surfaces the "new version" prompt.

## Component organization (verified)

Grouped dirs: `components/{admin,authority,booking,checkout,form,ui}` plus shared widgets (`Navbar`, `NotificationsBell`, `CustomerMessagesBell`, `BookingWizard`, `TaxBreakdown`, `CurrencySwitcher`, `TimezonePicker`, `ThemeToggle`, `PushToggle`, `ErrorBoundary`, `SEO`). Good separation for shared pieces; the *pages* are where bloat lives.

## The two-CSS-vocabulary problem (verified: 268 `.adm-*` vs 162 `.admin-*` + 27 `.modal-*`)

There are **two admin design systems** in the codebase:
1. A styled `.adm-*` vocabulary (268 selectors).
2. A separate `.admin-*` / `.modal-*` / `.form-row` vocabulary now consolidated into `styles/admin-system.css` (imported globally).

Pages written against one vocabulary look unstyled if they expect the other. This is the root cause of "this admin page looks broken/unstyled" reports. Unifying onto one system is a real (if unglamorous) cleanup.

## UI/UX bloat & god-components (verified sizes)

The largest page components are maintenance hotspots and likely UX-consistency offenders:

| Page | Lines |
|---|---:|
| `AdminBookings.jsx` | 2,385 |
| `BingeManagement.jsx` | 2,029 |
| `AdminLoyaltyCenter.jsx` | 1,616 |
| `MyBookings.jsx` | 1,170 |
| `AdminUsersConfig.jsx` | 922 |
| `PaymentPage.jsx` | 906 |
| `AdminTaxes.jsx` | 874 |
| `BookingConfirmation.jsx` | 853 |

A 2.3k-line page holds data-fetching, tables, modals, forms, and business logic in one file — hard to test, easy to regress, and a driver of inconsistent behaviour between similar admin screens. Extracting table/modal/form sub-components and moving data logic into hooks is the highest-leverage frontend work.

## Accessibility (from prior audit, partially open)

AA text tokens, a global reduced-motion layer, and keyboard-reachable reveal buttons were added. The **residue**: hand-built drawers / click-only rows (`RoomDetailModal`, `AdminBookings` tables, `CustomerReviewsDrawer` row actions) are not fully keyboard/AT accessible. Worth an axe pass + keyboard sweep before launch.

## UI/UX findings (see 07)

- Dual state (Zustand + Context) — pick one.
- Two CSS vocabularies — unify.
- God-components — decompose, extract hooks.
- Inline endpoint calls — add a per-resource API layer.
- A11y residue on custom drawers/rows.
- Verify every admin page respects `useModuleAccess` + binge context so no screen assumes a binge is selected (past 403-storm cause).
