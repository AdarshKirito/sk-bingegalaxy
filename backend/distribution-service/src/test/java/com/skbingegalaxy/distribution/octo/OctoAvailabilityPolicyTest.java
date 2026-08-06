package com.skbingegalaxy.distribution.octo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a 30-minute slot grid into windows a reseller can actually buy.
 *
 * <p>The endpoint used to proxy availability-service's internal day view, which contained
 * no {@code availabilityId} at all — so the booking endpoint, which requires one, could
 * never be reached with data this API had produced. The two halves of the supplier
 * surface did not meet, and nothing failed: every request answered 200.
 */
@DisplayName("OCTO availability windows")
class OctoAvailabilityPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    /** 18:00, 18:30, 19:00, 19:30 free — a two-hour run. */
    private static final Set<Integer> FREE_18_TO_20 = Set.of(1080, 1110, 1140, 1170);

    @Nested
    @DisplayName("bookable durations")
    class Durations {

        @Test
        @DisplayName("an explicit allow-list wins, sorted and sanitised")
        void explicitAllowList() {
            assertThat(OctoAvailabilityPolicy.bookableDurations(List.of(240, 120, 180), 1, 8))
                .containsExactly(120, 180, 240);
        }

        @Test
        @DisplayName("without an allow-list, every half-hour step in the hour range")
        void derivedFromHourRange() {
            // The pre-V84 rule. Defaulting to a single length instead would make a venue
            // that sells 2, 3 and 4 hour blocks appear to sell only one of them.
            assertThat(OctoAvailabilityPolicy.bookableDurations(List.of(), 2, 3))
                .containsExactly(120, 150, 180);
        }

        @Test
        @DisplayName("nonsense durations are dropped, not published")
        void rejectsUnusableDurations() {
            // 45 is not on the half-hour grid and 0 is not a booking.
            assertThat(OctoAvailabilityPolicy.bookableDurations(List.of(45, 0, -30, 120), 1, 4))
                .containsExactly(120);
        }
    }

    @Nested
    @DisplayName("bookable windows")
    class Windows {

        @Test
        @DisplayName("each window carries an id the booking endpoint can decode")
        void emitsDecodableIds() {
            List<OctoAvailabilityPolicy.Availability> windows =
                OctoAvailabilityPolicy.bookableWindows(DAY, FREE_18_TO_20, List.of(120));

            assertThat(windows).hasSize(1);
            OctoAvailabilityPolicy.Availability only = windows.get(0);
            assertThat(only.id()).isEqualTo("2026-09-01T18:00|120");

            // The round trip is the contract between the two endpoints. Asserting it here
            // is what stops them drifting apart again.
            assertThat(AvailabilityIdCodec.decode(only.id()))
                .hasValue(new AvailabilityIdCodec.Window(
                    only.localDateTimeStart(), 120));
        }

        @Test
        @DisplayName("a window is offered only when EVERY slot it covers is free")
        void refusesPartiallyFreeWindows() {
            // 18:00 and 18:30 free, 19:00 taken, 19:30 free again.
            Set<Integer> gappy = Set.of(1080, 1110, 1170);

            List<OctoAvailabilityPolicy.Availability> windows =
                OctoAvailabilityPolicy.bookableWindows(DAY, gappy, List.of(120));

            // Checking only the first slot would sell a two-hour booking that overlaps an
            // existing one after its first half hour — an oversell created by the
            // supplier rather than caught by it.
            assertThat(windows).isEmpty();
        }

        @Test
        @DisplayName("every start and duration that fits is offered")
        void offersEveryFittingCombination() {
            List<OctoAvailabilityPolicy.Availability> windows =
                OctoAvailabilityPolicy.bookableWindows(DAY, FREE_18_TO_20, List.of(30, 60));

            // 30-min: 18:00, 18:30, 19:00, 19:30. 60-min: 18:00, 18:30, 19:00.
            assertThat(windows).hasSize(7);
            assertThat(windows).allSatisfy(w -> {
                assertThat(w.available()).isTrue();
                // Exclusive whole-space hire: more than one vacancy would imply the venue
                // can be sold twice over for the same window.
                assertThat(w.vacancies()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("the end time reflects the duration, not the slot")
        void endTimeIsTheWindowEnd() {
            OctoAvailabilityPolicy.Availability window =
                OctoAvailabilityPolicy.bookableWindows(DAY, FREE_18_TO_20, List.of(120)).get(0);

            assertThat(window.localDateTimeStart()).isEqualTo(DAY.atTime(18, 0));
            assertThat(window.localDateTimeEnd()).isEqualTo(DAY.atTime(20, 0));
        }

        @Test
        @DisplayName("output is bounded, because resellers poll wide and hard")
        void boundedOutput() {
            // A venue open all day with many permitted durations. Risk DIST-R6 is that a
            // reseller asks for this across a year.
            Set<Integer> allDay = new java.util.TreeSet<>();
            for (int m = 0; m < 24 * 60; m += 30) allDay.add(m);
            List<Integer> manyDurations = OctoAvailabilityPolicy.bookableDurations(List.of(), 1, 8);

            assertThat(OctoAvailabilityPolicy.bookableWindows(DAY, allDay, manyDurations))
                .hasSizeLessThanOrEqualTo(OctoAvailabilityPolicy.MAX_OBJECTS_PER_DAY);
        }

        @Test
        @DisplayName("nothing free means nothing offered, not an error")
        void emptyIsEmpty() {
            assertThat(OctoAvailabilityPolicy.bookableWindows(DAY, Set.of(), List.of(120))).isEmpty();
            assertThat(OctoAvailabilityPolicy.bookableWindows(DAY, FREE_18_TO_20, List.of())).isEmpty();
            assertThat(OctoAvailabilityPolicy.bookableWindows(null, FREE_18_TO_20, List.of(120))).isEmpty();
        }

        @Test
        @DisplayName("the order is stable between identical requests")
        void stableOrdering() {
            // A list that reorders itself reads to a reseller as inventory that changed.
            List<String> first = OctoAvailabilityPolicy
                .bookableWindows(DAY, FREE_18_TO_20, List.of(30, 60))
                .stream().map(OctoAvailabilityPolicy.Availability::id).toList();
            List<String> second = OctoAvailabilityPolicy
                .bookableWindows(DAY, FREE_18_TO_20, List.of(30, 60))
                .stream().map(OctoAvailabilityPolicy.Availability::id).toList();

            assertThat(first).isEqualTo(second);
        }
    }
}
