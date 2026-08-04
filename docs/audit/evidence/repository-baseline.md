# Repository Baseline — Audit Run 2026-07-25

## Audit run identity
- **Audit run ID:** AUD-2026-07-25-01
- **Date started:** 2026-07-25
- **Auditor:** GitHub Copilot (automated forensic audit, lead auditor + Explore subagents)

## Repository identity
- **Workspace folder opened in editor:** `d:\sk-binge-galaxy` (NOT the Git root)
- **True Git repository root:** `D:\sk-binge-galaxy\sk-binge-galaxy` (nested one level below the workspace folder)
- **Branch:** `main`
- **Commit SHA:** `6440f5825153f9024fc0f5c6f7ee83ee3881aa4d`
- **Commit date:** 2026-07-24 05:22:33 -0500
- **Commit subject:** `merge: reconcile divergent origin/main (July-2 checkpoint) into current July-24 line`
- **Detached HEAD:** No (`HEAD -> main`, in sync with `origin/main`, `origin/HEAD`)
- **Remote:** `origin` → `https://github.com/AdarshKirito/sk-bingegalaxy.git` (fetch/push)
- **Working tree at audit start:** CLEAN — `git status --porcelain` returned 0 lines
  (evidence: `git-status-baseline.txt`)
- **Tracked files:** 1,423
- **Repo size (excluding .git, node_modules, target):** ~426.7 MB
- **Recent history:**
  - `6440f58` merge: reconcile divergent origin/main (July-2 checkpoint) into current July-24 line
  - `a27c3fa` fix(loyalty): debounce redemption slider
  - `785eeab` chore: land accumulated platform overhaul (binge lifecycle, payments, MFA, approval, docs)
  - `a5685d6` feat(loyalty): flexible redemption slider, country-driven point value, per-binge super-admin lock
  - `d2a3e7a` feat: security hardening, temp-password onboarding, legal terms, FX & authority locks

## Workspace-root anomalies (outside the Git repo)
| Path | Observation | Classification |
|---|---|---|
| `D:\sk-binge-galaxy\.git` | **Empty directory** (0 items). Not a valid Git repo; causes `git` commands run from the workspace root to fail with "not a git repository". | Hygiene defect (workspace-level, not tracked) |
| `D:\sk-binge-galaxy\sk-binge-galaxy;C` | Empty directory with a shell-artifact name (`;C`), almost certainly created by a malformed command. | Hygiene defect (workspace-level, not tracked) |
| `D:\sk-binge-galaxy\.agents`, `.claude` | Agent tooling folders at workspace root. | Tooling, not project source |

## Environment and tool availability
| Tool | Status | Version |
|---|---|---|
| OS | Available | Microsoft Windows NT 10.0.26200.0 (Windows 11) |
| PowerShell | Available | 5.1 (Windows PowerShell) |
| Git | Available | 2.54.0.windows.1 |
| Java (JDK) | **NOT ON PATH** | — |
| Maven (`mvn`) | **NOT ON PATH** | — |
| Maven wrapper (`mvnw`) | **NOT TRACKED in repo** (`.mvn` dir exists but no `mvnw` script tracked at root) | — |
| Node.js | **NOT ON PATH** | — |
| npm | **NOT ON PATH** | — |
| Docker CLI | Available | 29.6.1, build 8900f1d |
| Docker Compose | Available (CLI) | v5.3.0 |
| Docker daemon | **NOT RUNNING** (`docker ps` fails: cannot connect to `dockerDesktopLinuxEngine`) | — |
| PostgreSQL client tools | Not verified on PATH | — |
| MongoDB client tools | Not verified on PATH | — |
| Redis client tools | Not verified on PATH | — |
| Kafka client tools | Not verified on PATH | — |

## Verification boundary for this audit run (CRITICAL)
Because Java, Maven, Node, npm are not on PATH and the Docker daemon is not running:

- **NO builds were executed** in this audit run.
- **NO tests were executed** in this audit run.
- **NO runtime checks were executed** in this audit run.
- **NO provider (payment) sandbox calls were executed** in this audit run.

**Every conclusion in this audit run is therefore STATIC-ANALYSIS ONLY** (source reading,
grep, inventory generation) unless explicitly labeled as *Historical evidence* citing
artifacts already committed to the repository (e.g., `production-proof/`, stress-test
logs). Historical evidence is never presented as current-runtime verification.

## Snapshot integrity method
- The working tree was clean at audit start, so **Git blob SHA-1 hashes are used as the
  content hash for every tracked file** (`evidence/source-snapshot.tsv`, 1,423 rows,
  format: `<blob-sha>\t<path>`).
- Integrity re-checks are performed by re-running `git status --porcelain` and
  `git rev-parse HEAD` before major phases and at completion. Any dirty file or new
  commit invalidates affected conclusions.

## File census by extension (tracked files)
| Ext | Count | | Ext | Count |
|---|---|---|---|---|
| .java | 712 | | .ps1 | 21 |
| .jsx | 153 | | .xml | 18 |
| .sql | 119 | | .html | 14 |
| .md | 109 | | (no ext) | 12 |
| .js | 61 | | .dockerignore | 10 |
| .yml | 47 | | .tsv | 8 |
| .css | 42 | | .png / .sh | 7 each |
| .json | 34 | | .ts | 5 |
| .txt | 21 | | .log | 4 |
| .zip | 3 | | .crt | 2 |

Other: `.example` (2), `.svg` (2), `.out`, `.webmanifest`, `.ttf`, `.config`, `.gitignore`, `.node`, `.cjs` (1 each).

## Top-level layout (tracked)
| Dir/file group | Files |
|---|---|
| `backend/` (9 Maven modules) | 901 |
| `frontend/` | 297 |
| `docs/` | 96 |
| `k8s/` | 23 |
| `production-proof/` | 20 |
| `scripts/` | 7 |
| `load-tests/` | 6 |
| `.ca-patch/` | 3 |
| Root clutter (test scripts, stress logs, k6 JSON, tokens, zips) | ~60 |

Backend modules: `api-gateway`, `auth-service`, `availability-service`, `booking-service`,
`common-lib`, `config-server`, `discovery-server`, `notification-service`, `payment-service`.
