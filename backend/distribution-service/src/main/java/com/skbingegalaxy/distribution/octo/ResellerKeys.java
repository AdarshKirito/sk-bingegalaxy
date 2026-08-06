package com.skbingegalaxy.distribution.octo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minting and verifying the key a reseller presents to SK Binge.
 *
 * <p>Kept apart from {@code ResellerAuthenticator} so the cryptography can be tested
 * without a database, and so the one place that decides what a key looks like is not the
 * same place that decides who is allowed in.
 *
 * <p><b>The key is never stored.</b> Only its SHA-256 is, which is enough to verify a
 * presented key and not enough to produce one — so a dump of {@code distribution_db}
 * yields nothing an attacker can present. Unlike a password there is no need for a slow
 * KDF here: the key is 256 bits from a CSPRNG, not something a human chose, so there is
 * no guessable keyspace for bcrypt/argon2 to defend.
 */
public final class ResellerKeys {

    private ResellerKeys() {}

    /**
     * A recognisable prefix, so a key found in a log or a support ticket can be
     * identified — and revoked — without anyone having to work out what it is.
     */
    public static final String PREFIX = "skbg_octo_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 bytes. The same strength the platform's other bearer secrets use. */
    private static final int KEY_BYTES = 32;

    /** The plaintext key and everything that gets persisted about it. */
    public record IssuedKey(String plaintext, String hash, String hint) {}

    public static IssuedKey issue() {
        byte[] raw = new byte[KEY_BYTES];
        RANDOM.nextBytes(raw);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new IssuedKey(plaintext, sha256Hex(plaintext), hint(plaintext));
    }

    /** Lower-case hex SHA-256. Hex rather than base64 so it is greppable in a psql session. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Last four characters only — enough for an operator to tell two keys apart in the
     * console, not enough to narrow a 256-bit search by anything that matters.
     */
    public static String hint(String plaintext) {
        if (plaintext == null || plaintext.length() < 4) return null;
        return "••••" + plaintext.substring(plaintext.length() - 4);
    }

    /**
     * Constant-time digest comparison.
     *
     * <p>Both sides are public-length hex digests, so the timing channel is far weaker
     * than it would be against a raw secret — but a lookup that returns a candidate row
     * still ends in a comparison, and there is no reason for that comparison to be the
     * one place that leaks.
     */
    public static boolean matches(String presentedKey, String storedHash) {
        if (presentedKey == null || storedHash == null) return false;
        return MessageDigest.isEqual(
            sha256Hex(presentedKey).getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
