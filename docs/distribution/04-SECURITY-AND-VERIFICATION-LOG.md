# Security Findings & Verification Log — Phase 1

> Covers the implementation phase that followed the Phase −1 research gate.
> Everything here was executed, not inferred; commands and outcomes are recorded
> so a reviewer can reproduce them.

---

## 1. Security findings

### SEC-1 — Internal service endpoints were reachable from the internet · **FIXED**

**Severity: P0.** Predates this phase; found while preparing to add reservation
ingestion to that surface.

Every gateway route is a broad prefix — `Path=/api/v1/bookings/**` **also matches
`/api/v1/bookings/internal/**`**. The gateway stripped spoofable `X-User-*`
headers but **not** `X-Internal-Secret`. So service-to-service endpoints were
routable from the public internet, with only the shared secret as the barrier —
a credential designed for callers already inside the trusted network, not an
internet-facing bearer token.

Blast radius before the fix was **information disclosure**: internal endpoints
deliberately return data the public API strips, notably a binge's `adminId`. It
became urgent because reservation *ingestion* was about to join that surface,
which would have turned it into "create arbitrary reservations from the internet".

**Fix** (`JwtAuthenticationFilter`):
1. `isInternalServicePath()` — segment-shape check on the **normalized** path, so
   `/api/v1/bookings/binges/../internal/...` traversal is caught. Returns **404,
   not 403**: probing must not confirm the surface exists.
2. `X-Internal-Secret` added to the stripped-header list (defence in depth).

Matched on segment, not substring, so a venue slug containing the word "internal"
still routes. **Covered by `InternalPathBlockingTest` — 11 tests** including
traversal and the slug case.

### SEC-2 — Internal secret exposure audit · **NOT LEAKED, no rotation required**

SEC-1 stops future reachability; it cannot un-leak a credential. Audited every
vector:

| Vector | Result |
|---|---|
| `.env` tracked in git | ✅ gitignored (`.gitignore:50-52`); `git log --all -- .env` **empty — never committed** |
| Secret value in any tracked file | ✅ `git grep <value>` returns nothing |
| Secret value in working-tree logs (`*.log`, `*.txt`, `*.out`) | ✅ nothing — and this repo carries many stray build/k6 logs |
| Actuator `env` / `configprops` | ✅ **not exposed** — every service publishes only `health,info,prometheus,metrics[,circuitbreakers]` |
| Config-server | ✅ basic-auth protected (`CONFIG_SERVER_PASSWORD`) |
| Code logging the secret / wholesale header dumping | ✅ none found |

**Conclusion: the internal API secret has not been exposed. Rotation is not
required.** This is a point-in-time result — re-run the checks before any public
exposure of the repository or infrastructure.

### SEC-4 — Register item P0-2 re-assessed · **downgraded P0 → P1, root cause fixed**

Earlier audits describe `admin_token.txt` / `stress-tokens.txt` as leaked
credentials needing immediate rotation. Measured against the artefacts:

| Question | Finding |
|---|---|
| Contents | Three JWTs — two `SUPER_ADMIN`, one `CUSTOMER` |
| Lifetime | `exp − iat` = **900 s (15 minutes)** |
| Still valid? | **No** — expired ~**88** and ~**98 days** ago. Replay is impossible. |
| Signing key recoverable from them? | `JWT_SECRET` is **48 decoded bytes (384-bit)** and not the shipped placeholder — offline brute-force is infeasible |

**No rotation required; there is no live exposure.** What remains is ordinary
hygiene: a repository must not carry credentials, because the next set might not
be expired and the next key might not be strong.

**Root cause — and why this survived multiple audits.** `stress-test-26apr.ps1:35`
mints tokens by logging in and writes them to a repo-relative path; four other
stress scripts read them back. `.gitignore` **already listed both files** (lines
25–27) and had no effect, because **gitignore never applies to a file git is
already tracking**. The ignore rule looked like the fix.

**Fixed:** `git rm --cached` on both — untracked, but left on disk, so all five
stress scripts keep working unchanged and the existing ignore rules now bite.
*Commit that staged deletion to remove them from `HEAD`.*

**History purge deliberately NOT executed.** It rewrites every commit SHA and
needs a force-push, breaking every clone — a decision requiring human
coordination, and not urgent given the above. Full runbook with backup steps,
verification and team instructions:
[../runbooks/purge-tokens-from-git-history.md](../runbooks/purge-tokens-from-git-history.md).

### SEC-3 — Provider PII validated at the edge

`ChannelReservationRequest` carries guest name/email/phone supplied by a third
party. All are length-capped and format-checked before reaching persistence, and
the DTO rejects unknown fields (`@JsonIgnoreProperties(ignoreUnknown = false)`) so
a provider payload cannot be posted raw and have parts silently ignored.

**No user account is minted for a channel guest.** They never authenticate;
creating accounts would silently build a PII estate nobody asked for. They are
attributed to `customerId = 0`, the existing "no known customer" convention.

---

## 2. Correctness findings

### COR-1 — Canonicalisation was in the wrong layer · **FIXED**

The redelivery guard is the unique index on `(external_source, external_ref)`,
which compares **bytes**. If `ACME-Channel` and `acme-channel` can both be stored
they are two different channels — a provider varying its own casing between the
original delivery and a retry defeats the guard, and the venue is **double-booked
for a slot it already sold**, silently.

Normalisation initially lived only in the service. Bean validation runs against
the field *after* deserialization, so the lowercase-only `@Pattern` would have
**400'd `"ACME-Channel"` at the endpoint** while the service happily normalised the
same input — the edge and the core disagreeing about what is valid, and a
legitimate channel unable to deliver at all.

**Fix:** canonicalise in the DTO setters, with `canonicalSource()` as the single
definition reused by the service for builder-constructed requests (which bypass
setters). **`external_ref` is trimmed but deliberately NOT case-folded** — provider
references are opaque and case-sensitive, and folding them would merge two
genuinely distinct reservations, the opposite failure and a worse one.

**V86** adds a DB CHECK (`external_source = lower(…) AND = btrim(…)`) so the
property belongs to the *data*, not to one code path: no future importer, manual
fix-up or second ingestion route can reintroduce the ambiguity. It rejects rather
than silently matching, because a non-canonical row arriving means some caller
skipped normalisation and that should fail loudly.

### COR-5 — Surefire was truncating JaCoCo coverage data · **FIXED**

Surefire SIGKILLs the forked JVM **30 seconds after `System.exit(0)`** (its default).
That deadline races the JaCoCo agent, which writes `jacoco.exec` from a shutdown
hook and takes longer the more classes it instrumented. booking-service is the
largest module and reliably tripped it on a loaded machine:

```
[ERROR] Surefire is going to kill self fork JVM.
        The exit has elapsed 30 seconds after System.exit(0).
```

This was **not cosmetic**, and the damage compounds:

1. `jacoco:check` runs at `verify` and **fails the build** below its thresholds.
   A truncated `jacoco.exec` yields artificially low coverage, so the gate fails
   for reasons unrelated to the code — a flaky, load-dependent "coverage
   regression" that is maddening to diagnose.
2. **JaCoCo appends by default.** Once truncated, the file stays corrupt, so
   *every subsequent build* inherits the damage until someone runs `mvn clean`.

Confirmed empirically rather than assumed — with the poisoned file in place,
report generation failed outright:

```
Error while creating report: Unknown block type 66
```

That is JaCoCo failing to parse a corrupt execution file. First hypothesis
(a leaked non-daemon thread from a Spring test context) was **tested and
disproved**: running `BingeRepositoryGeoTest` alone produced no warning.

**Fix — two parts, because one alone leaves a trap:**

1. `<forkedProcessExitTimeoutInSeconds>300</forkedProcessExitTimeoutInSeconds>` on
   Surefire, so the dump completes instead of being killed. A ceiling, not a sleep —
   a fast exit costs nothing.
2. `<append>false</append>` on `jacoco:prepare-agent`, so each run starts a **fresh**
   `jacoco.exec`.

Part 2 matters as much as part 1. Appending means *any* interrupted run — CI
cancelled, container killed, OOM — leaves a partial file that every later build
appends to and chokes on, with errors that point nowhere useful. Both signatures
were observed here:

```
Error while creating report: Unknown block type 66
Error while creating report: malformed input around byte 2
```

The second was self-inflicted during this session (I `docker kill`ed several
in-flight reactor runs), which is exactly the point: the recovery was to delete
every `target/jacoco.exec` by hand, and nothing tells you that. With `append=false`
a corrupt exec is **self-healing** rather than sticky, and part 1 stops the
truncation occurring at all. Nothing is lost by starting fresh — `verify` re-runs
the tests, so the gate should measure *this* run, not an accumulation across runs.

### COR-6 — Coverage gate never ran, and failed when it did · **FIXED via ratchet + CI wiring**

**Resolution (2026-08-01):** implemented the **ratchet**, and wired `verify` into CI.

Measuring the whole reactor showed this was **not a booking-service problem** — five of
seven modules were below the hard-coded 0.60/0.50:

| Module | Line | Branch | Ratchet floor |
|---|---|---|---|
| common-lib | meets target | | none — stays at 0.60/0.50 |
| api-gateway | meets target | | none — stays at 0.60/0.50 |
| availability-service | 0.55 | 0.43 | 0.54 / 0.42 · **closest to target** |
| notification-service | 0.44 | 0.29 | 0.43 / 0.28 |
| payment-service | 0.30 | 0.20 | 0.29 / 0.19 · **prioritise — moves money, PR-PAY-01 open** |
| auth-service | 0.28 | 0.21 | 0.27 / 0.20 |
| booking-service | 0.28 | 0.22 | 0.27 / 0.21 · 392 files, 5,189-line service class |

**Why the ratchet rather than deleting the gate or lowering it globally.** A gate set to
an aspiration nobody measures is not enforcement, it is decoration — which is exactly how
this sat unnoticed. Set to each module's *measured* baseline, it starts doing the job that
actually matters on day one: **failing the build when coverage goes backwards.** The
0.60/0.50 target survives as the parent default and as the number each module is raised
toward.

Implementation: thresholds became the properties `jacoco.min.line` / `jacoco.min.branch`
in `backend/pom.xml` (defaulted to the target), overridden per module in each module's own
POM with a comment stating the contract — **raise, never lower; if a change drops coverage,
add tests rather than editing the number.** Floors sit one point below the measured value
so JaCoCo's 2-dp display rounding cannot cause a false failure.

`Jenkinsfile` now runs **`mvn verify -Dtestcontainers.enabled=true`** instead of
`mvn test`, so the gate — and the Testcontainers DB suites — execute on every build.

**Suggested order of attack:** availability-service (smallest gap), then payment-service
(highest risk), then auth-service and booking-service.

---

<details>
<summary>Original finding (kept for the record)</summary>

### COR-6 — `mvn verify` fails the coverage gate · **REPORTED, not silently changed**

With valid coverage data, `jacoco:check` measures booking-service at
**28% line / 22% branch** against configured minimums of **60% / 50%**. So
`mvn verify` fails on that module.

**This is pre-existing and latent, not a regression.** Nothing runs it: the
Jenkinsfile's build stage is `mvn clean package -DskipTests` and its test stage is
`mvn test` — neither reaches the `verify` phase where `jacoco:check` is bound. The
POM's own comment ("thresholds are intentionally conservative for the first
enforcement pass") reads as an aspiration that was never measured against reality.
This phase added ~90 tests, which moves coverage up, not down.

**Deliberately not "fixed" by lowering the threshold.** That would make the gate
pass while making the problem invisible, and choosing an engineering standard is
an owner's decision. Two honest options:

| Option | Trade-off |
|---|---|
| **Ratchet** — set minimums to the measured baseline (e.g. 0.28/0.22) and raise them as coverage improves | Gate becomes meaningful immediately and prevents *regression*; requires accepting today's number in writing |
| **Keep 60/50 as the target** and leave `verify` failing until coverage reaches it | Honest about the goal; `mvn verify` stays red, so nobody can use it as a check |

Either way, **wire `verify` into CI once chosen** — a quality gate nothing executes
is not a gate. Note booking-service is 392 files including a 5,189-line service
class the audit already flags as the main regression source; reaching 60% there is
a substantial piece of work, not a quick fix.

</details>

### COR-2 — `.mvn/jvm.config` was breaking real CI · **FIXED**

`-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` is correct on a Windows host behind
a TLS-intercepting antivirus, and **fatal everywhere else**. The `Jenkinsfile` runs
`sh 'mvn ...'` on a **Linux agent**, where that truststore type does not exist, so
the JVM cannot build a default SSL context and every artifact download fails. It
stayed hidden because a warm `~/.m2` masks it — add one dependency and CI breaks
on an error that points at TLS rather than at this file.

**Fix:** `jvm.config` is now portable (memory limits only); `.mvn/README.md`
documents the failure mode and where host-specific settings belong.

---

## 2b. Wiring findings — `distribution-service` existed only as a Maven module

An audit of what had actually been *wired* (as opposed to written) found the new
service could not have started in any environment. Each item below was a real gap,
not a stylistic one.

### WIR-1 — Fixing COR-2 broke **host** builds · **FIXED**

Making `.mvn/jvm.config` portable was correct for CI and wrong for the machine this
was developed on, where a TLS-intercepting antivirus means Maven cannot reach Maven
Central without `WINDOWS-ROOT`. Removing the flag traded a broken Linux CI for a
broken Windows host — one regression for another.

**Fix:** `scripts/mvn-host.ps1` sets `MAVEN_OPTS` for the duration of a single
invocation and restores it in a `finally`. Host-specific settings now live with the
host, not in a file every environment shares. The README gained a *Building and
testing the backend* section so the next person does not rediscover this.

### WIR-2 — No config-server profile · **FIXED**

Every other service resolves its real configuration from
`config-server/src/main/resources/configurations/<name>.yml`; `distribution-service`
had only its in-jar fallback. It would have booted with defaults nobody reviewed.

**Fix:** `distribution-service.yml` added, following the established shape.
Deliberate departures from copy-paste: a **Hikari pool of 8, not 20** — bookkeeping
must not be able to starve the connection budget that direct booking depends on —
and `management.endpoints.web.exposure` excludes `env`/`configprops`, which would
otherwise dump `INTERNAL_API_SECRET` and the datasource password.

### WIR-3 — Nothing created `distribution_db` · **FIXED**

There was no database, no role, no Dockerfile and no compose service. The subtle
part is *where* the database gets created: `/docker-entrypoint-initdb.d` runs **once,
when the Postgres volume is first created**. Adding four lines to
`infra/init-databases.sql` would work on a fresh checkout and silently fail for
everyone who already has a `postgres-data` volume — the service would crash-loop on
*"database distribution_db does not exist"* and the cause would not be in its logs.

**Fix:** `infra/init-02-distribution.sql` is idempotent (the `\gexec` idiom for
`CREATE DATABASE`, `IF NOT EXISTS` for the role) and runs on **every** `up` via a
`postgres-init` one-shot, mirroring the existing `kafka-init` pattern. Fresh volumes
and old ones converge. `init-databases.sql` now says so, so the next database added
follows the working pattern rather than the historical one.

### WIR-4 — Nine tables, one Java class · **FIXED**

`ddl-auto: validate` was already configured, which sounds like protection but was
protecting nothing: with no entities there was nothing to validate, and schema drift
would have surfaced whenever someone eventually wrote the entity.

**Fix:** entities for all nine tables, repositories, and `EntitySchemaParityIT` —
which bootstraps Hibernate directly against the Flyway-migrated schema and lets
`validate` fail the build. Two design points worth keeping:

* **The entity list is discovered by classpath scan, not hard-coded.** A hand-written
  list passes happily on the day someone adds a tenth entity and forgets it — exactly
  the failure the test exists to catch. The scan result is asserted non-trivial so a
  broken scanner cannot make the test vacuously green.
* **A second test pins the table names.** Validation only walks entities *towards*
  the database and can never notice a table that has no entity at all.

### WIR-5 — CI would never have validated the new migration · **FIXED**

`distribution-service` was absent from every loop in the `Jenkinsfile`.

**Fix:** added to the **Flyway checksum validation** loop, and deliberately *not* to
the image build / push / scan / deploy loops — it exposes no API yet and has no k8s
manifests, so deploying it would ship an empty service and adding it to the rollout
loop would fail `Verify Deployment` on a Deployment that does not exist. The
`Jenkinsfile` records that reasoning inline so the omission reads as a decision.

### WIR-6 — Defects found by auditing my own slice-1 output · **FIXED**

A pass over what slice 1 actually shipped, rather than what it was supposed to ship.

**Spring Security on the classpath with no `SecurityConfig` (P0).** Every other service
in the repo has one; this one did not. Boot's default applies HTTP Basic over
everything with a generated password — including `/actuator/health`, which the
Dockerfile `HEALTHCHECK` calls, so the container would have sat permanently `unhealthy`
and looked like a broken service rather than a missing bean. Added, with
`anyRequest().denyAll()` rather than `authenticated()`: the first controller added to
this service should be a visible 403 until someone writes its matcher, not accidentally
reachable.

**Four `NOT NULL DEFAULT '{}'` array columns were nullable in the entities (P0).**
`providers.supported_countries`, `destinations.supported_countries`,
`listing_mappings.external_option_ids`, `listing_mappings.blocking_reasons`. Hibernate
includes a mapped column in the INSERT even when the field is null, so the database
default never applies — the first programmatic save would have failed with *"null value
in column violates not-null constraint"*.

Worth dwelling on: **`EntitySchemaParityIT` could not catch this, and says so in its own
javadoc** — `validate` checks types, not nullability, and only walks entities *towards*
the database. A test that documents its blind spot is still blind. The entities now
supply `new String[0]` themselves.

**`@EnableScheduling` with no ShedLock (P1).** Harmless with no `@Scheduled` methods, and
a trap the moment one is added — every replica would run it, so a staleness sweep would
send duplicate warnings. Removed, with a comment saying to enable it in the same change
that adds ShedLock and the first job.

**Currency normalisation (P2).** Both currency columns carry a `^[A-Z]{3}$` CHECK with
nothing normalising the value. Setters now uppercase, plus a `@PrePersist`/`@PreUpdate`
callback — because `@Builder` assigns fields directly and never calls a setter, so the
lifecycle hook is the only point every construction path converges on.

### WIR-7 — A new config-server profile is inert until config-server is rebuilt · **FIXED, and a trap worth naming**

Found by actually booting the service rather than reasoning about it. `distribution-service`
came up **healthy**, logged *"Located environment: name=distribution-service"* — and was
still running on in-jar fallback defaults. The tell was Zipkin trying `localhost:9411`
instead of the `zipkin:9411` my profile specifies.

Cause: config-server serves profiles from files **baked into its jar at build time**.
`distribution-service.yml` existed in the source tree and in **zero** copies inside the
running container, which was 47 hours old. Adding a profile is therefore a two-service
change, and nothing in the repo said so.

It fails *silently* by design: `spring.config.import: optional:configserver:` means an
unreachable — or, as here, an incomplete — config server is not an error. The service
starts, reports healthy, and quietly runs on whatever the in-jar `application.yml` says.

**Fix:** rebuilt config-server; the profile is now served (verified by querying
`/distribution-service/default` from inside the network and seeing
`distribution.credentials.expiry-warning-days`, `distribution.inbox.max-payload-bytes`
and the correct Zipkin endpoint). Recorded in the README build section so the next
profile added does not repeat it.

**What saved this from being a security finding.** With the profile inert, the actuator
exposure list came from the in-jar fallback — which deliberately carries the same
restriction. Verified on the running container: `/actuator/env` returns **403** (it would
otherwise dump `INTERNAL_API_SECRET` and the datasource password) while
`/actuator/health` returns `UP` for the container healthcheck. Writing that limit in both
places looked redundant when it was written; it was the only thing standing between a
silent config miss and a leaked secret.

---

## 3. Verification performed

| What | How | Result |
|---|---|---|
| Unit + component tests, all modules | `mvn -B test` (10-module reactor) | ✅ green |
| **Full reactor `verify`, coverage gates engaged** | `mvn -B verify` (11 modules, 2026-08-02) | ✅ **BUILD SUCCESS**, all gates met — but see the caveat below |
| Migration chain V1→head | Applied in order to a clean `postgres:16-alpine` | ✅ **86/86 apply** |
| Occupancy backstop logic | `OccupancyBackstopIT` — real Flyway chain + real trigger, Testcontainers | ✅ **14/14** |
| Occupancy backstop **under contention** | `OccupancyContentionIT` — 12 simultaneous writers behind a release barrier | ✅ **4/4** |
| V81 buffers | `db-smoke/V81_01_assertions.sql` | ✅ 9/9 |
| V82 duration canonicalisation + full chain | `db-smoke/V82_01_full_chain_assertions.sql` | ✅ 8/8 |
| V83/V84/V85 windows, durations, origin | `db-smoke/V85_01_window_and_origin_assertions.sql` | ✅ 14/14 |
| V86 canonical source | `db-smoke/V86_01_canonical_source_assertions.sql` | ✅ 5/5 |
| Gateway internal-path blocking | `InternalPathBlockingTest` | ✅ 11/11 |
| Distribution schema invariants | `DistributionSchemaIT` — real Flyway + real PostgreSQL | ✅ 14/14 |
| Distribution **entity/schema parity** | `EntitySchemaParityIT` — Hibernate `validate` against the migrated schema | ✅ 2/2 |
| `docker-compose.yml` after the additions | `docker compose config` | ✅ parses |
| CI **Migration Safety Check** stage | `scripts/check-migration-safety.sh backend` | ✅ exit 0 — **was red on 14 migrations**, see [INC-2026-08-02](../audit/INC-2026-08-02-binge-auto-deactivation.md) §5 |
| Binge grace-period sweep | `BingeGracePeriodTest` — suite did not previously exist | ✅ 5/5 |

### What the new module's green coverage gate does **not** prove

`distribution-service` inherits the 0.60/0.50 target with no override and passes. That
is close to meaningless today and should be read as such: the parent excludes
`**/entity/**`, the repositories are interfaces, and JaCoCo reports *"Analyzed bundle
'distribution-service' with **1 classes**"*. Green currently means *the one default
method with a body is covered*, not *this module is well tested*.

It is still worth having — a ratchet holding at the target from day one is far cheaper
than the position the five older modules are in — but the gate only starts measuring
anything from the first service class. Recorded here because an earlier version of this
document claimed the module "starts at the platform target", which was true and
uninformative at the same time.

### What the contention tests actually prove

`OccupancyBackstopIT` proves the trigger's *logic* — one writer, right answers.
That is necessary but not sufficient: the backstop exists precisely for the case
where the application's advisory lock was bypassed, which by definition means two
writers are racing. A trigger that computes correctly against a snapshot taken
before a competing insert committed would still admit an oversell, and no
single-threaded test can see it.

`OccupancyContentionIT` fires **12 simultaneous transactions** at one slot from a
`CountDownLatch` release barrier (without the barrier, JDBC connection setup
staggers them and the race never happens — the test would pass vacuously) and
asserts the exact survivor count:

| Scenario | Expected | Result |
|---|---|---|
| Capacity-1 room, 12 racers | exactly **1** | ✅ |
| Capacity-3 room, 12 racers | exactly **3** — not 1, not 12; it must *count* correctly under contention, not merely serialise | ✅ |
| 60-min cleanup buffer, starts 30 min apart | exactly **1** — buffered windows evaluated inside the lock, not against a pre-race snapshot | ✅ |
| Room-less venue, `max_concurrent_bookings = 2` | exactly **2** — the trigger's second branch uses a different advisory-lock keyspace and needs its own proof | ✅ |

**Register item TEST-01 is closed**: the trigger is proven correct *and* proven
safe under concurrent writers.

### Running the integration tests

```powershell
./scripts/run-integration-tests.ps1
./scripts/run-integration-tests.ps1 -Modules common-lib,distribution-service
```

Needed because on Windows, Docker Desktop serves its API over a named pipe: a
mounted `/var/run/docker.sock` answers **HTTP 400** to `/info` and Testcontainers
reports *"Could not find a valid Docker environment"*. The script uses the standard
CI pattern — a **Docker-in-Docker sidecar** reached over TCP — needing no Docker
Desktop setting changed and installing nothing on the host. The `Jenkinsfile` passes
`-Dtestcontainers.enabled=true` so CI runs the same tests.

The sidecar's `/var/lib/docker` now sits on a named volume. It is destroyed after
every run, so without one the daemon starts empty and re-pulls `postgres:16-alpine`
each time — **measured at 18m39s**, against roughly five seconds of actual schema
testing. The volume survives cleanup deliberately and is not reclaimed by
`docker system prune`; `-ResetImageCache` reclaims it when disk matters more than
time.

Verified rather than assumed: the run that created the volume still downloaded the
image, so the cache had to be proven on a *subsequent* run. It was — no pull at all,
**52s end-to-end**, 290 MB resident in `tc-dind-cache`.

---

## 4. Still open

| Item | Severity | Note |
|---|---|---|
| **PR-PAY-01** | **P0 (launch gate)** | No end-to-end payment/refund proven against a provider sandbox. Distribution ships **agency-model only** because of it. |
| **P0-2** *(re-assessed)* | ~~P0~~ → **P1** | Tokens expired 88–98 days ago; signing key is 384-bit. **No rotation needed.** Untracked from the index; history purge is a pre-publication step with a [runbook](../runbooks/purge-tokens-from-git-history.md). See SEC-4. |
| **COR-6** | P1 | `mvn verify` fails booking-service's coverage gate (28%/22% vs 60%/50%). Pre-existing and latent — CI never runs `verify`. Needs an owner decision on ratchet-vs-target. |
| Uncommitted working tree | P1 | Large uncommitted change set; commit before further work so deployed and source builds cannot diverge. |
| `GLB-01` i18n | P2 | Promoted from P3 by the global-market decision. |
