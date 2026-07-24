# 09 — Security, RBAC, Privacy

Defensive review. Evidence in `evidence/specialist-01-authn-authz-sessions.md`, `-02-binge-isolation.md`, and runtime tests in `21-RUNTIME-VERIFICATION-LOG.md`. No exploitation instructions are given.

## Trust architecture (CONFIRMED sound)

- **JWT validated only at the edge.** `api-gateway/.../filter/JwtAuthenticationFilter` verifies the HS256 JWT (`verifyWith`, no `alg:none`), checks iss/aud, normalizes paths against `../` traversal, and consults a Redis session-revocation list. It **strips** any client-supplied `X-User-*` / `X-Authority-*` headers, then injects trusted identity headers downstream (`:136-153`). JWTs are issued by `auth-service/.../security/JwtProvider`.
- **Downstream services trust gateway headers only** via `common-lib GatewayHeaderAuthFilter`; they do not re-validate the JWT. This is safe because **backend service ports are not published to the host** — only the gateway (`:8090`) and frontend (`:3000`) are (compose). Header-spoofing requires bypassing the gateway, which is not reachable.
- **Internal service-to-service auth:** `/internal/**` is guarded by `common-lib InternalApiAuthFilter` (constant-time shared-secret compare) + `hasRole("SYSTEM")`, and is not routed through the gateway. Runtime-confirmed: `/api/v1/bookings/internal/...` via gateway → 403; `/internal/...` via gateway → 404.
- **CSRF:** double-submit cookie (`SameSite=Strict`) + Origin/Referer allow-list, constant-time compare. Runtime-confirmed: register 403 without Origin/CSRF, 201 with.
- **Auth hardening:** BCrypt(12), SHA-256-hashed reset tokens, OTP attempt-limiting, MFA/TOTP, password-history + HIBP checks, per-IP tiered rate limiting, full OWASP security-header set (observed in responses), file-upload path-traversal defenses.

## Permission matrix (as implemented)

| Capability | Anonymous | Customer | Binge Admin (owner) | Super Admin | Delegated Admin |
|---|---|---|---|---|---|
| Public discovery (`/bookings/binges`, event-types) | ✔ | ✔ | ✔ | ✔ | ✔ |
| Register / login / verify | ✔ | — | — | — | — |
| Own profile / bookings / payments | ✖ (401) | ✔ (own only) | ✔ | ✔ | ✔ |
| Admin customer directory (`/auth/admin/customers`) | ✖ | ✖ (403, runtime-confirmed) | ✔ | ✔ | scope-gated |
| Binge-owned admin data (bookings/pricing/tax/invoices) | ✖ | ✖ | ✔ **only for owned binge** | ✔ (all binges) | ✔ if not locked |
| Global super-admin pages (currencies, loyalty, CMS, notifications) | ✖ | ✖ | ✖ | ✔ | ✔ **only within granted scope, ≤24h** |
| Platform ops (`/admin/ops` DLT/outbox) | ✖ | ✖ | ⚠ reachable (SEC-005) | ✔ | ✔ |
| Internal `/internal/**` | ✖ | ✖ | ✖ | ✖ | ✖ (service accounts only) |

Ownership is `Binge.adminId == callerAdminId` unless role is SUPER_ADMIN (`AdminBingeScopeService`). Delegation (`AuthorityGrant`) grants **global scopes**, not per-binge data — the gateway only elevates `effectiveRole` to SUPER_ADMIN on `SCOPE_MAP` (global) paths with a matching scope, so binge-owned-data paths keep ADMIN and enforce ownership.

## Tenant-isolation status

The isolation **plumbing is correct** (internal binge contract, module-permission matrix, cache keys include bingeId, events carry bingeId) but enforcement is **per-endpoint by convention**, and a few endpoints skipped it:

- **SEC-001 (Critical, CONFIRMED):** `AdminRecoveryQueueController` reads bookings/holds with no binge filter → cross-binge PII.
- **SEC-002 (High, CONFIRMED):** `InvoiceController.listInvoicesForBinge` uses presence-only `requireSelectedBinge` → cross-binge invoices.
- **SEC-005 (Medium, PROBABLE):** `AdminOpsController` + funnel aggregates reachable/spoofable by any binge admin.

`X-Binge-Id` is client-controlled (`localStorage`) and **not validated at the gateway** — isolation depends entirely on each endpoint calling an ownership check. The recommended systemic fix (roadmap) is a mandatory service-side ownership filter so new endpoints are secure by default.

## Secrets & config

- **SEC-003 (High):** no deployment activates the `production` profile → captcha stub + payment FATAL guards inert.
- **SEC-004 (Medium):** committed VAPID private key as a silent default (unlike fail-fast `JWT_SECRET`/`INTERNAL_API_SECRET`).
- **SEC-007 (High):** live-looking JWTs (`admin_token.txt`, `stress-tokens.txt`) tracked in git.
- **SEC-008 (Low):** reCAPTCHA secret has a weak dev default.
- Real secrets in compose use `${VAR}` with no default (good); DB service passwords have dev-only `:-default` fallbacks. `.env` holds dev credentials (gitignored going forward).

## Privacy / retention

- **Strong on auth:** `users` has soft-delete/anonymize/consent/retention columns (`V14`) — a real DPDP/GDPR story.
- **DATA-004 (High):** auth anonymization does **not** propagate; PII copies persist in booking/payment/Mongo stores → incomplete erasure.
- **DATA-003 (High):** Mongo notification PII never auto-expires (TTL index inert).
- **SEC-006 (Low):** booking-transfer public preview leaks both parties' emails.

## Injection / other

- All `@Query` are parameterized; no `Runtime.exec`/deserialization sinks (specialist-01). No SQL-injection surface found.
