package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.dto.*;
import com.skbingegalaxy.auth.entity.PasswordResetToken;
import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.PasswordResetTokenRepository;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.auth.security.JwtProvider;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.enums.UserRole;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.CaptchaRequiredException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Month;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final DateTimeFormatter MONTH_FORMATTER = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL)
        .toFormatter(Locale.ENGLISH);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final TokenRevocationService tokenRevocationService;
    private final AuthAuditService auditService;
    private final UserSessionService sessionService;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailVerificationService emailVerificationService;
    private final TotpService totpService;
    private final AuthorityService authorityService;
    private final CaptchaValidationService captchaValidationService;
    private final PwnedPasswordService pwnedPasswordService;

    @Value("${app.otp.expiration-minutes:10}")
    private int otpExpirationMinutes;

    @Value("${app.otp.length:6}")
    private int otpLength;

    /**
     * How many logins an admin-issued temporary password is valid for before the
     * customer must use "Forgot password". Default 2 (the front-desk admin
     * "consumed" the first of three when they created and read out the password).
     */
    @Value("${app.temp-password.max-logins:2}")
    private int tempPasswordMaxLogins;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;
    /** After this many failures the client must present a solved CAPTCHA before we check credentials. */
    private static final int CAPTCHA_THRESHOLD = 3;

    /** Pre-computed BCrypt hash used in constant-time login to prevent timing oracles. */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        this.dummyHash = passwordEncoder.encode("timing-safety-dummy-" + java.util.UUID.randomUUID());
    }

    /** Same OAuth Web client id the frontend uses; blank means Google sign-in is not configured. */
    @Value("${app.google.client-id:}")
    private String googleClientId;

    @Value("${app.admin.email:admin@skbingegalaxy.com}")
    private String supportEmail;

    @Value("${app.admin.phone:9876543210}")
    private String supportPhone;

    @Value("${app.admin.phoneCountryCode:+91}")
    private String supportPhoneCountryCode;

    @Value("${app.support.hours:9:00 AM to 10:00 PM IST}")
    private String supportHours;

    // ── Google OAuth login ───────────────────────────────────
    @Transactional
    public AuthResponse googleLogin(String credential) {
        // Fail clearly when the server hasn't been given a Google client id — every
        // token would otherwise fail audience verification and surface as a vague
        // "Google login failed". Tell the user to use email sign-in instead.
        if (googleClientId == null || googleClientId.isBlank()) {
            log.warn("Rejected Google login attempt — Google sign-in is not configured (app.google.client-id is empty)");
            throw new BusinessException(
                "Google sign-in isn't available on this server. Please sign in with your email and password.",
                HttpStatus.UNAUTHORIZED);
        }
        try {
            GoogleIdToken idToken = googleIdTokenVerifier.verify(credential);
            if (idToken == null) {
                throw new BusinessException("Invalid Google token", HttpStatus.UNAUTHORIZED);
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            if (email == null) {
                throw new BusinessException("Google account has no email address", HttpStatus.UNAUTHORIZED);
            }
            email = email.toLowerCase();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            if (firstName == null) firstName = "User";
            if (lastName == null) lastName = "";

            var existingUser = userRepository.findByEmail(email);
            if (existingUser.isPresent()) {
                User user = existingUser.get();
                if (!user.isActive()) {
                    throw new BusinessException("Account is deactivated. Contact support.", HttpStatus.FORBIDDEN);
                }
                log.info("Google login (existing user): {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(email));
                return buildAuthResponse(user);
            }

            // New user via Google — create account
            User user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phone(null)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(UserRole.CUSTOMER)
                .active(true)
                .build();

            user = userRepository.save(user);
            log.info("Google login (new user created): {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(email));

            sendNotification(user, "WELCOME", Map.of("name", user.getFirstName()));

            return buildAuthResponse(user);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google login failed", e);
            throw new BusinessException("Google login failed. Please try again.", HttpStatus.UNAUTHORIZED);
        }
    }

    // ── Register ─────────────────────────────────────────────
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User", "email", email);
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        // Breach password check: reject passwords that appear in known data breaches
        // (HaveIBeenPwned k-anonymity API — the plaintext password never leaves the JVM).
        // Disabled in dev/test via app.security.check-pwned-passwords=false.
        if (pwnedPasswordService.isPasswordPwned(request.getPassword())) {
            throw new BusinessException(
                "This password has appeared in a data breach and cannot be used. "
                + "Please choose a unique password that you haven't used elsewhere.",
                HttpStatus.BAD_REQUEST);
        }

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(email)
            .phone(request.getPhone())
            .phoneCountryCode(request.getPhoneCountryCode())
            .addressLine1(trimToNull(request.getAddressLine1()))
            .addressLine2(trimToNull(request.getAddressLine2()))
            .city(trimToNull(request.getCity()))
            .state(trimToNull(request.getState()))
            .country(trimToNull(request.getCountry()))
            .postalCode(trimToNull(request.getPostalCode()))
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.CUSTOMER)
            .active(true)
            // DPDP: record explicit consent at registration time
            .consentGivenAt(java.time.LocalDateTime.now(ZoneOffset.UTC))
            .consentMarketing(request.isConsentMarketing())
            .build();

        user = userRepository.save(user);
        log.info("Customer registered: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));

        // Record initial password so future change-password attempts can check history.
        passwordHistoryService.record(user.getId(), user.getPassword());
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        // Fire email verification (best-effort — welcome email is sent separately by the
        // notification service's WELCOME template if configured).
        try { emailVerificationService.issue(user); } catch (Exception ex) {
            log.warn("Failed to issue email verification for userId={}: {}", user.getId(), ex.getMessage());
        }

        // Fire notification event
        sendNotification(user, "WELCOME", Map.of(
            "name", user.getFirstName()
        ));

        auditService.success(AuthAuditService.EventType.REGISTER, user.getId(), user.getRole(),
            user.getId(), user.getEmail(), null);
        return buildAuthResponse(user);
    }

    // ── Customer login ───────────────────────────────────────
    // noRollbackFor BusinessException: a failed login throws (bad creds / CAPTCHA / lockout)
    // to set the HTTP status, but the failed-attempt increment + account lock + audit rows
    // MUST persist. With the default rollback-on-RuntimeException, every throw would undo the
    // increment, so the counter never grows and lockout/CAPTCHA would never trigger across
    // requests (brute-force protection silently dead). CaptchaRequiredException extends
    // BusinessException, so it is covered too.
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest request) {
        // Constant-time login: always perform password check to prevent email enumeration via timing
        User user = userRepository.findByEmail(request.getEmail().toLowerCase()).orElse(null);
        String storedHash = user != null ? user.getPassword() : this.dummyHash;

        // CAPTCHA gate: if the account has accumulated enough failures, require a solved CAPTCHA
        // before we even attempt credential verification. Done before password check to prevent
        // attackers from burning attempts without proving they are human.
        if (user != null && user.getFailedLoginAttempts() >= CAPTCHA_THRESHOLD) {
            if (request.getCaptchaToken() == null || request.getCaptchaToken().isBlank()
                    || !captchaValidationService.validate(request.getCaptchaToken())) {
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "CAPTCHA_REQUIRED");
                throw new CaptchaRequiredException();
            }
        }

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (user == null) {
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, null, null, null, request.getEmail(), "UNKNOWN_EMAIL");
            throw new BusinessException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }
        if (!user.isActive()) {
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "ACCOUNT_DEACTIVATED");
            throw new BusinessException("Account is deactivated. Contact support.", HttpStatus.FORBIDDEN);
        }
        if (user.isAccountLocked()) {
            auditService.failure(AuthAuditService.EventType.LOGIN_LOCKED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "ACCOUNT_LOCKED");
            throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!passwordMatches) {
            // Atomic DB-level increment prevents race condition where concurrent failed logins
            // both read the same count and lockout triggers one attempt late
            userRepository.incrementFailedLoginAttempts(user.getId());
            user = userRepository.findById(user.getId()).orElse(user); // Re-read after atomic increment
            if (user.getFailedLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                user.lockAccount(LOCKOUT_MINUTES);
                log.warn("Account locked for user {} after {} failed attempts", user.getId(), MAX_LOGIN_ATTEMPTS);
                userRepository.save(user);
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
                throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
            }
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
            // Signal CAPTCHA requirement once threshold is crossed
            if (user.getFailedLoginAttempts() >= CAPTCHA_THRESHOLD) {
                throw new CaptchaRequiredException();
            }
            throw new BusinessException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        // MFA gate (opt-in per user)
        if (user.isMfaEnabled()) {
            if (request.getMfaCode() == null || request.getMfaCode().isBlank()) {
                auditService.success(AuthAuditService.EventType.LOGIN_MFA_CHALLENGED, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
                return buildMfaChallenge(user);
            }
            if (!totpService.verifyCodeOrRecovery(user, request.getMfaCode())) {
                auditService.failure(AuthAuditService.EventType.LOGIN_MFA_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_TOTP_CODE");
                throw new BusinessException("Invalid verification code", HttpStatus.UNAUTHORIZED);
            }
        }

        user.resetFailedAttempts();

        // ── Temporary-password gate ──────────────────────────────────────────
        // Admin-issued temp passwords are valid for a limited number of logins,
        // and every such login forces a password change. Once the allowance is
        // spent the temp password is dead and the customer must use Forgot-password.
        boolean mustChange = false;
        if (user.isMustChangePassword()) {
            Integer remaining = user.getTempPasswordLoginsRemaining();
            if (remaining != null && remaining <= 0) {
                userRepository.save(user); // persist the failed-attempt reset
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(),
                    user.getId(), user.getEmail(), "TEMP_PASSWORD_EXPIRED");
                throw new BusinessException(
                    "Your temporary password has expired. Please use \"Forgot password\" to set a new one.",
                    HttpStatus.UNAUTHORIZED);
            }
            if (remaining != null) {
                user.setTempPasswordLoginsRemaining(remaining - 1);
            }
            mustChange = true;
        }

        userRepository.save(user);
        log.info("Customer login: userId={}{}", user.getId(), mustChange ? " (temp password — change required)" : "");
        auditService.success(AuthAuditService.EventType.LOGIN_SUCCESS, user.getId(), user.getRole(), user.getId(), user.getEmail(),
            mustChange ? "temporary password" : null);
        AuthResponse response = buildAuthResponse(user);
        response.setMustChangePassword(mustChange);
        return response;
    }

    // ── Refresh token ───────────────────────────────────────
    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtProvider.validateRefreshToken(refreshToken)) {
            throw new BusinessException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }
        String jti;
        try {
            jti = jwtProvider.getJtiFromToken(refreshToken);
        } catch (Exception ex) {
            throw new BusinessException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }
        if (tokenRevocationService.isRevoked(jti)) {
            log.warn("Refresh attempt with revoked jti={}", jti);
            throw new BusinessException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }
        // Reject refresh if the session behind this JTI was force-revoked (but not yet purged)
        var existingSession = sessionService.findByJti(jti);
        if (existingSession.isPresent() && existingSession.get().getRevokedAt() != null) {
            log.warn("Refresh attempt with revoked session jti={}", jti);
            throw new BusinessException("Session revoked. Please sign in again.", HttpStatus.UNAUTHORIZED);
        }
        Long userId = jwtProvider.getUserIdFromToken(refreshToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.UNAUTHORIZED));
        if (!user.isActive()) {
            throw new BusinessException("Account is deactivated", HttpStatus.FORBIDDEN);
        }
        // Rotate: revoke the presented refresh token so it can't be reused.
        tokenRevocationService.revoke(refreshToken);

        // Mint fresh tokens and rotate the existing session row (so each device keeps a
        // stable session id across refreshes). Fall back to creating a new session if
        // no existing row was found (e.g. legacy token from before sessions shipped).
        TokenPair pair = mintTokenPair(user);
        String newAccess = pair.access;
        String newRefresh = pair.refresh;
        try {
            String newJti = jwtProvider.getJtiFromToken(newRefresh);
            java.time.LocalDateTime newExp = jwtProvider.getExpiryFromToken(newRefresh);
            if (newJti != null && newExp != null) {
                if (sessionService.rotate(jti, newJti, newExp).isEmpty()) {
                    sessionService.recordLogin(user.getId(), newJti, newExp);
                }
            }
        } catch (Exception ex) {
            log.warn("Session rotate failed for userId={}: {}", user.getId(), ex.getMessage());
        }
        log.info("Token refreshed for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        auditService.success(AuthAuditService.EventType.TOKEN_REFRESHED, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
        return AuthResponse.builder()
            .token(newAccess)
            .refreshToken(newRefresh)
            .user(toDto(user))
            .build();
    }

    // ── Admin login ──────────────────────────────────────────
    // noRollbackFor BusinessException — see login(): the failed-attempt increment + lockout
    // must survive the auth-failure throw, otherwise brute-force protection never engages.
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse adminLogin(LoginRequest request) {
        // Constant-time login: always perform password check to prevent email enumeration via timing
        User user = userRepository.findByEmail(request.getEmail().toLowerCase()).orElse(null);
        String storedHash = user != null ? user.getPassword() : this.dummyHash;

        // CAPTCHA gate for admin login (same threshold as customer login)
        if (user != null && (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN)
                && user.getFailedLoginAttempts() >= CAPTCHA_THRESHOLD) {
            if (request.getCaptchaToken() == null || request.getCaptchaToken().isBlank()
                    || !captchaValidationService.validate(request.getCaptchaToken())) {
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "CAPTCHA_REQUIRED");
                throw new CaptchaRequiredException();
            }
        }

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (user == null || (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN)) {
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED,
                user == null ? null : user.getId(), user == null ? null : user.getRole(),
                user == null ? null : user.getId(), request.getEmail(), "NOT_AN_ADMIN");
            throw new BusinessException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        if (!user.isActive()) {
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "ACCOUNT_DEACTIVATED");
            throw new BusinessException("Account is deactivated", HttpStatus.FORBIDDEN);
        }
        if (user.isAccountLocked()) {
            auditService.failure(AuthAuditService.EventType.LOGIN_LOCKED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "ACCOUNT_LOCKED");
            throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!passwordMatches) {
            userRepository.incrementFailedLoginAttempts(user.getId());
            user = userRepository.findById(user.getId()).orElse(user);
            if (user.getFailedLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                user.lockAccount(LOCKOUT_MINUTES);
                log.warn("Admin account locked for user {} after {} failed attempts", user.getId(), MAX_LOGIN_ATTEMPTS);
                userRepository.save(user);
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
                throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
            }
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
            if (user.getFailedLoginAttempts() >= CAPTCHA_THRESHOLD) {
                throw new CaptchaRequiredException();
            }
            throw new BusinessException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        // SUPER_ADMIN accounts must always have MFA enrolled. This is enforced at promotion time
        // (promoteToSuperAdmin blocks promotion without MFA), but we double-check here as a
        // defence-in-depth measure against out-of-band DB modifications.
        if (user.getRole() == UserRole.SUPER_ADMIN && !user.isMfaEnabled()) {
            log.warn("SUPER_ADMIN login blocked: MFA not enrolled for userId={}", user.getId());
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "SUPER_ADMIN_MFA_NOT_ENROLLED");
            throw new BusinessException(
                "Super admin accounts require MFA. Please contact another super admin to enroll TOTP before logging in.",
                HttpStatus.FORBIDDEN);
        }

        // FIDO2/WebAuthn gate: warn if SUPER_ADMIN does not have a hardware key enrolled.
        // When webauthnRequired=true (production), block login entirely — TOTP alone is
        // insufficient for accounts with full-platform authority (phishing-resistant auth required).
        // webauthnRequired defaults to false to avoid blocking existing setups on upgrade;
        // set SUPER_ADMIN_REQUIRE_WEBAUTHN=true once keys are provisioned.
        if (user.getRole() == UserRole.SUPER_ADMIN && user.getWebauthnEnrolledAt() == null) {
            boolean webauthnRequired = Boolean.parseBoolean(
                System.getenv().getOrDefault("SUPER_ADMIN_REQUIRE_WEBAUTHN", "false"));
            if (webauthnRequired) {
                log.warn("SUPER_ADMIN login blocked: WebAuthn not enrolled for userId={}", user.getId());
                auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "SUPER_ADMIN_WEBAUTHN_NOT_ENROLLED");
                throw new BusinessException(
                    "Super admin accounts require a hardware security key (FIDO2/WebAuthn). "
                    + "Please enroll a security key before logging in. Contact your security team.",
                    HttpStatus.FORBIDDEN);
            } else {
                log.warn("SECURITY: SUPER_ADMIN userId={} logged in without a WebAuthn hardware key. "
                    + "Set SUPER_ADMIN_REQUIRE_WEBAUTHN=true after provisioning keys.", user.getId());
            }
        }

        // MFA is enforced for SUPER_ADMIN (above) and opt-in for ADMIN
        if (user.isMfaEnabled()) {
            if (request.getMfaCode() == null || request.getMfaCode().isBlank()) {
                auditService.success(AuthAuditService.EventType.LOGIN_MFA_CHALLENGED, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
                return buildMfaChallenge(user);
            }
            if (!totpService.verifyCodeOrRecovery(user, request.getMfaCode())) {
                auditService.failure(AuthAuditService.EventType.LOGIN_MFA_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_TOTP_CODE");
                throw new BusinessException("Invalid verification code", HttpStatus.UNAUTHORIZED);
            }
        }

        user.resetFailedAttempts();
        userRepository.save(user);
        log.info("Admin login: userId={}", user.getId());
        auditService.success(AuthAuditService.EventType.LOGIN_SUCCESS, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
        return buildAuthResponse(user);
    }

    // ── Forgot password ──────────────────────────────────────
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        var optionalUser = userRepository.findByEmail(request.getEmail().toLowerCase());
        if (optionalUser.isEmpty()) {
            // Return silently to prevent email enumeration
            log.debug("Forgot-password request for non-existent email: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(request.getEmail()));
            return;
        }
        User user = optionalUser.get();

        // The plaintext token is sent to the user; only its SHA-256 hash is
        // persisted, so a DB breach does NOT expose unredeemed reset links.
        String plaintextToken = UUID.randomUUID().toString();
        String tokenHash = sha256Hex(plaintextToken);
        String otp = generateOtp();

        // Invalidate any existing unused tokens for this user
        resetTokenRepository.markAllUnusedAsUsedForUser(user.getId());

        PasswordResetToken resetToken = PasswordResetToken.builder()
            .token(tokenHash)
            .otp(otp)
            .user(user)
            .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(otpExpirationMinutes))
            .build();

        resetTokenRepository.save(resetToken);
        log.info("Password reset requested for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));

        // Send OTP via email (and optionally SMS) — `token` is the plaintext
        // value; the DB only ever holds its hash.
        sendNotification(user, "PASSWORD_RESET", Map.of(
            "name", user.getFirstName(),
            "otp", otp,
            "token", plaintextToken,
            "expiryMinutes", String.valueOf(otpExpirationMinutes)
        ));
    }

    // ── Reset password via token ─────────────────────────────
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = resetTokenRepository
            .findByTokenAndUsedFalse(sha256Hex(request.getToken()))
            .orElseThrow(() -> new BusinessException("Invalid or expired reset token"));

        if (resetToken.isExpired()) {
            throw new BusinessException("Reset token has expired");
        }

        User user = resetToken.getUser();
        if (passwordHistoryService.isRecentlyUsed(user.getId(), request.getNewPassword())) {
            throw new BusinessException("You cannot reuse a recently used password. Please choose a different one.");
        }
        String newHash = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(newHash);
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        clearTempPasswordState(user); // setting a real password ends the temp-password lifecycle
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), newHash);

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        try { sessionService.revokeAllForUser(user.getId(), user.getId(), "PASSWORD_RESET"); } catch (Exception e) { log.warn("Failed to revoke sessions after PASSWORD_RESET for user {}: {}", user.getId(), e.getMessage()); }
        auditService.success(AuthAuditService.EventType.PASSWORD_RESET_COMPLETED, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
        log.info("Password reset completed for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
    }

    // ── Verify OTP (for phone-based recovery) ────────────────
    @Transactional
    public void verifyOtpAndResetPassword(VerifyOtpRequest request) {
        // OTP must be paired with token to prevent brute-force across all users
        if (request.getToken() == null || request.getToken().isBlank()) {
            throw new BusinessException("Reset token is required along with OTP");
        }

        // Look up by token first (not by OTP) to enforce per-token attempt counting
        PasswordResetToken resetToken = resetTokenRepository
            .findByTokenAndUsedFalse(sha256Hex(request.getToken()))
            .orElseThrow(() -> new BusinessException("Invalid or expired reset token"));

        if (resetToken.isExpired()) {
            throw new BusinessException("OTP has expired");
        }

        // Enforce max OTP attempts to prevent brute-force
        if (resetToken.isOtpAttemptsExhausted()) {
            resetToken.setUsed(true);
            resetTokenRepository.save(resetToken);
            throw new BusinessException("Too many incorrect OTP attempts. Please request a new code.");
        }

        if (!request.getOtp().equals(resetToken.getOtp())) {
            resetToken.incrementOtpAttempts();
            resetTokenRepository.save(resetToken);
            throw new BusinessException("Invalid OTP");
        }

        User user = resetToken.getUser();
        if (passwordHistoryService.isRecentlyUsed(user.getId(), request.getNewPassword())) {
            throw new BusinessException("You cannot reuse a recently used password. Please choose a different one.");
        }
        String newHash = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(newHash);
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        clearTempPasswordState(user); // setting a real password ends the temp-password lifecycle
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), newHash);

        // Invalidate ALL unused tokens for this user (not just the current one)
        resetTokenRepository.markAllUnusedAsUsedForUser(user.getId());

        try { sessionService.revokeAllForUser(user.getId(), user.getId(), "PASSWORD_RESET"); } catch (Exception e) { log.warn("Failed to revoke sessions after OTP PASSWORD_RESET for user {}: {}", user.getId(), e.getMessage()); }
        auditService.success(AuthAuditService.EventType.PASSWORD_RESET_COMPLETED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "via OTP");
        log.info("OTP-based password reset completed for userId: {}", user.getId());
    }

    // ── Get current user profile ─────────────────────────────
    public UserDto getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return toDto(user);
    }

    // ── Change password (authenticated) ──────────────────────
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            auditService.failure(AuthAuditService.EventType.PASSWORD_CHANGED, userId, user.getRole(), userId, user.getEmail(), "BAD_CURRENT_PASSWORD");
            throw new BusinessException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException("New password must be different from the current password", HttpStatus.BAD_REQUEST);
        }

        // Reject reuse of recent passwords (history depth configured in PasswordHistoryService)
        if (passwordHistoryService.isRecentlyUsed(userId, request.getNewPassword())) {
            auditService.failure(AuthAuditService.EventType.PASSWORD_CHANGED, userId, user.getRole(), userId, user.getEmail(), "PASSWORD_REUSED");
            throw new BusinessException("You cannot reuse a recently used password. Please choose a different one.", HttpStatus.BAD_REQUEST);
        }

        // Breach password check on the new password
        if (pwnedPasswordService.isPasswordPwned(request.getNewPassword())) {
            auditService.failure(AuthAuditService.EventType.PASSWORD_CHANGED, userId, user.getRole(), userId, user.getEmail(), "PWNED_PASSWORD");
            throw new BusinessException(
                "This password has appeared in a known data breach. Please choose a different password.",
                HttpStatus.BAD_REQUEST);
        }

        String newHash = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(newHash);
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        clearTempPasswordState(user); // a real password ends the temporary-password lifecycle
        userRepository.save(user);
        passwordHistoryService.record(userId, newHash);

        // Force-logout other sessions for safety (user keeps the caller's JWT until natural expiry)
        try {
            sessionService.revokeAllForUser(userId, userId, "PASSWORD_CHANGED");
        } catch (Exception ex) {
            log.warn("Failed to revoke sibling sessions after password change for userId={}: {}", userId, ex.getMessage());
        }

        auditService.success(AuthAuditService.EventType.PASSWORD_CHANGED, userId, user.getRole(), userId, user.getEmail(), null);
        log.info("Password changed for userId: {}", userId);
    }

    // ── Self-update profile (firstName/lastName/phone/address) ─────────────
    // Email and password are intentionally not editable here — they go through
    // change-email / change-password flows that require additional verification.
    @Transactional
    public UserDto selfUpdateProfile(Long userId, com.skbingegalaxy.auth.dto.SelfUpdateProfileRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Phone uniqueness check (only if changed)
        if (!java.util.Objects.equals(user.getPhone(), request.getPhone())
                && userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName() == null ? null : request.getLastName().trim());
        user.setPhone(request.getPhone());
        user.setPhoneCountryCode(request.getPhoneCountryCode());
        user.setAddressLine1(trimToNull(request.getAddressLine1()));
        user.setAddressLine2(trimToNull(request.getAddressLine2()));
        user.setCity(trimToNull(request.getCity()));
        user.setState(trimToNull(request.getState()));
        user.setCountry(trimToNull(request.getCountry()));
        user.setPostalCode(trimToNull(request.getPostalCode()));

        user = userRepository.save(user);
        log.info("User {} updated own profile", userId);
        return toDto(user);
    }

    // ── Change email (authenticated, requires current password) ────────────
    // Production-grade flow: re-verify the user with their password before any
    // email rotation. The new email is uniqueness-checked and the user is
    // marked unverified so the standard email-verification flow re-runs.
    @Transactional
    public UserDto changeEmail(Long userId, com.skbingegalaxy.auth.dto.ChangeEmailRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            auditService.failure(AuthAuditService.EventType.PASSWORD_CHANGED, userId, user.getRole(), userId, user.getEmail(), "BAD_CURRENT_PASSWORD_FOR_EMAIL_CHANGE");
            throw new BusinessException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        String newEmail = request.getNewEmail().trim().toLowerCase();
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw new BusinessException("New email must be different from your current email", HttpStatus.BAD_REQUEST);
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw new DuplicateResourceException("User", "email", newEmail);
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        // Force re-verification of the new address.
        user.setEmailVerified(false);
        user = userRepository.save(user);

        // Revoke other sessions: a credential identifier (email) just changed.
        try {
            sessionService.revokeAllForUser(userId, userId, "EMAIL_CHANGED");
        } catch (Exception ex) {
            log.warn("Failed to revoke sibling sessions after email change for userId={}: {}", userId, ex.getMessage());
        }

        log.info("User {} changed email from {} to {}",
            userId,
            com.skbingegalaxy.common.util.LogSanitizer.maskEmail(oldEmail),
            com.skbingegalaxy.common.util.LogSanitizer.maskEmail(newEmail));
        return toDto(user);
    }

    public SupportContactDto getSupportContact() {
        String digits = digitsOnly(supportPhone);
        String phoneRaw = toPhoneRaw(digits);
        return SupportContactDto.builder()
            .email(supportEmail)
            .phoneDisplay(formatPhoneDisplay(digits))
            .phoneRaw(phoneRaw)
            .whatsappRaw(toWhatsAppRaw(digits))
            .hours(supportHours)
            .build();
    }

    @Transactional
    public UserDto updateAccountPreferences(Long userId, UpdateAccountPreferencesRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        String birthdayMonth = normalizeCelebrationMonth(trimToNull(request.getBirthdayMonth()));
        Integer birthdayDay = request.getBirthdayDay();
        validateCelebrationDate("Birthday", birthdayMonth, birthdayDay);

        String anniversaryMonth = normalizeCelebrationMonth(trimToNull(request.getAnniversaryMonth()));
        Integer anniversaryDay = request.getAnniversaryDay();
        validateCelebrationDate("Anniversary", anniversaryMonth, anniversaryDay);

        if (!Objects.equals(user.getBirthdayMonth(), birthdayMonth) || !Objects.equals(user.getBirthdayDay(), birthdayDay)) {
            user.setBirthdayReminderSentYear(null);
        }
        if (!Objects.equals(user.getAnniversaryMonth(), anniversaryMonth) || !Objects.equals(user.getAnniversaryDay(), anniversaryDay)) {
            user.setAnniversaryReminderSentYear(null);
        }

        user.setPreferredExperience(trimToNull(request.getPreferredExperience()));
        user.setVibePreference(trimToNull(request.getVibePreference()));
        user.setReminderLeadDays(request.getReminderLeadDays() != null ? request.getReminderLeadDays() : 14);
        user.setBirthdayMonth(birthdayMonth);
        user.setBirthdayDay(birthdayDay);
        user.setAnniversaryMonth(anniversaryMonth);
        user.setAnniversaryDay(anniversaryDay);
        user.setNotificationChannel(normalizeNotificationChannel(request.getNotificationChannel()));
        user.setReceivesOffers(request.getReceivesOffers() != null ? request.getReceivesOffers() : Boolean.TRUE);
        user.setWeekendAlerts(request.getWeekendAlerts() != null ? request.getWeekendAlerts() : Boolean.TRUE);
        user.setConciergeSupport(request.getConciergeSupport() != null ? request.getConciergeSupport() : Boolean.TRUE);

        user = userRepository.save(user);
        log.info("Customer account preferences updated for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        return toDto(user);
    }

    // ── Complete profile (add phone after Google sign-in) ─────
    @Transactional
    public AuthResponse completeProfile(Long userId, CompleteProfileRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        String phone = request.getPhone();
        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("User", "phone", phone);
        }
        user.setPhone(phone);
        user.setPhoneCountryCode(request.getPhoneCountryCode());
        user.setAddressLine1(trimToNull(request.getAddressLine1()));
        user.setAddressLine2(trimToNull(request.getAddressLine2()));
        user.setCity(trimToNull(request.getCity()));
        user.setState(trimToNull(request.getState()));
        user.setCountry(trimToNull(request.getCountry()));
        user.setPostalCode(trimToNull(request.getPostalCode()));
        user = userRepository.save(user);
        log.info("Profile completed for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        // Return fresh tokens so the JWT includes the phone claim
        TokenPair pair = mintTokenPair(user);
        String token = pair.access;
        String refreshToken = pair.refresh;
        return AuthResponse.builder()
            .token(token)
            .refreshToken(refreshToken)
            .user(toDto(user))
            .build();
    }

    // ── Admin: search customers ──────────────────────────────
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<UserDto> searchCustomers(String query, org.springframework.data.domain.Pageable pageable) {
        return userRepository.searchCustomers(query, pageable).map(this::toDto);
    }

    // ── Admin: list all customers ────────────────────────────
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<UserDto> getAllCustomers(org.springframework.data.domain.Pageable pageable) {
        return userRepository.findAllCustomers(pageable).map(this::toDto);
    }

    // ── Super Admin: list all admins ─────────────────────────
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<UserDto> getAllAdmins(org.springframework.data.domain.Pageable pageable) {
        // Hard cap page size so a caller cannot dump the entire admin table.
        int size = Math.min(pageable.getPageSize(), 100);
        org.springframework.data.domain.Pageable safe = org.springframework.data.domain.PageRequest.of(
            pageable.getPageNumber(), size, pageable.getSort());
        return userRepository.findAllAdmins(safe).map(this::toDto);
    }

    // ── Super Admin: delete user ─────────────────────────────
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BusinessException("Cannot delete the super admin account", HttpStatus.FORBIDDEN);
        }
        Long actor = currentActorId();
        UserRole actorRole = currentActorRole();
        try { sessionService.revokeAllForUser(user.getId(), actor, "USER_DELETED"); } catch (Exception e) { log.warn("Failed to revoke sessions after USER_DELETED for user {}: {}", user.getId(), e.getMessage()); }
        userRepository.delete(user);
        auditService.success(AuthAuditService.EventType.USER_DELETED, actor, actorRole, user.getId(), user.getEmail(), "role=" + user.getRole());
        log.info("Super admin deleted user: {} (ID: {}, role: {})", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), id, user.getRole());

        // Notify user about account deletion
        try {
            NotificationEvent event = NotificationEvent.builder()
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(user.getEmail())
                .recipientName(user.getFirstName())
                .subject("Account Deleted – SK Binge Galaxy")
                .body("Hi " + user.getFirstName() + ", your account has been deleted by an administrator. "
                    + "If you believe this was a mistake, please contact support.")
                .build();
            kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
        } catch (Exception e) {
            log.warn("Failed to send account-deletion notification to {}: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), e.getMessage());
        }
    }

    // ── Admin: get customer by ID ────────────────────────────
    @Transactional(readOnly = true)
    public UserDto getCustomerById(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        return toDto(user);
    }

    // ── Super Admin: bulk ban/unban ──────────────────────────
    @Transactional
    public int bulkSetActive(java.util.List<Long> userIds, boolean active) {
        if (userIds == null || userIds.isEmpty()) return 0;
        if (userIds.size() > 1000) {
            throw new BusinessException("Bulk operations limited to 1000 users", HttpStatus.BAD_REQUEST);
        }
        java.util.List<User> users = userRepository.findAllById(userIds);
        java.util.List<User> toUpdate = users.stream()
                .filter(u -> u.getRole() != UserRole.SUPER_ADMIN && u.isActive() != active)
                .toList();
        for (User user : toUpdate) {
            user.setActive(active);
            log.info("Bulk {} user: {} (ID: {})", active ? "unbanned" : "banned", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), user.getId());
            auditService.success(
                active ? AuthAuditService.EventType.USER_UNBANNED : AuthAuditService.EventType.USER_BANNED,
                currentActorId(), currentActorRole(), user.getId(), user.getEmail(), null);
            if (!active) {
                try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "USER_BANNED"); } catch (Exception e) { log.warn("Failed to revoke sessions after USER_BANNED for user {}: {}", user.getId(), e.getMessage()); }
            }
        }
        userRepository.saveAll(toUpdate);
        return toUpdate.size();
    }

    // ── Super Admin: bulk delete ─────────────────────────────
    @Transactional
    public int bulkDeleteUsers(java.util.List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        if (userIds.size() > 1000) {
            throw new BusinessException("Bulk operations limited to 1000 users", HttpStatus.BAD_REQUEST);
        }
        java.util.List<User> users = userRepository.findAllById(userIds);
        java.util.List<User> toDelete = users.stream()
                .filter(u -> u.getRole() != UserRole.SUPER_ADMIN)
                .toList();
        for (User user : toDelete) {
            log.info("Bulk deleted user: {} (ID: {}, role: {})", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), user.getId(), user.getRole());
            try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "BULK_DELETE"); } catch (Exception e) { log.warn("Failed to revoke sessions after BULK_DELETE for user {}: {}", user.getId(), e.getMessage()); }
            auditService.success(AuthAuditService.EventType.USER_BULK_DELETED, currentActorId(), currentActorRole(), user.getId(), user.getEmail(), "role=" + user.getRole());
        }
        userRepository.deleteAll(toDelete);
        return toDelete.size();
    }

    // ── Admin: update customer ───────────────────────────────
    @Transactional
    public UserDto adminUpdateCustomer(Long id, UpdateCustomerRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        // Check email uniqueness (if changed)
        if (!user.getEmail().equals(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
        }
        // Check phone uniqueness (if changed)
        if (!java.util.Objects.equals(user.getPhone(), request.getPhone())
                && request.getPhone() != null
                && userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setPhoneCountryCode(request.getPhoneCountryCode());
        user.setAddressLine1(trimToNull(request.getAddressLine1()));
        user.setAddressLine2(trimToNull(request.getAddressLine2()));
        user.setCity(trimToNull(request.getCity()));
        user.setState(trimToNull(request.getState()));
        user.setCountry(trimToNull(request.getCountry()));
        user.setPostalCode(trimToNull(request.getPostalCode()));

        // Admin password reset
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            log.info("Admin reset password for customer ID: {}", id);
        }

        user = userRepository.save(user);
        log.info("Admin updated customer: {} (ID: {})", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), id);
        return toDto(user);
    }

    // ── Super-Admin: update admin ────────────────────────────
    @Transactional
    public UserDto updateAdmin(Long id, UpdateCustomerRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("User is not an admin", HttpStatus.BAD_REQUEST);
        }

        if (!user.getEmail().equals(request.getEmail()) && userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
        }
        if (!java.util.Objects.equals(user.getPhone(), request.getPhone())
                && request.getPhone() != null
                && userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setPhoneCountryCode(request.getPhoneCountryCode());
        user.setAddressLine1(trimToNull(request.getAddressLine1()));
        user.setAddressLine2(trimToNull(request.getAddressLine2()));
        user.setCity(trimToNull(request.getCity()));
        user.setState(trimToNull(request.getState()));
        user.setCountry(trimToNull(request.getCountry()));
        user.setPostalCode(trimToNull(request.getPostalCode()));

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            log.info("Super-admin reset password for admin ID: {}", id);
        }

        user = userRepository.save(user);
        log.info("Super-admin updated admin: {} (ID: {})", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), id);
        return toDto(user);
    }

    // ── Admin: create customer ───────────────────────────────
    /**
     * Front-desk customer provisioning. The account is created with a
     * <b>temporary</b> password (never a long-lived one the admin keeps): the
     * customer is forced to change it on login and may only use it for
     * {@link #tempPasswordMaxLogins} logins before "Forgot password" is required.
     * The temp password is hashed for storage, returned once to the creating
     * admin (so they can read it out), and delivered to the customer by email
     * <i>and</i> SMS.
     */
    @Transactional
    public UserDto adminCreateCustomer(AdminCreateCustomerRequest request) {
        String email = request.getEmail().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User", "email", email);
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()
                && userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        // Admin-created customers ALWAYS get a temporary password (server-side
        // generated when the admin didn't supply one).
        String tempPassword = (request.getPassword() != null && !request.getPassword().isBlank())
            ? request.getPassword()
            : generateSecurePassword();

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(email)
            .phone(trimToNull(request.getPhone()))
            .phoneCountryCode(trimToNull(request.getPhoneCountryCode()))
            .addressLine1(trimToNull(request.getAddressLine1()))
            .addressLine2(trimToNull(request.getAddressLine2()))
            .city(trimToNull(request.getCity()))
            .state(trimToNull(request.getState()))
            .country(trimToNull(request.getCountry()))
            .postalCode(trimToNull(request.getPostalCode()))
            .password(passwordEncoder.encode(tempPassword))
            .role(UserRole.CUSTOMER)
            .active(true)
            .mustChangePassword(true)
            .tempPasswordLoginsRemaining(tempPasswordMaxLogins)
            .build();

        user = userRepository.save(user);
        log.info("Admin created customer with temporary password: {} (temp logins={})",
            com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), tempPasswordMaxLogins);
        auditService.success(AuthAuditService.EventType.CUSTOMER_CREATED,
            currentActorId(), currentActorRole(), user.getId(), user.getEmail(),
            "admin-created customer (temporary password)");

        sendTempPasswordNotification(user, tempPassword);

        UserDto dto = toDto(user);
        dto.setTemporaryPassword(tempPassword); // surfaced to the creating admin only
        return dto;
    }

    /**
     * Re-issues a fresh temporary password for an admin-created customer who
     * never changed theirs (e.g. lost the email/SMS). Resets the login counter
     * and re-sends by email + SMS. Returns the new temp password to the admin.
     */
    @Transactional
    public UserDto regenerateTempPassword(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.getRole() != UserRole.CUSTOMER) {
            throw new BusinessException("Temporary passwords can only be issued for customer accounts",
                HttpStatus.BAD_REQUEST);
        }
        String tempPassword = generateSecurePassword();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setTempPasswordLoginsRemaining(tempPasswordMaxLogins);
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);
        // Invalidate any live sessions so the old credential can't linger.
        try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "TEMP_PASSWORD_REISSUED"); }
        catch (Exception e) { log.warn("Failed to revoke sessions on temp-password reissue for {}: {}", user.getId(), e.getMessage()); }
        auditService.success(AuthAuditService.EventType.PASSWORD_RESET_COMPLETED,
            currentActorId(), currentActorRole(), user.getId(), user.getEmail(),
            "admin re-issued temporary password");
        sendTempPasswordNotification(user, tempPassword);
        log.info("Admin re-issued temporary password for customer {}",
            com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        UserDto dto = toDto(user);
        dto.setTemporaryPassword(tempPassword);
        return dto;
    }

    /**
     * Deliver a temporary password to the customer over both EMAIL and SMS
     * (best-effort; failure to enqueue must not break account creation). The SMS
     * event carries no email so the email-keyed dedup never suppresses it.
     */
    private void sendTempPasswordNotification(User user, String tempPassword) {
        String name = user.getFirstName() != null ? user.getFirstName() : "there";
        String emailBody = String.format(
            "Hi %s,%n%nAn account was created for you at SK Binge Galaxy so we can manage your reservation.%n%n"
            + "Email: %s%nTemporary password: %s%n%n"
            + "Please sign in and you'll be asked to set your own password. For your security this temporary "
            + "password only works for the next %d sign-in(s); after that use \"Forgot password\" to set a new one.%n%n"
            + "Never share this password with anyone.",
            name, user.getEmail(), tempPassword, tempPasswordMaxLogins);
        String smsBody = String.format(
            "SK Binge Galaxy: temporary password %s for %s. Valid for %d login(s); you'll be asked to change it.",
            tempPassword, user.getEmail(), tempPasswordMaxLogins);
        try {
            NotificationEvent emailEv = NotificationEvent.builder()
                .type("ACCOUNT_TEMP_PASSWORD")
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(user.getEmail())
                .recipientName(name)
                .recipientPhone(user.getPhone())
                .recipientPhoneCountryCode(user.getPhoneCountryCode())
                .subject("Your SK Binge Galaxy temporary password")
                .body(emailBody)
                .build();
            kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, emailEv);
            if (user.getPhone() != null && !user.getPhone().isBlank()) {
                NotificationEvent smsEv = NotificationEvent.builder()
                    .type("ACCOUNT_TEMP_PASSWORD")
                    .channel(NotificationChannel.SMS)
                    .recipientName(name)
                    .recipientPhone(user.getPhone())
                    .recipientPhoneCountryCode(user.getPhoneCountryCode())
                    .subject("SK Binge Galaxy temporary password")
                    .body(smsBody)
                    .build();
                kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, smsEv);
            }
        } catch (Exception e) {
            log.warn("Failed to enqueue temporary-password notification for {}: {}",
                com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), e.getMessage());
        }
    }

    // ── Admin register ────────────────────────────────────────
    @Transactional
    public AuthResponse adminRegister(RegisterRequest request) {
        String email = request.getEmail().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User", "email", email);
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new DuplicateResourceException("User", "phone", request.getPhone());
        }

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(email)
            .phone(request.getPhone())
            .phoneCountryCode(request.getPhoneCountryCode())
            .addressLine1(trimToNull(request.getAddressLine1()))
            .addressLine2(trimToNull(request.getAddressLine2()))
            .city(trimToNull(request.getCity()))
            .state(trimToNull(request.getState()))
            .country(trimToNull(request.getCountry()))
            .postalCode(trimToNull(request.getPostalCode()))
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.ADMIN)
            .active(true)
            // DPDP: the creating super-admin accepts the onboarding terms on the
            // new admin's behalf (the Add-Admin form requires the consent checkbox),
            // so record the consent timestamp like the customer register path does.
            .consentGivenAt(java.time.LocalDateTime.now(ZoneOffset.UTC))
            .consentMarketing(request.isConsentMarketing())
            .build();

        user = userRepository.save(user);
        log.info("Admin registered: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        passwordHistoryService.record(user.getId(), user.getPassword());
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);
        auditService.success(AuthAuditService.EventType.ADMIN_CREATED,
            currentActorId(), currentActorRole(),
            user.getId(), user.getEmail(),
            "role=" + user.getRole());
        return buildAuthResponse(user);
    }

    // ── Helpers ──────────────────────────────────────────────
    /**
     * Issue a fresh access + refresh token pair AND persist a {@link com.skbingegalaxy.auth.entity.UserSession}
     * keyed by the refresh-token JTI so the login is visible in the super-admin session view
     * and can be force-revoked. The refresh-token JTI also backs the rotation check in
     * {@link #refreshToken(String)}.
     */
    private AuthResponse buildAuthResponse(User user) {
        TokenPair pair = mintTokenPair(user);
        String accessToken = pair.access;
        String refreshToken = pair.refresh;
        try {
            String jti = jwtProvider.getJtiFromToken(refreshToken);
            java.time.LocalDateTime exp = jwtProvider.getExpiryFromToken(refreshToken);
            if (jti != null && exp != null) {
                sessionService.recordLogin(user.getId(), jti, exp);
            }
        } catch (Exception ex) {
            // Session tracking must not break login
            log.warn("Failed to record user session for userId={}: {}", user.getId(), ex.getMessage());
        }
        return AuthResponse.builder()
            .token(accessToken)
            .refreshToken(refreshToken)
            .user(toDto(user))
            .build();
    }

    /**
     * Holder for an access+refresh pair so we can mint both with the same Authority
     * Handover delegation snapshot in a single DB read.
     */
    private record TokenPair(String access, String refresh) {}

    /**
     * Mint an access+refresh JWT pair. If the user has any active Authority Handover
     * grants, both tokens carry the union of granted scopes plus the earliest grant
     * expiry as {@code delegationExpiresAt}. The native {@code role} claim is unchanged
     * — the gateway is responsible for elevating {@code X-User-Role} on a per-path
     * basis when the request matches a granted scope.
     *
     * <p>Looking up grants here (rather than in {@link JwtProvider}) keeps the JWT
     * layer decoupled from authority persistence, makes the dependency direction
     * one-way (auth-service → authority-service), and ensures refresh-token rotation
     * picks up newly granted or revoked authority on the next refresh cycle.
     */
    private TokenPair mintTokenPair(User user) {
        try {
            java.util.Set<com.skbingegalaxy.common.enums.AuthorityScope> scopes =
                authorityService.getActiveScopesForUser(user.getId());
            if (scopes.isEmpty()) {
                return new TokenPair(jwtProvider.generateToken(user), jwtProvider.generateRefreshToken(user));
            }
            long expiryMs = authorityService.getEarliestGrantExpiryEpochMillis(user.getId());
            return new TokenPair(
                jwtProvider.generateToken(user, scopes, expiryMs),
                jwtProvider.generateRefreshToken(user, scopes, expiryMs)
            );
        } catch (Exception ex) {
            // Authority lookup must NEVER break login. Fall back to non-delegated tokens.
            log.warn("Authority lookup failed during token mint for userId={}: {}", user.getId(), ex.getMessage());
            return new TokenPair(jwtProvider.generateToken(user), jwtProvider.generateRefreshToken(user));
        }
    }

    /** Build an MFA challenge response (no tokens issued; caller must resubmit with code). */
    private AuthResponse buildMfaChallenge(User user) {
        return AuthResponse.builder()
            .mfaRequired(true)
            .challengeType("TOTP")
            .user(UserDto.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .email(user.getEmail())
                .role(user.getRole())
                .mfaEnabled(true)
                .emailVerified(user.isEmailVerified())
                .active(user.isActive())
                .build())
            .build();
    }

    UserDto toDto(User user) {
        return UserDto.builder()
            .id(user.getId())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .email(user.getEmail())
            .phone(user.getPhone())
            .phoneCountryCode(user.getPhoneCountryCode())
            .addressLine1(user.getAddressLine1())
            .addressLine2(user.getAddressLine2())
            .city(user.getCity())
            .state(user.getState())
            .country(user.getCountry())
            .postalCode(user.getPostalCode())
            .preferredExperience(user.getPreferredExperience())
            .vibePreference(user.getVibePreference())
            .reminderLeadDays(user.getReminderLeadDays() != null ? user.getReminderLeadDays() : 14)
            .birthdayMonth(user.getBirthdayMonth())
            .birthdayDay(user.getBirthdayDay())
            .anniversaryMonth(user.getAnniversaryMonth())
            .anniversaryDay(user.getAnniversaryDay())
            .notificationChannel(normalizeNotificationChannel(user.getNotificationChannel()))
            .receivesOffers(user.getReceivesOffers() != null ? user.getReceivesOffers() : true)
            .weekendAlerts(user.getWeekendAlerts() != null ? user.getWeekendAlerts() : true)
            .conciergeSupport(user.getConciergeSupport() != null ? user.getConciergeSupport() : true)
            .role(user.getRole())
            .active(user.isActive())
            .createdAt(user.getCreatedAt())
            .emailVerified(user.isEmailVerified())
            .mfaEnabled(user.isMfaEnabled())
            .lastPasswordChangeAt(user.getLastPasswordChangeAt())
            .mustChangePassword(user.isMustChangePassword())
            .build();
    }

    // ── Actor introspection (for audit rows) ─────────────────
    /** Current authenticated user id from Spring Security, or null if anonymous. */
    Long currentActorId() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            Object principal = auth.getPrincipal();
            if (principal == null || "anonymousUser".equals(principal)) return null;
            String name = auth.getName();
            if (name == null || name.isBlank()) return null;
            try { return Long.parseLong(name); } catch (NumberFormatException ex) { return null; }
        } catch (Exception ex) { return null; }
    }

    /** Current authenticated user's role, or null. */
    UserRole currentActorRole() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getAuthorities() == null) return null;
            for (var ga : auth.getAuthorities()) {
                String a = ga.getAuthority();
                if (a == null) continue;
                if (a.equals("ROLE_SUPER_ADMIN")) return UserRole.SUPER_ADMIN;
                if (a.equals("ROLE_ADMIN")) return UserRole.ADMIN;
                if (a.equals("ROLE_CUSTOMER")) return UserRole.CUSTOMER;
            }
            return null;
        } catch (Exception ex) { return null; }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeNotificationChannel(String notificationChannel) {
        if (notificationChannel == null || notificationChannel.isBlank()) {
            return "EMAIL";
        }
        String normalized = notificationChannel.trim().toUpperCase(Locale.ENGLISH);
        return "CALLBACK".equals(normalized) ? "CALLBACK" : "EMAIL";
    }

    private String normalizeCelebrationMonth(String month) {
        if (month == null) {
            return null;
        }
        return parseMonth(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private void validateCelebrationDate(String label, String month, Integer day) {
        if (month == null && day == null) {
            return;
        }
        if (month == null || day == null) {
            throw new BusinessException(label + " reminders need both month and day", HttpStatus.BAD_REQUEST);
        }

        Month parsedMonth = parseMonth(month);
        int maxDay = parsedMonth == Month.FEBRUARY ? 29 : parsedMonth.maxLength();
        if (day < 1 || day > maxDay) {
            throw new BusinessException(label + " date is invalid for " + parsedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), HttpStatus.BAD_REQUEST);
        }
    }

    private Month parseMonth(String month) {
        try {
            return Month.from(MONTH_FORMATTER.parse(month));
        } catch (Exception exception) {
            throw new BusinessException("Month must be a full month name", HttpStatus.BAD_REQUEST);
        }
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private String toPhoneRaw(String digits) {
        if (digits.isBlank()) {
            return "";
        }
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        return digits.startsWith("+") ? digits : "+" + digits;
    }

    private String toWhatsAppRaw(String digits) {
        if (digits.isBlank()) {
            return "";
        }
        if (digits.length() == 10) {
            return "91" + digits;
        }
        return digits;
    }

    private String formatPhoneDisplay(String digits) {
        if (digits.length() == 10) {
            return String.format("+91 %s %s", digits.substring(0, 5), digits.substring(5));
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            String local = digits.substring(2);
            return String.format("+91 %s %s", local.substring(0, 5), local.substring(5));
        }
        if (digits.isBlank()) {
            return "";
        }
        return supportPhone;
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int bound = (int) Math.pow(10, otpLength);
        return String.format("%0" + otpLength + "d", random.nextInt(bound));
    }

    /**
     * SHA-256 hex digest of {@code input}, used to hash high-entropy
     * password-reset tokens before persistence. Plain SHA-256 (no salt) is
     * sufficient because the input is a fresh random UUID; the goal is to
     * prevent direct token replay if the DB is breached, not to defend
     * against precomputed attacks on low-entropy secrets.
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; this branch is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Ends the temporary-password lifecycle: the account now has a real, self-chosen password. */
    private static void clearTempPasswordState(User user) {
        user.setMustChangePassword(false);
        user.setTempPasswordLoginsRemaining(null);
    }

    private String generateSecurePassword() {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*";
        String all = upper + lower + digits + special;
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        // Ensure at least one char from each category
        sb.append(upper.charAt(rng.nextInt(upper.length())));
        sb.append(lower.charAt(rng.nextInt(lower.length())));
        sb.append(digits.charAt(rng.nextInt(digits.length())));
        sb.append(special.charAt(rng.nextInt(special.length())));
        for (int i = 4; i < 16; i++) {
            sb.append(all.charAt(rng.nextInt(all.length())));
        }
        // Shuffle to avoid predictable pattern
        char[] arr = sb.toString().toCharArray();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
        return new String(arr);
    }

    private void sendNotification(User user, String template, Map<String, String> data) {
        try {
            NotificationEvent event = NotificationEvent.builder()
                .recipientEmail(user.getEmail())
                .recipientPhone(user.getPhone())
                .recipientPhoneCountryCode(user.getPhoneCountryCode())
                .recipientName(user.getFirstName())
                .channel(NotificationChannel.EMAIL)
                .templateName(template)
                .templateData(data)
                .build();
            kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
        } catch (Exception e) {
            log.warn("Failed to send notification event for {}: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Super-admin: role management (promote/demote ADMIN ↔ SUPER_ADMIN)
    // ════════════════════════════════════════════════════════════════════════

    /** Promote an ADMIN to SUPER_ADMIN. Caller must already be SUPER_ADMIN. */
    @Transactional
    public UserDto promoteToSuperAdmin(Long targetId) {
        User user = userRepository.findById(targetId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetId));
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException("Only ADMIN users can be promoted to SUPER_ADMIN", HttpStatus.BAD_REQUEST);
        }
        // Enforce MFA before granting the highest privilege. An account without MFA cannot be
        // promoted — this prevents a compromised ADMIN account (password-only) from escalating.
        if (!user.isMfaEnabled()) {
            throw new BusinessException(
                "MFA must be enabled before promoting a user to SUPER_ADMIN. Ask the user to enroll TOTP first.",
                HttpStatus.BAD_REQUEST);
        }
        // Warn if WebAuthn not enrolled — log for ops visibility; not a hard block yet
        // (admins may be promoted before their hardware key arrives). Set
        // SUPER_ADMIN_REQUIRE_WEBAUTHN=true to make this a hard requirement at login.
        if (user.getWebauthnEnrolledAt() == null) {
            log.warn("SECURITY: Promoting user {} to SUPER_ADMIN without a WebAuthn hardware key enrolled. "
                + "Provision a FIDO2 key and complete enrollment before granting production access.", user.getId());
        }
        user.setRole(UserRole.SUPER_ADMIN);
        user = userRepository.save(user);
        // Role change invalidates cached JWT claims → force re-login everywhere.
        try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "ROLE_PROMOTED"); } catch (Exception e) { log.warn("Failed to revoke sessions after ROLE_PROMOTED for user {}: {}", user.getId(), e.getMessage()); }
        auditService.success(AuthAuditService.EventType.ROLE_PROMOTED, currentActorId(), currentActorRole(),
            user.getId(), user.getEmail(), "ADMIN → SUPER_ADMIN");
        log.info("User {} promoted to SUPER_ADMIN", user.getId());
        return toDto(user);
    }

    /** Demote a SUPER_ADMIN back to ADMIN. Cannot demote yourself or the last super admin. */
    @Transactional
    public UserDto demoteFromSuperAdmin(Long targetId) {
        Long actor = currentActorId();
        if (actor != null && actor.equals(targetId)) {
            throw new BusinessException("You cannot demote yourself", HttpStatus.FORBIDDEN);
        }
        User user = userRepository.findById(targetId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", targetId));
        if (user.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("User is not a SUPER_ADMIN", HttpStatus.BAD_REQUEST);
        }
        long remaining = userRepository.countByRoleAndActiveTrue(UserRole.SUPER_ADMIN);
        if (remaining <= 1) {
            throw new BusinessException("At least one active SUPER_ADMIN must remain", HttpStatus.CONFLICT);
        }
        user.setRole(UserRole.ADMIN);
        user = userRepository.save(user);
        try { sessionService.revokeAllForUser(user.getId(), actor, "ROLE_DEMOTED"); } catch (Exception e) { log.warn("Failed to revoke sessions after ROLE_DEMOTED for user {}: {}", user.getId(), e.getMessage()); }
        auditService.success(AuthAuditService.EventType.ROLE_DEMOTED, actor, currentActorRole(),
            user.getId(), user.getEmail(), "SUPER_ADMIN → ADMIN");
        log.info("User {} demoted to ADMIN", user.getId());
        return toDto(user);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MFA (TOTP) management
    // ════════════════════════════════════════════════════════════════════════

    /** Begin MFA enrollment for the current user. Returns secret + otpauth:// uri + recovery codes. */
    @Transactional
    public MfaEnrollmentResponse beginMfaEnrollment(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isMfaEnabled()) {
            throw new BusinessException("MFA is already enabled. Disable it first to re-enroll.", HttpStatus.CONFLICT);
        }
        TotpService.EnrollmentPayload payload = totpService.beginEnrollment(user.getId());
        return new MfaEnrollmentResponse(
            payload.getSecret(),
            payload.getOtpauthUri(),
            payload.getRecoveryCodes()
        );
    }

    /** Confirm MFA enrollment by providing a valid code from the authenticator app. */
    @Transactional
    public void confirmMfaEnrollment(Long userId, String code, java.util.List<String> recoveryCodes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (recoveryCodes == null || recoveryCodes.isEmpty()) {
            throw new BusinessException("Recovery codes must be echoed back on MFA confirmation", HttpStatus.BAD_REQUEST);
        }
        totpService.confirmEnrollment(userId, code, recoveryCodes);
        auditService.success(AuthAuditService.EventType.MFA_ENROLLED, userId, user.getRole(), userId, user.getEmail(), null);
        log.info("MFA enrolled for userId={}", userId);
    }

    /** Disable MFA for the current user. Requires a valid code (or recovery code). */
    @Transactional
    public void disableMfa(Long userId, String code) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (!user.isMfaEnabled()) {
            return; // idempotent
        }
        totpService.disable(userId, code);
        auditService.success(AuthAuditService.EventType.MFA_DISABLED, userId, user.getRole(), userId, user.getEmail(), null);
        log.info("MFA disabled for userId={}", userId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Email verification
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void verifyEmail(String email, String otp) {
        User verified = emailVerificationService.verifyWithOtp(email == null ? "" : email.toLowerCase(), otp);
        auditService.success(AuthAuditService.EventType.EMAIL_VERIFIED, verified.getId(), verified.getRole(), verified.getId(), verified.getEmail(), null);
    }

    @Transactional
    public void verifyEmailByToken(String token) {
        User verified = emailVerificationService.verifyWithToken(token);
        auditService.success(AuthAuditService.EventType.EMAIL_VERIFIED, verified.getId(), verified.getRole(), verified.getId(), verified.getEmail(), null);
    }

    @Transactional
    public void resendVerificationEmail(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isEmailVerified()) return;
        emailVerificationService.issue(user);
        auditService.success(AuthAuditService.EventType.EMAIL_VERIFICATION_SENT, userId, user.getRole(), userId, user.getEmail(), null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Session read / revoke (exposes UserSessionService via the service boundary)
    // ════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public java.util.List<UserSessionDto> listMySessions(Long userId, String currentRefreshJti) {
        return sessionService.listActiveForUser(userId).stream()
            .map(s -> UserSessionDto.from(s, s.getRefreshJti() != null && s.getRefreshJti().equals(currentRefreshJti)))
            .toList();
    }

    @Transactional
    public void revokeMySession(Long userId, Long sessionId) {
        var opt = sessionService.findById(sessionId);
        if (opt.isEmpty() || !opt.get().getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Session", "id", sessionId);
        }
        if (sessionService.revoke(sessionId, userId, "USER_REVOKED")) {
            auditService.success(AuthAuditService.EventType.SESSION_REVOKED, userId, UserRole.CUSTOMER, userId, null, "sessionId=" + sessionId);
        }
    }

    /**
     * Self-service: revoke every active session for the caller EXCEPT the one matching
     * {@code currentRefreshJti}. If {@code currentRefreshJti} is null/blank, every session
     * is revoked (the caller is signing out everywhere).
     *
     * Returns the number of sessions actually revoked.
     */
    @Transactional
    public int revokeAllOtherMySessions(Long userId, String currentRefreshJti) {
        var sessions = sessionService.listActiveForUser(userId);
        int n = 0;
        for (var s : sessions) {
            if (currentRefreshJti != null && !currentRefreshJti.isBlank()
                && currentRefreshJti.equals(s.getRefreshJti())) {
                continue; // keep current device
            }
            if (sessionService.revoke(s.getId(), userId, "USER_REVOKED_OTHERS")) {
                n++;
            }
        }
        if (n > 0) {
            auditService.success(AuthAuditService.EventType.SESSION_REVOKED_ALL, userId, currentActorRole(),
                userId, null, "scope=others count=" + n);
        }
        return n;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<UserSessionDto> listAllActiveSessions(org.springframework.data.domain.Pageable pageable) {
        return sessionService.listAllActive(pageable).map(s -> UserSessionDto.from(s, false));
    }

    @Transactional
    public void revokeAnySession(Long sessionId) {
        var opt = sessionService.findById(sessionId);
        if (opt.isEmpty()) throw new ResourceNotFoundException("Session", "id", sessionId);
        Long actor = currentActorId();
        if (sessionService.revoke(sessionId, actor, "ADMIN_REVOKED")) {
            auditService.success(AuthAuditService.EventType.SESSION_REVOKED, actor, currentActorRole(),
                opt.get().getUserId(), null, "sessionId=" + sessionId);
        }
    }

    @Transactional
    public int revokeAllSessionsForUser(Long userId) {
        int n = sessionService.revokeAllForUser(userId, currentActorId(), "ADMIN_REVOKED_ALL");
        auditService.success(AuthAuditService.EventType.SESSION_REVOKED_ALL, currentActorId(), currentActorRole(),
            userId, null, "count=" + n);
        return n;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Super-admin: audit log search + platform stats
    // ════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<AuthAuditLogDto> searchAuditLog(String eventType, Long actorId, Long targetId,
                                                                               org.springframework.data.domain.Pageable pageable) {
        return auditService.search(eventType, actorId, targetId, pageable).map(AuthAuditLogDto::from);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSuperAdminStats() {
        long customers = userRepository.countByRole(UserRole.CUSTOMER);
        long admins = userRepository.countByRole(UserRole.ADMIN);
        long superAdmins = userRepository.countByRole(UserRole.SUPER_ADMIN);
        long activeSessions = sessionService.listAllActive(org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("customers", customers);
        out.put("admins", admins);
        out.put("superAdmins", superAdmins);
        out.put("activeSessions", activeSessions);
        return out;
    }
}
