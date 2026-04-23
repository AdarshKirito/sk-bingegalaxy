package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Loyalty v2 — human-readable member-number generator.
 *
 * <p>Format: {@code SK-XXXX-XXXX} (11 characters including dashes).
 * Alphabet excludes visually confusable glyphs ({@code 0/O, 1/I/L})
 * so customers can correctly read their number off a card / phone
 * screen.
 *
 * <p>Uniqueness: the backing database has a UNIQUE constraint on
 * {@code member_number}; this generator retries (up to 8 attempts)
 * with a fresh random draw on collision.  With a 30-character alphabet
 * and 8 characters of entropy, the namespace is ≈ 6.56 × 10¹¹ —
 * collisions at human scale are negligible but we still honor the
 * DB constraint.
 */
@Component
@RequiredArgsConstructor
public class MemberNumberGenerator {

    // No '0', 'O', '1', 'I', 'L' — reduces read/dictation errors.
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ATTEMPTS = 8;

    private final LoyaltyMembershipRepository membershipRepository;

    public String generate() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String candidate = randomNumber();
            if (!membershipRepository.existsByMemberNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique member number after " + MAX_ATTEMPTS + " attempts — "
                        + "namespace may be exhausted, investigate.");
    }

    private String randomNumber() {
        StringBuilder sb = new StringBuilder("SK-");
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        sb.append('-');
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
