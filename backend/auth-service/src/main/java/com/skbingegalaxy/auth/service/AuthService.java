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

    @Value("${app.otp.expiration-minutes:10}")
    private int otpExpirationMinutes;

    @Value("${app.otp.length:6}")
    private int otpLength;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    /** Pre-computed BCrypt hash used in constant-time login to prevent timing oracles. */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        this.dummyHash = passwordEncoder.encode("timing-safety-dummy-" + java.util.UUID.randomUUID());
    }

    @Value("${app.admin.email:admin@skbingegalaxy.com}")
    private String supportEmail;

    @Value("${app.admin.phone:9876543210}")
    private String supportPhone;

    @Value("${app.support.hours:9:00 AM to 10:00 PM IST}")
    private String supportHours;

    // ── Google OAuth login ───────────────────────────────────
    @Transactional
    public AuthResponse googleLogin(String credential) {
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

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(email)
            .phone(request.getPhone())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.CUSTOMER)
            .active(true)
            .build();

        user = userRepository.save(user);
        log.info("Customer registered: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));

        // Record initial password so future change-password attempts can check history.
        passwordHistoryService.record(user.getId(), user.getPassword());
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now());
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
    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Constant-time login: always perform password check to prevent email enumeration via timing
        User user = userRepository.findByEmail(request.getEmail().toLowerCase()).orElse(null);
        String storedHash = user != null ? user.getPassword() : this.dummyHash;

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
            }
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
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
        userRepository.save(user);
        log.info("Customer login: userId={}", user.getId());
        auditService.success(AuthAuditService.EventType.LOGIN_SUCCESS, user.getId(), user.getRole(), user.getId(), user.getEmail(), null);
        return buildAuthResponse(user);
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
        String newAccess = jwtProvider.generateToken(user);
        String newRefresh = jwtProvider.generateRefreshToken(user);
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
    @Transactional
    public AuthResponse adminLogin(LoginRequest request) {
        // Constant-time login: always perform password check to prevent email enumeration via timing
        User user = userRepository.findByEmail(request.getEmail().toLowerCase()).orElse(null);
        String storedHash = user != null ? user.getPassword() : this.dummyHash;

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
            }
            auditService.failure(AuthAuditService.EventType.LOGIN_FAILED, user.getId(), user.getRole(), user.getId(), user.getEmail(), "BAD_PASSWORD");
            throw new BusinessException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        // MFA is strongly recommended for admins; enforced when enabled
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
            .expiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes))
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
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), newHash);

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        try { sessionService.revokeAllForUser(user.getId(), user.getId(), "PASSWORD_RESET"); } catch (Exception ignored) {}
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
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), newHash);

        // Invalidate ALL unused tokens for this user (not just the current one)
        resetTokenRepository.markAllUnusedAsUsedForUser(user.getId());

        try { sessionService.revokeAllForUser(user.getId(), user.getId(), "PASSWORD_RESET"); } catch (Exception ignored) {}
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

        String newHash = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(newHash);
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now());
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
    public AuthResponse completeProfile(Long userId, String phone) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("User", "phone", phone);
        }
        user.setPhone(phone);
        user = userRepository.save(user);
        log.info("Profile completed for: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        // Return fresh tokens so the JWT includes the phone claim
        String token = jwtProvider.generateToken(user);
        String refreshToken = jwtProvider.generateRefreshToken(user);
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
        try { sessionService.revokeAllForUser(user.getId(), actor, "USER_DELETED"); } catch (Exception ignored) {}
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
                try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "USER_BANNED"); } catch (Exception ignored) {}
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
            try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "BULK_DELETE"); } catch (Exception ignored) {}
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

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            log.info("Super-admin reset password for admin ID: {}", id);
        }

        user = userRepository.save(user);
        log.info("Super-admin updated admin: {} (ID: {})", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()), id);
        return toDto(user);
    }

    // ── Admin: create customer ───────────────────────────────
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

        // Generate a secure random password if none provided (admin-created customers)
        String rawPassword = (request.getPassword() != null && !request.getPassword().isBlank())
            ? request.getPassword()
            : generateSecurePassword();

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(email)
            .phone(request.getPhone())
            .password(passwordEncoder.encode(rawPassword))
            .role(UserRole.CUSTOMER)
            .active(true)
            .build();

        user = userRepository.save(user);
        log.info("Admin created customer: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        return toDto(user);
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
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.ADMIN)
            .active(true)
            .build();

        user = userRepository.save(user);
        log.info("Admin registered: {}", com.skbingegalaxy.common.util.LogSanitizer.maskEmail(user.getEmail()));
        passwordHistoryService.record(user.getId(), user.getPassword());
        user.setLastPasswordChangeAt(java.time.LocalDateTime.now());
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
        String accessToken = jwtProvider.generateToken(user);
        String refreshToken = jwtProvider.generateRefreshToken(user);
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
        user.setRole(UserRole.SUPER_ADMIN);
        user = userRepository.save(user);
        // Role change invalidates cached JWT claims → force re-login everywhere.
        try { sessionService.revokeAllForUser(user.getId(), currentActorId(), "ROLE_PROMOTED"); } catch (Exception ignored) {}
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
        try { sessionService.revokeAllForUser(user.getId(), actor, "ROLE_DEMOTED"); } catch (Exception ignored) {}
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
