package com.skbingegalaxy.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Booking attribution (distribution G-B)")
class BookingAttributionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 12, 0);

    @Nested
    @DisplayName("Canonical form")
    class Canonicalisation {

        @Test
        @DisplayName("lowercases and trims, matching the DB CHECK")
        void canonicalises() {
            assertThat(BookingAttribution.canonicalSource("  Google_Things_To_Do "))
                .isEqualTo("google_things_to_do");
        }

        @Test
        @DisplayName("blank and null collapse to null, not to an empty bucket")
        void blankIsNull() {
            assertThat(BookingAttribution.canonicalSource("   ")).isNull();
            assertThat(BookingAttribution.canonicalSource(null)).isNull();
        }

        @Test
        @DisplayName("an over-long source is truncated to the column width, not rejected")
        void truncatesRatherThanRejects() {
            String source = BookingAttribution.canonicalSource("x".repeat(200));
            // The column is VARCHAR(64). Throwing here would turn a caller's bad
            // utm_source into a failed booking.
            assertThat(source).hasSize(BookingAttribution.MAX_SOURCE_LENGTH);
        }
    }

    @Nested
    @DisplayName("What gets recorded")
    class Recording {

        @Test
        @DisplayName("an UNRECOGNISED source is still recorded verbatim")
        void unknownSourceIsKept() {
            BookingAttribution a = BookingAttribution.of(
                "some_channel_nobody_has_integrated_yet", "click-1", NOW.minusDays(1), NOW);

            // Discarding unknown sources would throw away the first data about a new
            // channel — exactly the data needed to decide whether to build it.
            assertThat(a).isNotNull();
            assertThat(a.source()).isEqualTo("some_channel_nobody_has_integrated_yet");
        }

        @Test
        @DisplayName("a ref without a source is dropped — it belongs to no bucket")
        void refWithoutSourceIsDropped() {
            assertThat(BookingAttribution.of(null, "click-1", NOW, NOW)).isNull();
        }

        @Test
        @DisplayName("a source without a ref is kept — plain utm_source is normal")
        void sourceWithoutRefIsKept() {
            BookingAttribution a = BookingAttribution.of("google", null, NOW, NOW);
            assertThat(a).isNotNull();
            assertThat(a.ref()).isNull();
        }

        @Test
        @DisplayName("malformed input returns null instead of throwing")
        void neverThrows() {
            // The customer is trying to buy something. Losing an analytics dimension is
            // acceptable; losing the sale is not.
            assertThat(BookingAttribution.of("", "", null, NOW)).isNull();
        }
    }

    @Nested
    @DisplayName("30-day window")
    class Window {

        @Test
        @DisplayName("inside the window is credited")
        void insideWindow() {
            assertThat(BookingAttribution.of("google", "c", NOW.minusDays(29), NOW)).isNotNull();
        }

        @Test
        @DisplayName("beyond 30 days is treated as organic")
        void beyondWindow() {
            assertThat(BookingAttribution.of("google", "c", NOW.minusDays(31), NOW)).isNull();
        }

        @Test
        @DisplayName("a future capture time fails closed rather than lasting forever")
        void futureCaptureIsExpired() {
            // The timestamp comes from the customer's browser clock. Trusting it would
            // let a skewed or forged clock hold attribution open indefinitely.
            assertThat(BookingAttribution.isExpired(NOW.plusDays(1), NOW)).isTrue();
            assertThat(BookingAttribution.of("google", "c", NOW.plusDays(1), NOW)).isNull();
        }

        @Test
        @DisplayName("a missing capture time does not expire the referral")
        void nullCaptureIsNotExpired() {
            assertThat(BookingAttribution.of("google", "c", null, NOW)).isNotNull();
        }
    }
}
