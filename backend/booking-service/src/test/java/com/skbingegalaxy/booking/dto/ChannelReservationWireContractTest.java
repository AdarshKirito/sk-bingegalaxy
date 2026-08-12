package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the ingestion contract that lives on the receiving side.
 *
 * <p><b>Why this cannot be one test.</b> distribution-service builds this body; this
 * module binds it. The two share no dependency — deliberately, so booking-service never
 * learns what a sales channel is — so nothing in the compiler checks that the keys one
 * writes are the keys the other reads. Strict binding turns any disagreement into a 400
 * for <i>every</i> channel reservation, and the sending side would see only a refusal it
 * cannot explain.
 *
 * <p>So it is checked by a pair: distribution-service's
 * {@code BookingIngestClientContractTest} pins what it sends; this pins what is accepted,
 * from the same literal JSON. Change either side and one of the two goes red.
 *
 * <p><b>The strictness test is what gives the pairing teeth.</b> If unknown fields were
 * silently ignored, a renamed key on the sending side would bind to nothing, arrive as a
 * null, and fail as a confusing validation error — or worse, bind a default and book the
 * wrong thing. Proving the refusal is real is what makes the other side's exact key-set
 * assertion meaningful rather than decorative.
 */
@DisplayName("Channel reservation wire contract (receiving side)")
class ChannelReservationWireContractTest {

    /**
     * Byte-for-byte the body {@code BookingIngestClient.reservationBody} produces:
     * the same ten keys, ISO date and time as strings, guests as a number.
     */
    private static final String WIRE_BODY = """
        {"externalSource":"simulator","externalRef":"EXT-1","bingeId":1,"eventTypeId":14,
         "bookingDate":"2026-09-01","startTime":"18:00","durationMinutes":120,
         "numberOfGuests":2,"guestName":"Asha Rao","guestEmail":"asha@example.com"}""";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();

    private static ChannelReservationRequest bind(String json) throws Exception {
        return MAPPER.readValue(json, ChannelReservationRequest.class);
    }

    @Test
    @DisplayName("the body distribution-service sends binds to every field it means")
    void theWireBodyBinds() throws Exception {
        ChannelReservationRequest request = bind(WIRE_BODY);

        assertThat(request.getExternalSource()).isEqualTo("simulator");
        assertThat(request.getExternalRef()).isEqualTo("EXT-1");
        assertThat(request.getBingeId()).isEqualTo(1L);
        assertThat(request.getEventTypeId()).isEqualTo(14L);
        assertThat(request.getBookingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(request.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(request.getDurationMinutes()).isEqualTo(120);
        assertThat(request.getNumberOfGuests()).isEqualTo(2);
        assertThat(request.getGuestName()).isEqualTo("Asha Rao");
        assertThat(request.getGuestEmail()).isEqualTo("asha@example.com");
    }

    @Test
    @DisplayName("and passes validation, so the endpoint would not 400 it")
    void theWireBodyIsValid() throws Exception {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            // Binding is not enough: @Valid runs next, and a body that binds but fails
            // validation is refused just as completely.
            assertThat(validator.validate(bind(WIRE_BODY))).isEmpty();
        }
    }

    @Test
    @DisplayName("an unknown key is refused — which is what makes the paired test meaningful")
    void unknownKeysAreRefused() {
        String renamed = WIRE_BODY.replace("\"guestName\"", "\"guest_name\"");

        // If this silently ignored the unknown key, a rename on the sending side would
        // arrive as a null guestName and surface as a puzzling validation error rather
        // than a named field. The strictness is load-bearing.
        assertThatThrownBy(() -> bind(renamed))
            .isInstanceOf(UnrecognizedPropertyException.class)
            .hasMessageContaining("guest_name");
    }

    // ── The other two lifecycle messages ─────────────────────────────────────

    /** Byte-for-byte what {@code BookingIngestClient.cancellationBody} produces. */
    private static final String CANCEL_WIRE_BODY = """
        {"externalSource":"simulator","externalRef":"EXT-1","bingeId":1,
         "reason":"Cancelled by SIMULATOR"}""";

    /** Byte-for-byte what {@code BookingIngestClient.confirmationBody} produces. */
    private static final String CONFIRM_WIRE_BODY = """
        {"externalSource":"simulator","externalRef":"EXT-1","bingeId":1}""";

    @Test
    @DisplayName("a cancellation binds, and its venue is required")
    void theCancellationBodyBinds() throws Exception {
        ChannelCancellationRequest request =
            MAPPER.readValue(CANCEL_WIRE_BODY, ChannelCancellationRequest.class);

        assertThat(request.getBingeId()).isEqualTo(1L);
        assertThat(request.getExternalSource()).isEqualTo("simulator");
        assertThat(request.getExternalRef()).isEqualTo("EXT-1");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();

            // The venue is what stops a cancellation resolving to whichever venue used
            // this reference first. A body without it must be refused, not defaulted —
            // a null bingeId reaching the lookup is the cross-venue cancel itself.
            ChannelCancellationRequest venueless = MAPPER.readValue(
                CANCEL_WIRE_BODY.replace("\"bingeId\":1,", ""), ChannelCancellationRequest.class);
            assertThat(factory.getValidator().validate(venueless)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("a confirmation binds, and its venue is required too")
    void theConfirmationBodyBinds() throws Exception {
        ChannelConfirmationRequest request =
            MAPPER.readValue(CONFIRM_WIRE_BODY, ChannelConfirmationRequest.class);

        assertThat(request.getBingeId()).isEqualTo(1L);
        assertThat(request.getExternalSource()).isEqualTo("simulator");
        assertThat(request.getExternalRef()).isEqualTo("EXT-1");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();

            ChannelConfirmationRequest venueless = MAPPER.readValue(
                CONFIRM_WIRE_BODY.replace(",\"bingeId\":1", ""), ChannelConfirmationRequest.class);
            assertThat(factory.getValidator().validate(venueless)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("both also refuse an unknown key, so their pairings have teeth as well")
    void theOtherTwoAreStrictToo() {
        assertThatThrownBy(() -> MAPPER.readValue(
                CANCEL_WIRE_BODY.replace("\"reason\"", "\"why\""), ChannelCancellationRequest.class))
            .isInstanceOf(UnrecognizedPropertyException.class)
            .hasMessageContaining("why");

        // A confirmation that arrived carrying a price would be a second chance to state
        // what the sale was worth. It is refused rather than ignored.
        assertThatThrownBy(() -> MAPPER.readValue(
                CONFIRM_WIRE_BODY.replace("}", ",\"grossMinor\":9900}"), ChannelConfirmationRequest.class))
            .isInstanceOf(UnrecognizedPropertyException.class)
            .hasMessageContaining("grossMinor");
    }

    @Test
    @DisplayName("a null guest email is accepted; the string \"null\" is not")
    void nullEmailIsAcceptedButTheWordIsNot() throws Exception {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(bind(
                WIRE_BODY.replace("\"asha@example.com\"", "null")))).isEmpty();

            // The failure mode String.valueOf would have introduced: guestEmail is
            // optional, so a stringified null would not be caught by a required check —
            // it would be caught here, by @Email, as a rejection of every reservation
            // that happens to carry no email.
            assertThat(validator.validate(bind(
                WIRE_BODY.replace("asha@example.com", "null")))).isNotEmpty();
        }
    }
}
