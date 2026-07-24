# 11 — Operational Modules (admin) — status classification

Each intended per-binge admin module classified by verified completeness. Classification is based on route + component + endpoint + backend evidence; where the full data-changing path wasn't runtime-exercised it is marked accordingly. Legend: ✅ functional · ⚠️ functional-with-defects · 🟡 partial · 🔵 backend-strong/UX-unverified.

| Module | Route | Backend | Status | Notes / issues |
|---|---|---|---|---|
| Reports | `/admin/reports` | AdminReports + analytics | 🔵 | binge-scoped; UX not runtime-verified |
| Messages (admin) | `/admin/messages` | AdminMessages + MessageAttachment | 🔵 | new in overhaul; attachment controller present |
| Venue / Rooms | `/admin/venue-rooms` | BookingController venue-rooms + AdminBooking | 🔵 | rooms are binge-scoped inventory |
| Event Types | `/admin/event-types` | AdminEventTypes | 🔵 | priced, binge-scoped |
| Rate Codes | `/admin/rate-codes` | AdminPricing (validatePricingScope) | ✅ | ownership-checked |
| Surge Rules | `/admin/surge-rules` | AdminPricing (V74) | 🔵 | production-grade surge migration |
| Blocked Dates | `/admin/blocked-dates` | availability-service | ⚠️ | granularity mismatch (DATA-008) |
| Slot Holds | `/admin/slot-holds` | SlotHold* | ⚠️ | **hold hand-off is dead code (BOOK-001)** — module shows holds that don't reserve |
| People / Users | `/admin/users-config` | AdminUsersConfig | 🔵 | binge-scoped |
| Waitlist | `/admin/waitlist` | Waitlist* | ⚠️ | OFFER doesn't reserve slot (BOOK-002) |
| Customer Freezes | `/admin/customer-freezes` | CustomerFreeze* | 🔵 | ownership-checked |
| Risk Flags | `/admin/risk-flags` | AdminRiskFlag (requireBingeOwnership) | ✅ | correctly scoped |
| Support Console | `/admin/support` | AdminSupport | 🔵 | AdminRoute (not AdminBingeRequired) — verify scoping |
| Disputes | `/admin/disputes` | payment disputes (V12) | 🔵 | cross-service module (deniedModules gated) |
| Failed Refunds | `/admin/failed-refunds` | payment refunds | ⚠️ | over-refund not DB-enforced (DATA-002) |
| Recovery Queues | `/admin/recovery` | AdminRecoveryQueue | ❌ | **cross-binge PII leak (SEC-001)** — not ownership-scoped |
| Approvals | `/admin/approvals` | AdminApproval (V11) | 🔵 | maker-checker |
| Currencies (super) | `/admin/currencies` | AdminCurrency | 🔵 | super-admin scope |
| Taxes | `/admin/taxes` | AdminTax (V72) | ✅ | ownership-checked |
| Ops (super) | `/admin/ops` | AdminOps | ⚠️ | reachable by any binge admin (SEC-005) |

## Key takeaways

- The intended admin menu is **largely realized** as routes + backend, gated server-side by the V71 module-permission matrix.
- Three modules have material defects tied to the top issues: **Recovery Queues** (SEC-001, the worst), **Slot Holds** (BOOK-001), **Failed Refunds** (DATA-002). **Ops** and the funnel (SEC-005) are over-reachable.
- No module is pure UI-mock/dead — all have real backends. Completeness verification was mostly static + endpoint-level; a per-module data-path runtime pass (create/update/delete) was **not** completed for every module (CSRF write-harness limit) and is recommended.
