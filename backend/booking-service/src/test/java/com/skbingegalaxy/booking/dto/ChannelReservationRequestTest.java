package com.skbingegalaxy.booking.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonicalisation of the channel identifiers, and the reason it lives in the
 * setters rather than in the service.
 *
 * <p>The redelivery guard is the unique index on {@code (external_source, external_ref)}.
 * That index compares bytes, so if {@code "ACME-Channel"} and {@code "acme-channel"}
 * can both be stored they are two different channels — a provider that varies its own
 * casing between the original delivery and a retry defeats the guard entirely, and the
 * venue is double-booked for a slot it already sold. Silently.
 *
 * <p>The subtle part is <em>where</em> normalisation happens. Bean validation runs
 * against the field after deserialization. Normalising only in the service would mean
 * the HTTP endpoint 400s on {@code "ACME-Channel"} while the service accepts it —
 * the edge and the core disagreeing about what is valid.
 */
class ChannelReservationRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private ChannelReservationRequest valid() {
        ChannelReservationRequest r = new ChannelReservationRequest();
        r.setExternalSource("acme-channel");
        r.setExternalRef("ACME-1");
        r.setBingeId(1L);
        r.setEventTypeId(1L);
        r.setBookingDate(LocalDate.now().plusDays(7));
        r.setStartTime(LocalTime.of(19, 0));
        r.setDurationMinutes(180);
        r.setNumberOfGuests(4);
        r.setGuestName("Channel Guest");
        r.setGuestEmail("guest@example.com");
        r.setGuestPhone("9999999999");
        return r;
    }

    @Nested
    @DisplayName("externalSource canonicalisation")
    class SourceCanonicalisation {

        @Test
        void mixedCaseAndWhitespaceAreCanonicalisedOnSet() {
            ChannelReservationRequest r = new ChannelReservationRequest();
            r.setExternalSource("  ACME-Channel  ");
            assertThat(r.getExternalSource()).isEqualTo("acme-channel");
        }

        @Test
        @DisplayName("a mixed-case source PASSES validation because the setter normalised it first")
        void mixedCaseSourcePassesValidation() {
            // The regression this guards: with normalisation only in the service, the
            // lowercase-only @Pattern would reject this at the edge with a 400, and a
            // legitimate channel would be unable to deliver reservations at all.
            ChannelReservationRequest r = valid();
            r.setExternalSource("ACME-Channel");

            assertThat(validator.validate(r))
                .as("mixed-case input must be normalised, not rejected")
                .isEmpty();
            assertThat(r.getExternalSource()).isEqualTo("acme-channel");
        }

        @Test
        void twoSpellingsOfTheSameChannelConvergeOnOneValue() {
            ChannelReservationRequest a = new ChannelReservationRequest();
            ChannelReservationRequest b = new ChannelReservationRequest();
            a.setExternalSource("ACME-Channel");
            b.setExternalSource("acme-channel");

            assertThat(a.getExternalSource())
                .as("otherwise the unique index stores both and the retry guard misses")
                .isEqualTo(b.getExternalSource());
        }

        @Test
        void theStaticCanonicaliserIsTheSingleDefinition() {
            // The service reuses this for builder-constructed requests, which bypass
            // setters. One definition, so the two paths cannot drift.
            assertThat(ChannelReservationRequest.canonicalSource("  ACME-Channel  "))
                .isEqualTo("acme-channel");
            assertThat(ChannelReservationRequest.canonicalSource(null)).isNull();
        }

        @Test
        void aTrulyMalformedSourceIsStillRejected() {
            // Normalising must not mean "accept anything". A slash would let a provider
            // smuggle path-ish values into a column other systems key on.
            ChannelReservationRequest r = valid();
            r.setExternalSource("acme/channel");

            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("externalSource"));
        }

        @Test
        void blankSourceIsRejected() {
            ChannelReservationRequest r = valid();
            r.setExternalSource("   ");
            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("externalSource"));
        }
    }

    @Nested
    @DisplayName("externalRef handling")
    class RefHandling {

        @Test
        void refIsTrimmedButCaseIsPreserved() {
            // Provider references are opaque, case-sensitive identifiers. Folding case
            // would merge two genuinely distinct reservations into one — the opposite
            // failure to the source problem, and a worse one.
            ChannelReservationRequest r = new ChannelReservationRequest();
            r.setExternalRef("  ACME-1a  ");
            assertThat(r.getExternalRef()).isEqualTo("ACME-1a");
        }

        @Test
        void twoRefsDifferingOnlyByCaseRemainDistinct() {
            ChannelReservationRequest a = new ChannelReservationRequest();
            ChannelReservationRequest b = new ChannelReservationRequest();
            a.setExternalRef("ACME-1A");
            b.setExternalRef("acme-1a");

            assertThat(a.getExternalRef()).isNotEqualTo(b.getExternalRef());
        }

        @Test
        void blankRefIsRejected() {
            ChannelReservationRequest r = valid();
            r.setExternalRef("   ");
            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("externalRef"));
        }
    }

    @Nested
    @DisplayName("payload validation")
    class PayloadValidation {

        @Test
        void aWellFormedRequestHasNoViolations() {
            assertThat(validator.validate(valid())).isEmpty();
        }

        @Test
        void durationOutsideTheAllowedRangeIsRejected() {
            ChannelReservationRequest r = valid();
            r.setDurationMinutes(5);
            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("durationMinutes"));
        }

        @Test
        void aMalformedGuestEmailIsRejectedAtTheEdge() {
            // PII arriving from a third party: validate before it reaches persistence.
            ChannelReservationRequest r = valid();
            r.setGuestEmail("not-an-email");
            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("guestEmail"));
        }

        @Test
        void guestNameIsRequired() {
            ChannelReservationRequest r = valid();
            r.setGuestName("  ");
            assertThat(validator.validate(r))
                .anyMatch(v -> v.getPropertyPath().toString().equals("guestName"));
        }
    }
}
