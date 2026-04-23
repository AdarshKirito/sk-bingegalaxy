package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.auth.repository.UserRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Time-based One-Time Password (RFC 6238) provider for admin / super-admin 2FA.
 * <p>
 * Uses HMAC-SHA1 (the canonical TOTP algorithm supported by Google Authenticator,
 * Authy, Microsoft Authenticator, 1Password, etc.). Secrets are 160-bit (20 bytes),
 * Base32-encoded per the {@code otpauth://} URI standard. Verification accepts a
 * ±1-step window to tolerate clock skew.
 * <p>
 * Implemented with the JDK only so no new third-party dependency is introduced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TotpService {

    private static final int SECRET_BYTES = 20;       // 160 bit, RFC 6238 recommended
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS       = 6;
    private static final int ALLOWED_SKEW = 1;        // ±1 step (±30s)
    private static final String HMAC_ALG  = "HmacSHA1";
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final UserRepository userRepository;

    @Value("${app.totp.issuer:SK Binge Galaxy}")
    private String totpIssuer;

    private final SecureRandom secureRandom = new SecureRandom();

    @lombok.Value
    public static class EnrollmentPayload {
        String secret;        // Base32 secret — only shown to the user during enrolment
        String otpauthUri;    // otpauth://totp/...   (feed to QR code generator)
        List<String> recoveryCodes;  // plaintext — only displayed once at enrolment
    }

    /**
     * Start MFA enrolment. Generates a secret + recovery codes but does NOT enable MFA
     * yet — the user must confirm a code from their authenticator app via {@link #confirmEnrollment}.
     */
    @Transactional
    public EnrollmentPayload beginEnrollment(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND));
        if (user.isMfaEnabled()) {
            throw new BusinessException("MFA is already enabled. Disable it before re-enrolling.", HttpStatus.BAD_REQUEST);
        }
        String secret = generateBase32Secret();
        user.setMfaSecret(secret);
        user.setMfaEnabled(false);  // stays disabled until confirmEnrollment succeeds
        user.setMfaRecoveryCodesHash(null);
        userRepository.save(user);

        List<String> recoveryPlain = generateRecoveryCodes(10);
        // recovery codes returned once; they are stored hashed on confirmEnrollment.

        String otpauth = buildOtpauthUri(user.getEmail(), secret);
        return new EnrollmentPayload(secret, otpauth, recoveryPlain);
    }

    /**
     * Confirm enrolment by validating the first code from the user's authenticator app,
     * then persist the hashed recovery codes and mark MFA enabled.
     */
    @Transactional
    public void confirmEnrollment(Long userId, String code, List<String> recoveryCodesPlain) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND));
        if (user.getMfaSecret() == null) {
            throw new BusinessException("Start MFA enrolment first", HttpStatus.BAD_REQUEST);
        }
        if (!verifyCode(user.getMfaSecret(), code)) {
            throw new BusinessException("Invalid verification code", HttpStatus.BAD_REQUEST);
        }
        user.setMfaEnabled(true);
        user.setMfaEnrolledAt(LocalDateTime.now());
        user.setMfaRecoveryCodesHash(hashRecoveryCodes(recoveryCodesPlain));
        userRepository.save(user);
    }

    /** Disable MFA after proving possession of a valid code (or recovery code). */
    @Transactional
    public void disable(Long userId, String code) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND));
        if (!user.isMfaEnabled()) return;
        if (!verifyCodeOrRecovery(user, code)) {
            throw new BusinessException("Invalid verification code", HttpStatus.BAD_REQUEST);
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaEnrolledAt(null);
        user.setMfaRecoveryCodesHash(null);
        userRepository.save(user);
    }

    /**
     * Verify a supplied code against the user's TOTP secret or burn a recovery code.
     * Returns {@code true} if the code is valid; on recovery-code redemption the used
     * code is removed from the persisted list so it can't be replayed.
     */
    @Transactional
    public boolean verifyCodeOrRecovery(User user, String code) {
        if (code == null || code.isBlank()) return false;
        String normalised = code.replace("-", "").replace(" ", "").trim();
        if (verifyCode(user.getMfaSecret(), normalised)) {
            return true;
        }
        // Try recovery code
        String hash = sha256Hex(normalised.toUpperCase());
        String existing = user.getMfaRecoveryCodesHash();
        if (existing == null || existing.isBlank()) return false;
        List<String> hashes = new ArrayList<>(Arrays.asList(existing.split(",")));
        if (hashes.remove(hash)) {
            user.setMfaRecoveryCodesHash(String.join(",", hashes));
            userRepository.save(user);
            log.info("MFA recovery code used for userId={}; {} codes remaining", user.getId(), hashes.size());
            return true;
        }
        return false;
    }

    public boolean verifyCode(String base32Secret, String code) {
        if (base32Secret == null || code == null) return false;
        String digits = code.replaceAll("\\s+", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) return false;
        long step = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        byte[] key = base32Decode(base32Secret);
        for (int i = -ALLOWED_SKEW; i <= ALLOWED_SKEW; i++) {
            String expected = generateTotp(key, step + i);
            if (constantTimeEquals(expected, digits)) return true;
        }
        return false;
    }

    public List<String> regenerateRecoveryCodes(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND));
        if (!user.isMfaEnabled()) {
            throw new BusinessException("MFA is not enabled", HttpStatus.BAD_REQUEST);
        }
        List<String> plain = generateRecoveryCodes(10);
        user.setMfaRecoveryCodesHash(hashRecoveryCodes(plain));
        userRepository.save(user);
        return plain;
    }

    // ── RFC 6238 core ────────────────────────────────────────
    private String generateTotp(byte[] key, long counter) {
        byte[] data = new byte[8];
        long value = counter;
        for (int i = 7; i >= 0; i--) { data[i] = (byte) (value & 0xff); value >>= 8; }
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            byte[] hmac = mac.doFinal(data);
            int offset = hmac[hmac.length - 1] & 0x0F;
            int binary = ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) <<  8)
                | (hmac[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // ── secret + recovery-code generation ────────────────────
    private String generateBase32Secret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return base32Encode(bytes);
    }

    private List<String> generateRecoveryCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] b = new byte[6];
            secureRandom.nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(Character.forDigit(((x >> 4) & 0x0F), 16));
                sb.append(Character.forDigit((x & 0x0F), 16));
            }
            String hex = sb.toString().toUpperCase();
            codes.add(hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12));
        }
        return codes;
    }

    private String hashRecoveryCodes(List<String> plain) {
        if (plain == null || plain.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plain.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sha256Hex(plain.get(i).replace("-", "").toUpperCase()));
        }
        return sb.toString();
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── otpauth URI ──────────────────────────────────────────
    private String buildOtpauthUri(String email, String secret) {
        String label = URLEncoder.encode(totpIssuer + ":" + email, StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode(totpIssuer, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
            + "?secret=" + secret
            + "&issuer=" + issuer
            + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    // ── minimal Base32 (RFC 4648, no padding) ────────────────
    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1f;
                sb.append(BASE32_ALPHABET[idx]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(BASE32_ALPHABET[idx]);
        }
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replaceAll("\\s+", "").toUpperCase();
        int bitsLeft = 0, buffer = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int idx = 0;
        for (char c : clean.toCharArray()) {
            int v = indexOfChar(BASE32_ALPHABET, c);
            if (v < 0) throw new IllegalArgumentException("Invalid base32 character: " + c);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private int indexOfChar(char[] arr, char c) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == c) return i;
        return -1;
    }
}
