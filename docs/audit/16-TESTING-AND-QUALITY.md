# 16 — Testing and Quality (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · **no tests were executed this run** (Maven/Node unavailable). Counts are static; pass-rates are HISTORICAL.

## Test census (VERIFIED-STATIC)

| Module | Test files |
|---|---:|
| booking-service | 27 |
| payment-service | 8 |
| auth-service | 8 |
| api-gateway | 7 |
| common-lib | 7 |
| notification-service | 6 |
| availability-service | 2 |
| config-server / discovery-server | 0 |
| **Backend total** | **80** |
| Frontend (Vitest/RTL) | 42 |
| Playwright e2e specs | 7 |

HISTORICAL pass evidence: CHANGELOG-2026-07-21 claims 430 backend passed/6 skipped + 359 frontend tests; CHANGELOG-2026-07-24 claims 439 backend passed. Believable but **not re-proven** in this run.

## The critical structural gap

🔴 **Zero `@Testcontainers` tests in the entire repo.** Consequences:

1. **V75 occupancy trigger has no automated test** — the last-line oversell defense is unverified by CI
2. Advisory-lock concurrency paths are **mocked** in unit tests — real contention never exercised
3. Flyway chains (80 booking migrations!) are never applied against a real PG in tests (historical manual runs only)
4. Mongo TTL/dedup behavior untested
5. Kafka listener + outbox relay integration untested (unit-mocked)

`BookingFlowIntegrationTest` exists but is `@Disabled` (BOOK-02).

## Test-quality signals (spot-read of 15 test files)

✅ Real assertions (not smoke-only); negative-path tests for RBAC (AdminRecoveryQueueScopeTest is a proper tenant-isolation regression test); state-machine transition tables tested; money math has table-driven cases
⚠️ Heavy Mockito use at repository boundaries (necessity, given no Testcontainers)

## Static quality signals (whole-repo grep, VERIFIED)

| Signal | Count | Assessment |
|---|---:|---|
| TODO / FIXME / XXX | 0 | Very clean |
| `printStackTrace()` | 0 | Clean |
| Empty catch blocks | 0 | Clean |
| `orElse(null)` | 79 | NPE seam risk — QUAL-02 (P3) |
| `@Deprecated` | 1 | Fine |
| System.out.println in main code | 0 | Clean |

## Coverage tooling

No JaCoCo/coverage gate in the build (Jenkins runs tests without a coverage threshold); frontend has no coverage threshold either (TEST-03, P2).

## Risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| TEST-01 | **P1** | No Testcontainers: V75 trigger, advisory locks, Flyway, Kafka, Mongo TTL untested at integration level |
| TEST-03 | P2 | No coverage thresholds/gates in CI |
| BOOK-02 | P3 | Disabled integration test |
| QUAL-02 | P3 | 79 `orElse(null)` seams |
