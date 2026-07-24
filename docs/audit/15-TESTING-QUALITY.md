# 15 — Testing & Quality

Tests were **not executed** (no host JDK/Maven/Node; Docker builds excluded for disk-safety). This assesses test *inventory and coverage of critical paths* statically; existing result logs are historical only.

## Inventory

| Suite | Count | Notes |
|---|---|---|
| Backend JUnit (all services) | 75 test classes | booking 41, gateway 7, auth 7, notification 6, payment 5, common-lib 7, availability 2 |
| Frontend Vitest | 40 `.test.*` | pages, ui components, stores, api, endpoints, contexts, integration |
| Frontend Playwright e2e | 7 specs | admin-dashboard, booking, customer-toolbar, edge-cases, home-cms(×2), login |
| CI gates | Jenkinsfile | mvn test, npm test, npm-audit, OWASP dep-check, Trivy, migration-safety, Flyway validate |
| Coverage gate | JaCoCo | 60% line / 50% branch (parent pom) |

Notable: `CrossBingeIsolationTest` exists and documents the isolation invariant — a good sign the team knows the boundary — but does not cover the endpoints that violate it (SEC-001/002).

## Critical-path test coverage matrix (static assessment)

| Requirement | Covered? |
|---|---|
| Auth (login/refresh/MFA) | ✅ auth tests |
| Authorization role checks | ⚠️ partial; **no cross-binge test on recovery/invoice endpoints** (TEST-001) |
| Binge isolation | ⚠️ invariant test exists; violating endpoints untested |
| Double booking / concurrency | ⚠️ conflict logic tested; concurrent race not in suite |
| Slot-hold expiry / conversion | ⚠️ hold expiry likely tested; conversion is dead code (BOOK-001) |
| Pricing precedence / surge / rate codes | 🔵 pricing + checkout tests exist (`CheckoutQuoteServiceTest`, `BookingCheckoutAndRevenueTest`) |
| Payment idempotency / duplicate+delayed+out-of-order webhook | 🔵 `PaymentEventListenerTest`; over-refund/duplicate-refund DB path not covered |
| Booking compensation / cancel / refund | 🔵 `BookingServiceLifecycleTest` |
| Timezone / DST | ❌ no DST test found |
| Currency rounding | 🔵 `MoneyUtilTest` |
| Event replay / outbox | 🔵 `OutboxPublisherTest` |
| Cross-binge access | ❌ (TEST-001) |
| Browser refresh during checkout | ❌ (e2e edge-cases partial) |

## Gaps (→ TEST-001)

Highest-value missing tests: (1) cross-binge authz on `AdminRecoveryQueueController` + `InvoiceController.listInvoicesForBinge`; (2) over-refund / duplicate-refund DB enforcement; (3) multi-room same-customer concurrent duplicate; (4) concurrent double-booking race; (5) DST behavior for `America/Chicago` venues; (6) Mongo TTL index presence. These map directly to the confirmed defects and would have caught them.

## Quality signals

- Tests exist at the right layers and CI has real security/quality gates (dep-check, Trivy, migration-safety, coverage). This is a mature test posture.
- The gap is not quantity but **coverage of the specific integrity/isolation invariants that the confirmed defects live in** — the tests assert the happy path and the documented invariant, but not the endpoints that break it.
