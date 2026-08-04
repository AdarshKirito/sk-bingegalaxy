# 19 — Code Quality and Maintainability (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · static census + spot-reads (no linter executed)

## Cleanliness census (whole-repo grep — unusually good)

| Signal | Count |
|---|---:|
| TODO / FIXME / XXX / HACK | **0** |
| `printStackTrace()` | **0** |
| Empty catch blocks | **0** |
| `System.out.println` (main code) | **0** |
| `@Deprecated` | 1 |
| `orElse(null)` | **79** (QUAL-02 — NPE seams; prefer `orElseThrow` domain exceptions) |

## Structure

- Consistent per-service layering: controller → service → repository → entity; DTO mapping via MapStruct
- common-lib centralizes enums/topics/money — no copy-paste drift found in sampled shared types
- Exception handling: `@ControllerAdvice` per service, ApiResponse envelope, no leaked stack traces in handlers (static read)
- Naming/conventions: uniform; Flyway files well-described (`V75__room_occupancy_db_backstop.sql` style)

## Hotspots (size/complexity)

| File | Issue |
|---|---|
| [AdminBookings.jsx](../../frontend/src/pages/AdminBookings.jsx) (~1,800 LOC) | God-page: filters/table/modals/exports; split into components (FE-02) |
| BookingService | Large orchestrator; acceptable but nearing split threshold (booking create vs lifecycle vs admin ops) |
| PaymentReconciliationScheduler | Dense logic (receipt-first resolution); deserves the integration tests it lacks |
| LoyaltyV2 engines | Well-factored (Earn/Redeem/Wallet separated) ✅ |

## Documentation-in-code

Javadoc on tricky spots is genuinely good (e.g., UserAnonymizationService explains per-user commit isolation; LoyaltyV2SuperAdminController documents its three-layer guard). Rare and valuable.

## Maintainability risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| QUAL-02 | P3 | 79 `orElse(null)` |
| FE-02 | P3 | AdminBookings monolith |
| QUAL-03 | P3 | No static-analysis gate (SpotBugs/ErrorProne/ESLint-strict) in CI |
| QUAL-04 | P3 | config-server/discovery-server have zero tests (low logic, low risk — but zero) |
