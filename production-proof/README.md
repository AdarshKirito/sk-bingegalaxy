# Production-Proof Evidence Pack — SK Binge Galaxy

**Owner:** Platform / SRE  
**Last reviewed:** 2026-05-01  
**Audience:** Launch reviewers, SRE on-call, security, executive sign-off

This folder is the *evidence archive* that backs the production launch. Every claim
in [PRODUCTION-LAUNCH-CHECKLIST.md](../PRODUCTION-LAUNCH-CHECKLIST.md) maps to one
of the proof reports below. Each report follows the same structure:

1. **Objective** — what we are proving.
2. **Method** — exactly how it was tested (commands, harness, environment).
3. **Evidence** — log excerpts, metrics, IDs, screenshots / artifact paths.
4. **Result** — pass / partial / fail and the residual risk.
5. **Remediation / follow-ups** — open work and ownership.

## Index

| # | Domain | Report |
|---|--------|--------|
| 1 | Booking concurrency | [booking-race-test-results.md](booking-race-test-results.md) |
| 2 | Payment idempotency  | [payment-webhook-replay-results.md](payment-webhook-replay-results.md) |
| 3 | Payment safety       | [duplicate-payment-results.md](duplicate-payment-results.md) |
| 4 | Refund integrity     | [refund-failure-results.md](refund-failure-results.md) |
| 5 | Messaging resilience | [kafka-downtime-recovery.md](kafka-downtime-recovery.md) |
| 6 | Cache resilience     | [redis-downtime-recovery.md](redis-downtime-recovery.md) |
| 7 | Backup / restore     | [db-backup-restore-proof.md](db-backup-restore-proof.md) |
| 8 | Deploy / rollback    | [k8s-rollback-proof.md](k8s-rollback-proof.md) |
| 9 | Security             | [security-test-report.md](security-test-report.md) |
| 10 | Launch readiness    | [launch-readiness-report.md](launch-readiness-report.md) |

## Conventions

- All evidence references real files in this repo (logs, manifests, scripts) using
  workspace-relative links, so reviewers can click through without leaving the
  repository.
- Tests were executed against the staging cluster (`sk-binge-galaxy-staging`) on
  an immutable image tag (`v1.0.0-rc4`, sha `c3d8a91`). Where a test was run
  locally against the docker-compose stack, the report says so explicitly.
- "Pass" requires: deterministic reproduction, captured artifacts, automated
  regression in CI, and a runbook entry for on-call response.

## How to refresh this pack

The pack must be re-run before every major release.

```powershell
# From repository root
pwsh ./stress-test-26apr.ps1            # booking + auth + admin adversarial suite
pwsh ./stress-worstcase-26apr.ps1       # extended worst-case battery
k6 run load-tests/smoke.js              # baseline load
k6 run load-tests/spike.js              # spike load
k6 run load-tests/soak-bookings.js      # 4h soak
```

Update each report's *Date* and *Build* header, and replace the evidence block
with fresh artifact paths. Do not delete prior runs — archive them under
`production-proof/archive/<YYYY-MM-DD>/` for trend analysis.
