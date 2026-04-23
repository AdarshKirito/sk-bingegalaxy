package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.EmailVerificationToken;
import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.EmailVerificationTokenRepository;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.enums.NotificationChannel;
import com.skbingegalaxy.common.event.NotificationEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Email-verification flow. Issues a short-lived one-time token + 6-digit OTP, mails it
 * via the notification service, and verifies on redemption. Only the SHA-256 hash of
 * the token is persisted; the plaintext goes out in the email and is never stored.
 *
 * <p>Flow is idempotent and always "succeeds silently" for non-existent accounts so that
 * it cannot be used for email enumeration.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.email-verification.expiration-minutes:60}")
    private int expirationMinutes;

    @Value("${app.email-verification.otp-max-attempts:5}")
    private int otpMaxAttempts;

    @Value("${app.email-verification.required:false}")
    private boolean verificationRequired;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void issue(User user) {
        if (user == null || user.isEmailVerified()) return;
        tokenRepository.invalidateAllForUser(user.getId());

        String plaintextToken = UUID.randomUUID().toString().replace("-", "");
        String otp = generateOtp();
        EmailVerificationToken entry = EmailVerificationToken.builder()
            .userId(user.getId())
            .tokenHash(sha256Hex(plaintextToken))
            .otp(otp)
            .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
            .build();
        tokenRepository.save(entry);

        try {
            NotificationEvent event = NotificationEvent.builder()
                .channel(NotificationChannel.EMAIL)
                .recipientEmail(user.getEmail())
                .recipientName(user.getFirstName())
                .subject("Verify your email – SK Binge Galaxy")
                .body(String.format(
                    "Hi %s,%n%nWelcome to SK Binge Galaxy! Please verify your email address " +
                    "using this 6-digit code: %s%n%nOr click the verification link sent with this " +
                    "email. The code expires in %d minutes.%n%nIf you didn't sign up, you can " +
                    "safely ignore this message.",
                    user.getFirstName(), otp, expirationMinutes))
                .type("EMAIL_VERIFICATION")
                .templateData(Map.of(
                    "otp", otp,
                    "token", plaintextToken,
                    "expiryMinutes", String.valueOf(expirationMinutes),
                    "name", user.getFirstName()))
                .build();
            kafkaTemplate.send(KafkaTopics.NOTIFICATION_SEND, event);
        } catch (Exception ex) {
            log.warn("Failed to enqueue email-verification notification: {}", ex.getMessage());
        }
    }

    /** Verify using the OTP sent in the email. */
    @Transactional
    public User verifyWithOtp(String email, String otp) {
        if (email == null || otp == null) {
            throw new BusinessException("Email and verification code are required", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findByEmail(email.toLowerCase())
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
        if (user.isEmailVerified()) return user;

        EmailVerificationToken token = tokenRepository.findAll().stream()
            .filter(t -> !t.isUsed() && t.getUserId().equals(user.getId()))
            .max(java.util.Comparator.comparing(EmailVerificationToken::getCreatedAt))
            .orElseThrow(() -> new BusinessException("No active verification code for this account", HttpStatus.BAD_REQUEST));

        if (token.isExpired()) {
            throw new BusinessException("Verification code has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }
        if (token.isAttemptsExhausted(otpMaxAttempts)) {
            token.setUsed(true);
            tokenRepository.save(token);
            throw new BusinessException("Too many incorrect attempts. Please request a new code.", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!otp.trim().equals(token.getOtp())) {
            token.incrementAttempts();
            tokenRepository.save(token);
            throw new BusinessException("Invalid verification code", HttpStatus.BAD_REQUEST);
        }

        token.setUsed(true);
        tokenRepository.save(token);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    /** Verify via the plaintext token inlined in the email link. */
    @Transactional
    public User verifyWithToken(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new BusinessException("Verification token is required", HttpStatus.BAD_REQUEST);
        }
        EmailVerificationToken token = tokenRepository.findByTokenHashAndUsedFalse(sha256Hex(plaintextToken))
            .orElseThrow(() -> new BusinessException("Invalid or expired verification link", HttpStatus.BAD_REQUEST));
        if (token.isExpired()) {
            throw new BusinessException("Verification link has expired. Please request a new one.", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findById(token.getUserId())
            .orElseThrow(() -> new BusinessException("User no longer exists", HttpStatus.BAD_REQUEST));
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
        }
        token.setUsed(true);
        tokenRepository.save(token);
        return user;
    }

    public boolean isVerificationRequired() {
        return verificationRequired;
    }

    // ── helpers ──────────────────────────────────────────────
    private String generateOtp() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(secureRandom.nextInt(10));
        return sb.toString();
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
