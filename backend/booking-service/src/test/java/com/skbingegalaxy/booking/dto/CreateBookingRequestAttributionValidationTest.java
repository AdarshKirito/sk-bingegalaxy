package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.domain.BookingAttribution;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribution must never be able to fail a booking.
 *
 * <p>{@code BookingController.createBooking} takes {@code @Valid @RequestBody}, so any
 * constraint violation on this DTO returns <b>400 before a single line of service code
 * runs</b>. Putting {@code @Size} on the attribution fields therefore meant an over-long
 * {@code utm_source} — data the customer never typed and cannot fix — would kill a real
 * sale at the final step of the funnel, on the exact channel attribution exists to
 * measure.
 *
 * <p>This test exists because that is a one-annotation regression with no other symptom:
 * everything compiles, every other test passes, and the failure only appears for
 * customers arriving from a referrer with long campaign parameters.
 */
@DisplayName("CreateBookingRequest — attribution can never fail a booking")
class CreateBookingRequestAttributionValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static CreateBookingRequest validRequest() {
        CreateBookingRequest r = new CreateBookingRequest();
        r.setEventTypeId(1L);
        r.setBookingDate(LocalDate.now().plusDays(3));
        r.setStartTime(LocalTime.of(18, 0));
        r.setDurationHours(2);
        r.setNumberOfGuests(2);
        return r;
    }

    @Test
    @DisplayName("an absurdly long attribution source produces NO validation error")
    void overLongAttributionSourceDoesNotFailTheBooking() {
        CreateBookingRequest request = validRequest();
        request.setAttributionSource("x".repeat(500));
        request.setAttributionRef("y".repeat(2000));
        request.setAttributionCapturedAt(LocalDateTime.now());

        var violations = validator.validate(request);

        assertThat(violations)
            .as("a marketing parameter the customer never typed must not reject their booking")
            .isEmpty();
    }

    @Test
    @DisplayName("the domain type truncates it to the column widths instead")
    void theServiceTruncatesRatherThanRejecting() {
        // The bound moved from the edge (where it 400s) to the domain (where it clamps).
        BookingAttribution attribution = BookingAttribution.of(
            "x".repeat(500), "y".repeat(2000), LocalDateTime.now(), LocalDateTime.now());

        assertThat(attribution).isNotNull();
        assertThat(attribution.source()).hasSize(BookingAttribution.MAX_SOURCE_LENGTH);
        assertThat(attribution.ref()).hasSize(BookingAttribution.MAX_REF_LENGTH);
    }

    /**
     * The recurring series is the SECOND write path into bookings, and it originally
     * carried no attribution at all — the same shape of defect as the binge
     * grace-period bug: one path sets a field, another silently does not. It needs the
     * identical exemption, or an over-long referral would reject an entire series of
     * real bookings.
     */
    @Test
    @DisplayName("the recurring request carries attribution and is equally unconstrained")
    void recurringRequestMirrorsTheSameRules() {
        RecurringBookingRequest recurring = new RecurringBookingRequest();
        recurring.setEventTypeId(1L);
        recurring.setStartDate(LocalDate.now().plusDays(3));
        recurring.setStartTime(LocalTime.of(18, 0));
        recurring.setDurationMinutes(120);
        recurring.setNumberOfGuests(2);
        recurring.setPattern(RecurringBookingRequest.RecurrencePattern.WEEKLY);
        recurring.setOccurrences(4);
        recurring.setAttributionSource("x".repeat(500));
        recurring.setAttributionRef("y".repeat(2000));

        assertThat(validator.validate(recurring))
            .as("one click produced the whole series; a long referral must not reject it")
            .isEmpty();
    }

    @Test
    @DisplayName("constraints on the REST of the request still apply")
    void otherConstraintsAreUnaffected() {
        // Removing @Size from two fields must not read as "validation is off here".
        CreateBookingRequest request = validRequest();
        request.setSpecialNotes("n".repeat(5000));

        assertThat(validator.validate(request))
            .as("only the attribution fields are exempt, and only because they are not user input")
            .isNotEmpty();
    }
}
