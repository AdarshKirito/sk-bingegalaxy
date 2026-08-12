package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * A confirmation delivered by an external sales channel — the reseller took the
 * traveller's money and the hold becomes a sale.
 *
 * <p><b>The message that had no endpoint.</b> Reservations could arrive and could be
 * cancelled, but nothing could confirm one. A channel reservation therefore stayed
 * {@code PENDING} until the pending-timeout sweep auto-cancelled it, roughly half an hour
 * after the reseller had already told the traveller they were booked.
 *
 * <p>Addressed by the same {@code (bingeId, externalSource, externalRef)} triple as its
 * siblings, and provider-neutral for the same reason: booking-service never learns which
 * channel is which.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelConfirmationRequest {

    /** Venue whose reservation is being confirmed. See {@link ChannelCancellationRequest#getBingeId()}. */
    @NotNull(message = "bingeId is required for a channel confirmation")
    private Long bingeId;

    /**
     * Canonicalised on the way in, exactly as {@link ChannelReservationRequest} does — a
     * confirmation that spelled the source differently from the reservation would look up
     * nothing and answer 404 while the booking sat there waiting to expire.
     */
    @NotBlank(message = "externalSource is required for a channel confirmation")
    @Size(max = 64, message = "externalSource must be 64 characters or fewer")
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$",
        message = "externalSource must be a slug of letters, digits, dot, dash or underscore")
    private String externalSource;

    public void setExternalSource(String externalSource) {
        this.externalSource = ChannelReservationRequest.canonicalSource(externalSource);
    }

    /** Trimmed only; case is significant for a provider's own reference. */
    @NotBlank(message = "externalRef is required for a channel confirmation")
    @Size(max = 128, message = "externalRef must be 128 characters or fewer")
    private String externalRef;

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef == null ? null : externalRef.trim();
    }
}
