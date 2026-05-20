# Security Test Report

**Build:** `v1.0.0-rc4` (`c3d8a91`)  
**Date:** 2026-04-30  
**Environment:** staging  
**Owner:** Application Security  
**Status:** ✅ PASS (after CRITICAL `super-admin` regression — see §5)

---

## 1. Objective

Demonstrate that the platform's security posture matches the threat model and
launch checklist, covering:

- **S1 — AuthN:** JWT signature mandatory; `alg:none` rejected; tampered tokens rejected; rotated keys honoured.
- **S2 — AuthZ:** Per-route role checks; ownership checks on read and write; admin-only and super-admin-only routes enforce role at *both* the gateway and the controller layer (defence-in-depth).
- **S3 — Header spoofing:** Client-supplied `X-User-*` headers are stripped and re-stamped at the gateway; spoof attempts are logged.
- **S4 — Input validation:** Bean Validation enforced on every public DTO; structured error responses; no stack traces leaked.
- **S5 — OWASP Top-10 sweep:** No High or Critical findings from ZAP, Burp Active Scan, or `npm audit` / `mvn dependency-check`.
- **S6 — Secrets hygiene:** All runtime secrets sourced from Vault-synced `*-creds` / `*-secrets` k8s Secrets; no defaults baked into images.
- **S7 — Network exposure:** Ingress is the only public entry point; service-to-service traffic is namespace-restricted by NetworkPolicy.
- **S8 — Webhook integrity:** Razorpay HMAC verified before any business logic.
- **S9 — TLS:** Public traffic is TLS-1.2+ only with HSTS; cert-manager handles rotation.

## 2. Method

| Layer | Tool / harness | Run profile |
|-------|----------------|-------------|
| Adversarial app testing | [stress-test-26apr.ps1](../stress-test-26apr.ps1), [stress-pwn-test.ps1](../stress-pwn-test.ps1), [stress-round3.ps1](../stress-round3.ps1) | Manual scripted, multi-role tokens |
| OWASP ZAP | Docker `owasp/zap2docker-stable` | Full active scan vs. staging gateway, authenticated context |
| Burp Suite Pro | Active scan + manual triage | Authenticated tester crawl |
| Dependency scan | `mvn org.owasp:dependency-check-maven:check`, `npm audit --omit=dev` | CI gate, fails on High |
| Image scan | Trivy | CI gate, fails on High |
| Secrets scan | gitleaks + truffleHog | CI gate, runs on every PR |
| TLS / config | sslyze, testssl.sh | External run vs. staging ingress |
| Network policy | `kubectl exec` curl from each namespace | Manual matrix |

## 3. Evidence

### S1 — AuthN

- `C-alg-none` JWT → `401`. Evidence: [STRESS-TEST-REPORT-26APR2026.md](../STRESS-TEST-REPORT-26APR2026.md) verified-secure list.
- `C1` tampered signature → `401`. Same source.
- Key rotation tested: rolled `auth-jwt-keys` Secret → tokens minted on the old key validated until expiry, then rejected. No customer logout storm (rotation is grace-period aware).

### S2 — AuthZ

- `/api/v1/{svc}/admin/*` paths gated at the gateway ([JwtAuthenticationFilter.isAdminPath](../backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/JwtAuthenticationFilter.java)) and at each service's `SecurityConfig` URL matcher.
- `/api/v2/loyalty/super-admin/*` — **PATCHED** in this build. The CRITICAL
  documented in [STRESS-TEST-REPORT-26APR2026.md](../STRESS-TEST-REPORT-26APR2026.md) (bug #1) is fixed at *both* layers:
  - Gateway `JwtAuthenticationFilter` now matches `/api/v*/**/super-admin/**` (verified by reading [JwtAuthenticationFilter.java](../backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/JwtAuthenticationFilter.java) `isAdminPath`/`isSuperAdminPath`).
  - Booking-service `SecurityConfig` declares `requestMatchers("/api/v2/loyalty/super-admin/**").hasRole("SUPER_ADMIN")` (see [SecurityConfig.java](../backend/booking-service/src/main/java/com/skbingegalaxy/booking/config/SecurityConfig.java)) so any direct service-mesh traffic is also gated.
  - Re-run of `stress-pwn-test.ps1`: previously-successful customer PUTs and POSTs now return `403`. ✅
- Cross-customer cancel/reschedule now returns `403`, not `400` (bug #4 / #5 patched in [BookingService.java](../backend/booking-service/src/main/java/com/skbingegalaxy/booking/service/BookingService.java); ownership check runs *before* `@Valid` body binding).

### S3 — Header spoofing

- `C2` — customer JWT + `X-User-Id: 6` → returned customer-17 data (gateway re-stamp). ✅
- WARN log + `auth.header.spoof.attempt` counter implemented in [JwtAuthenticationFilter.java](../backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/filter/JwtAuthenticationFilter.java) (bug #8 closed). Verified in Loki.

### S4 — Input validation

- `A4*`, `A8*`, `F1b` validation paths green (see stress report).
- `F1` emoji/RTL malformed-body bug (#3) patched: charset enforced; emoji + Arabic accepted. Regression test added.

### S5 — OWASP Top-10 / scanners

| Scanner | High | Critical | Findings of note |
|---------|------|----------|------------------|
| ZAP active scan | 0 | 0 | 4 Informational (X-Frame-Options on static assets — accepted, CSP supersedes) |
| Burp Active Scan | 0 | 0 | 1 Low (cacheable HTTPS response on a public list page — accepted) |
| `mvn dependency-check` | 0 | 0 | 3 Medium with documented suppressions |
| `npm audit --omit=dev` | 0 | 0 | clean |
| Trivy (images) | 0 | 0 | base image rebuilt 2026-04-29 |
| gitleaks | 0 | 0 | clean |

CI configuration: scanners are **gating**, not advisory — High or Critical
fails the build (per [PRODUCTION-LAUNCH-CHECKLIST.md](../PRODUCTION-LAUNCH-CHECKLIST.md) Release Gate).

### S6 — Secrets

`grep -R 'changeme\|password=\|secret=' backend/ k8s/` returns only test
fixtures and templated placeholders. Runtime values come from
`app-secrets`, `auth-db-creds`, `availability-db-creds`, `booking-db-creds`,
`payment-db-creds`, `mongo-secrets`, `notification-secrets`,
`postgres-admin-creds` — see [k8s/external-secrets.yml](../k8s/external-secrets.yml).

### S7 — Network

`NetworkPolicy` matrix verified: only api-gateway accepts external traffic;
service-to-service is namespace-scoped; no pod can reach the internet
except via the configured egress for payment provider and notification SMTP
(see [k8s/network-policy.yml](../k8s/network-policy.yml)).

### S8 — Webhook

See [payment-webhook-replay-results.md](payment-webhook-replay-results.md) §3.

### S9 — TLS

- `sslyze` against `https://staging.skbingegalaxy.test`: TLS 1.2 + 1.3 only,
  HSTS `max-age=31536000; includeSubDomains; preload`, modern cipher suites,
  no SSLv3/TLS 1.0/1.1 negotiated.
- `cert-manager` verified renewing leaf cert (rotated 2026-04-15).

## 4. Result

✅ Security posture meets launch criteria. The previously-CRITICAL
`super-admin` authorization gap is patched at *both* the gateway and the
controller, with regression coverage. No outstanding High or Critical
findings from any scanner.

## 5. Follow-ups

- ✅ Quarterly external pentest scheduled (vendor: redacted, kickoff 2026-07-01).
- ✅ Bug bounty disclosure policy published at `/security.txt`.
- 🟡 **MEDIUM (open):** Threat model document (`docs/threat-model.md`) is in
  draft; should be ratified before GA.
- 🟢 **LOW:** Add Dependabot / Renovate auto-PRs for transitive Java
  dependencies — currently only direct deps update.
