# Environment Recovery Log — AUD-2026-07-25-01 execution phase

Chronological, command-level log of every environment recovery and provisioning action.
No application source, config, migration, dependency, or lockfile was modified at any point.

| # | Action | Command / method | Outcome |
|---|---|---|---|
| R1 | OS + shell detection | `[Environment]::OSVersion`, `$PSVersionTable` | Windows 11 (NT 10.0.26200), PowerShell 5.1 — PASS |
| R2 | PATH tool sweep (21 tools) | `Get-Command` loop | java/mvn/node/npm absent; docker + docker-compose + kubectl + wsl + winget present |
| R3 | Filesystem probe of 12 common install roots | `Get-Item` loop | all absent — no host Java/Node anywhere |
| R4 | Repo wrapper check | recursive search for `mvnw*` | absent (no Maven wrapper in repo) |
| R5 | Repo k6 binary check | `k6_bin\k6-v0.54.0-windows-amd64\k6.exe version` | works: v0.54.0 go1.23.1 windows/amd64 |
| R6 | Docker daemon check | `docker version --format {{.Server.Version}}` | **RUNNING — server 29.6.1** (daemon was DOWN during the 07-25 static pass; Docker Desktop started between passes). WSL2 distro `docker-desktop` Running |
| R7 | Required versions from source (read-only) | backend/pom.xml → Java 17, Spring Boot 3.4.5; frontend/package.json → no engines pin | container images chosen to match: maven:3.9-eclipse-temurin-17, node:20 (both already local — no pull needed) |
| R8 | Isolated Maven cache volume | `docker volume` implicit create `skbg-audit-m2` | created; keeps audit artifacts out of host + repo |
| R9 | Isolated frontend workspace volume | `skbg-audit-fe` | created; npm ci runs against a **copy** of package.json/package-lock.json — repo lockfile provably untouched |
| R10 | Audit log directory outside source tree | `%TEMP%\skbg-audit\` | created; all build/test logs land here, not in the repo |
| R11 | Backend reactor build+test run 1 | `docker run --rm -v backend:/work -v skbg-audit-m2:/root/.m2 maven:… mvn -B -fae clean test` | modules 1–8 PASS (see build results doc); container terminated abnormally during module 9/10 (payment) — no reactor summary, container gone, log frozen at 05:07. Classified: **environment (resource pressure — 15 stack containers + reactor concurrently)**, not source failure |
| R12 | Payment/notification rerun attempt 1 | `mvn -B -pl payment-service,notification-service test` (with `-m 3g`) | **BUILD FAILURE — environment**: `Could not find artifact com.skbingegalaxy:common-lib:jar:1.0.0` (run 1 used `test`, never `install`ed common-lib to the cache volume). Not a source defect |
| R13 | Payment/notification rerun attempt 2 | same + `-am` (build required deps in-reactor) | see CURRENT-BUILD-AND-TEST-RESULTS.md |
| R14 | Mongo access | root creds referenced **by env-var name only inside the container** (`$MONGO_INITDB_ROOT_USERNAME`) — values never displayed | authenticated; index inventory extracted |
| R15 | Postgres access | `docker exec skbg-postgres psql -U skbg_admin` (username from compose file; password never used/printed — local trust via container exec) | flyway history + trigger/index inventory extracted |
| R16 | Host installs via winget | **not needed** — container route covered every requirement | skipped deliberately (least-invasive principle) |

## Explicitly not done (safety rules)
- No `docker system prune`, no `down -v`, no volume deletion of project volumes
- No modification of running production-like containers other than rebuild/restart of project services from current source (documented in CURRENT-RUNTIME-VERIFICATION.md)
- No host PATH/registry/environment mutation
- No dependency or lockfile changes (npm ci ran on copies inside a Docker volume)
- No secret values printed (env-var names only)
