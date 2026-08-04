# ENVIRONMENT RECOVERY AND EXECUTION — AUD-2026-07-25-01

> This document exists because the first pass of this audit ended "static-only: Java/Maven/Node absent, Docker daemon down."
> Per the execution override, that state was treated as **interim, not final**. This is the record of how every
> execution capability was recovered or definitively dispositioned.

## 1. What changed between the static pass and the execution pass

| Item | Static pass (2026-07-25) | Execution pass (2026-07-26) |
|---|---|---|
| Docker daemon | DOWN (`error during connect`) | **RUNNING** — server 29.6.1, WSL2 backend (`docker-desktop` distro Running) |
| Application stack | not running | **15/15 containers healthy** (restart policies brought the stack up with the daemon) |
| Java/Maven on host | absent | still absent — **recovered via `maven:3.9-eclipse-temurin-17` container** (Java 17 = exact pom requirement) |
| Node/npm on host | absent | still absent — **recovered via `node:20` container** (Node 20.20.2, npm 10.8.2) |
| k6 | not checked as executable | repo-shipped `k6_bin\k6-v0.54.0-windows-amd64\k6.exe` verified working |
| Provider credentials | unknown | verified EMPTY in live payment container (names/prefix classification only) |

## 2. Recovery strategy chosen and why

Order of preference applied (per mandate): existing installs → repo wrappers → user-local portable →
**containerized equivalents** → isolated temp environments.

- Existing installs: none found (21-command PATH sweep + 12-directory probe — [evidence/tool-detection-results.md](evidence/tool-detection-results.md)).
- Repo wrappers: no `mvnw`; repo *does* ship a working k6 binary.
- Containerized toolchain won because the **exact-version images were already local** (zero download,
  zero host mutation) and Docker was healthy. Host installs via winget remained available as fallback but
  were never needed.

Isolation guarantees applied to every execution:
1. Maven cache in dedicated volume `skbg-audit-m2` — never the host, never the repo.
2. Frontend work in dedicated volume `skbg-audit-fe`; the repo `frontend/` directory was mounted **read-only**
   and copied in (`tar` pipe) — `npm ci` can provably never touch the real lockfile.
3. All logs to `%TEMP%\skbg-audit\` — outside the source tree.
4. No project volume deleted, no `down -v`, no prune, no unrelated container touched.

## 3. Failures encountered during recovery and their resolution

| Failure | Root cause classification | Resolution |
|---|---|---|
| First full-reactor run terminated at module 9/10 with no reactor summary | **Environment** — resource pressure (15-container stack + full reactor + vitest concurrently); container OOM/kill, not a source defect (modules 1–8 all green at that point) | Re-ran remaining modules with `-m 3g` memory cap |
| Rerun `-pl payment-service,notification-service` → `Could not find artifact com.skbingegalaxy:common-lib:jar:1.0.0` | **Audit-command error** — run 1 used `test` phase, so common-lib was never `install`ed into the cache volume; `-pl` alone could not resolve it | Re-ran with `-am` (build required deps in-reactor); passed |
| `mongosh` unauthenticated → `requires authentication` | Expected security posture (finding: Mongo is NOT open) | Re-ran inside container with `$MONGO_INITDB_ROOT_USERNAME`/`$MONGO_INITDB_ROOT_PASSWORD` env-var references — values never printed |
| `psql -U postgres` → role does not exist | compose uses `POSTGRES_USER: skbg_admin`, DBs are `auth_db`/`availability_db`/`booking_db`/`payment_db` (underscored) | corrected user + db names |
| VS Code sync terminal stopped echoing output mid-session | tooling quirk (terminal session corruption) | switched to a fresh persistent terminal; no data lost (logs were file-backed) |

## 4. Runtime currency problem found and fixed during this phase

The auto-started stack was **not built from current source**:
- Live `auth_db` Flyway head = **V19**, source head = **V20** (`V20__mfa_hardening.sql`)
- Live `payment_db` Flyway head = **V15**, source head = **V16** (`V16__stripe_connected_accounts.sql`)
- 7 of 9 service images carried build dates (2026-07-18) predating the last backend change (2026-07-24 02:37).

Per mandate §8 ("Do not use historical containers as proof of the current source"), all app images were rebuilt
from the current tree and the stack restarted before any runtime claim was recorded as CURRENT
(see [CURRENT-RUNTIME-VERIFICATION.md](CURRENT-RUNTIME-VERIFICATION.md) and [CURRENT-MIGRATION-VERIFICATION.md](CURRENT-MIGRATION-VERIFICATION.md)).

## 5. Final capability outcome

See [evidence/execution-capability-matrix.tsv](evidence/execution-capability-matrix.tsv) for the complete
capability × tool × status matrix. Summary: backend build/tests PASS (containerized), frontend
install/typecheck/tests/build PASS (containerized), runtime + migrations verified CURRENT after rebuild,
k6 smoke executed, Mongo/Postgres inspected live; the only **BLOCKED-EXTERNAL** item is true provider-sandbox
interaction (Razorpay/Stripe credentials verified EMPTY in the environment — user-owned secret).
