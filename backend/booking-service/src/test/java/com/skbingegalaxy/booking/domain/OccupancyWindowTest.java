package com.skbingegalaxy.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V81. These tests encode the rule that was missing before the migration: a
 * reservation occupies more than it bills for, and two reservations conflict on
 * their occupancy windows.
 */
class OccupancyWindowTest {

    /** 19:00 in minutes-since-midnight. */
    private static final int SEVEN_PM = 19 * 60;
    /** 22:00 in minutes-since-midnight. */
    private static final int TEN_PM = 22 * 60;

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void zeroBuffers_windowEqualsBillableInterval() {
            OccupancyWindow w = OccupancyWindow.of(SEVEN_PM, 180, 0, 0);
            assertThat(w.startMinute()).isEqualTo(SEVEN_PM);
            assertThat(w.endMinute()).isEqualTo(TEN_PM);
            assertThat(w.lengthMinutes()).isEqualTo(180);
        }

        @Test
        void buffersWidenBothEnds() {
            OccupancyWindow w = OccupancyWindow.of(SEVEN_PM, 180, 30, 45);
            assertThat(w.startMinute()).isEqualTo(SEVEN_PM - 30);
            assertThat(w.endMinute()).isEqualTo(TEN_PM + 45);
            assertThat(w.lengthMinutes()).isEqualTo(180 + 75);
        }

        @Test
        void bufferAcrossMidnight_producesNegativeStartRatherThanClamping() {
            // 00:15 start with a 30-minute setup. Clamping to 0 would silently
            // shrink the window and let a conflict through; a negative value is
            // arithmetically correct and overlap math handles it.
            OccupancyWindow w = OccupancyWindow.of(15, 60, 30, 0);
            assertThat(w.startMinute()).isEqualTo(-15);
        }

        @Test
        void durationBelowOne_isTreatedAsOneMinute() {
            // A malformed row must still occupy something; a zero-length window
            // would overlap nothing and read as universally available.
            assertThat(OccupancyWindow.of(600, 0, 0, 0).lengthMinutes()).isEqualTo(1);
            assertThat(OccupancyWindow.of(600, -5, 0, 0).lengthMinutes()).isEqualTo(1);
        }

        @Test
        void buffersAreClampedToTheRangeTheDatabaseAccepts() {
            OccupancyWindow w = OccupancyWindow.of(SEVEN_PM, 60, 9999, -20);
            assertThat(w.startMinute()).isEqualTo(SEVEN_PM - OccupancyWindow.MAX_BUFFER_MINUTES);
            assertThat(w.endMinute()).isEqualTo(SEVEN_PM + 60);
        }

        @Test
        void nullBufferIsZero() {
            assertThat(OccupancyWindow.clampBuffer(null)).isZero();
        }

        @Test
        void endBeforeStart_isRejected() {
            assertThatThrownBy(() -> new OccupancyWindow(600, 599))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot end before it starts");
        }
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        @Test
        void backToBackWithNoBuffers_doesNotOverlap() {
            // The pre-V81 behaviour, preserved exactly for venues that set no buffers.
            OccupancyWindow first = OccupancyWindow.of(SEVEN_PM, 180, 0, 0);   // 19:00-22:00
            OccupancyWindow second = OccupancyWindow.of(TEN_PM, 180, 0, 0);    // 22:00-01:00
            assertThat(first.overlaps(second)).isFalse();
            assertThat(second.overlaps(first)).isFalse();
        }

        @Test
        void backToBackWithCleanupBuffer_overlaps() {
            // THE bug this migration exists to fix: a 19:00-22:00 party needing
            // 45 minutes to reset must block the 22:00 slot.
            OccupancyWindow first = OccupancyWindow.of(SEVEN_PM, 180, 0, 45);  // occupies 19:00-22:45
            OccupancyWindow second = OccupancyWindow.of(TEN_PM, 180, 0, 45);   // starts 22:00
            assertThat(first.overlaps(second)).isTrue();
            assertThat(second.overlaps(first)).isTrue();
        }

        @Test
        void setupBufferOnTheLaterBooking_alsoCreatesTheConflict() {
            // Widening only one side would miss this: the earlier booking has no
            // cleanup time, but the later one needs 30 minutes to set up.
            OccupancyWindow first = OccupancyWindow.of(SEVEN_PM, 180, 0, 0);   // 19:00-22:00
            OccupancyWindow second = OccupancyWindow.of(TEN_PM, 120, 30, 0);   // occupies 21:30-
            assertThat(first.overlaps(second)).isTrue();
        }

        @Test
        void gapWiderThanTheBuffers_doesNotOverlap() {
            OccupancyWindow first = OccupancyWindow.of(SEVEN_PM, 120, 0, 30);  // 19:00-21:30
            OccupancyWindow second = OccupancyWindow.of(TEN_PM, 120, 30, 0);   // 21:30-
            // Touching endpoints on a half-open interval: legal, exactly enough time.
            assertThat(first.overlaps(second)).isFalse();
        }

        @Test
        void oneMinuteShortOfEnoughTurnover_overlaps() {
            OccupancyWindow first = OccupancyWindow.of(SEVEN_PM, 120, 0, 31);  // 19:00-21:31
            OccupancyWindow second = OccupancyWindow.of(TEN_PM, 120, 30, 0);   // 21:30-
            assertThat(first.overlaps(second)).isTrue();
        }

        @Test
        void containedWindow_overlaps() {
            OccupancyWindow outer = OccupancyWindow.of(SEVEN_PM, 240, 0, 0);
            OccupancyWindow inner = OccupancyWindow.of(SEVEN_PM + 60, 60, 0, 0);
            assertThat(outer.overlaps(inner)).isTrue();
            assertThat(inner.overlaps(outer)).isTrue();
        }

        @Test
        void identicalWindows_overlap() {
            OccupancyWindow a = OccupancyWindow.of(SEVEN_PM, 180, 15, 15);
            OccupancyWindow b = OccupancyWindow.of(SEVEN_PM, 180, 15, 15);
            assertThat(a.overlaps(b)).isTrue();
        }

        @Test
        void distantWindows_doNotOverlap() {
            OccupancyWindow morning = OccupancyWindow.of(9 * 60, 60, 60, 60);
            OccupancyWindow evening = OccupancyWindow.of(SEVEN_PM, 60, 60, 60);
            assertThat(morning.overlaps(evening)).isFalse();
        }

        @Test
        void overlapIsSymmetric_acrossABufferBoundary() {
            OccupancyWindow a = OccupancyWindow.of(600, 60, 0, 20);
            OccupancyWindow b = OccupancyWindow.of(670, 60, 20, 0);
            assertThat(a.overlaps(b)).isEqualTo(b.overlaps(a));
        }
    }

    @Nested
    @DisplayName("billable-interval factory")
    class BillableInterval {

        @Test
        void matchesTheZeroBufferWindow() {
            assertThat(OccupancyWindow.ofBillableInterval(SEVEN_PM, 180))
                .isEqualTo(OccupancyWindow.of(SEVEN_PM, 180, 0, 0));
        }
    }
}
