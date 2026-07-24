# 13 — DevOps & Reliability

Evidence: docs-infra census, `evidence/specialist-01` (profile config), live `docker ps`. Depth = Level B.

## Deployment reality (two targets, divergent maturity)

- **docker-compose.yml** (running): 15 containers healthy — 6 services + gateway + config + discovery + Kafka/ZooKeeper + Redis + Postgres + Mongo + Zipkin (+ kafka-init one-shot). Only gateway `:8090` + frontend `:3000` published. Resource limits on every service. `infra/init-databases.sql` bootstraps the 4 Postgres DBs. Optional KRaft overlay. `rebuild.ps1/.sh` do a disk-bounded `down -v` rebuild (DB wipe) per the project's disk-bloat history.
- **k8s/** (target, not exercised): 22 manifests — Istio mTLS, Argo Rollouts canaries, HPA (7)/PDB (10), CloudNativePG HA, External Secrets/Vault, cert-manager, Prometheus/Grafana/Loki, backup CronJobs. Rendered via `scripts/render-k8s-manifests.sh`, secrets via `sync-k8s-secrets.sh`. A genuine prod-grade superset — but review-only here.
- **CI:** Jenkinsfile (13 stages: build/test backend+frontend, npm-audit + OWASP dependency-check, Trivy image scan, migration-safety gate, Flyway validate, k8s deploy + rollback-on-failure). `.github/` has **dependabot only, no Actions workflows.**

## Configuration & secrets

- Real secrets use `${VAR}` no-default (fail-fast) — good. Exceptions: VAPID private key (SEC-004), reCAPTCHA secret (SEC-008) have committed defaults.
- **SEC-003 (High):** no deployment activates the `production` profile → dev security stubs + inert payment guards.
- **DEVOPS-004:** `.env`↔`.env.example` drift; `PAYMENT_SIMULATION_ENABLED` undocumented.
- **SEC-007 / DEVOPS-002:** live-looking JWTs + build/k6/log artifacts tracked in git.
- `scripts/generate-env.{sh,ps1}` generate cryptographically-secure secrets — good practice.

## Observability

- Micrometer→Zipkin tracing wired across services (running Zipkin container). JSON logging (logstash encoder) + card-number masking. k8s adds Prometheus/Grafana/Loki + alerts (not exercised).
- **Gap:** silent-fallback activation (stale availability, circuit-breaker open) has no obvious metric/alert — recommend counters on fallback usage and on `MANUAL_REVIEW_FLAGGED`/DLT depth.

## Health & readiness

- Actuator health confirmed UP at the gateway (liveness/readiness groups). **DEVOPS-003 (PROBABLE):** compose app services are referenced with `condition: service_healthy` but define no compose-level healthcheck — they rely on Dockerfile `HEALTHCHECK` (unverified). Verify each Dockerfile or add explicit compose healthchecks.

## Backup / restore / DR

- `BACKUP-RESTORE.md` + `scripts/restore-postgres-backup.sh` / `restore-mongodb-backup.sh`; k8s `backups.yml` CronJobs + `postgres-maintenance.yml`. `production-proof/db-backup-restore-proof.md` records a past successful test (historical). Restore procedures exist; not re-verified this run.

## Operator playbook (detect → repair)

For a failed workflow an operator can: **detect** via recovery queues / `MANUAL_REVIEW_FLAGGED` / DLT / health; **trace** via Zipkin + `booking_event_log` + payment `audit_log`; **repair** via admin ops (DLT replay, outbox retry) + maker-checker refunds. **Cannot yet** cleanly handle: poison messages (REL-001 loop), cross-service PII erasure (DATA-004), notification-delivery failures (no documented replay). A workflow that can fail silently is not production-ready — the silent-fallback observability gap is the main reliability risk beyond the isolation defects.

## Disk hygiene (project-specific, from memory)

Docker WSL2 vhdx has previously bloated to hundreds of GB; `rebuild.*` scripts intentionally `down -v` to bound it. `spike.out` (202 MB) and `k6.zip` (30 MB) at repo root are the largest artifacts (k6.zip tracked — DEVOPS-002).
