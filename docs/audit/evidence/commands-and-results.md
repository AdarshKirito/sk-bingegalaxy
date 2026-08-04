# Commands and Results — Raw Evidence Log

> AUD-2026-07-25-01 · commit `6440f58` · condensed raw outputs backing [26-COMMANDS-AND-EVIDENCE-LEDGER.md](../26-COMMANDS-AND-EVIDENCE-LEDGER.md). Secret **values** never recorded — names/prefixes only.

## Baseline

```text
> git rev-parse HEAD
6440f5825153f9024fc0f5c6f7ee83ee3881aa4d

> git branch -vv
* main 6440f58 [origin/main] merge: reconcile divergent origin/main

> git remote -v
origin https://github.com/AdarshKirito/sk-bingegalaxy.git (fetch/push)

> git status --porcelain
(empty — clean tree)

> git ls-files | measure
1423

> java -version / mvn -v / node -v / npm -v
not recognized (all four absent from PATH)

> docker version
Client 29.6.1 OK; Server: error during connect — daemon not running

> git diff --stat e3edbc1..HEAD  (tail)
566 files changed, 49367 insertions(+), 6439 deletions(-)
```

## Census (selected)

```text
Flyway heads (filename max per service):
auth V20 | availability V2 | booking V80 | payment V16
Controllers: 47 | Mapping annotations: 477 | Entities+Documents: 93 | Repositories: 93
@Scheduled: 33 | @KafkaListener: 16 | Topics in KafkaTopics.java: 20
Compose services: 23 | Dockerfiles: 11 | k8s manifests: 23
```

## Secrets hygiene verification

```text
> git ls-files --error-unmatch .env
error: pathspec '.env' did not match any file(s) known to git   ← NOT tracked

> git log --all --oneline -- .env
(empty)                                                          ← NEVER committed

> git ls-files "*.zip"
frontend/playwright-report/data/6170d534….zip
frontend/test-results/…/trace.zip
k6.zip

> git ls-files | where {$_ -match "/target/"} | measure
0

> git ls-files k6_bin
(empty)                                                          ← k6_bin NOT tracked

> admin_token.txt first 15 chars
eyJhbGciOiJIUzI...                                               ← real JWT format, tracked at HEAD

> stress-tokens.txt line count
4

> git log --oneline --follow -- admin_token.txt | tail -1
3d65090 chore: push all latest changes - loyalty v2, booking hardening, …

> Select-String .gitignore '\.env|token'
25: admin_token.txt
26: *_token.txt
27: stress-tokens.txt
50: .env
51: .env.*
52: !.env.example
```

## Largest tracked files

```text
28.67 MB  k6.zip
 7.20 MB  frontend/test-results/edge-cases-…/trace.zip
 2.58 MB  build_log.txt
 0.68 MB  frontend/test-results/home-smoke.png
 ~0.6 MB  frontend/playwright-report/* (each of several)
```

## Targeted source verifications

```text
AuthService.java L464-480      → getOrDefault("SUPER_ADMIN_REQUIRE_MFA", "true")
docker-compose.yml L456        → SUPER_ADMIN_REQUIRE_MFA: "false"  (dev)
auth application.yml L11-12    → spring.profiles.group.kubernetes: production
V75__room_occupancy_db_backstop.sql → EXISTS (trigger)
PaymentService.java L107-136   → @PostConstruct + 4× IllegalStateException fail-fast
SecretCipher.java L55-57       → CRYPTO_SECRET_KEY fallback ← derived from JWT_SECRET
BookingRepository.java:433     → SELECT pg_advisory_xact_lock(…)
BookingService.java:261        → invokes the lock before occupancy re-check
LoyaltyV2SuperAdminController.java:47-49 → @RequestMapping("/api/v2/loyalty/super-admin") + @PreAuthorize("hasRole('SUPER_ADMIN')")
UserAnonymizationService.java  → requestDeletion L56 · anonymizeUser L84 · cron "0 30 2 * * *" L101 · publish USER_ANONYMIZED L159
USER_ANONYMIZED consumers      → booking:31 · payment:26 · notification:39 (UserAnonymizedEventListener each)
```

## Evidence generation

```text
endpoint-inventory-current.tsv      → 430 lines (429 rows + header)
frontend-routes-current.tsv         → 72 lines (71 + header)
frontend-api-pairs-current.tsv      → 373 lines (372 + header)
final-file-coverage.tsv             → A=1040 B=183 C=163 X=37
backend-dependency-inventory.tsv    → 141 lines (140 + header)
frontend-dependency-inventory.tsv   → 31 lines (30 + header)
container-image-inventory.tsv       → 30 lines — all tag-only, zero digest-pinned
Archive → docs/_previous/2026-07-25T00-00-00Z/  (8 files preserved)
```

## Close-out

Final `git status --porcelain` and `git rev-parse HEAD` results recorded in [FINAL-AUDIT-VALIDATION.md](../FINAL-AUDIT-VALIDATION.md) and [audit-file-change-manifest.tsv](audit-file-change-manifest.tsv).
