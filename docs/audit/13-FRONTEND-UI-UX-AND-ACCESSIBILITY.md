# 13 — Frontend, UI/UX and Accessibility (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; no browser session executed this run

## Architecture

- React 18.3 + Vite 5, 67 pages / 71 routes ([evidence/frontend-routes-current.tsv](evidence/frontend-routes-current.tsv)), 13 service modules, Zustand + Context state
- PWA: vite-plugin-pwa `autoUpdate`; **`/api` is NetworkOnly** — the July-16 blocker SEC-009 (authenticated API responses cached by the service worker) is **FIXED** in [vite.config.js](../../frontend/vite.config.js)
- nginx serves the build ([nginx.conf](../../frontend/nginx.conf)); Dockerfile multi-stage non-root

## Token & session security (strong — VERIFIED-STATIC)

| Control | Implementation |
|---|---|
| Storage | httpOnly cookies only; no tokens in localStorage/sessionStorage |
| Refresh | single-flight refresh (concurrent 401s share one refresh promise) |
| CSRF | token attached on mutations |
| Idempotency | `Idempotency-Key` auto-attached by the axios client on POST/PUT/PATCH/DELETE |
| Sanitization | DOMPurify at the single CMS-HTML injection point; no other `dangerouslySetInnerHTML` |
| Route guards | SuperAdminRoute / AdminBingeRequired / BingeRequired / ProtectedRoute — all paired with backend enforcement (see doc 07) |

## Guard census

9 super-admin, 31 admin-binge, 18 customer-binge, 8 public, 5 utility routes; **no orphaned pages** — every page reachable and guarded; staff/customer identity separation enforced in App.jsx.

## UX findings

| Finding | Sev | Evidence |
|---|---|---|
| `AdminBookings.jsx` ~1,800 LOC monolith — filters, tables, modals, exports in one file | P3 (maintainability) | frontend/src/pages/AdminBookings.jsx |
| AdminApprovals executes only REFUND_RETRY; other buttons no-op with console log | P3 (product) | AdminApprovals.jsx:111 |
| Loading/error states present on data pages (spot-checked 12 pages) | OK | — |
| Mobile: responsive grid + PWA installable; venue pages usable at 360 px (static CSS read) | OK-static | — |

## Accessibility (static-only review)

| Area | Status |
|---|---|
| Semantic landmarks, labels on forms | Mostly present (spot-checks) |
| `prefers-reduced-motion` | **Absent** — animations unconditional (A11Y-01, P3) |
| Color-contrast verification | **Not performed** (needs browser tooling) — unknown |
| Keyboard traps | None found in static read of modals (focus management present in dialog components) |
| alt text | Venue images carry alt props in sampled components |

## Testing

42 frontend test files + 7 Playwright specs exist. HISTORICAL claim (CHANGELOG-2026-07-21): 41 files / 359 tests passing — not re-executed this run. Playwright artifacts are wrongly **tracked in git** (playwright-report/, test-results/ — HYG-03).

## Risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| A11Y-01 | P3 | No reduced-motion support; contrast unaudited |
| FE-02 | P3 | AdminBookings.jsx monolith |
| HYG-03 | P2 | Test artifacts tracked in git |
