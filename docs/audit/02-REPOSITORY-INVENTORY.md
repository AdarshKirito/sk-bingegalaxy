# 02 — Repository Inventory (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · 1,423 tracked files · ~426.7 MB working tree

## Git identity (VERIFIED)

| Fact | Value |
|---|---|
| True repo root | `d:\sk-binge-galaxy\sk-binge-galaxy` |
| Branch | `main` = `origin/main` (https://github.com/AdarshKirito/sk-bingegalaxy.git) |
| HEAD | `6440f5825153f9024fc0f5c6f7ee83ee3881aa4d` — "merge: reconcile divergent origin/main" (2026-07-24) |
| Working tree at baseline | Clean (`git status --porcelain` empty) — [evidence/git-status-baseline.txt](evidence/git-status-baseline.txt) |
| Snapshot | 1,423 `<blob-sha> <path>` rows — [evidence/source-snapshot.tsv](evidence/source-snapshot.tsv) |
| Delta since last full audit (e3edbc1) | 566 files changed, +49,367 / −6,439 lines |

## Workspace anomalies (VERIFIED)

1. **Nested repo layout** — the VS Code workspace root `d:\sk-binge-galaxy` contains a broken, empty `.git` directory; all git operations must run in the nested `sk-binge-galaxy` folder. Confusing for tooling and CI; recommend flattening. *(Filesystem observation, not a tracked-file issue.)*
2. **Empty anomalous folder** `d:\sk-binge-galaxy\sk-binge-galaxy;C` — appears to be a shell-quoting accident; safe to delete manually (left untouched by this audit).

## Top-level layout

| Path | Contents | Hygiene assessment |
|---|---|---|
| `backend/` | 9 Maven modules + parent [pom.xml](../../backend/pom.xml) | Clean; no `target/` tracked (verified: 0 files) |
| `frontend/` | Vite React app, e2e, Dockerfile, nginx.conf | ⚠️ `playwright-report/` + `test-results/` build artifacts tracked (incl. 7.2 MB trace.zip) |
| `docs/` | Audit sets, changelogs, runbooks, codebase docs | Current set is this folder; staleness mapped in [DOCUMENTATION-MAP.md](DOCUMENTATION-MAP.md) |
| `k8s/` | 23 manifests (namespace, HPA, PDB, NetworkPolicy, External Secrets, Argo, backups) | Good |
| `infra/` | init-databases.sql | ⚠️ hardcoded dev passwords (dev-only, but see register HYG-04) |
| `k6_bin/` | k6 Windows binary directory | **Not tracked** (verified `git ls-files k6_bin` = empty) — local-only |
| `load-tests/`, `production-proof/` | k6 scripts, historical evidence | Historical, labeled |
| `scripts/` | migration-safety, k8s render, restore scripts | Good; wired into Jenkinsfile |
| Root clutter | 40+ files: `k6-*.json`, `stress-*.ps1/txt`, `*_log.txt`, `probe*.ps1`, [build_log.txt](../../build_log.txt) (2.58 MB), [k6.zip](../../k6.zip) (28.67 MB) | ⚠️ tracked noise; see register HYG-02 |

## Secrets & sensitive files (VERIFIED — names only, values never printed)

| File | Tracked at HEAD? | Ever committed? | Finding |
|---|---|---|---|
| `admin_token.txt` (451 B, real JWT `eyJ…` prefix) | **YES** | yes (since `3d65090`) | **P0 — live-format token in git** (register SEC-HYG-01) |
| `stress-tokens.txt` (4 JWT lines) | **YES** | yes | **P0 — same** |
| `.env` (local, 9+ secret names) | **NO** | **never** (`git log --all -- .env` empty) | OK — correctly ignored (`.gitignore:50-52`); values exist only on this machine |
| `infra/init-databases.sql` | YES | yes | Dev-grade passwords hardcoded (`*_svc_dev`) — acceptable for compose dev only if rotated for prod |
| `.gitignore` rules | — | — | Lines 25–27 ignore `admin_token.txt`/`*_token.txt`/`stress-tokens.txt` **but git keeps already-tracked files** — ignore rules were added after the files were committed |

> Correction recorded: a specialist pass reported `.env` as "tracked at HEAD" — **disproved** by `git ls-files --error-unmatch .env` (pathspec unknown) and empty `--all` history. The tokens files, however, are confirmed tracked.

## Large / binary tracked files (top verified)

| Size | Path | Class |
|---:|---|---|
| 28.67 MB | [k6.zip](../../k6.zip) | X — binary; should be removed + fetched in CI |
| 7.20 MB | frontend/test-results/…/trace.zip | X — Playwright artifact |
| 2.58 MB | [build_log.txt](../../build_log.txt) | C — log noise |
| 0.68 MB | frontend/test-results/home-smoke.png | X — artifact |
| ~0.6 MB each | frontend/playwright-report/* | X — artifacts |

## File-class accounting

Full per-file classification (1,423 rows): [evidence/final-file-coverage.tsv](evidence/final-file-coverage.tsv)

| Class | Meaning | Count |
|---|---|---:|
| A | Deep-read (backend main, migrations, config, frontend src, infra/CI) | 1,040 |
| B | Partially read (tests, docs) | 183 |
| C | Inventoried (root scripts/logs/misc) | 163 |
| X | Excluded binaries/artifacts | 37 |

## CI/CD & governance files

- [Jenkinsfile](../../Jenkinsfile) — the only pipeline (no GitHub Actions; `.github/` holds only dependabot.yml)
- [LICENSE](../../LICENSE) + [NOTICE](../../NOTICE) — proprietary / all-rights-reserved
- [docker-compose.yml](../../docker-compose.yml) (23 services) + [docker-compose.kraft.yml](../../docker-compose.kraft.yml) (KRaft overlay)
