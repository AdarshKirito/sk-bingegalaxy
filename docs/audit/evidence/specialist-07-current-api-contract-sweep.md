# Specialist 07 — Current frontend route and API-contract sweep

**Inspection date:** 2026-07-16  
**Scope:** current source tree; static extraction only  
**Result:** all current frontend calls map to a Spring endpoint; the prior 424-endpoint catalog is stale.

## Current counts

| Surface | Current result |
|---|---:|
| React Router declarations | 70 (69 concrete + wildcard) |
| Axios-family call definitions | 413 |
| Other logical calls | 2 (EventSource SSE + analytics beacon/fetch) |
| Logical frontend call definitions | 415 |
| Normalized unique frontend method/path pairs | 407 |
| Unmatched frontend method/path pairs | **0** |
| Spring method mappings | **421** |
| Controller classes | **47** |

The old `endpoint-inventory.tsv` / `06a-ENDPOINT-CATALOG.md` count of 424 is not current. It retains two deleted `CheckoutController` mappings and mis-parses the class-level mapping on `LoyaltyV2SuperAdminController` as a standalone `ANY` endpoint. The regenerated current inventory must contain 421 rows.

## Reproducible method

1. Routes: extract one-line `<Route path=...>` declarations from `frontend/src/App.jsx`; identify wrapper guards and the terminal page component.
2. Frontend HTTP: scan `.js/.jsx/.ts/.tsx` for `api`, loyalty-v2, and direct Axios method calls. All first arguments are statically recoverable literals/templates. Prefix client bases, strip query strings, replace `${...}` parameters with `{}`, normalize slashes, and deduplicate by HTTP method + full path.
3. Add the EventSource admin stream and analytics beacon/fetch call, which are not Axios calls.
4. Backend: scan first-party `*Controller.java` under `src/main/java`; compose the final pre-class `@RequestMapping` base with each method mapping; normalize Spring path variables (including regex variables) to `{}`.
5. Exact-diff method + normalized path. Result: zero frontend orphans.

## Guard inventory

| Guard class | Route count | Semantics |
|---|---:|---|
| Public / fallback | 5 | Unauthenticated access allowed; includes wildcard |
| `PublicOnlyRoute` | 5 | Redirects an active authenticated user |
| `CompleteProfileRoute` | 1 | Authenticated non-admin with missing phone |
| `ProtectedRoute` | 9 | Active authenticated non-admin with completed phone |
| `BingeRequired` | 7 | Authenticated non-admin + selected Binge |
| `AdminRoute` | 8 | Active authenticated admin |
| `AdminBingeRequired` | 21 | Authenticated admin + selected Binge |
| `SuperAdminRoute` | 13 | Native/delegated super-admin module scope |
| Nested super-admin + Binge | 1 | Tax administration |

The detailed route declarations remain in `docs/audit/04-FRONTEND.md`; this pass verified all 70 against the current `App.jsx`.

## Backend mappings without a normal Axios caller

Sixteen mappings are absent from the static Axios set. Twelve are explained by service-to-service calls, provider webhooks, EventSource, analytics beacon, dynamic media URLs, CSRF compatibility, or gateway fallback. Four are product-surface gaps/candidates:

| Method/path | Classification |
|---|---|
| `POST /api/v1/auth/privacy/admin/anonymize/{userId}` | Regulatory super-admin operation with no SPA surface |
| `POST /api/v1/bookings/{bookingRef}/transfer` | Unsafe legacy immediate-transfer endpoint; superseded by consent-based plural `/transfers` flow |
| `GET /api/v1/bookings/admin/export/csv` | Server export exists; SPA exports only its in-memory page |
| `GET /api/v1/payments/booking/{bookingRef}/refunds` | Customer refund timeline exists but is not rendered; endpoint also lacks customer ownership enforcement |

## API-002 — immediate transfer bypasses the recipient-consent workflow

`BookingController.java:168-178` exposes the singular `/transfer` endpoint. `BookingService.java:1378-1444` immediately overwrites recipient PII, marks the booking transferred, and keeps the original `customerId`. The current frontend instead uses the two-phase plural endpoints at `frontend/src/services/endpoints.js:133-139`; `BookingTransferService` invokes the same mutation only after magic-link acceptance.

Required target: make the immediate mutation internal/private to the acceptance service or delete the public singular mapping. A customer should not be able to bypass recipient consent and account/ownership reconciliation by calling the legacy route directly.

## FE-001 — customer payment screens silently stop at 20 records

`GET /payments/my` is paginated with defaults `page=0,size=20` (`PaymentController.java:118-126`). `frontend/src/services/endpoints.js:304` sends no paging parameters. `CustomerPayments.jsx` correctly recognizes a Spring Page but renders only `content`; it exposes no pagination/load-more control. `AccountCenter.jsx` and `Dashboard.jsx` also consume the same first page, so lifetime totals/counts become incomplete for customers with more than 20 payment rows.

Required target: cursor/page navigation for history and server-computed aggregate endpoints for dashboard totals; never derive lifetime spend from a default page.

## Positive controls

- All 407 normalized frontend method/path pairs have a matching backend mapping.
- High-risk request payloads previously checked still align with strict DTO fields.
- Internal, provider-webhook, EventSource, beacon, and media-resource endpoints were classified rather than falsely reported as orphans.

## Limitations

- Static contract existence does not prove response-shape, authorization, runtime gateway routing, or semantic correctness.
- Dynamic query values and request/response fields were not exhaustively type-checked across all 407 pairs.
- No browser or live HTTP contract suite was run in this pass.
