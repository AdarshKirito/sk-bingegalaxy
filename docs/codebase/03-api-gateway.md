# 03 — `api-gateway` (Spring Cloud Gateway, :8080)

The single ingress for the whole platform. Reactive (WebFlux) Spring Cloud Gateway. Its job:
terminate the public contract, authenticate, protect against CSRF/abuse, inject trusted
identity headers, add security/observability headers, and route to services via Eureka.

Source root: `backend/api-gateway/src/main/java/com/skbingegalaxy/gateway/`

## Global filter order (lowest = earliest)

| Order | Filter | Job |
|---|---|---|
| −100 | `SecurityHeadersFilter` | attach OWASP headers (even on 401/429) |
| −3 | `CsrfProtectionFilter` | double-submit cookie + Origin pinning |
| −2 | `RateLimitFilter` | per-IP token bucket (Redis) |
| −1 | `JwtAuthenticationFilter` | validate JWT, strip+inject `X-User-*`, RBAC, delegation |
| 0 | `UserRateLimitFilter` | per-user limits on high-value writes |
| 1 | `MdcContextFilter` | propagate userId/bingeId/sentryTrace into MDC |
| LOWEST−1 | `ApiVersionHeaderFilter` | add `X-API-Version` to responses |

The ordering is deliberate: security headers attach first (so they're present even on
short-circuited rejections), CSRF rejects forged cross-site requests before any auth/limit work,
the coarse IP limit precedes JWT validation (can't trust `X-User-Id` yet), JWT validation then
establishes trusted identity, and only *after* that does the per-user limiter and MDC capture run.

---

## `ApiGatewayApplication.java`
13 lines: `@SpringBootApplication` + `@EnableDiscoveryClient` (registers with Eureka so
`lb://service` URIs resolve).

## `config/GatewayConfig.java`
A single `CorsWebFilter` bean. Allowed origins from `${app.cors.allowed-origins}`; methods
`GET/POST/PUT/PATCH/DELETE/OPTIONS`; **explicitly allowed headers** include `Authorization`,
`Content-Type`, `X-Binge-Id`, `X-Requested-With`, `X-XSRF-TOKEN`, and `Idempotency-Key`. The
comment notes the why: browsers won't send custom headers cross-origin unless they're in the
preflight `Access-Control-Allow-Headers` — so the Stripe-style `Idempotency-Key` and the CSRF
`X-XSRF-TOKEN` must be named here. `allowCredentials(true)` (cookies cross-origin),
`maxAge(3600)` (cache preflight 1h), applied to `/**`.

## `controller/CsrfTokenController.java`
`GET /api/v1/csrf` — the SPA's CSRF bootstrap. Calls `csrfFilter.mintToken()`, sets the
`XSRF-TOKEN` cookie via `buildCookie`, and **also returns the token in JSON** so the SPA can
cache it in memory. The header note: this path bypasses the route table and `CsrfProtectionFilter`
doesn't run for it — intentional, since `GET` is safe/idempotent.

## `controller/FallbackController.java`
`@RequestMapping("/fallback")` handling **all methods** — where the circuit breaker forwards a
request when a downstream is open/unavailable. Sets `503 SERVICE_UNAVAILABLE` and returns the
standard `{success:false, message, status:503}` envelope.

## `filter/JwtAuthenticationFilter.java` (order −1)
The identity core — documented in full in [ARCHITECTURE.md §5.1](../../ARCHITECTURE.md) and
the earlier deep-dive. In brief: unconditionally strips inbound `X-User-*`/`X-Authority-*`
(spoof-proofing, logs `auth.header.spoof.attempt`); extracts JWT from `Authorization` or the
httpOnly `token` cookie; validates HMAC-SHA256 (`verifyWith`, blocks `alg:none`, 30s skew, soft
iss/aud); checks the role against `{CUSTOMER,ADMIN,SUPER_ADMIN}`; enforces RBAC by URI-normalized
path segment (`admin`/`super-admin`); resolves Authority-Handover delegation (elevates effective
role to SUPER_ADMIN per-path when `delegatedScopes` covers the path's `SCOPE_MAP` entry and the
grant is unexpired); injects trusted `X-User-Id/Email/Role/Name/Phone/Phone-Country-Code` (+
`X-Authority-*` when delegated). `PUBLIC_PATHS` lists the unauthenticated routes (login/register/
google/forgot/reset/verify-otp/refresh/logout, availability dates/slots/event-types, booking
binges/event-types/add-ons/booked-slots/funnel, payment callback + razorpay webhooks, public
site-content, booking-transfer magic-link, `/actuator/health`).

## `filter/CsrfProtectionFilter.java` (order −3)
Double-submit-cookie + Origin/Referer pinning CSRF defense.
- **Strategy**: SPA fetches `/api/v1/csrf` → opaque `XSRF-TOKEN` cookie (non-httpOnly so the
  SPA can read it, `SameSite=Strict`, `Secure` in prod, 8h Max-Age refreshed on each safe call).
  Every state-changing request must echo the cookie in `X-XSRF-TOKEN`; `SameSite=Strict` means a
  cross-origin attacker can never read it. Origin/Referer matching the allow-list is the
  defense-in-depth second factor.
- **Safe methods** (GET/HEAD/OPTIONS) skip the check but get the cookie piggy-backed on the
  response (`ensureTokenCookie`) so the SPA's first GET seeds the token without an explicit call.
- **Exemptions**: webhook paths (`/payments/callback`, `/payments/webhooks/razorpay` — HMAC-auth,
  no browser origin); `BOOTSTRAP_AUTH_PATHS` (login/register/admin-login/google/forgot/reset/
  verify-otp/refresh — the SPA has no cookie yet; Origin pinning + rate limit + lockout cover
  them); `ANONYMOUS_POST_PATHS` (`/bookings/analytics/funnel` — fired by guests via `sendBeacon`
  with `credentials:'omit'`, can't carry the cookie by design).
- **Enforcement** for other state-changing requests: Origin must be allow-listed (`isOriginAllowed`
  checks `Origin` then falls back to parsing `Referer`'s scheme+authority), then cookie and header
  must both be present and **constant-time-equal** (`constantTimeEquals` — length check + XOR
  accumulate). Failures → 403 with `{errorCode, retryable:false}` and a `csrf.reject` WARN +
  `skbg_gateway_csrf_rejected_total` counter. `mintToken` = 32 SecureRandom bytes, URL-base64.

## `filter/RateLimitFilter.java` (order −2)
Distributed **per-IP** token bucket with a **three-tier** design:
- **Sensitive** (`/forgot-password`, `/reset-password`, `/verify-email`): 5/min — curbs
  enumeration & reset spam.
- **Credential auth** (login, admin-login, register, admin-register, google, verify-otp,
  change-password, change-email): 30/min — caps brute force.
- **Standard** (everything else, incl. logout/refresh/profile/admin reads/business APIs):
  100/min.
Caps overridable via `app.ratelimit.{standard,auth,sensitive}`. The comment cites industry
practice (Google/AWS/GitHub/Auth0): throttle credential endpoints hard per-IP, mitigate
post-auth abuse per-account — which is why logout/refresh/profile are *not* bucketed with login
(so a busy admin doesn't trip 429).
**Critical security note in code**: it keys **only on client IP, never `X-User-Id`**, because at
order −2 the JWT filter hasn't run yet — `X-User-Id` is still client-supplied and spoofable; an
attacker could set `X-User-Id=<victim>` to drain the victim's bucket (targeted DoS). Per-user
limits are deferred to `UserRateLimitFilter` (order 0, post-JWT).
**Redis-first** (`increment` + `expire` per-minute counter key, `ReactiveStringRedisTemplate`)
with a **local LRU fallback** (Bucket4j, max 10K entries) on Redis error (rate-limited fallback
logging via CAS). `resolveClientIp` trusts `X-Forwarded-For` (last entry) / `X-Real-IP` **only
when the direct peer is private/loopback** (i.e. behind our own ingress/nginx), else uses the
socket address — preventing header-spoofed IP rotation. 429 responses carry `Retry-After: 60`.

## `filter/UserRateLimitFilter.java` (order 0)
Per-**user** (authenticated) / per-IP (anonymous) limiter for a small set of high-value writes,
running *after* JWT validation so `X-User-Id` is trusted. Rule set (exact path+method):
- `POST /api/v1/bookings` → 10/min (booking-flood/seat-hoarding).
- `POST /api/v1/payments/initiate` → 15/min (allow retries, block gateway probing).
- `POST /api/v1/auth/forgot-password` → 5/15min (IP-keyed; enumeration/spam).
- `POST /api/v1/auth/verify-otp` → 10/15min (OTP brute-force).
- `PUT /api/v1/auth/change-email` → 5/15min (account-takeover vector).
Keying: `user:<id>:<bucket>` when authenticated, else `ip:<ip>:<bucket>` — so a user behind CGNAT
isn't sharing a bucket with strangers. **Booking & payment buckets are additionally binge-scoped**
(`:binge-<id>`, with `sanitizeBingeId` accepting only ≤20 digits) so hitting the limit at venue A
doesn't block venue B — the Airbnb/Booking.com multi-venue pattern. Same Redis-first + local-LRU
fallback. 429 responses include `Retry-After`, `X-RateLimit-Limit/Reset` (exact reset epoch
computed from the window quantization) and a human, per-bucket message.

## `filter/SecurityHeadersFilter.java` (order −100)
Injects the OWASP baseline on **every** response (incl. 4xx/5xx/preflight) via a `beforeCommit`
hook (so downstream filters/error handlers can't strip them) using `putIfAbsent`:
`Strict-Transport-Security` (1y, includeSubDomains, preload), `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY` (frontend is never embedded), `Referrer-Policy:
strict-origin-when-cross-origin`, a `Permissions-Policy` denying camera/mic/geo/payment/usb/
sensors, a locked-down `Content-Security-Policy` (`default-src 'self'; frame-ancestors 'none';
base-uri 'self'; form-action 'self'` — a safety net for API responses which should never render
HTML), the three `Cross-Origin-*` isolation headers, and legacy `X-XSS-Protection: 0`. Order
−100 ensures the hook is attached before any rejection short-circuits.

## `filter/MdcContextFilter.java` (order 1)
Propagates `userId`, `bingeId`, and `sentryTraceId` into MDC for structured JSON logs. Because
MDC is ThreadLocal and reactive pipelines hop threads, it uses **both** Reactor `contextWrite`
(survives async boundaries) and `doFirst`/`doFinally` (populates/clears the current thread).
Reads `X-User-Id`/`X-Binge-Id` (falls back to the `bingeId` query param), and the frontend-set
`X-Sentry-Trace-Id` — which it also **forwards downstream** (via `headers(h -> h.set(...))`, set
not append, so services see exactly one value) so a Sentry frontend error correlates to the
backend Zipkin trace without manual log search. Order 1 (after JWT −1 and UserRateLimit 0) so
both identity headers are populated before capture.

## `filter/ApiVersionHeaderFilter.java` (order LOWEST−1)
Adds `X-API-Version: v1` to `/api/v1/*` responses (runs in the response phase via
`then(Mono.fromRunnable)`). Carries dormant `V1_DEPRECATED`/`V1_SUNSET_DATE` flags so that when
`/api/v2` lands, v1 responses can emit `Deprecation`/`Sunset` headers for graceful client
migration.

## Resources
- `application.yml` — local bootstrap (graceful shutdown, config-server import, Redis fallback,
  Eureka). The route table lives in config-server's `api-gateway.yml`.
- `logback-spring.xml` — wires the `common-lib` PCI-masking converter/provider.

## Tests (`src/test/`)
`FallbackControllerTest`, `ApiVersionHeaderFilterTest`, `JwtAuthenticationFilterTest`,
`RateLimitFilterTest`, `SecurityHeadersFilterTest`, `UserRateLimitFilterTest` — covering fallback
shape, version headers, JWT validation/RBAC/delegation, per-IP and per-user limiting, and the
security-header set.
