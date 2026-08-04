# 18 — Software Supply Chain and Repository Hygiene (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · all facts command-verified (see [26-COMMANDS-AND-EVIDENCE-LEDGER.md](26-COMMANDS-AND-EVIDENCE-LEDGER.md))

## Secrets in git (the P0)

| Artifact | Verified state | Action |
|---|---|---|
| [admin_token.txt](../../admin_token.txt) (451 B, JWT `eyJ…` prefix confirmed) | **Tracked at HEAD**; committed in `3d65090` | **SEC-HYG-01 (P0)**: remove from HEAD, purge history (git filter-repo/BFG), rotate JWT_SECRET, revoke all sessions |
| [stress-tokens.txt](../../stress-tokens.txt) (4 JWT lines) | **Tracked at HEAD** | same |
| `.env` | **Never committed** (`git log --all -- .env` empty; `ls-files` no match) — correctly ignored (`.gitignore:50-52`) | none (specialist claim of ".env tracked" disproved) |
| `.gitignore:25-27` | Ignores the token files — **but they were committed before the rule**; gitignore never untracks | part of SEC-HYG-01 |
| [infra/init-databases.sql](../../infra/init-databases.sql) | Dev passwords hardcoded (`*_svc_dev`) | HYG-04 (P3) — dev-only; ensure prod bootstrap differs |

Mitigating context: JWT_SECRET was rotated 2026-07-13 (SEC-007), so tokens signed by the old secret are invalid — **but** the files at HEAD may be signed post-rotation; treat as live until proven otherwise.

## Large/binary tracked files (verified sizes)

| Size | Path | Class |
|---:|---|---|
| 28.67 MB | [k6.zip](../../k6.zip) | Remove; fetch in CI (HYG-01, P2) |
| 7.2 MB | frontend/test-results/**/trace.zip | Playwright artifact — untrack + ignore (HYG-03, P2) |
| 2.58 MB | [build_log.txt](../../build_log.txt) | Log noise (HYG-02) |
| ~2 MB total | frontend/playwright-report/** | artifacts (HYG-03) |

Corrections vs specialist report: `k6_bin/` is **NOT tracked** (local-only); `backend/**/target/` is **NOT tracked** (0 files). Root clutter (40+ stress/probe/log files) **is** tracked — HYG-02 (P3): archive to `docs/_previous/` or delete.

## Dependency posture

- Backend: Spring Boot 3.4.5 / Cloud 2024.0.1 (supported line), parent-POM pinning — [evidence/backend-dependency-inventory.tsv](evidence/backend-dependency-inventory.tsv)
- Frontend: 26 direct deps, `npm ci` + committed lockfile — [evidence/frontend-dependency-inventory.tsv](evidence/frontend-dependency-inventory.tsv)
- Dependabot enabled (`.github/dependabot.yml`); OWASP (CVSS≥7 fail) + Trivy in Jenkins
- 🔴 No SBOM generation (CycloneDX) — SUP-02 (P3)
- 🔴 No image digest pinning — SUP-01 (P2)
- 🔴 No secret-scanning gate (gitleaks) — SEC-OP-04 (P2)

## Container images

[evidence/container-image-inventory.tsv](evidence/container-image-inventory.tsv): maven:3.9, eclipse-temurin:17-jre, node:20, nginx:alpine, postgres:16, mongo:7, redis, cp-kafka/cp-zookeeper (+ kraft overlay), k6 — all tag-pinned only.

## Licensing

Proprietary all-rights-reserved ([LICENSE](../../LICENSE) + [NOTICE](../../NOTICE)); dependency licenses inventoried in [evidence/license-inventory.tsv](evidence/license-inventory.tsv) — standard Apache-2.0/MIT/EPL set for Spring/React stacks; no copyleft surprises found in direct deps (static read of POMs/package.json; no license-scanner executed).

## Provenance

No signed commits, no SLSA/provenance attestations, images pushed by short-SHA tag from Jenkins ([evidence/artifact-provenance.md](evidence/artifact-provenance.md)).

## Risks (register refs)

SEC-HYG-01 (P0) · SUP-01 (P2) · HYG-01/03 (P2) · SEC-OP-04 (P2) · SUP-02 (P3) · HYG-02/04 (P3)
