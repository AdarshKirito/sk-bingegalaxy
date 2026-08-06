package com.skbingegalaxy.distribution.octo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Availability request bounding (DIST-R6)")
class AvailabilityRequestPolicyTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 1);

    @Test
    @DisplayName("a 365-day calendar poll is CLAMPED, not rejected")
    void wideCalendarIsClamped() {
        // A reseller asking for a year behaves exactly as OCTO expects. Refusing would
        // break a conforming client; clamping answers honestly at a bounded cost.
        var w = AvailabilityRequestPolicy.clamp(D, D.plusDays(365),
            AvailabilityRequestPolicy.MAX_CALENDAR_DAYS);

        assertThat(w.clamped()).isTrue();
        assertThat(w.from()).isEqualTo(D);
        assertThat(w.to()).isEqualTo(D.plusDays(AvailabilityRequestPolicy.MAX_CALENDAR_DAYS - 1));
    }

    @Test
    @DisplayName("the FINE window is far tighter than the calendar")
    void detailWindowIsTighter() {
        // The fine query returns every slot with its price, so cost per day is far
        // higher — which is the entire reason OCTO splits the two endpoints.
        assertThat(AvailabilityRequestPolicy.MAX_DETAIL_DAYS)
            .isLessThan(AvailabilityRequestPolicy.MAX_CALENDAR_DAYS);

        var w = AvailabilityRequestPolicy.clamp(D, D.plusDays(30),
            AvailabilityRequestPolicy.MAX_DETAIL_DAYS);
        assertThat(w.to()).isEqualTo(D.plusDays(AvailabilityRequestPolicy.MAX_DETAIL_DAYS - 1));
    }

    @Test
    @DisplayName("a window inside the limit is returned untouched")
    void narrowWindowUnchanged() {
        var w = AvailabilityRequestPolicy.clamp(D, D.plusDays(3),
            AvailabilityRequestPolicy.MAX_CALENDAR_DAYS);
        assertThat(w.clamped()).isFalse();
        assertThat(w.to()).isEqualTo(D.plusDays(3));
    }

    @Test
    @DisplayName("a nonsensical range is rejected rather than silently corrected")
    void nonsensicalRangeRejected() {
        // to-before-from is a client bug. Answering it with a quietly fixed window
        // hides the bug and returns data for dates nobody asked about.
        assertThatThrownBy(() -> AvailabilityRequestPolicy.clamp(D, D.minusDays(1), 62))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be before");
        assertThatThrownBy(() -> AvailabilityRequestPolicy.clamp(null, D, 62))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    }

    @Test
    @DisplayName("a single-day window is exactly one day")
    void singleDayIsOneDay() {
        var w = AvailabilityRequestPolicy.clamp(D, D, AvailabilityRequestPolicy.MAX_DETAIL_DAYS);
        assertThat(w.from()).isEqualTo(w.to());
        assertThat(w.clamped()).isFalse();
    }
}
