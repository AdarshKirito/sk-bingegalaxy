package com.skbingegalaxy.distribution.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the ingestion contract that lives on the sending side.
 *
 * <p><b>Why this cannot be one test.</b> booking-service binds this body with
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)}. A key it does not know is a 400
 * for <i>every</i> channel reservation; a key it requires but never receives is a
 * validation failure for <i>every</i> channel reservation. Neither module can see the
 * other — that independence is deliberate, so booking-service never learns what a channel
 * is — which means nothing in the compiler or the type system checks this seam.
 *
 * <p>So it is checked by a pair: this test pins what distribution-service <b>sends</b>,
 * and booking-service's {@code ChannelReservationWireContractTest} pins what it
 * <b>accepts</b>, from the same literal key names. Change one side and one of the two goes
 * red with a diff naming the field.
 *
 * <p>This is the seam the OCTO contract test could not reach: it mocks this client, so a
 * wire-format disagreement here would pass it silently. Same failure shape as the three
 * bugs that shipped — two halves that each work alone and do not agree.
 */
@DisplayName("Channel reservation wire contract (sending side)")
class BookingIngestClientContractTest {

    /**
     * Every key booking-service's {@code ChannelReservationRequest} declares as required
     * or reads for a channel reservation. Spelled out as literals rather than derived, so
     * a rename on either side produces a failing assertion instead of two definitions
     * that quietly agree to be wrong together.
     */
    private static final Set<String> AGREED_KEYS = Set.of(
        "externalSource", "externalRef", "bingeId", "eventTypeId",
        "bookingDate", "startTime", "durationMinutes", "numberOfGuests",
        "guestName", "guestEmail");

    private static Map<String, Object> body() {
        return BookingIngestClient.reservationBody(
            "simulator", "EXT-1", 1L, 14L,
            LocalDate.of(2026, 9, 1), LocalTime.of(18, 0), 120, 2,
            "Asha Rao", "asha@example.com");
    }

    @Test
    @DisplayName("sends exactly the keys booking-service accepts — no more, no fewer")
    void keySetIsExactlyTheAgreedContract() {
        // Exact equality, not containsAll. An EXTRA key is a 400 under strict binding,
        // and a MISSING one is a validation failure — both break every reservation, so
        // neither direction may be tolerated.
        assertThat(body().keySet()).isEqualTo(AGREED_KEYS);
    }

    @Test
    @DisplayName("date and time are ISO strings, which is what LocalDate/LocalTime bind from")
    void temporalFieldsAreIsoStrings() {
        // String.valueOf on a temporal is only correct because ISO-8601 is its toString.
        // Pinned because a switch to a formatter with a different pattern would still
        // compile, still look sane in a log, and 400 on arrival.
        assertThat(body()).containsEntry("bookingDate", "2026-09-01")
                          .containsEntry("startTime", "18:00");
    }

    @Test
    @DisplayName("a null guest email is sent as null, not the string \"null\"")
    void nullEmailStaysNull() {
        Map<String, Object> body = BookingIngestClient.reservationBody(
            "simulator", "EXT-2", 1L, 14L,
            LocalDate.of(2026, 9, 1), LocalTime.of(18, 0), 120, 1, "Asha Rao", null);

        // guestEmail is optional, but @Email would reject the four-character string
        // "null" — the mistake String.valueOf makes on the temporal fields deliberately
        // and must not make here.
        assertThat(body).containsEntry("guestEmail", null);
        assertThat(body.get("guestEmail")).isNotEqualTo("null");
    }
}
