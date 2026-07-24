# Specialist 10 — Independent current-tree verification

**Date:** 2026-07-16  
**Method:** fresh read-only verifier pass over the current working tree; no files or runtime state changed.

## Verdict corrections

- **Reject PAY-002 as current:** `RazorpayGatewayClient#createRefund` performs a real provider request and `PaymentService` wires it; retain the old issue only as historical/resolved.
- **Reject PAY-003 as current:** callback HMAC verification now precedes the late-capture mutation.
- **Reject PAY-004 as current:** the provider abstraction is wired for real callback verification/refund behavior.
- **Revise PAY-005:** best-effort receipt lookup mitigates retries, but provider order creation still occurs before the booking DB lock; status is MITIGATED/PARTIAL.
- **Revise A11Y-001:** shared Modal default close behavior is fixed; separate custom-dialog/focus/keyboard defects remain A11Y-003.
- **Reject webhook dedup/dispute lifecycle as blanket positive controls:** HMAC/no-self-approval remain positives, but PAY-008/PAY-009 are current defects.

## Independently confirmed current findings

| Finding | Verdict |
|---|---|
| SEC-009 authenticated Workbox cache | Critical/P0 confirmed |
| SEC-010 cross-Binge approvals/action | Critical/P0 confirmed |
| SEC-011 manual/payment booking binding | Critical/P0 confirmed |
| PAY-006 external refund vs DB commit ambiguity | Critical/P0 confirmed |
| PAY-007 unreachable status → FAILED → duplicate charge | Critical/P0 confirmed |
| SEC-012 Replay privacy | High/P1 source confirmed; deployment activation not verified |
| PAY-008 dedup transaction split | High/P1 confirmed |
| PAY-009 dispute ordering/accounting | High/P1 confirmed |
| PAY-010 refund revenue double-subtraction | High/P1 confirmed |
| BOOK-004 cancellation/refund disconnect | High/P1 confirmed |
| customer refund timeline ownership gap | confirmed; folded into SEC-011 |
| manual overcollection guard weakness | confirmed; folded into SEC-011 |

## Key evidence anchors

- PWA: `frontend/vite.config.js:74-85`, `api.js:50-59`, `authStore.ts:203-210`.
- Approval: `AdminApprovalController.java:40-124`, `AdminApprovalService.java:107-230`, `PaymentService.java:1015-1039`.
- Payment binding/manual writes/refund timeline: `PaymentService.java:143-270,789-927`; booking `PaymentEventListener.java:58-84`.
- Refund ambiguity: `PaymentService.java:565-698`; provider refund `RazorpayGatewayClient.java:153-197`.
- Stale reconciliation: `RazorpayGatewayClient.java:259-280`, `PaymentReconciliationScheduler.java:55-91`, callback/initiation guards in `PaymentService`.
- Dedup/dispute: `WebhookDedupService.java:58-75`, `DisputeWebhookService.java:101-284`.
- Cancellation: `BookingService.java:1097-1106,2014-2040,4721-4792`, `BookingCancelledEventListener.java:30-45`.
- Reporting: `PaymentRepository.java:49-53`, `PaymentService.java:740-748,1164-1176`.

## Limitations

The pass independently verified reachability/root causes from current code. It did not exploit cross-tenant paths, send Razorpay requests, inject failures, run a browser/service worker, or measure production frequency. Those are required acceptance tests, not reasons to downgrade confirmed static boundary defects.
