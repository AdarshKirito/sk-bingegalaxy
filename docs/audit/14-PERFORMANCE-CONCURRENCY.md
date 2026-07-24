# 14 — Performance & Concurrency

Depth = Level B. No new load test was run (no host k6/Node; existing k6 evidence cited as historical only). No benchmark numbers are invented. Findings are separated into confirmed, likely, and load-test-required.

## Confirmed characteristics (from code/schema)

- **Booking write path serializes per venue-day** on a Postgres advisory lock. Correct for integrity; a **concurrency ceiling** — all bookings for the same binge+date contend on one lock. For a busy binge this serializes checkout. Likely acceptable at current scale; a capacity risk at high per-venue booking rates.
- **Availability is computed live** (COUNT/SUM, not cached inventory) — read cost scales with bookings/blocks per day; mitigated by Caffeine caches (binge-scoped) with `noStore` on the availability HTTP responses.
- **Money/inventory via live aggregates** (add-on SUM, capacity COUNT) inside the lock — correct, but each is a query per booking.
- **Outbox drain every 2s** + **schedulers** (17 in booking) under ShedLock — single-runner, fine for correctness; outbox throughput is bounded by the 2s poll × batch size (verify batch size for burst payment traffic).

## Likely bottlenecks (PROBABLE, need measurement)

- **booking-service** is the hub (Feign target for payment + availability, owner of pricing/tax/FX/loyalty) — the most likely first bottleneck under load.
- **Redundant bookings indexes** (DATA-007) add write amplification on the hottest table.
- **N+1 risk** in admin list/report endpoints (not profiled) — pricing/loyalty enrichment per row is a candidate.
- **Notification Mongo growth unbounded** (DATA-003) — with no TTL, collection size grows without limit, degrading queries over time.

## Capacity risks

- Single writable Postgres per service; the advisory lock assumes single-primary. Horizontal scaling of booking-service is safe for reads but write-serialization is per venue-day regardless of instance count (lock is in the DB).
- k8s HPA (7) + PDB (10) exist for horizontal scaling of stateless services; stateful (Postgres/Kafka/Mongo) scaling is the harder constraint.

## Load/concurrency tests required to confirm (define, don't fabricate)

1. **Booking contention:** N concurrent bookings on the same binge+date → measure p95 checkout latency + lock wait; find the per-venue booking-rate ceiling.
2. **Concurrent double-booking:** the test the audit harness could not run (Secure-cookie CSRF) — drive via browser/HTTPS: two same-slot requests, assert exactly one succeeds.
3. **Payment webhook burst:** duplicate + out-of-order + high-rate webhooks → assert dedup holds and no double-credit.
4. **Outbox drain under burst:** publish backlog, measure drain time and consumer lag (soak).
5. **Admin report/export** on a large binge → p95 + N+1 detection.
6. **Notification volume soak** → confirm the (currently absent) TTL is needed and Mongo query latency over time.

The existing `load-tests/` k6 suite (smoke/spike/soak, payments-spike) is the right harness; `production-proof/load-testing/LOAD-TEST-EVIDENCE.md` records prior runs (historical, not re-verified).
