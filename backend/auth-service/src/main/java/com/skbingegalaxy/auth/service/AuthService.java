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
import java.security.SecureRandom;
import java.time.Month;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
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
            String email = payload.getEmail().toLowerCase();
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
                log.info("Google login (existing user): {}", email);
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
            log.info("Google login (new user created): {}", email);

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
        log.info("Customer registered: {}", user.getEmail());

        // Fire notification event
        sendNotification(user, "WELCOME", Map.of(
            "name", user.getFirstName()
        ));

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
            throw new BusinessException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }
        if (!user.isActive()) {
            throw new BusinessException("Account is deactivated. Contact support.", HttpStatus.FORBIDDEN);
        }
        if (user.isAccountLocked()) {
            throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!passwordMatches) {
            user.incrementFailedAttempts();
            if (user.getFailedLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                user.lockAccount(LOCKOUT_MINUTES);
                log.warn("Account locked for user {} after {} failed attempts", user.getId(), MAX_LOGIN_ATTEMPTS);
            }
            userRepository.save(user);
            throw new BusinessException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        user.resetFailedAttempts();
        userRepository.save(user);
        log.info("Customer login: userId={}", user.getId());
        return buildAuthResponse(user);
    }

    // ── Refresh token ───────────────────────────────────────
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new BusinessException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }
        Long userId = jwtProvider.getUserIdFromToken(refreshToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.UNAUTHORIZED));
        if (!user.isActive()) {
            throw new BusinessException("Account is deactivated", HttpStatus.FORBIDDEN);
        }
        log.info("Token refreshed for: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    // ── Admin login ──────────────────────────────────────────
    @Transactional
    public AuthResponse adminLogin(LoginRequest request) {
        // Constant-time login: always perform password check to prevent email enumeration via timing
        User user = userRepository.findByEmail(request.getEmail().toLowerCase()).orElse(null);
        String storedHash = user != null ? user.getPassword() : this.dummyHash;

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (user == null || (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN)) {
            throw new BusinessException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        if (!user.isActive()) {
            throw new BusinessException("Account is deactivated", HttpStatus.FORBIDDEN);
        }
        if (user.isAccountLocked()) {
            throw new BusinessException("Account is temporarily locked due to too many failed attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!passwordMatches) {
            user.incrementFailedAttempts();
            if (user.getFailedLoginAttempts() >= MAX_LOGIN_ATTEMPTS) {
                user.lockAccount(LOCKOUT_MINUTES);
                log.warn("Admin account locked for user {} after {} failed attempts", user.getId(), MAX_LOGIN_ATTEMPTS);
            }
            userRepository.save(user);
            throw new BusinessException("Invalid admin credentials", HttpStatus.UNAUTHORIZED);
        }

        user.resetFailedAttempts();
        userRepository.save(user);
        log.info("Admin login: userId={}", user.getId());
        return buildAuthResponse(user);
    }

    // ── Forgot password ──────────────────────────────────────
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        var optionalUser = userRepository.findByEmail(request.getEmail().toLowerCase());
        if (optionalUser.isEmpty()) {
            // Return silently to prevent email enumeration
            log.debug("Forgot-password request for non-existent email: {}", request.getEmail());
            return;
        }
        User user = optionalUser.get();

        String token = UUID.randomUUID().toString();
        String otp = generateOtp();

        // Invalidate any existing unused tokens for this user
        resetTokenRepository.markAllUnusedAsUsedForUser(user.getId());

        PasswordResetToken resetToken = PasswordResetToken.builder()
            .token(token)
            .otp(otp)
            .user(user)
            .expiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes))
            .build();

        resetTokenRepository.save(resetToken);
        log.info("Password reset requested for: {}", user.getEmail());

        // Send OTP via email (and optionally SMS)
        sendNotification(user, "PASSWORD_RESET", Map.of(
            "name", user.getFirstName(),
            "otp", otp,
            "token", token,
            "expiryMinutes", String.valueOf(otpExpirationMinutes)
        ));
    }

    // ── Reset password via token ─────────────────────────────
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = resetTokenRepository
            .findByTokenAndUsedFalse(request.getToken())
            .orElseThrow(() -> new BusinessException("Invalid or expired reset token"));

        if (resetToken.isExpired()) {
            throw new BusinessException("Reset token has expired");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);

        log.info("Password reset completed for: {}", user.getEmail());
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
            .findByTokenAndUsedFalse(request.getToken())
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
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Invalidate ALL unused tokens for this user (not just the current one)
        resetTokenRepository.markAllUnusedAsUsedForUser(user.getId());

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
            throw new BusinessException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException("New password must be different from the current password", HttpStatus.BAD_REQUEST);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
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
        log.info("Customer account preferences updated for: {}", user.getEmail());
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
        log.info("Profile completed for: {}", user.getEmail());
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
    public java.util.List<UserDto> getAllAdmins() {
        return userRepository.findAllAdmins().stream().map(this::toDto).toList();
    }

    // ── Super Admin: delete user ─────────────────────────────
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BusinessException("Cannot delete the super admin account", HttpStatus.FORBIDDEN);
        }
        userRepository.delete(user);
        log.info("Super admin deleted user: {} (ID: {}, role: {})", user.getEmail(), id, user.getRole());

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
            log.warn("Failed to send account-deletion notification to {}: {}", user.getEmail(), e.getMessage());
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
        int count = 0;
        for (Long uid : userIds) {
            User user = userRepository.findById(uid).orElse(null);
            if (user == null) continue;
            if (user.getRole() == UserRole.SUPER_ADMIN) continue;
            user.setActive(active);
            userRepository.save(user);
            count++;
            log.info("Bulk {} user: {} (ID: {})", active ? "unbanned" : "banned", user.getEmail(), uid);
        }
        return count;
    }

    // ── Super Admin: bulk delete ─────────────────────────────
    @Transactional
    public int bulkDeleteUsers(java.util.List<Long> userIds) {
        int count = 0;
        for (Long uid : userIds) {
            User user = userRepository.findById(uid).orElse(null);
            if (user == null) continue;
            if (user.getRole() == UserRole.SUPER_ADMIN) continue;
            userRepository.delete(user);
            count++;
            log.info("Bulk deleted user: {} (ID: {}, role: {})", user.getEmail(), uid, user.getRole());
        }
        return count;
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
        log.info("Admin updated customer: {} (ID: {})", user.getEmail(), id);
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
        log.info("Super-admin updated admin: {} (ID: {})", user.getEmail(), id);
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
        log.info("Admin created customer: {}", user.getEmail());
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
        log.info("Admin registered: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    // ── Helpers ──────────────────────────────────────────────
    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
            .token(jwtProvider.generateToken(user))
            .refreshToken(jwtProvider.generateRefreshToken(user))
            .user(toDto(user))
            .build();
    }

    private UserDto toDto(User user) {
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
            .build();
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
            log.warn("Failed to send notification event for {}: {}", user.getEmail(), e.getMessage());
        }
    }
}
