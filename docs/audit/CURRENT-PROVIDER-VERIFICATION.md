# CURRENT PROVIDER VERIFICATION — AUD-2026-07-25-01 (execution phase)

> Scope: Razorpay (primary) and Stripe Connect (optional) integrations in payment-service.
> Snapshot: `main` @ `6440f58`. Rules honored: no real credentials, no real money, no provider settings touched.

## 1. Credential availability check (live environment, values never printed)

Method: in-container prefix classification — each var tested for emptiness and, if set, classified only as
`TEST-PREFIX-SET` (rzp_test_/sk_test_/whsec_), `LIVE-PREFIX-SET` (rzp_live_/sk_live_) or `SET-unknown-prefix`.

| Variable (payment container) | Result |
|---|---|
| RAZORPAY_KEY_ID | **EMPTY** |
| RAZORPAY_KEY_SECRET | **EMPTY** |
| STRIPE_SECRET_KEY | **EMPTY** |
| STRIPE_WEBHOOK_SECRET | **EMPTY** |

Compose contract (docker-compose.yml L565-577): empty secrets intentionally keep providers dormant in development;
`RAZORPAY_WEBHOOK_SECRET`, `STRIPE_*` all default empty.

## 2. What WAS verified locally (current source, executed by this audit)

| Verification | Method | Result |
|---|---|---|
| Payment module mocked-contract suite | `mvn test` payment-service in Java 17 container | **93/93 PASS** — includes webhook signature validation, duplicate-webhook idempotency, refund-intent state machine, authz scope tests (`AdminApprovalControllerScopeTest` 5/5, `PaymentControllerAuthzTest`) |
| Double-refund DB guard | live `payment_db` inspection | unique indexes `uq_refunds_gateway_refund_id` + `uq_refunds_gateway_receipt` present |
| Refund intent tables (V15) + Stripe connected accounts (V16) | Flyway history after rebuild | applied cleanly from empty DB (see CURRENT-MIGRATION-VERIFICATION.md) |
| Provider-dormant behavior | runtime | payment container healthy with all provider vars EMPTY — graceful-degradation path holds (service starts and serves non-provider endpoints) |

## 3. What CANNOT be verified without user action — BLOCKED-EXTERNAL

| Blocked action | Why necessary | Recovery attempted | Required external action |
|---|---|---|---|
| Sandbox payment initiation → capture → webhook → refund round-trip | Only true provider interaction proves the revenue path end-to-end (PR-PAY-01) | Environment searched for any test credentials (all EMPTY); no credential fabrication permissible; mocked-contract coverage completed instead | Owner must supply **rzp_test_*** (and/or **sk_test_***) keys in `.env` and run one full booking→payment→refund cycle; runbook: PRODUCTION-READINESS-CHECKLIST.md gate PR-PAY-01 |
| Dispute/chargeback flow | provider-side state machine | n/a locally | same credentials + provider dashboard simulation |
| Provider timeout/unknown-state reconciliation against real API | needs live sandbox latency | fault-injection covered logic locally (unit level) | same |

## 4. Verdict

Local verification is as complete as it can be without provider credentials: contract logic, signatures,
idempotency, refund state machine and DB-level double-refund defenses are all **PASS (current, executed)**.
The remaining gap is exactly one item: a real sandbox round-trip — **BLOCKED-EXTERNAL (user-owned secret)**,
unchanged as launch blocker PR-PAY-01.
