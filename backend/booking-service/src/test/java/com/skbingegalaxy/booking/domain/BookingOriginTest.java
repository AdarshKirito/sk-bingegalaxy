package com.skbingegalaxy.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V85 (gaps G8/G3). These assertions are the written-down version of a rule that is
 * easy to get catastrophically wrong in either direction: too strict and paid channel
 * reservations are silently rejected; too loose and the customer-abuse protections
 * evaporate for everyone.
 */
class BookingOriginTest {

    @Test
    @DisplayName("only DIRECT bookings are subject to customer-funnel guards")
    void customerFunnelGuardsApplyOnlyToDirect() {
        assertThat(BookingOrigin.DIRECT.customerFunnelGuardsApply())
            .as("a self-service customer has a funnel to abuse")
            .isTrue();

        assertThat(BookingOrigin.ADMIN.customerFunnelGuardsApply())
            .as("staff booking a walk-in are not abusing the customer funnel")
            .isFalse();

        assertThat(BookingOrigin.CHANNEL.customerFunnelGuardsApply())
            .as("a channel guest has no SK account and usually already paid")
            .isFalse();
    }

    @Test
    @DisplayName("only CHANNEL bookings carry an external reference")
    void onlyChannelRequiresAnExternalReference() {
        assertThat(BookingOrigin.CHANNEL.requiresExternalReference()).isTrue();
        assertThat(BookingOrigin.DIRECT.requiresExternalReference()).isFalse();
        assertThat(BookingOrigin.ADMIN.requiresExternalReference()).isFalse();
    }

    @Test
    @DisplayName("an unrecognised origin fails closed to DIRECT")
    void parsingFailsClosed() {
        // Failing closed matters: an unknown value must inherit the STRICTEST guard
        // set, never skip abuse protection because a string did not match.
        assertThat(BookingOrigin.parseOrDirect(null)).isEqualTo(BookingOrigin.DIRECT);
        assertThat(BookingOrigin.parseOrDirect("")).isEqualTo(BookingOrigin.DIRECT);
        assertThat(BookingOrigin.parseOrDirect("   ")).isEqualTo(BookingOrigin.DIRECT);
        assertThat(BookingOrigin.parseOrDirect("SOMETHING_NEW")).isEqualTo(BookingOrigin.DIRECT);
        assertThat(BookingOrigin.parseOrDirect("bogus")).isEqualTo(BookingOrigin.DIRECT);
    }

    @Test
    @DisplayName("parsing is case- and whitespace-tolerant for known values")
    void parsingAcceptsKnownValuesLoosely() {
        assertThat(BookingOrigin.parseOrDirect("channel")).isEqualTo(BookingOrigin.CHANNEL);
        assertThat(BookingOrigin.parseOrDirect("  Admin ")).isEqualTo(BookingOrigin.ADMIN);
        assertThat(BookingOrigin.parseOrDirect("DIRECT")).isEqualTo(BookingOrigin.DIRECT);
    }

    @Test
    @DisplayName("every origin is classified — a new value cannot be added silently")
    void everyOriginIsExplicitlyClassified() {
        // If someone adds a fourth origin, this fails until they decide which guard
        // set it belongs to, rather than defaulting into one by accident.
        assertThat(BookingOrigin.values()).hasSize(3);
    }
}
