package com.skbingegalaxy.distribution.octo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token that tells the inbox WHAT a reseller bought.
 *
 * <p>Everything downstream — the slot lock, the occupancy window, the turnover buffer,
 * the database backstop — protects the venue only once it knows which window is being
 * sold. Decoding leniently here would hand those checks a window nobody asked for, and
 * they would defend it perfectly.
 */
@DisplayName("OCTO availabilityId")
class AvailabilityIdCodecTest {

    @Test
    @DisplayName("round-trips a window")
    void roundTrip() {
        String id = AvailabilityIdCodec.encode(LocalDateTime.of(2026, 9, 1, 18, 0), 120);

        assertThat(id).isEqualTo("2026-09-01T18:00|120");
        assertThat(AvailabilityIdCodec.decode(id))
            .hasValue(new AvailabilityIdCodec.Window(LocalDateTime.of(2026, 9, 1, 18, 0), 120));
    }

    @ParameterizedTest
    @DisplayName("refuses anything it did not issue")
    @ValueSource(strings = {
        "",
        "2026-09-01T18:00",              // no duration
        "2026-09-01|120",                // no time
        "2026-09-01T18:00|",             // empty duration
        "|120",                          // no instant
        "sometime-next-week",
        "2026-13-01T18:00|120",          // month 13
        "2026-02-31T18:00|120",          // never existed
        "2026-09-01T18:00|abc",
        "2026-09-01T18:00|0",            // zero-length booking
        "2026-09-01T18:00|-60",
        "2026-09-01T18:00|20",           // under the 30-minute floor
        "2026-09-01T18:00|1440",         // a full day, over the 12-hour ceiling
        "2026-09-01T18:00|47",           // not a half-hour step the slot grid can express
    })
    void rejectsMalformed(String token) {
        assertThat(AvailabilityIdCodec.decode(token)).isEmpty();
    }

    @Test
    @DisplayName("refuses null rather than throwing")
    void nullIsEmpty() {
        // The caller turns an empty result into a REJECTED inbox row with a readable
        // reason. An exception here would become a FAILED row instead, and a malformed
        // token would be retried forever.
        assertThat(AvailabilityIdCodec.decode(null)).isEmpty();
    }

    @Test
    @DisplayName("accepts the boundaries it documents")
    void boundariesAccepted() {
        assertThat(AvailabilityIdCodec.decode("2026-09-01T18:00|30")).isPresent();
        assertThat(AvailabilityIdCodec.decode("2026-09-01T18:00|720")).isPresent();
    }
}
