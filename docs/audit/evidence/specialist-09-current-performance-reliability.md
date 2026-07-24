# Specialist 09 — Current performance and reliability inspection

**Inspection date:** 2026-07-16  
**Method:** static source and checked-in build-artifact inspection. No current benchmark or load generator was available.

## PERF-001 — booking DTO mapping has list-level N+1 query paths

Many page/list methods in `BookingService` map each booking through `toDto`. `toDto` evaluates customer cancellation eligibility and add-on/category data for each row. `evaluateCustomerCancellation` resolves the Binge and cancellation tiers per booking, while booking page entity graphs do not fetch every required association. Depending on persistence-context warmth, a page can therefore add several queries per booking.

Required target: query-budget tests, a projection/batch-fetch strategy for list DTOs, preloaded policy data per Binge, and separate detail-only enrichment. Acceptance: a fixed upper query bound for 1, 20, and 100-row pages rather than linear growth.

## PERF-002 — address dataset creates an approximately 8.7 MB precached chunk

`AddressFields.jsx` imports the full `country-state-city` package. `vite.config.js:69-72` raises Workbox's single-file precache cap to 12 MiB specifically for that dataset. The checked-in prior build contains an `AddressFields` JavaScript chunk of approximately 8.66 MB, and the generated service worker precaches it. Source configuration still has the same root cause; a current build measurement was not possible.

Required target: country-scoped data, server lookup, or dynamically imported region subsets; remove the large chunk from unconditional PWA precache; add bundle-budget CI (initial, route, and precache totals). Treat the 8.66 MB number as historical corroboration until a clean current build is measured.

## REL-002 — reconciliation performs remote I/O inside long database transactions

`PaymentReconciliationScheduler#reconcileStalePayments` is a 240-second transaction and loops all stale rows while calling Razorpay. `reconcileDailySettlement` is an 1,100-second transaction and loops all successful payments for the day while calling Razorpay. This holds a database connection for network latency, couples all progress to one rollback boundary, and has no bounded page size.

Required target: page/claim rows in short transactions, perform remote calls outside the transaction, then commit each conditional result in its own bounded transaction. Add queue depth, row age, provider-latency, unknown-outcome, and last-success metrics.

## Manual-payment reconciliation false positives

Cash/admin payment rows use synthetic nonblank order identifiers (`CASH-ORD-*`, `ADM-ORD-*`). Daily settlement skips only null/blank IDs and otherwise asks Razorpay for them, so it can flag legitimate manual payments as mismatches. Use an explicit payment-source/provider field rather than string-prefix inference, and exclude non-provider settlements from provider reconciliation.

## Reliability findings maintained elsewhere

- Unknown provider status is incorrectly converted to `FAILED` (PAY-007).
- Webhook dedup commits independently of business state (PAY-008).
- Refund provider calls precede durable intent/result (PAY-006).
- PWA response caching is a security and stale-data reliability defect (SEC-009).

## Positive controls

- Route-level lazy loading is broadly used.
- Schedulers use ShedLock to avoid duplicate cluster execution.
- Refund settlement polling leaves `null` provider status pending rather than failing it, which is safer than the stale-payment path.
- Transaction timeouts bound the worst single batch duration, although the design still holds resources too long.

## Limitations

- No current Lighthouse, Web Vitals, JFR, database query-count profile, k6 run, or production trace was captured.
- The prior `dist` bundle is historical evidence; current source persistence is confirmed but current byte size is not.
- N+1 severity is based on reachable ORM call structure; exact query counts require an integration test/profile.
