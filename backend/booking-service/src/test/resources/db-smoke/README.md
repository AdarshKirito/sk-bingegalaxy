# Database-level smoke tests

Register item **TEST-01**. The occupancy backstop is a PostgreSQL trigger, so no
mocked unit test can prove it.

**Two automated ITs now cover this**, both Testcontainers-based, both gated behind
`-Dtestcontainers.enabled=true` so a contributor without Docker still gets a green
`mvn test`. **The Jenkinsfile sets that property**, so CI runs them.

| Test | Proves |
|---|---|
| [`OccupancyBackstopIT`](../../java/com/skbingegalaxy/booking/db/OccupancyBackstopIT.java) | The real Flyway chain applies, and the trigger's **logic** is right for a single writer |
| [`OccupancyContentionIT`](../../java/com/skbingegalaxy/booking/db/OccupancyContentionIT.java) | The trigger is right under **concurrent writers** — 12 simultaneous transactions, exact survivor counts |

The second is not redundant. The backstop exists precisely for the case where the
application's advisory lock was bypassed, which by definition means writers are
racing; a trigger that computes correctly against a snapshot taken before a
competing insert committed would still admit an oversell, and no single-threaded
test can see that. The contention test releases all threads from a
`CountDownLatch` barrier — without it, JDBC connection setup staggers them and
the race never happens, so the test would pass vacuously.

## Running it locally with no JDK installed

```powershell
./scripts/run-integration-tests.ps1
```

That script exists because of a real obstacle: on Windows, Docker Desktop serves
its API over a named pipe, so mounting `/var/run/docker.sock` into a build
container yields a socket that answers **HTTP 400** to `/info` and Testcontainers
reports *"Could not find a valid Docker environment"*. The script uses the same
pattern CI systems use — a **Docker-in-Docker sidecar** the build talks to over
TCP — which needs no Docker Desktop setting changed and installs nothing on the
host. Verified: **14/14 passing**.

**These SQL scripts remain useful** as a fast, dependency-free way to exercise the
trigger and constraints directly (no Maven, no JVM), and they cover the V83/V84/V85
constraints the IT does not. Run them when you want a 20-second answer.

**Run one or the other whenever you touch the occupancy backstop or its migrations.**

## V82 — full migration chain

`V82_01_full_chain_assertions.sql` runs against the **real schema after all
migrations V1→head**, not a stub. It proves `duration_minutes` is NOT NULL, the
duration CHECK validated, the trigger still fires at head, buffer CHECKs survived
the rest of the chain, and every V81/V82 entity column exists — the last being a
direct proxy for whether `ddl-auto=validate` will let the service boot.

```powershell
docker run -d --name v82pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=booking_db postgres:16-alpine
docker cp backend/booking-service/src/main/resources/db/migration v82pg:/tmp/migration
docker exec v82pg sh -c 'cd /tmp/migration && ls V*.sql | sort -t V -k2 -n | while read f; do psql -U postgres -d booking_db -v ON_ERROR_STOP=1 -q -f "$f" || exit 1; done'
docker cp backend/booking-service/src/test/resources/db-smoke/V82_01_full_chain_assertions.sql v82pg:/tmp/a.sql
docker exec v82pg psql -U postgres -d booking_db -v ON_ERROR_STOP=1 -q -f /tmp/a.sql
docker rm -f v82pg
```

Expected tail: `=== ALL V82 / FULL-CHAIN ASSERTIONS PASSED ===`.

## The other assertion scripts

Run each the same way (copy in, `psql -v ON_ERROR_STOP=1 -f`) after the chain has
been applied. Each aborts on the first failure, because every assertion `RAISE`s
rather than returning a value.

| Script | Covers |
|---|---|
| `V81_01_assertions.sql` | Turnover buffers against a stub schema (9 assertions) |
| `V82_01_full_chain_assertions.sql` | `duration_minutes` NOT NULL + CHECK, trigger alive at head, entity/schema parity (8) |
| `V85_01_window_and_origin_assertions.sql` | V83 defaults, V84 booking window + permitted durations, V85 origin/external-ref pairing and uniqueness (14) |
| `V86_01_canonical_source_assertions.sql` | Channel slug canonicalisation — uppercase and untrimmed sources rejected, case-sensitive refs stay distinct (5) |
| `V90_01_venue_scoped_external_ref_assertions.sql` | Channel reference uniqueness is per VENUE — two venues may share a reseller's reference, a redelivery within one venue still collides (6) |

**`V86` is the commercially important one.** The redelivery guard is a unique index
on `(external_source, external_ref)`, which compares bytes. If `ACME-Channel` and
`acme-channel` could both be stored, a provider varying its own casing between the
original delivery and a retry would defeat the guard and double-book a slot the
venue had already sold — silently.

## V81 — turnover buffers

`V81__turnover_buffers.sql` rewrites the `booking_occupancy_backstop()` trigger so
it compares *occupancy windows* (`[start − setup, start + duration + cleanup)`)
rather than billable intervals. The application does the same thing in
`BookingService`/`OccupancyWindow`; if the two ever disagree the trigger either
rejects writes the app considers legal, or admits oversells the app already
allowed. Both directions are outages, so the trigger needs its own proof.

```powershell
# 1. throwaway Postgres
docker run -d --name v81pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=bt postgres:16-alpine
# wait for: docker exec v81pg pg_isready -U postgres

# 2. copy in the stub schema, the migration, and the assertions
$mig = "backend/booking-service/src/main/resources/db/migration/V81__turnover_buffers.sql"
$dir = "backend/booking-service/src/test/resources/db-smoke"
docker cp "$dir/V81_00_stub_schema.sql" v81pg:/tmp/stub.sql
docker cp "$mig"                        v81pg:/tmp/v81.sql
docker cp "$dir/V81_01_assertions.sql"  v81pg:/tmp/assert.sql

# 3. run
docker exec v81pg psql -U postgres -d bt -v ON_ERROR_STOP=1 -q -f /tmp/stub.sql
docker exec v81pg psql -U postgres -d bt -v ON_ERROR_STOP=1 -q -f /tmp/v81.sql
docker exec v81pg psql -U postgres -d bt -v ON_ERROR_STOP=1 -q -f /tmp/assert.sql

# 4. clean up
docker rm -f v81pg
```

Expected tail: `=== ALL V81 TRIGGER ASSERTIONS PASSED ===`. Any `FAIL:` line
aborts the run because every assertion raises rather than returning a value.

### What the assertions cover

| # | Assertion |
|---|---|
| 1 | The legacy `duration_minutes` backfill leaves no NULL/0 rows the trigger would mis-count |
| 2 | `CHECK` constraints reject an out-of-range buffer |
| 3 | **The regression:** 19:00–22:00 with a 45-minute cleanup blocks a 22:00 start |
| 4 | A start *after* the buffer clears is still accepted — the buffer is not a lockout |
| 5 | A setup buffer on the *later* booking also creates the conflict (both sides widen) |
| 6 | Zero-buffer venues keep the exact pre-V81 back-to-back behaviour |
| 7 | Room capacity ceilings still hold once buffers are applied |
| 8 | A status-only transition on an at-capacity row is not re-rejected by its own occupancy |
| 9 | Cancelling releases the buffered window |

`V81_00_stub_schema.sql` deliberately installs a **no-op** `booking_occupancy_backstop()`
first, so the migration's `CREATE OR REPLACE` is exercised as a real replacement
rather than a first-time create.
