package com.skbingegalaxy.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Conversions produced by one marketing source over a date range.
 *
 * <p>This is what makes the Google Things to Do channel arguable on data rather than on
 * hope: without a read surface, attribution is captured and then invisible, and the
 * decision to build the channel stays a guess.
 *
 * <p><b>{@code bookings} counts realised conversions only</b> — CONFIRMED, CHECKED_IN
 * and COMPLETED. A cancelled or no-show booking is not a conversion, and counting it
 * would overstate every channel by exactly the amount it is worst at. {@code cancelled}
 * is reported separately rather than hidden, because a source with an unusually high
 * cancellation rate is a signal about that source, not noise to be filtered away.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionPerformanceDto {

    /** Canonical lowercase source, exactly as stored. Never prettified server-side. */
    private String source;

    /** Realised conversions: CONFIRMED + CHECKED_IN + COMPLETED. */
    private long bookings;

    /** Cancelled or no-show. Surfaced, not silently dropped. */
    private long cancelled;

    /**
     * Gross value of the realised conversions, in the venue's own currency.
     *
     * <p>No cross-venue total is offered here on purpose: under native per-binge pricing
     * each venue charges in its own country's currency, so summing across venues would
     * add different currencies together and produce a confident, meaningless number.
     */
    private BigDecimal revenue;

    /** The venue's currency, so a caller can render {@link #revenue} honestly. */
    private String currency;
}
