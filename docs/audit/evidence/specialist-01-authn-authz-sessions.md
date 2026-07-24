# Specialist Investigation 01 — AuthN / AuthZ / Sessions (read-only)

Agent completed 2026-07-11. Scope: API gateway, all backend services, common-lib security, config-server configs, docker/k8s manifests. Output is EVIDENCE, reconciled by lead before entering the canonical issue register.

## FINDING 1 (HIGH — systemic misconfiguration): Production security beans never load — no deployment activates the `production` Spring profile

Codebase gates dev/test stubs vs real implementations on literal profile string `"production"`, but no deployment ever activates it.

- K8s sets `SPRING_PROFILES_ACTIVE: "kubernetes"` — `k8s/namespace.yml:14`
- Docker Compose sets nothing (default profile). `.env` has no `SPRING_PROFILES_ACTIVE`.
- Repo-wide: string `production` is never used to activate a profile; no `spring.profiles.group` mapping `kubernetes`→`production`.

Because `"kubernetes"` ≠ `"production"`, every `@Profile("production")` bean is skipped and every `@Profile("!production")` stub loads in the real deployment:

- `StubCaptchaValidationService` (`auth-service/.../service/impl/StubCaptchaValidationService.java:13`, `@Profile("!production")`) `return token != null && !token.isBlank();` — accepts ANY non-blank token. Real `RecaptchaValidationService` (`.../impl/RecaptchaValidationService.java:20`, `@Profile("production")`) never registers. Net: reCAPTCHA not validated in production. It's the post-3-failed-login bot/brute-force gate (`AuthService.java:90,262,420`); now trivially satisfied. (Gateway per-IP login limit 30/min + account lockout still apply → defeated defense-in-depth, not full bypass.)
- `PaymentService.validateConfig` (`payment-service/.../PaymentService.java:86-119`) computes `isProduction` from `environment.getActiveProfiles().contains("production")` → always false. FATAL guards ("simulation must be off in production", "live key must start `rzp_live_`") are inert.
- Prometheus alert (`k8s/monitoring.yml:673-683`) assumes `@Profile("!production")` mocks ABSENT in prod — i.e. assumes `production` active. Profile-name mismatch silently defeats that assumption.

Fix direction: activate `production` in real deployments (`SPRING_PROFILES_ACTIVE: "kubernetes,production"`) or add `spring.profiles.group.kubernetes: production`.

## FINDING 2 (MEDIUM): Committed VAPID Web Push private key as silent config default

`config-server/.../configurations/notification-service.yml:99`:
`private-key: ${WEBPUSH_PRIVATE_KEY:Yc2-7f9bmIYsrgKpoGW1HKs1ggYgmJqTN1d26WI-xOU}`
If unset in prod, app silently runs with repo-committed private key (anyone with repo can sign Web Push payloads). Inconsistent with fail-fast pattern used for `INTERNAL_API_SECRET` and `JWT_SECRET` (no default, boot fails). Same anti-pattern lower value: `recaptcha.secret-key: ${RECAPTCHA_SECRET_KEY:changeme-dev-only-stub-is-used}` (`auth-service.yml:127`).

## FINDING 3 (LOW): Booking-transfer public preview leaks both parties' emails

`BookingTransferController.java:94-110` — unauthenticated `GET /api/v1/booking-transfers/by-token/{token}` returns `fromCustomerEmail` and `toEmail`, despite comment claiming "no PII beyond names." Gated by 256-bit SecureRandom token, but token sits in URL path; `Referrer-Policy: strict-origin-when-cross-origin`. Recommend trimming emails from preview DTO.

## FINDING 4 (LOW / hygiene): Live `.env` with weak dev secrets in working tree

`.env` (and `.env.bak-crlf`) contain dev creds: `ADMIN_PASSWORD=Admin@123Local`, `CONFIG_SERVER_PASSWORD=config_secret`, `EUREKA_PASSWORD=eureka_secret`, `POSTGRES_PASSWORD=skbg_pass`, real `JWT_SECRET`. Dev values, but live `.env`. `PAYMENT_SIMULATION_ENABLED=true` here — harmless in dev, dangerous if reused for real env (and per Finding 1 the FATAL guard is inert).

## Controls verified SOUND (no action)
- Gateway identity trust: `JwtAuthenticationFilter` strips client-supplied `X-User-*`/`X-Authority-*` before re-deriving from signature-verified JWT (HS256 `verifyWith`, no alg:none), iss/aud checks, `../` traversal normalization, Redis session-revocation. Downstream `GatewayHeaderAuthFilter` trusts only gateway headers; backend ports NOT published to host (only gateway 8090 + frontend 3000) → gateway bypass + header spoof not reachable.
- Internal endpoints: `/internal/**` guarded by `InternalApiAuthFilter` (constant-time shared-secret) + `hasRole("SYSTEM")`, not routed through gateway.
- CSRF: double-submit cookie (SameSite=Strict) + Origin/Referer allow-list, constant-time compare.
- Payments: HMAC-SHA256 on all callbacks + Razorpay webhooks; server-side amount/FX re-validation vs booking balance; pessimistic-lock + DB-SUM over-refund guards; maker-checker approval above threshold; webhook dedup; binge tenancy checks on reads.
- SQL: all `@Query` parameterized — no injection. No `Runtime.exec`/deserialization sinks.
- Auth: BCrypt(12), SHA-256-hashed reset tokens, OTP attempt-limiting, MFA/TOTP, password history + HIBP checks, per-IP tiered rate limiting, OWASP security headers, file-upload path-traversal defenses (`MediaController`).
