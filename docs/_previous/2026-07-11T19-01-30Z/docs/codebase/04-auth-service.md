# 04 — `auth-service` (:8081, `auth_db`)

Identity and access. Registration, login (customer + admin), JWT issuance/refresh/revocation,
TOTP MFA + WebAuthn scaffolding, multi-device sessions, password policy (history + breach
check), Google OAuth, email verification, Authority Handover (delegation grants + resource
locks), GDPR/DPDP erasure, celebration reminders, and the platform CMS (`site_content`).

Source root: `backend/auth-service/src/main/java/com/skbingegalaxy/auth/`

The booking-flow deep dive in [ARCHITECTURE.md §5.2/§14](../../ARCHITECTURE.md) and the
earlier `login` trace cover the hot paths; this doc walks every file.

---

## entity/

### `User.java`
The central account row (`users`, unique `email` + `phone`). Beyond identity (name/email/phone/
`phoneCountryCode`) and the postal address block, it carries:
- **Preferences/celebration**: `preferredExperience`, `vibePreference`, `reminderLeadDays`(14),
  `birthday/anniversary Month/Day`, the `...ReminderSentYear` dedup guards, `notificationChannel`,
  `receivesOffers`, `weekendAlerts`, `conciergeSupport`.
- **Auth**: `password` (BCrypt), `role`, `active`.
- **Lockout**: `failedLoginAttempts`, `lockedUntil` + behavior methods `isAccountLocked()`
  (UTC compare), `incrementFailedAttempts()`, `resetFailedAttempts()`, `lockAccount(minutes)`.
- **Email verification (V7)**: `emailVerified`, `emailVerifiedAt`.
- **MFA/TOTP (V7)**: `mfaEnabled`, `mfaSecret` (Base32), `mfaEnrolledAt`,
  `mfaRecoveryCodesHash` (comma-separated SHA-256 hashes of single-use codes).
- **WebAuthn/FIDO2 (V15)**: `webauthnCredentialId/PublicKeyCose/EnrolledAt/LastUsedAt/Aaguid` —
  the comment notes it's required for SUPER_ADMIN, phishing-resistant because the authenticator
  signs an origin-bound challenge (unlike TOTP which can be proxied by a MITM).
- **DPDP/GDPR (V14)**: `consentGivenAt`, `consentMarketing`, `deletionRequestedAt`, `deletedAt`
  (soft-delete), `anonymizedAt` (irreversible PII scrub), `dataRetentionExpiresAt`.

### `UserSession.java`
`user_session` — one row per device/login. `refreshJti` (unique), `ipAddress`, `userAgent`,
`deviceLabel`, `createdAt`, `lastSeenAt`, `expiresAt`, and revoke fields (`revokedAt/By/Reason`).
Methods: `isActive()`, `touch(newJti, newExpiresAt)` (refresh rotation updates the JTI **in
place** rather than spawning a new row — so a device stays one session), `revoke(byUserId,
reason)`.

### `AuthorityGrant.java`
The delegation record (`authority_grant`). `granteeUserId`, `scopes` (a `Set<AuthorityScope>`
via an element-collection), `grantedBy`, `reason`, `grantedAt`, `expiresAt`, revoke fields.
`isActive()` = not revoked and not expired.

### `ResourceLock.java`
Cooperative locks for admin surfaces (`resource_type` + `resource_id`, `lockedBy`,
`lockedByName`, `reason`, `lockedAt`) — lets the UI show "X is editing this" and prevents a
delegated admin from stealing a super-admin's in-progress edit.

### `RevokedToken.java`
`revoked_token` — JWT denylist by `jti` (+ `userId`, `tokenType`, `expiresAt`, `revokedAt`).
Rows are purged once past `expiresAt` (no point denying an already-expired token).

### `EmailVerificationToken.java`
`token_hash` (unique, hashed — the plaintext is emailed, never stored), a 12-char `otp`,
`otpAttempts` counter, `expiresAt`, `used`. Methods: `isExpired()`, `isAttemptsExhausted(max)`,
`incrementAttempts()`.

### `PasswordResetToken.java`
Similar shape for password reset: unique `token`, 6-char `otp`, FK to `User`, `expiresAt`,
`used`, `otpAttempts` with `MAX_OTP_ATTEMPTS=5`, `isExpired()`, `isOtpAttemptsExhausted()`.

### `PasswordHistoryEntry.java`
`password_history` — `userId` + `passwordHash` + `createdAt`; the rolling window of recent
hashes used to block password reuse.

### `AuthAuditLog.java`
`auth_audit_log` — `eventType`, `actorId/Role`, `targetId/Email`, `ipAddress`, `userAgent`,
`requestId`, `success`, `failureReason`, free-text `details`, `createdAt`. Every auth-sensitive
action lands here.

### `SiteContent.java`
`site_content` — CMS document keyed by `slug`, holding `contentJson` (TEXT), `updatedAt`,
`updatedBy`. Serves the platform-wide home/account pages.

---

## security/

### `JwtProvider.java`
Mints and validates JWTs (HMAC-SHA256, jjwt).
- **`@PostConstruct validateJwtSecret()`** — fail-fast at boot: secret must be set and ≥32 bytes
  (256-bit). Enforces a **rotation policy** via `app.jwt.secret-issued-at`: hard-fails if older
  than `secret-max-age-days` (default 365), WARNs past 90 days. A missing issued-at skips the
  check (dev/test).
- **`buildToken`** — claims: `email`, `role`, `firstName`, `phone`, `phoneCountryCode`,
  `token_type` (access|refresh). Sets `jti` (random UUID), `subject` = user id, `issuer`,
  `audience`, issued/expiry. When `delegatedScopes` is non-empty it adds the **comma-joined,
  sorted** `delegatedScopes` claim + `delegationExpiresAt` (epoch ms) — and the doc comment
  stresses the **native `role` claim is never modified** (the gateway rewrites `X-User-Role`
  per-path; the token stays truthful).
- **`parseToken`** — verifies signature, then **soft** iss/aud (reject only if present-and-wrong,
  so pre-rollout tokens still validate).
- `validateToken`, `validateRefreshToken` (asserts `token_type == "refresh"` so an access token
  can't be replayed at `/refresh`), `getUserIdFromToken`, `getJtiFromToken`, `getExpiryFromToken`,
  `getTokenType`.

### `SecurityConfig.java`
Stateless chain: CSRF disabled, `STATELESS` sessions, `InternalApiAuthFilter` +
`GatewayHeaderAuthFilter` before the username/password filter. Authorization ladder (most
specific first): `/authority/internal/**` → SYSTEM; `/privacy/admin/**` → SUPER_ADMIN;
`/privacy/**` → authenticated; `/admin/login` → permitAll; a long list of `/admin/{register,
user,users,bulk-delete,admins,sessions,audit-log,super-admin}/**` → **SUPER_ADMIN**; other
`/admin/**` → ADMIN/SUPER_ADMIN; catch-all `/api/v1/auth/**` → permitAll (because the bootstrap
auth endpoints must be open — finer control is the rules above); site-content public→permitAll,
admin→SUPER_ADMIN; actuator health permitAll, rest SYSTEM; swagger ADMIN/SUPER_ADMIN; else
authenticated. The ordering comment is explicit: the `/auth/**` permitAll is a catch-all so the
SYSTEM/SUPER_ADMIN rules above it do the real gating.

---

## service/

### `AuthService.java` (1,565 lines) — the orchestrator
`login` (constant-time, CAPTCHA gate, lockout, MFA challenge), `adminLogin` (+ mandatory
SUPER_ADMIN MFA), `register`, `refreshToken` (revocation + session-revoke checks), `logout`,
`forgotPassword`/`resetPassword` (OTP), `changePassword` (history + pwned check), `changeEmail`
(verified), profile read/update/preferences, `completeProfile`, admin customer CRUD, bulk
ban/unban/delete, `promoteToSuperAdmin` (blocks without MFA), MFA enroll/confirm/disable, and
the auth-response builder that mints access+refresh and opens a session. The `login` path is
traced line-by-line in [ARCHITECTURE.md §8.6](../../ARCHITECTURE.md).

### `TotpService.java`
RFC-6238 TOTP (HMAC-SHA1, 160-bit Base32 secrets — compatible with Google Authenticator/Authy/
1Password). `beginEnrollment` (secret + otpauth URI + recovery codes), `confirmEnrollment`
(verify a live code + echo-back recovery codes), `disable` (requires a valid code),
`verifyCodeOrRecovery` (accepts a TOTP code **or** a single-use recovery code), `verifyCode`,
`regenerateRecoveryCodes`.

### `UserSessionService.java`
Session lifecycle. `recordLogin` (new session row), `rotate(oldJti→newJti)` (refresh rotation
updates the JTI on the existing row), `revoke(sessionId)`, `revokeAllForUser` (force "log out
everywhere" — the lever Authority-grant issuance/revocation pulls), `listActiveForUser`,
`listAllActive` (super-admin overview), `findById`, `findByJti`, `purgeExpired`.

### `TokenRevocationService.java`
JWT denylist. `revoke(token)` (extracts JTI/expiry and persists), `isRevoked(jti)`,
`revokeByJti`, `purgeExpired`. The reason: a logged-out refresh token has a valid signature, so
signature alone can't reject it — the JTI denylist does, until the row self-expires.

### `EmailVerificationService.java`
`issue(user)` (mint token+OTP, email it), `verifyWithOtp(email, otp)`, `verifyWithToken(token)`
(magic-link), `isVerificationRequired()` (feature flag).

### `PasswordHistoryService.java`
`isRecentlyUsed(userId, candidate)` — O(keep) BCrypt matches against the last N hashes
(default 5, `app.security.password-history.keep`); deliberately slow to throttle probing.
`record(userId, newHash)` — append + trim to the window.

### `PwnedPasswordService.java`
HaveIBeenPwned **k-anonymity** breach check: SHA-1 the candidate, send only the first 5 hex
chars to the range API, match the suffix locally — the full hash never leaves the service.
`isPasswordPwned(plain)`.

### `CaptchaValidationService.java` (+ impls)
Interface with two impls: `RecaptchaValidationService` (production reCAPTCHA v3, score threshold
`app.recaptcha.score-threshold` default 0.5) and `StubCaptchaValidationService` (dev/test —
accepts any non-blank token, wired only when the profile is not `production`).

### `UserAnonymizationService.java`
DPDP/GDPR right-to-erasure. PII is **never hard-deleted** (bookings/payments reference `user_id`
and must be retained 7y for Indian GST); instead `anonymizeUser` replaces PII with placeholders.
`requestDeletion` (soft-delete + schedule), `anonymizeUser` (irreversible scrub),
`anonymizePendingDeletions` (the scheduled sweep past `dataRetentionExpiresAt`).

### `CelebrationReminderService.java`
`runDailyCelebrationReminders` / `processCelebrationReminders(today)` — birthday/anniversary
outreach, using the `...ReminderSentYear` guards on `User` to fire once per year and
`reminderLeadDays` to send ahead of the date.

### `PasswordResetTokenCleanupService.java`
`purgeExpiredResetTokens` — daily physical delete of used/expired reset tokens to stop table
bloat (B-tree growth, slower inserts, autovacuum cost).

### `AuthAuditService.java`
Writes `auth_audit_log`. **Never throws to the caller** and runs in `REQUIRES_NEW` so an audit
failure can't block a login/admin action and a business rollback can't lose the audit row.
`record(...)`, `success(...)`, `failure(...)`, `search(eventType, actorId, targetId, pageable)`.
Defines the `EventType` enum (LOGIN_SUCCESS/FAILED/LOCKED/MFA_CHALLENGED/MFA_FAILED,
MFA_ENROLLED, AUTHORITY_GRANTED, …).

### `AuthorityService.java` (411 lines)
Delegation + resource locks. `createGrant`/`revokeGrant` (traced in
[ARCHITECTURE.md §8](../../ARCHITECTURE.md) — super-admin-only, no self-grant, time-boxed,
session-revoke on change), `listAllGrants`/`listActiveGrants`/`listGrantsForUser`,
`getEffectiveAuthority` (computes `delegated` + union of scopes for JWT stamping),
`getActiveScopesForUser`, `getEarliestGrantExpiryEpochMillis` (feeds `delegationExpiresAt`),
and the resource-lock API (`createLock`/`releaseLock`/`findLock`/`listLocksByType`/
`listAllLocks`).

---

## config/

### `AdminSeeder.java`
`CommandLineRunner` — on first boot, creates the initial admin from `app.admin.email`/password
(auto-generated, see `.env`) if no admin exists. Idempotent.

### `GoogleAuthConfig.java`
Exposes a `GoogleIdTokenVerifier` bean (from `app.google.client-id`) used by the Google-OAuth
login path to verify the Google ID token.

### `ShedLockConfig.java`
`@EnableSchedulerLock(defaultLockAtMostFor = "10m")` + a JDBC `LockProvider` over the datasource
— so the celebration/cleanup schedulers run on exactly one replica.

### `StartupWarmupRunner.java`
Pre-warms cold-start hot paths (JIT, connection pool, one-time BCrypt class load) with a synthetic
workload at boot, so the first real request sees steady-state latency — the Netflix/Spotify/Uber
warm-up pattern.

### `OpenApiConfig.java`
Springdoc bean (title/version/security scheme) for swagger.

---

## health/

### `DatabaseHealthIndicator.java`
Custom actuator health indicator extending `AbstractHealthIndicator` — surfaces datasource
reachability in `/actuator/health`.

---

## controller/

### `AuthController.java` (533 lines)
`/api/v1/auth`. The full public + customer + admin surface (see [ARCHITECTURE.md §6](../../ARCHITECTURE.md)):
register/login/admin-login/google/refresh/logout, forgot/reset/verify-otp, profile read/update/
change-password/change-email/preferences/complete-profile, support-contact, MFA enroll/confirm/
disable, email verify/resend, sessions list/delete/revoke-others, and the admin customer/admins/
bulk/sessions/audit-log management endpoints. Sets the JWT as an httpOnly cookie on login.

### `AuthorityController.java`
`/api/v1/auth/authority`. `GET /me` (effective authority), grants CRUD (`POST/DELETE/GET
/grants`, `GET /grants/by-user/{id}`), resource locks (`POST/DELETE /locks`, `GET /locks`,
`/locks/lookup`, and the SYSTEM-only `/internal/locks/lookup` for service-to-service checks).

### `SiteContentController.java`
`/api/v1/site-content`. `GET /public/{slug}` (open, serves the CMS doc) and `PUT /admin/{slug}`
(SUPER_ADMIN, updates it).

### `UserPrivacyController.java`
`/api/v1/auth/privacy`. `DELETE /me` (self right-to-erasure request) and `POST
/admin/anonymize/{userId}` (SUPER_ADMIN-forced anonymization).

---

## repository/
Spring Data JPA interfaces: `UserRepository` (with the atomic `incrementFailedLoginAttempts`
`@Modifying` query that closes the lockout race), `UserSessionRepository`,
`AuthorityGrantRepository` (with the idempotent `revoke` update), `ResourceLockRepository`,
`RevokedTokenRepository`, `EmailVerificationTokenRepository`, `PasswordResetTokenRepository`,
`PasswordHistoryRepository`, `AuthAuditLogRepository`, `SiteContentRepository`.

## dto/
~30 request/response records: `RegisterRequest`, `LoginRequest`, `AuthResponse` (tokens + user +
`mfaRequired`), `RefreshTokenRequest`, `ForgotPasswordRequest`, `ResetPasswordRequest`,
`VerifyOtpRequest`, `ChangePasswordRequest`, `ChangeEmailRequest`/`VerifyEmailChangeRequest`,
`CompleteProfileRequest`, `SelfUpdateProfileRequest`, `UpdateAccountPreferencesRequest`,
`GoogleLoginRequest`, `MfaCodeRequest`/`MfaConfirmRequest`/`MfaEnrollmentResponse`, `UserDto`,
`UserSessionDto`, `AdminCreateCustomerRequest`/`UpdateCustomerRequest`, `AddressPayload`,
`CreateAuthorityGrantRequest`/`AuthorityGrantDto`/`EffectiveAuthorityDto`,
`CreateResourceLockRequest`/`ResourceLockDto`, `AuthAuditLogDto`, `AdminContactDto`/
`SupportContactDto`, `VerifyEmailRequest`. Most are validated (`@NotBlank`, `@Email`, `@Size`).

## Tests (`src/test/`)
`AuthControllerTest`, `JwtProviderTest`, `AuthServiceTest`, `CelebrationReminderServiceTest`,
`PasswordResetTokenCleanupServiceTest`, `TokenRevocationServiceTest`, and a `common.security`
`GatewayHeaderAuthFilterTest` — covering controller wiring, token mint/validate/rotation, the
login/lockout/MFA flows, reminder dedup, token cleanup, and revocation.
