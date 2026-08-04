# 08 — Security, RBAC and Privacy (Current)

> Audit run AUD-2026-07-25-01 · commit `6440f58` · static analysis; no penetration testing performed

## Perimeter (VERIFIED-STATIC)

| Control | Implementation | Evidence |
|---|---|---|
| JWT validation + revocation | Gateway validates access token, consults Redis session denylist per request | api-gateway filters |
| Spoofable-header stripping | Inbound `X-User-*` stripped before trusted injection | gateway filter chain |
| CSRF | Token issued/enforced at gateway for cookie-authenticated mutations | gateway config |
| Rate limiting | Redis-backed, per-route budgets | gateway config |
| Session security | httpOnly cookies (access + refresh); frontend single-flight refresh; no tokens in localStorage | frontend/src/services/http client |
| Internal APIs | `INTERNAL_API_SECRET` shared-secret filter (common-lib) | InternalAuthFilter |
| Webhooks | HMAC verification + event-id dedup (Razorpay, Stripe) | StripeWebhookHandler, RazorpayCallback |

## RBAC layering

1. **Gateway**: path-level role rules; authority-grant elevation only for matched paths (native JWT role unchanged).
2. **Service**: `@PreAuthorize` (e.g., LoyaltyV2SuperAdminController.java:49) and header-role checks.
3. **Tenancy**: `requireManagedBinge(bingeId)` on admin resources — the cross-binge recovery-queue PII leak (SEC-001) is **FIXED**; regression-tested by AdminRecoveryQueueScopeTest.
4. **Module matrix**: ModulePermissionInterceptor deny-list on 17 modules; 6 sensitive modules need dual sign-off.
5. **Object ownership**: customer reads owner-scoped (bookings/payments).
Full trace: [evidence/current-security-traces.md](evidence/current-security-traces.md) and [evidence/tenant-isolation-matrix.tsv](evidence/tenant-isolation-matrix.tsv).

## MFA

- TOTP with encrypted secrets (SecretCipher, AES-GCM)
- `SUPER_ADMIN_REQUIRE_MFA` **code default "true"** ([AuthService.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/service/AuthService.java) L464-480: `getOrDefault("SUPER_ADMIN_REQUIRE_MFA", "true")`); compose sets `"false"` (dev); k8s must set it explicitly or inherit the safe default — **gate PR-SEC-03**
- ⚠️ **CRYPTO_SECRET_KEY fallback**: [SecretCipher.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/security/SecretCipher.java) L55-57 derives the MFA encryption key from `JWT_SECRET` when `CRYPTO_SECRET_KEY` is unset — rotating JWT_SECRET without setting CRYPTO_SECRET_KEY **bricks all MFA enrollments** (register SEC-CR-02)

## Production profile enforcement

- SEC-003 (dev posture reachable in prod) **PARTIALLY FIXED**: auth-service application.yml L11-12 profile group maps `kubernetes → production`; payment-service `@PostConstruct` fail-fasts on simulation-in-production (PaymentService.java L107-136). Compose remains dev-postured by design (`COOKIE_SECURE=false`, MFA off, simulation on) — acceptable **only** if compose is never used for prod (gate PR-SEC-01).

## Secrets (names only)

| Issue | Status |
|---|---|
| `admin_token.txt` + `stress-tokens.txt` tracked at HEAD (real-format JWTs) | **P0 OPEN** — SEC-HYG-01: purge from HEAD **and history** (filter-repo/BFG), rotate `JWT_SECRET`, revoke sessions |
| `.env` | **never committed** (verified) — local only; `.gitignore:50-52` correct |
| JWT_SECRET rotation 2026-07-13 (SEC-007) | Done historically; tokens signed by old secret invalid — but the token files still sit in history |
| infra/init-databases.sql dev passwords | Dev-only; must not reach prod (gate PR-SEC-05) |
| k8s | External Secrets + Vault; no inline base64 secrets found in manifests |

## Privacy

- **Anonymization pipeline EXISTS** (a specialist claim of "no GDPR path" is **disproved**): [UserAnonymizationService.java](../../backend/auth-service/src/main/java/com/skbingegalaxy/auth/service/UserAnonymizationService.java) — `requestDeletion` (L56), `anonymizeUser` (L84), nightly sweep cron 02:30 (L101), per-user commit isolation; publishes `user.anonymized` (L159); consumers in booking/payment/notification (`UserAnonymizedEventListener` each) redact PII locally
- Notification TTL 90 d (Mongo); details in [20-PRIVACY-COMPLIANCE-AND-GOVERNANCE.md](20-PRIVACY-COMPLIANCE-AND-GOVERNANCE.md)
- ⚠️ auth-service publishes `user.anonymized` **directly** (no outbox) — Kafka outage during anonymization relies on retry semantics (register EVT-02); end-to-end erasure completeness NOT-VERIFIED (needs runtime test)

## Residual security risks (all in [ISSUE-REGISTER-CURRENT.md](ISSUE-REGISTER-CURRENT.md))

| ID | Sev | Summary |
|---|---|---|
| SEC-HYG-01 | P0 | Token files tracked at HEAD + in history |
| SEC-CR-02 | P1 | MFA key derives from JWT_SECRET fallback |
| PR-SEC-01 | P1 (gate) | Prod deploy must assert production profile + simulation off (static default safe; runtime proof outstanding) |
| SEC-OP-04 | P2 | No secret-scanning CI gate (gitleaks/trufflehog) to prevent recurrence |
| SEC-OP-05 | P2 | No dependency-vuln budget in front of `mvn` build locally (OWASP runs in Jenkins only) |
