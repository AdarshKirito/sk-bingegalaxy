# 11 — Pricing, Payments and Financial Integrity (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; **no provider sandbox calls executed** — that remains the single biggest launch gate

## Money contract (VERIFIED-STATIC)

- All amounts are **minor-unit longs**; scale/rounding rules in [docs/MONEY_SCALE_AND_ROUNDING_CONTRACT.md](../MONEY_SCALE_AND_ROUNDING_CONTRACT.md) match MoneyUtils implementation spot-checks
- Server-authoritative pricing: base → rate code → customer profile → surge → FX → tax → **BookingPriceSnapshot** (immutable at booking)
- Refund math computed from snapshots via CancellationTier policy — never recomputed from live prices
- Invariant matrix: [evidence/money-invariant-matrix.tsv](evidence/money-invariant-matrix.tsv)

## Gateways

| Gateway | Flow | Safeguards |
|---|---|---|
| Razorpay | order → callback/webhook | HMAC verify, event dedup, durable PaymentIntent, status history |
| Stripe Connect | onboarding → PaymentConnectedAccount; webhook `/payments/webhooks/stripe` | signature verify, dedup, venue-country method resolution |
| Simulation | dev only | `@PostConstruct` **fail-fast** if enabled in production or credentials missing ([PaymentService.java](../../backend/payment-service/src/main/java/com/skbingegalaxy/payment/service/PaymentService.java) L107-136: IllegalStateException at L113/120/127/136) |

## Refund integrity (PAY-002 history → FIXED IN SOURCE)

The July-16 audit's top finding — refunds were book-keeping-only — is **fixed in current source**:

1. Durable refund intents (saga) persisted before provider call
2. Real provider refund API calls (Razorpay + Stripe clients)
3. `RefundWebhookService` confirms asynchronously
4. **Receipt-first reconciliation**: `PaymentReconciliationScheduler` L158-208 resolves ambiguous outcomes by querying the provider before flipping local state
5. V14 partial UNIQUE index on `gateway_refund_id` — a double-refund row is structurally impossible
6. Failed refunds surface in `/admin/failed-refunds` console with retry via approval queue

**Gate:** all of this is code-verified only. No end-to-end payment or refund has ever been proven against a provider sandbox (product census: "No end-to-end payment has ever run" — CHANGELOG note). **PR-PAY-01 blocks launch.**

## Disputes & approvals

`PaymentDispute` ingests via Razorpay webhook; ops triage in /admin/disputes; approval queue executes REFUND_RETRY only (other action types intentionally unwired — PG-03). Ledger entries record financial movements in both booking and payment services.

## Financial risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| PR-PAY-01 | **P0 (gate)** | No provider-sandbox end-to-end proof for payment, refund, webhook, dispute paths |
| PAY-CR-03 | P2 | Reconciliation scheduler cadence + outbox depth have no alerting — silent stalls possible (ties to OBS-01) |
| PAY-CR-04 | P2 | Stripe Connect onboarding UX incomplete for edge states (deauthorized accounts) — static read of handler branches |
| PG-03 | P3 | Approval queue supports only REFUND_RETRY execution |

## Verified-fixed history (do not re-report)

- PAY-002 refunds real (above) · payment/approval tenant binding (`requireManagedBinge` on payment admin controllers) · webhook dedup + HMAC · paid-cancellation settlement math off snapshots. Historical statuses tracked in [HISTORICAL-AND-SUPERSEDED-FINDINGS.md](HISTORICAL-AND-SUPERSEDED-FINDINGS.md).
