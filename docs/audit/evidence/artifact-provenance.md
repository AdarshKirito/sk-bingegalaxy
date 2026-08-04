# Artifact Provenance — Evidence

> AUD-2026-07-25-01 · commit `6440f58`

## Build → artifact chain (as designed in Jenkinsfile)

1. Checkout at commit → `GIT_COMMIT_SHORT` captured
2. Maven reactor build (tests must pass) → jars
3. OWASP dependency check — **fails on CVSS ≥ 7**
4. Docker build per service → tag `<image>:<GIT_COMMIT_SHORT>` (immutable, no `latest` promotion) ✅
5. Trivy image scan
6. Push to registry
7. Migration-safety gate → k8s manifest render (`render-k8s-manifests.sh`) → deploy → verify → auto-rollback on failure

## Provenance gaps

| Gap | Impact | Register |
|---|---|---|
| Base images tag-only (maven:3.9, node:20, eclipse-temurin:17-jre, nginx:alpine, postgres:16, mongo:7 — see [container-image-inventory.tsv](container-image-inventory.tsv)) | Non-reproducible builds; upstream tag mutation risk | SUP-01 (P2) |
| No SBOM (CycloneDX/Syft) generated or attached | Cannot answer "are we affected by CVE-X?" instantly | SUP-02 (P3) |
| No image signing (cosign) / SLSA attestation | Registry compromise undetectable at deploy | SUP-03 (P3) |
| No signed commits | Author spoofing possible | GOV-03 scope |
| Jenkins is the single CI (no PR-level checks) | Untested merges possible before Jenkins runs | CI-01 (P2) |

## Verified positives

- Immutable per-commit tags (no `latest`) ✅
- `npm ci` with committed lockfile (frontend/Dockerfile:9) ✅
- Maven versions pinned via parent POM ✅
- Dependabot enabled ✅
