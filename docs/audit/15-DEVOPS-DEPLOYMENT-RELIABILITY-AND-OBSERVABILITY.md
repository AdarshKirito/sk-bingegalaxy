# 15 — DevOps, Deployment, Reliability and Observability (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · VERIFIED-STATIC; Docker daemon was down — no container was started this run

## Docker (11 Dockerfiles)

✅ Multi-stage builds, non-root users, healthchecks 100% coverage, log caps (10m/3 files), JVM tuning (-Xms192m -Xmx320m, G1GC, heap-dump + ExitOnOutOfMemoryError)
⚠️ **No digest pinning** — `maven:3.9`, `node:20`, `eclipse-temurin:17-jre`, `nginx:alpine` are tag-only (SUP-01, P2)
ℹ️ Base compose uses ZooKeeper Kafka; [docker-compose.kraft.yml](../../docker-compose.kraft.yml) overlay is the migration path (runbook exists)

## Compose (23 services — dev posture by design)

Hikari 20/svc (×5 = 100 baseline) vs PG `max_connections=200`; Tomcat 400 threads; restart `unless-stopped` (services) / `no` (init jobs). **Single PG/Redis/Kafka(RF=1)** — fine for dev, never for prod (documented; k8s provides the HA story).

## Kubernetes (23 manifests)

| Capability | Status |
|---|---|
| Namespace, requests/limits | ✅ |
| HPA 2–5 replicas (CPU/mem) | ✅ |
| PDB minAvailable=1 | ✅ |
| Liveness/readiness (`/actuator/health`) | ✅ |
| NetworkPolicy default-deny + DNS egress | ✅ |
| External Secrets (Vault KV + DB engine) | ✅ |
| Argo Rollouts canary 5→25→50→100% + AnalysisTemplate | ✅ |
| Backups CronJob (daily 2AM, 14 d retention, optional S3) | ✅ manifest exists |
| **Ingress + TLS** | ⚠️ **Aspirational** — cert-manager referenced in Jenkinsfile; no Ingress manifest in k8s/ (DEP-01, P1 gate) |
| Postgres HA | Manifest exists (postgres-ha.yml) but not default; launch checklist correctly demands managed HA DB |
| Kafka RF=3, Mongo RF=3 | ✅ in k8s |

## CI/CD — Jenkinsfile (the only pipeline)

Checkout → Build → Test → **OWASP (fails CVSS≥7)** → **Trivy** → Docker build/push (per GIT_COMMIT_SHORT — immutable tags) → **Migration safety gate** (check-migration-safety.sh L197) → K8s deploy (render-k8s-manifests.sh) → verify → **auto `kubectl rollout undo` on failure**.

⚠️ No GitHub Actions (only dependabot.yml): no PR-level checks before merge — CI runs only where Jenkins exists (CI-01, P2). No secret-scanning stage (SEC-OP-04).

## Observability

| Layer | Status |
|---|---|
| Metrics | Prometheus ServiceMonitor ✅ |
| Tracing | Zipkin/Brave ✅ |
| Logs | Loki ✅ |
| Dashboards | Not provisioned in repo (Grafana assumed) |
| **Alerts** | 🔴 **No PrometheusRule manifests at all** — no alerts for DLT depth, outbox lag, refund-reconciliation stalls, error rates, saturation (OBS-01, **P1**) |

## Backup/restore

Scripts exist ([scripts/restore-postgres-backup.sh](../../scripts/restore-postgres-backup.sh), [scripts/restore-mongodb-backup.sh](../../scripts/restore-mongodb-backup.sh)) + [BACKUP-RESTORE.md](../../BACKUP-RESTORE.md) + k8s CronJob. **No restore-rehearsal evidence anywhere** (DB-04, P2 — a backup you haven't restored is a hope, not a backup).

## Risks (register refs)

| ID | Sev | Summary |
|---|---|---|
| OBS-01 | P1 | Zero alert rules |
| DEP-01 | P1 (gate) | Ingress/TLS not in manifests |
| SUP-01 | P2 | No image digest pinning |
| CI-01 | P2 | No PR-level CI; single Jenkins dependency |
| DB-04 | P2 | Restore never rehearsed |
| DEP-02 | P3 | Grafana dashboards not codified |
