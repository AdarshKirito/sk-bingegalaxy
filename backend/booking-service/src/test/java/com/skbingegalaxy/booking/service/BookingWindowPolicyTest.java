package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** V84 — booking-window rules (G5) and the permitted-duration allow-list (B5). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingWindowPolicyTest {

    private static final long BINGE_ID = 5L;
    /** A venue well east of UTC, so a naive server-clock implementation would fail these. */
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Kolkata");

    @Mock private VenueClockService venueClock;
    @InjectMocks private BookingWindowPolicy policy;

    private Binge binge;

    @BeforeEach
    void setUp() {
        when(venueClock.zoneOf(any())).thenReturn(VENUE_ZONE);
        binge = new Binge();
        binge.setId(BINGE_ID);
        binge.setMinNoticeMinutes(0);
        binge.setMaxAdvanceDays(null);
    }

    private ZonedDateTime venueNow() {
        return ZonedDateTime.now(VENUE_ZONE);
    }

    @Nested
    @DisplayName("minimum notice")
    class MinimumNotice {

        @Test
        void zeroNotice_allowsAnImminentBooking() {
            ZonedDateTime soon = venueNow().plusMinutes(5);
            assertThatCode(() -> policy.assertWithinBookingWindow(
                binge, soon.toLocalDate(), soon.toLocalTime(), 365))
                .doesNotThrowAnyException();
        }

        @Test
        void bookingInsideTheNoticeWindow_isRejected() {
            binge.setMinNoticeMinutes(120);
            ZonedDateTime tooSoon = venueNow().plusMinutes(30);

            assertThatThrownBy(() -> policy.assertWithinBookingWindow(
                binge, tooSoon.toLocalDate(), tooSoon.toLocalTime(), 365))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 hours");
        }

        @Test
        void bookingOutsideTheNoticeWindow_isAccepted() {
            binge.setMinNoticeMinutes(120);
            ZonedDateTime later = venueNow().plusMinutes(180);

            assertThatCode(() -> policy.assertWithinBookingWindow(
                binge, later.toLocalDate(), later.toLocalTime(), 365))
                .doesNotThrowAnyException();
        }

        @Test
        void noticeIsMeasuredOnTheVenueClock_notTheServerClock() {
            // The whole point of resolving the zone: a booking 3 hours out in venue-local
            // terms must pass a 2-hour notice rule no matter where the server runs.
            binge.setMinNoticeMinutes(120);
            ZonedDateTime later = venueNow().plusHours(3);

            assertThatCode(() -> policy.assertWithinBookingWindow(
                binge, later.toLocalDate(), later.toLocalTime(), 365))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("advance horizon")
    class AdvanceHorizon {

        @Test
        void venueHorizonOverridesThePlatformDefault() {
            binge.setMaxAdvanceDays(90);
            LocalDate beyondVenue = venueNow().toLocalDate().plusDays(120);

            assertThatThrownBy(() -> policy.assertWithinBookingWindow(
                binge, beyondVenue, LocalTime.of(19, 0), 365))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("90 days");
        }

        @Test
        void withinTheVenueHorizon_isAccepted() {
            binge.setMaxAdvanceDays(90);
            LocalDate inside = venueNow().toLocalDate().plusDays(60);

            assertThatCode(() -> policy.assertWithinBookingWindow(binge, inside, LocalTime.of(19, 0), 365))
                .doesNotThrowAnyException();
        }

        @Test
        void nullVenueHorizon_fallsBackToThePlatformDefault() {
            binge.setMaxAdvanceDays(null);
            LocalDate beyondPlatform = venueNow().toLocalDate().plusDays(400);

            assertThatThrownBy(() -> policy.assertWithinBookingWindow(
                binge, beyondPlatform, LocalTime.of(19, 0), 365))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("365 days");
        }

        @Test
        void nullBinge_isANoOpRatherThanACrash() {
            assertThatCode(() -> policy.assertWithinBookingWindow(
                null, LocalDate.now().plusDays(1), LocalTime.of(19, 0), 365))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("permitted durations")
    class PermittedDurations {

        private EventType eventTypeWith(String csv) {
            EventType et = new EventType();
            et.setMinHours(1);
            et.setMaxHours(8);
            et.setPermittedDurationsCsv(csv);
            return et;
        }

        @Test
        void noAllowList_permitsAnyDuration() {
            EventType et = eventTypeWith(null);
            assertThat(policy.permittedDurations(et)).isEmpty();
            assertThatCode(() -> policy.assertDurationPermitted(et, 90)).doesNotThrowAnyException();
        }

        @Test
        void allowListedDuration_isAccepted() {
            EventType et = eventTypeWith("120,180,240");
            assertThatCode(() -> policy.assertDurationPermitted(et, 180)).doesNotThrowAnyException();
        }

        @Test
        void durationOutsideTheAllowList_isRejectedWithTheOfferedOptions() {
            EventType et = eventTypeWith("120,180,240");

            assertThatThrownBy(() -> policy.assertDurationPermitted(et, 90))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 hours")
                .hasMessageContaining("3 hours")
                .hasMessageContaining("4 hours");
        }

        @Test
        void storedValuesAreReturnedSorted() {
            assertThat(policy.permittedDurations(eventTypeWith("240,120,180")))
                .containsExactly(120, 180, 240);
        }

        @Test
        void malformedStoredValue_degradesToNoAllowListRatherThanBlockingBooking() {
            // A bad row must not take the venue offline; the permissive pre-V84
            // behaviour is the safe direction to fail in.
            EventType et = eventTypeWith("abc,120");
            assertThat(policy.permittedDurations(et)).isEmpty();
            assertThatCode(() -> policy.assertDurationPermitted(et, 90)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("saving an allow-list")
    class SavingAllowList {

        @Test
        void emptyOrNull_clearsTheAllowList() {
            assertThat(policy.normaliseDurationsForSave(null, 1, 8)).isNull();
            assertThat(policy.normaliseDurationsForSave(List.of(), 1, 8)).isNull();
        }

        @Test
        void valuesAreDeduplicatedAndSorted() {
            assertThat(policy.normaliseDurationsForSave(List.of(240, 120, 120, 180), 1, 8))
                .isEqualTo("120,180,240");
        }

        @Test
        void moreThanFourOptions_isRejected() {
            assertThatThrownBy(() -> policy.normaliseDurationsForSave(List.of(60, 120, 180, 240, 300), 1, 8))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("At most 4");
        }

        @Test
        void nonThirtyMinuteMultiple_isRejected() {
            assertThatThrownBy(() -> policy.normaliseDurationsForSave(List.of(45), 1, 8))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("30-minute blocks");
        }

        @Test
        void durationOutsideTheEventTypeRange_isRejected() {
            assertThatThrownBy(() -> policy.normaliseDurationsForSave(List.of(600), 1, 8))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outside this event type's 1–8 hour range");
        }

        @Test
        void roundTripsThroughTheCsvHelpers() {
            String csv = policy.normaliseDurationsForSave(List.of(120, 180), 1, 8);
            assertThat(BookingWindowPolicy.parse(csv)).containsExactly(120, 180);
            assertThat(BookingWindowPolicy.toCsv(List.of(180, 120))).isEqualTo("120,180");
        }
    }

    @Nested
    @DisplayName("human-readable durations")
    class Describe {

        @Test
        void readsNaturallyBecauseCustomersSeeIt() {
            assertThat(BookingWindowPolicy.describeMinutes(30)).isEqualTo("30 minutes");
            assertThat(BookingWindowPolicy.describeMinutes(60)).isEqualTo("1 hour");
            assertThat(BookingWindowPolicy.describeMinutes(120)).isEqualTo("2 hours");
            assertThat(BookingWindowPolicy.describeMinutes(150)).isEqualTo("2 hours 30 minutes");
        }
    }
}
