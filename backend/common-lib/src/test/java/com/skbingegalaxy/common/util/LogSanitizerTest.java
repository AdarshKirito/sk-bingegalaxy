package com.skbingegalaxy.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression fence for the PII masker used in hot log paths. A future
 * refactor that accidentally logs a raw email / phone / token would be
 * caught here — the exact output shape is part of the contract because
 * downstream log-review tooling greps for the fixed "***" marker.
 */
class LogSanitizerTest {

    @Nested
    @DisplayName("maskEmail")
    class MaskEmail {

        @Test
        @DisplayName("keeps first two local-part chars + full domain")
        void masksStandardEmail() {
            assertThat(LogSanitizer.maskEmail("john.doe@example.com"))
                .isEqualTo("jo***@example.com");
        }

        @Test
        @DisplayName("short local part keeps only one char")
        void handlesShortLocalPart() {
            assertThat(LogSanitizer.maskEmail("a@b.co"))
                .isEqualTo("a***@b.co");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "no-at-sign", "@no-local.com"})
        @DisplayName("returns *** for null / blank / malformed")
        void redactsInvalid(String input) {
            assertThat(LogSanitizer.maskEmail(input)).isEqualTo("***");
        }
    }

    @Nested
    @DisplayName("maskPhone")
    class MaskPhone {

        @Test
        @DisplayName("preserves country code + last 4 digits")
        void masksE164Phone() {
            assertThat(LogSanitizer.maskPhone("+919876543210"))
                .isEqualTo("+91***3210");
        }

        @Test
        @DisplayName("strips non-digit characters before masking")
        void stripsFormatting() {
            assertThat(LogSanitizer.maskPhone("(987) 654-3210"))
                .isEqualTo("***3210");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"123", "+1"})
        @DisplayName("returns *** for too-short or missing values")
        void redactsInvalid(String input) {
            assertThat(LogSanitizer.maskPhone(input)).isEqualTo("***");
        }
    }

    @Nested
    @DisplayName("maskToken")
    class MaskToken {

        @Test
        @DisplayName("keeps first and last 4 chars for long tokens")
        void masksLongToken() {
            String jwt = "eyJhbGciOiJIUzI1NiJ9.payload.signature";
            assertThat(LogSanitizer.maskToken(jwt))
                .isEqualTo(jwt.substring(0, 4) + "***" + jwt.substring(jwt.length() - 4));
        }

        @Test
        @DisplayName("short tokens collapse to ***")
        void redactsShortToken() {
            assertThat(LogSanitizer.maskToken("short")).isEqualTo("***");
        }

        @Test
        @DisplayName("null returns ***")
        void redactsNull() {
            assertThat(LogSanitizer.maskToken(null)).isEqualTo("***");
        }
    }
}
