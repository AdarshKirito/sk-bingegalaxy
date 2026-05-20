# Kubernetes Deploy & Rollback Proof

**Build:** `v1.0.0-rc4` (`c3d8a91`) — rolled back to `v1.0.0-rc3` (`a91e72f`)  
**Date:** 2026-04-30  
**Environment:** staging — `sk-binge-galaxy-staging`  
**Owner:** SRE  
**Status:** ✅ PASS

---

## 1. Objective

Prove that any service can be rolled back **without data loss, schema break, or
customer-visible downtime**, using the standard release tooling:

- **RB1 — Argo Rollouts canary** halts the rollout automatically when SLO
  burn rate exceeds threshold.
- **RB2 — `kubectl rollout undo`** returns the service to the previous
  immutable image tag in ≤ 90 s, and the previous ReplicaSet is still
  available for at least 24 h.
- **RB3 — Schema migrations** are forward-only and additive; rolling back the
  application image does **not** require rolling back migrations.
- **RB4 — In-flight requests** during rollback complete successfully thanks to
  PreStop hook + termination-grace + readiness gating.
- **RB5 — Pod Disruption Budgets** prevent quorum loss during voluntary
  disruptions.

## 2. Method

Three rollback dry runs, each preceded by a deliberate bad-deploy injection.

| Dry run | Bad change | Rollback method | Detection |
|---------|------------|------------------|-----------|
| DR1 | `booking-service` image with intentional 500 on `POST /api/v1/bookings` | Argo Rollouts SLO-based auto-abort | Prom alert `BookingErrorRateHigh` fired in 38 s; Argo aborted at 60 s |
| DR2 | `payment-service` image with broken JWT validation | `kubectl rollout undo` | Manual after smoke check |
| DR3 | `frontend` deploy referencing missing static asset | `kubectl rollout undo` | Synthetic check (Datadog) within 45 s |

Foreground load throughout: `k6 run load-tests/smoke.js` at 50 RPS.

Manifests under test:

- [k8s/argo-rollouts.yml](../k8s/argo-rollouts.yml)
- [k8s/services.yml](../k8s/services.yml)
- [k8s/pdb.yml](../k8s/pdb.yml)
- [k8s/hpa.yml](../k8s/hpa.yml)
- [k8s/frontend.yml](../k8s/frontend.yml)

## 3. Evidence

| Dry run | Time bad in service | Customer 5xx during window | Rollback wall-clock | 5xx during rollback | In-flight loss |
|---------|---------------------|----------------------------|---------------------|---------------------|----------------|
| DR1     | 60 s (auto-aborted at 5 % canary) | 14 (only the 5 % canary slice) | auto, **38 s** | 0 | 0 |
| DR2     | 4 m 12 s (manual)   | 312 (full traffic, payment-only) | **52 s** | 0 | 0 |
| DR3     | 2 m 30 s            | 0 (asset 404 only)            | **41 s** | 0 | n/a |

Raw artifacts:

- `kubectl rollout history deploy/booking-service -n sk-binge-galaxy-staging` shows revision history with image SHAs (no `latest`, immutable tags only — matches checklist requirement).
- Argo Rollouts CR transcript: `s3://skbg-evidence/2026-04-30/dr1-argo.yaml`.
- Prometheus screenshots (5xx, latency, saturation): `s3://skbg-evidence/2026-04-30/rollback-dashboards/`.
- Migration audit: ran `flyway info` against `booking_db` after rolling back from rc4 → rc3 → rc4 → rc3 — every transition succeeded, no failed migrations, no manual SQL needed.

Mechanisms confirmed:

- **PreStop hook** (`sleep 10`) on every backend Deployment in [k8s/services.yml](../k8s/services.yml) so endpoints drain before SIGTERM.
- **`terminationGracePeriodSeconds: 60`** matches the longest in-flight request envelope (payment + downstream Razorpay round-trip).
- **`maxSurge: 25%, maxUnavailable: 0`** on RollingUpdate — at least the original replica count is always Ready.
- **PDB `minAvailable: 1`** for stateless services and `minAvailable: N-1` for stateful sets — verified in [k8s/pdb.yml](../k8s/pdb.yml).
- **Image tags are immutable** (templated `IMAGE_TAG` token replaced by CI per service); no `:latest` references in any backend Deployment manifest in `k8s/services.yml`.

## 4. Result

✅ Rollback is fast (≤ 90 s in all three dry runs), zero in-flight loss, no
schema rollback required. Argo Rollouts catches bad deploys automatically; a
human can also force `kubectl rollout undo` and the system tolerates it
identically.

## 5. Follow-ups

- 🟡 **MEDIUM (open):** Forward-only migration policy is enforced by team
  convention and PR review; promote it to a CI check (fail on `DROP COLUMN`
  or destructive `ALTER COLUMN … TYPE` without an approved exception label).
- ✅ Quarterly **rollback fire-drill** scheduled (next: 2026-07).
- 🟢 **LOW (open):** Add automatic rollback on synthetic-check failure for
  `frontend`; today DR3 still required a human click.
