# 03 — Security, RBAC & Privacy

## The gateway trust boundary (code-verified: `JwtAuthenticationFilter`)

Order `-1` global filter; the single source of trusted identity. On every request it:

1. **Strips** all client-supplied identity headers — `X-User-Id/Email/Role/Name/Phone/Phone-Country-Code` and `X-Authority-Delegated/Scope/Native-Role` — and WARN-logs `auth.header.spoof.attempt` with remote IP + UA so SOC tooling can alert. **This is the anti-spoofing keystone.**
2. **Allow-lists public paths** with *segment-boundary* matching (`/binges` whitelists `/binges` and `/binges/{id}` but never `/binges-secret`), and **normalizes `../` / `./`** first so path-traversal can't smuggle a privileged segment past the checks. Fails safe (raw path) on unparseable URIs because services re-enforce anyway.
3. **Validates the JWT**: HMAC signature via `verifyWith(key)` (blocks `alg:none`), 30s clock skew, and **soft `iss`/`aud`** enforcement (rejected only when present-and-wrong — backward-compat for pre-rollout tokens).
4. **Gates by role**: `isAdminPath` (segment 4 == `admin`/`super-admin`) and `isSuperAdminPath` (segment 4 == `super-admin`).
5. **Delegation / Authority Handover:** for `role=ADMIN` tokens carrying `delegatedScopes` (+ non-expired `delegationExpiresAt`), if the path's required scope (`SCOPE_MAP`) is granted, the gateway sets **effective role = SUPER_ADMIN for that path only** and stamps `X-Authority-Delegated/Scope/Native-Role` downstream. The native role in the JWT is never mutated (token stays truthful).
6. **Temp-password gate:** a `mustChangePassword` token is restricted to change-password/profile/me/logout; everything else 403s with `X-Password-Change-Required`. Authoritative server-side enforcement (the SPA also forces it).
7. **Session revocation (sid):** if the token carries `sid` and Redis is available, checks the `auth:revoked-sid:` denylist and 401s revoked sessions with `X-Session-Revoked`. **Fails OPEN on Redis error** (revoked tokens then die at natural expiry).
8. **Injects trusted headers** (`X-User-*`, and delegation headers when elevated) for downstream services.

## Per-service re-enforcement (defence in depth — code-verified)

Every domain service runs two `common-lib` filters before Spring authz:
- **`InternalApiAuthFilter`** — for `/internal/**`, constant-time compares `X-Internal-Secret`; fail-closed if secret missing/blank/wrong; on success sets `ROLE_SYSTEM`. Registered by **auth, availability, booking** (the internal-endpoint providers). Payment/availability are Feign *consumers* and correctly don't need it inbound.
- **`GatewayHeaderAuthFilter`** — turns the trusted `X-User-Role` into `ROLE_<role>`, clears the context after the request, sets MDC (`userId`,`bingeId`) for structured logs.

Then each `SecurityConfig` matcher enforces role per path. Highlights:
- **auth:** carefully ordered — `authority/internal/**` & `internal/**` → SYSTEM; `privacy/admin/**` → SUPER_ADMIN; the whole `admin/users|admins|sessions|audit-log|super-admin` surface → SUPER_ADMIN; `admin/**` → ADMIN/SUPER_ADMIN; then **`/api/v1/auth/** permitAll`** catch-all (login/register/refresh). ⚠️ *Fail-open-within-namespace:* any new `/auth/**` endpoint is **public** unless a more-specific matcher precedes it. Ordering is load-bearing.
- **booking:** `admin/**` → ADMIN/SUPER_ADMIN; loyalty-v2 `super-admin/**` → SUPER_ADMIN; `internal/**` → SYSTEM; a broad `permitAll` list for public catalog/media/reviews/analytics; `actuator/**` → SYSTEM; swagger → admin. **Plus** service-layer per-binge module-matrix + ownership enforcement (not visible in the matcher).
- **payment:** `admin/**` → ADMIN/SUPER_ADMIN; `callback` + `webhooks/**` public (HMAC-verified in service); rest authenticated.

## Roles, delegation, module matrix

- **Native roles:** CUSTOMER, ADMIN, SUPER_ADMIN (validated against a whitelist in both gateway and header filter).
- **Delegation (Authority Handover):** time-boxed per-scope elevation of an ADMIN to super-admin powers on specific pages (`CURRENCIES`, `NOTIFICATIONS`, `LOYALTY`, `OPS`, `ALL_USERS`, `CUSTOMER_EDIT`, `ADMIN_REGISTER`, `ACCOUNT_CMS`, `HOME_CMS`, `SUPER_DASHBOARD`). Scope→path map lives in the gateway `SCOPE_MAP`; downstream authority-lock lookups enforce locks server-side via auth's `/authority/internal/locks/lookup`.
- **Per-binge module matrix:** each Binge enables/disables/locks admin modules; booking-service returns fail-closed 403 when a module is off or binge context is missing. (History: the global notification bell once broke because `/admin/notifications` was wrongly inside the per-binge MESSAGES gate — a reminder that global admin surfaces must be *outside* the per-binge matrix.)

## Authentication & session mechanics

- **JWT**: short-lived access token (~15 min) as **httpOnly cookie** *or* Bearer; refresh via httpOnly cookie. The SPA stores only `token_exp` + a minimal user object in localStorage — **the token itself is not in localStorage** (good XSS posture). Proactive refresh (<60s to expiry) + reactive 401 refresh with a queued-request drain.
- **MFA/TOTP**: `TotpService`, secrets encrypted with `CRYPTO_SECRET_KEY`. `SUPER_ADMIN_REQUIRE_MFA` defaults **false** for local compose (a documented dev convenience — the seeder never enrols TOTP, so `true` deadlocks admin login after `down -v`). Must be `true` in production.
- **Passwords**: BCrypt strength 12; password-history entity prevents reuse; forgot/reset + email verification tokens.

## Privacy / DPDP-GDPR

- `UserPrivacyController` + `UserAnonymizationService`; `user.anonymized` Kafka fan-out so every PII-holding service redacts its copy (notification has a dedicated listener). Privacy admin endpoints are SUPER_ADMIN-only.
- Structured-log **PII masking** (`CardNumberMaskingConverter`, `MaskingMessageJsonProvider`, `LogSanitizer`) and Sentry Replay masking.

## Security findings (see 07 for full register)

| Sev | Finding |
|---|---|
| **P0** | `admin_token.txt` + `stress-tokens.txt` still in committed `HEAD`/history (only staged for deletion). Purge history + rotate the JWT/admin creds they contain. |
| **P0** | ~599 uncommitted files ⇒ the deployed build cannot be reproduced or audited from git; security fixes in the tree may not be what's running. |
| **P1** | Redis-down fails **open** for both session revocation and rate limiting. Acceptable for availability, but a conscious production sign-off item; consider fail-closed for revocation on privileged paths. |
| **P1** | `SUPER_ADMIN_REQUIRE_MFA` default false + `CRYPTO_SECRET_KEY` defaulting to a JWT-derived key are dev conveniences that are dangerous if they leak into prod. Enforce true + independent key via profile fail-fast. |
| **P2** | `/api/v1/auth/** permitAll` and `/api/v1/bookings/**` broad permit lists are fail-open-within-namespace — one careless new endpoint under them is public. Prefer explicit allow-lists over broad `permitAll`. |
| **P2** | Soft `iss`/`aud` enforcement leaves a window where a token with no `iss`/`aud` is accepted. Fine during rollout; flip to hard enforcement once all live tokens carry them. |
| Note | The boundary itself (header stripping, traversal-normalized allow-lists, constant-time internal secret, HMAC webhooks, defence-in-depth re-enforcement) is genuinely strong and above-average for this stage. |
