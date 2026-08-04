# EXTERNAL BLOCKERS AND UNVERIFIED ITEMS — AUD-2026-07-25-01 (execution phase)

> `BLOCKED-EXTERNAL` is used **only** for items whose completion requires something outside the repository
> and outside available safe permissions. Missing tools did NOT qualify — Java/Maven/Node/Docker were all
> recovered (see ENVIRONMENT-RECOVERY-AND-EXECUTION.md). This list is the complete set.

## B1 — Provider sandbox round-trip (Razorpay / Stripe)

| Field | Value |
|---|---|
| Blocked action | Real sandbox payment initiation → capture → webhook → refund → reconciliation cycle |
| Why necessary | Sole remaining proof for the revenue path (launch gate PR-PAY-01); everything below the provider boundary is now test-verified locally (93/93 payment tests, live DB constraints) |
| Recovery attempts | Live payment-container env inspected: `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` all **EMPTY** (prefix-classification method; no values printed). No test credentials exist anywhere in the environment |
| Alternatives executed | Full mocked-contract suite (webhook signatures, duplicate webhooks, refund intents), live DB double-refund guard verification, provider-dormant startup verification |
| Exact error | n/a — no failed call was attempted (calling with fabricated keys would be pointless and unsafe) |
| Required external action | Owner supplies `rzp_test_*`/`sk_test_*` keys and runs the PR-PAY-01 runbook |
| Work completed regardless | CURRENT-PROVIDER-VERIFICATION.md sections 1–2 |
| Risk of remaining unverified | Provider-side behaviors (settlement timing, dispute flows, webhook retry policy) unproven — launch-blocking |

## B2 — GitHub server-side repository settings

| Field | Value |
|---|---|
| Blocked action | Verify branch protection, required reviews, secret-scanning, repo visibility |
| Why necessary | Supply-chain hygiene claims (18-SOFTWARE-SUPPLY-CHAIN) |
| Recovery attempts | Not accessible from working copy; no `gh` CLI; no API token available in environment |
| Required external action | Owner checks GitHub → Settings → Branches/Security |
| Risk | Unknown enforcement of review/CI gates on `main` |

## B3 — Production/staging environment posture

| Field | Value |
|---|---|
| Blocked action | Assert TLS, real ingress, production env-var posture (PR-SEC-01, DEP-01) |
| Why necessary | k8s manifests reference cluster-level resources (cert-manager, external-secrets, Argo) that exist only in a real cluster |
| Recovery attempts | Local kubectl exists but no cluster context configured for a staging environment (only docker-desktop) |
| Alternatives executed | Full static manifest review (15-DEVOPS doc); local compose runtime verified instead |
| Required external action | Provision staging cluster; run the PRODUCTION-READINESS-CHECKLIST gates |
| Risk | Deployment topology unproven beyond compose |

## Unverified items that are NOT external blockers (interim/partial, tracked as issues)

| Item | Status | Why not blocked-external | Tracking |
|---|---|---|---|
| Multi-hour soak / chaos scenarios (Kafka outage, Redis outage, provider-slow) | PARTIAL — smoke + contention executed this run; long soaks not repeated | Feasible locally; requires only wall-clock hours | PERF-02; HISTORICAL soak data exists (26-Apr) |
| Booking concurrency under true multi-instance scale-out | PARTIAL — single-instance concurrency verified live; advisory locks + trigger verified | Compose runs one instance per service; k8s HPA scenario needs cluster | BOOK-EX-01 |
| Accessibility automated scan (axe) | NOT RUN — no axe tooling configured in repo | Adding tooling = dependency change (forbidden); manual review done | A11Y-01 |
| Full 42-file e2e in CI matrix (chromium+mobile) | See CURRENT-RUNTIME-VERIFICATION.md §E2E for what ran | Browser image was pullable; only time-bounded | TEST-EX-03 |
