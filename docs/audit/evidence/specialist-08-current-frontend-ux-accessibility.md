# Specialist 08 — Current frontend UX, accessibility, PWA, and observability review

**Inspection date:** 2026-07-16  
**Method:** static current-tree inspection. No assistive-technology, responsive-browser, or visual-regression run was available.

## SEC-009 — authenticated booking/admin responses are cached across identities

`frontend/vite.config.js:74-86` registers a broad Workbox `NetworkFirst` route for `/api/v1/(bookings|availability|event-types|add-ons|binges)` using a shared `api-cache` and a five-second timeout. It includes `/bookings/my`, `/bookings/admin`, booking detail, support/recovery, and other identity- or Binge-scoped GETs.

Identity and tenant context are cookies/headers (`X-Binge-Id` in `frontend/src/services/api.js`), not part of the URL. No cache partition per user/Binge and no response `Vary` policy were found. Logout removes local state and cookies but never clears Cache Storage (`authStore.ts:203-210`). A slow/offline request on a shared browser profile can therefore return a previous user's or previous Binge's cached PII response.

Workbox documents that Cache Storage is separate from the browser HTTP cache and that `NetworkFirst` falls back to a cached response: <https://developer.chrome.com/docs/workbox/caching-strategies-overview/>. The nginx `Cache-Control: no-store` response policy does not itself delete or partition a Workbox Cache API entry.

Required target: `NetworkOnly` for every authenticated/tenant-scoped endpoint; cache only an allowlist of public, URL-keyed resources; clear named caches on login/logout/Binge change; regression-test slow/offline identity switching.

## SEC-012 — unsafe Sentry Replay privacy configuration (deployment-contingent)

`frontend/src/main.jsx:14-24` initializes Replay only when `VITE_SENTRY_DSN` exists, but explicitly disables text masking and media blocking, samples 10% of production sessions, and captures 100% of error sessions. Customer/admin pages render names, emails, phone numbers, bookings, payments, and support content as ordinary DOM text.

Sentry's privacy guidance says Replay masks text and blocks media by default; disabling both is only appropriate when sensitive data is absent or explicitly masked: <https://docs.sentry.io/platforms/javascript/session-replay/privacy/>.

Activation is **not verified**. The checked Docker/Compose build does not pass a Sentry DSN build argument, and the current CSP does not visibly allow normal Sentry ingest. This is both a latent privacy hazard if DSN/CSP are enabled and a likely observability misconfiguration if teams believe Replay is already operating. Restore privacy defaults and document/test the deployment path before enabling it.

## A11Y-003 — inconsistent dialogs and click-only controls block keyboard users

The shared native `Modal` and `ConfirmDialog` are good controls, but many pages implement independent overlays/drawers without a complete focus lifecycle or Tab containment. Examples include `RoomDetailModal`, `AdminAllUsers`, `AdminBookings`, `AdminUsersConfig`, `MyBookings`, `BookingConfirmation`, and `BingeManagement`. Several admin table rows/cells use `onClick` without equivalent keyboard activation. Password-reveal buttons across login/account/settings pages use `tabIndex={-1}`, removing an interactive control from sequential keyboard navigation.

WCAG 2.2 SC 2.1.1 requires functionality to be operable through a keyboard, and SC 2.4.7 requires visible focus: <https://www.w3.org/WAI/WCAG22/Understanding/keyboard.html>, <https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html>.

Required target: one shared dialog/drawer primitive with initial focus, Tab/Shift+Tab loop, Escape, return-focus, inert background, and correct names/descriptions; native buttons/links for actionable rows/cells; password reveal controls in the tab order. Add Playwright keyboard-only tests.

## A11Y-004 — normal-text color tokens fail the AA contrast baseline

Token-level contrast calculations against the current backgrounds identify normal-text failures: primary `#6366f1` on white is about 4.47:1 (below 4.5:1), success `#10b981` about 2.54:1, warning `#f59e0b` about 2.15:1, danger `#ef4444` about 3.76:1, and dark muted `#7a7a90` on `#1e1e22` about 3.97:1. These tokens are used for small badges and normal UI text, not only large graphics. WCAG's normal-text minimum is 4.5:1: <https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html>.

Required target: role-specific accessible foreground tokens (do not reuse decorative fills as text) and automated axe/contrast checks on representative pages/themes.

## A11Y-005 — reduced-motion preference coverage is incomplete

The only located `prefers-reduced-motion` handling is localized to `Home.css`, while the static survey counted 20 animation and 108 transition declarations. Examples include infinite skeleton shimmer, live pulse, spinners and drawer transitions. This proves incomplete preference support, not that every declaration independently violates WCAG. WCAG guidance covers disabling non-essential motion triggered by interaction: <https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html>.

Required target: a global reduced-motion layer that neutralizes non-essential animation/transition behavior, plus browser tests under the OS preference.

## Additional UX consistency observations

- Numerous pages still use `window.confirm`/`window.prompt` despite the shared confirm system, producing inconsistent copy, focus behavior, styling, and testability.
- Loading/error/empty handling is generally present, but some aggregate screens compute totals from paged data (see FE-001).
- Route-level lazy loading, global skip navigation, `:focus-visible`, meaningful image-alt use, and the repaired shared Modal/FormField behaviors are positive controls.

## Limitations

- Contrast results are token/usage evidence, not a claim that every rendered page was visually graded.
- No keyboard traversal, screen-reader announcement, touch-target, zoom/reflow, mobile viewport, dark-mode screenshot, or axe run was executed.
- Sentry data transmission in the deployed environment is not verified.
