# 10 — Infra / Kubernetes / Scripts / CI

Everything outside application code: container builds, local Compose, the Kubernetes manifest set,
the Jenkins pipeline, operational scripts, Flyway migrations, DB init, and the runbooks.

---

## Containers

### `backend/*/Dockerfile` (8) + `frontend/Dockerfile`
Multi-stage builds — a Maven/JDK (or Node) build stage compiles from source, then a slim runtime
stage (JRE / nginx) holds only the artifact. So `docker-compose up --build` needs **no local
JDK/Maven/Node**. Each backend image runs the service JAR with a health check; the frontend image
serves the Vite `dist/` via nginx with SPA fallback.

### `frontend/nginx.conf`
Production static serving: SPA history fallback (`try_files … /index.html`), gzip, immutable
cache headers for hashed assets, and the security headers belt-and-suspenders.

### `.dockerignore` files
Keep build context lean (exclude `target/`, `node_modules/`, logs).

---

## Local orchestration

### `docker-compose.yml` (22 KB)
The full local stack: `postgres`, `mongodb`, `redis`, `zookeeper`, `kafka` (+ `kafka-init` job),
`zipkin`, the two infra services (`discovery-server`, `config-server`), the `api-gateway`, the
five app services, and the `frontend` — plus named volumes (`postgres-data`, `mongo-data`,
`kafka-data`, …). Health checks gate startup ordering; env defaults wire HTTP-safe cookies and
payment simulation for dev.

### `docker-compose.kraft.yml`
KRaft variant of the stack (Kafka without ZooKeeper) — the target of the
[kafka-kraft-migration runbook](../runbooks/kafka-kraft-migration.md).

---

## Kubernetes (`k8s/`, 22 manifests)

### Workloads
- **`services.yml`** (589 L) — `Deployment` + `Service` for all 8 backend services + gateway.
- **`frontend.yml`** — frontend `Deployment` + `Service` + the public **`Ingress`** (TLS host).
- **`infrastructure.yml`** — Eureka/Config as `Deployment`s, and any in-cluster infra
  `StatefulSet`s.
- **`kafka.yml`** / **`kafka-init.yml`** — Kafka `StatefulSet` + topic-creation `Job`.
- **`mongodb.yml`** — Mongo `StatefulSet` + a replica-set init `Job` (the pipeline waits on this).
- **`postgres.yml`** (in-cluster), **`postgres-ha.yml`** (CloudNativePG `Cluster` + `Pooler`),
  **`postgres-managed.yml`** (an `ExternalName` `Service` pointing at managed RDS/Cloud SQL).

### Scaling, availability, resilience
- **`hpa.yml`** — `HorizontalPodAutoscaler` per service (CPU/memory targets).
- **`pdb.yml`** — `PodDisruptionBudget`s so rolling upgrades/node drains keep quorum.
- **`network-policy.yml`** (354 L) — **zero-trust**: a default `deny-all` plus explicit allow
  rules (gateway→services, services→their DB, services→Kafka). One `NetworkPolicy` per allowed
  edge.
- **`argo-rollouts.yml`** (888 L) — `Rollout` + `AnalysisTemplate` for **canary** deploys with
  automated metric-based promotion/rollback.
- **`istio.yml`** — `PeerAuthentication` (mTLS), `AuthorizationPolicy`, `DestinationRule` for the
  service mesh.

### Security & secrets
- **`cert-manager.yml`** — `ClusterIssuer` (Let's Encrypt) for automatic TLS.
- **`external-secrets.yml`** (530 L) — `ClusterSecretStore` + `ExternalSecret`s pulling runtime
  secrets from the cloud secret manager (no secrets in Git).
- **`rbac.yml`** — least-privilege `ServiceAccount` + `Role` + `RoleBinding`.
- **`namespace.yml`** — the `Namespace` + a baseline `ConfigMap`.

### Operations
- **`backups.yml`** (286 L) — daily Postgres + Mongo backup `CronJob`s writing to a PVC/bucket.
- **`postgres-maintenance.yml`** — autovacuum/reindex `CronJob`s for the high-write tables.
- **`monitoring.yml`** (833 L) — Prometheus (`ServiceMonitor`, `PrometheusRule` alerts) + Grafana
  (`Deployment` + dashboards `ConfigMap`).
- **`logging.yml`** (371 L) — log shipping `DaemonSet` (Promtail/Fluent) → Loki `StatefulSet`,
  with RBAC.
- **`grafana/`** — dashboard JSON.

Manifests are templated (`${VARS}`) and rendered per-environment by the scripts below.

---

## Scripts (`scripts/`)

### `render-k8s-manifests.sh`
`render-k8s-manifests.sh <env-file> <out-dir> <IMAGE_TAG>` — substitutes env vars (domain, image
tag, storage class, managed-Postgres host) into the `k8s/` templates, emitting deploy-ready
manifests. Immutable image tags only.

### `sync-k8s-secrets.sh`
Creates/updates the Kubernetes `Secret`s from the production `.env` (the bridge for clusters not
using External Secrets).

### `generate-env.sh` / `generate-env.ps1`
Generate a fresh `.env` with strong random secrets (JWT secret, DB passwords, admin password,
internal API secret) — the bootstrap for a new environment. PowerShell variant for Windows.

### `check-migration-safety.sh` (212 L)
Static analysis of pending Flyway migrations for **unsafe operations** (blocking `ALTER`s,
non-concurrent index creation, `DROP COLUMN` without a deprecation window) — gates the CI
"Migration Safety Check" stage.

### `restore-postgres-backup.sh` / `restore-mongodb-backup.sh`
Disaster-recovery restore from the backup CronJob artifacts (documented in `BACKUP-RESTORE.md`).

---

## CI/CD

### `Jenkinsfile` (13 stages)
`Checkout → Build Backend → Test Backend → Build Frontend → Test Frontend → Security Scan
(Dependency Audit) → Build Docker Images (per service) → Push Docker Images → Container Image Scan
→ Migration Safety Check → Verify Flyway Migrations → Deploy to Kubernetes → Verify Deployment`.
Builds **immutable image tags**, waits for the Mongo replica-set init Job before verifying
rollouts, and expects a Jenkins file credential `production-env` holding the prod `.env`.

### `.github/`
Dependabot config + repo metadata (GitHub Actions workflows directory is present but the primary
pipeline is Jenkins).

---

## Database

### `infra/init-databases.sql`
Postgres bootstrap: creates `auth_db`, `availability_db`, `booking_db`, `payment_db`, the
**least-privilege per-service roles** (`auth_svc`, `availability_svc`, `booking_svc`,
`payment_svc`) each granted only their own DB, and the `shedlock` table (owned by `booking_svc`).

### Flyway migrations (`backend/*/src/main/resources/db/migration/`)
Versioned DDL per service: **auth ~15, availability ~2, booking ~63, payment ~13** (source).
Booking's V1→V63 + extras track the long iterative evolution (finance snapshots V38/V39, binge
timezone V63, room blocks, autovacuum tuning, report indexes…). Validated by the CI "Verify
Flyway Migrations" stage and guarded by `check-migration-safety.sh`.

---

## Runbooks & ops docs (`docs/runbooks/`)
- **`operational-runbooks.md`** — incident playbooks (Kafka outage, DB failover, stuck saga, DLT
  drain, refund reconciliation).
- **`kafka-kraft-migration.md`** — ZooKeeper→KRaft cutover procedure.
- **`postmortem-template.md`** — the blameless post-incident template.

Plus the top-level `BACKUP-RESTORE.md`, `PRODUCTION-LAUNCH-CHECKLIST.md`,
`STRESS-TEST-REPORT-26APR2026.md`, and the `production-proof/` evidence pack (load tests, booking
race, Kafka outage, backup/restore, K8s rollback, security scans).

---

> **Documentation set complete** — all 11 modules (00–10, with booking-service split 06a–06e)
> are documented under `docs/codebase/`. See [00-INDEX.md](00-INDEX.md) for the map.
