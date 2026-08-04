package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.domain.OccupancyWindow;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.entity.SlotHold;
import com.skbingegalaxy.booking.repository.BingeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V81 buffer resolution: event-type override wins, venue default inherits, zero is the floor. */
@ExtendWith(MockitoExtension.class)
class TurnoverPolicyTest {

    private static final long BINGE_ID = 7L;

    @Mock private BingeRepository bingeRepository;
    @InjectMocks private TurnoverPolicy turnoverPolicy;

    private Binge bingeWithDefaults(int setup, int cleanup) {
        Binge b = new Binge();
        b.setId(BINGE_ID);
        b.setDefaultSetupMinutes(setup);
        b.setDefaultCleanupMinutes(cleanup);
        return b;
    }

    private EventType eventType(Integer setup, Integer cleanup) {
        EventType et = new EventType();
        et.setBingeId(BINGE_ID);
        et.setSetupMinutes(setup);
        et.setCleanupMinutes(cleanup);
        return et;
    }

    @Nested
    @DisplayName("resolution order")
    class Resolution {

        @Test
        void eventTypeOverridesBothSides_venueIsNotConsulted() {
            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(20, 40));

            assertThat(b.setupMinutes()).isEqualTo(20);
            assertThat(b.cleanupMinutes()).isEqualTo(40);
            // A full override must not cost a database round trip.
            verify(bingeRepository, never()).findById(anyLong());
        }

        @Test
        void nullOverrides_inheritTheVenueDefaults() {
            when(bingeRepository.findById(BINGE_ID)).thenReturn(Optional.of(bingeWithDefaults(15, 45)));

            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(null, null));

            assertThat(b.setupMinutes()).isEqualTo(15);
            assertThat(b.cleanupMinutes()).isEqualTo(45);
        }

        @Test
        void partialOverride_mixesEventTypeAndVenueDefault() {
            when(bingeRepository.findById(BINGE_ID)).thenReturn(Optional.of(bingeWithDefaults(15, 45)));

            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(null, 90));

            assertThat(b.setupMinutes()).isEqualTo(15);   // inherited
            assertThat(b.cleanupMinutes()).isEqualTo(90); // overridden
        }

        @Test
        void explicitZeroOverride_beatsANonZeroVenueDefault() {
            // "This event needs no reset time" must be expressible, and must not
            // be confused with "inherit". An explicit 0/0 is a complete override,
            // so the venue defaults are never even loaded.
            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(0, 0));

            assertThat(b.isZero()).isTrue();
            verify(bingeRepository, never()).findById(anyLong());
        }

        @Test
        void missingBinge_failsSoftToZeroRatherThanThrowing() {
            lenient().when(bingeRepository.findById(BINGE_ID)).thenReturn(Optional.empty());

            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(null, null));

            assertThat(b.isZero()).isTrue();
        }

        @Test
        void nullEventTypeAndNullBinge_resolveToZero() {
            assertThat(turnoverPolicy.resolve(null, null).isZero()).isTrue();
        }

        @Test
        void outOfRangeOverride_isClampedNotRejected() {
            TurnoverPolicy.Buffers b = turnoverPolicy.resolve(BINGE_ID, eventType(5000, -10));

            assertThat(b.setupMinutes()).isEqualTo(OccupancyWindow.MAX_BUFFER_MINUTES);
            assertThat(b.cleanupMinutes()).isZero();
        }
    }

    @Nested
    @DisplayName("reading snapshots off persisted rows")
    class Snapshots {

        @Test
        void bookingWindowUsesItsOwnSnapshot_notLiveConfiguration() {
            Booking booking = new Booking();
            booking.setStartTime(LocalTime.of(19, 0));
            booking.setSetupMinutes(30);
            booking.setCleanupMinutes(45);

            OccupancyWindow w = TurnoverPolicy.windowOf(booking, 180);

            assertThat(w.startMinute()).isEqualTo(19 * 60 - 30);
            assertThat(w.endMinute()).isEqualTo(22 * 60 + 45);
        }

        @Test
        void bookingWindowHonoursTheCallerSuppliedEffectiveDuration() {
            // Early-checkout / COMPLETED rounding is BookingService's rule; the
            // policy must not second-guess it.
            Booking booking = new Booking();
            booking.setStartTime(LocalTime.of(19, 0));
            booking.setSetupMinutes(0);
            booking.setCleanupMinutes(30);

            OccupancyWindow w = TurnoverPolicy.windowOf(booking, 60);

            assertThat(w.endMinute()).isEqualTo(20 * 60 + 30);
        }

        @Test
        void holdWindowUsesItsOwnSnapshot() {
            SlotHold hold = new SlotHold();
            hold.setStartTime(LocalTime.of(14, 30));
            hold.setDurationMinutes(90);
            hold.setSetupMinutes(15);
            hold.setCleanupMinutes(15);

            OccupancyWindow w = TurnoverPolicy.windowOf(hold);

            assertThat(w.startMinute()).isEqualTo(14 * 60 + 30 - 15);
            assertThat(w.endMinute()).isEqualTo(16 * 60 + 15);
        }
    }

    @Nested
    @DisplayName("Buffers value type")
    class BuffersType {

        @Test
        void windowForDelegatesToOccupancyWindow() {
            TurnoverPolicy.Buffers b = new TurnoverPolicy.Buffers(10, 20);
            assertThat(b.windowFor(600, 60)).isEqualTo(OccupancyWindow.of(600, 60, 10, 20));
        }

        @Test
        void noneIsZero() {
            assertThat(TurnoverPolicy.Buffers.NONE.isZero()).isTrue();
        }

        @Test
        void anyNonZeroSideMakesItNonZero() {
            assertThat(new TurnoverPolicy.Buffers(0, 1).isZero()).isFalse();
            assertThat(new TurnoverPolicy.Buffers(1, 0).isZero()).isFalse();
        }
    }
}
