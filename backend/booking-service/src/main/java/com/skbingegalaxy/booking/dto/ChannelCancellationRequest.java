package com.skbingegalaxy.booking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * A cancellation delivered by an external sales channel (V85, gap G2).
 *
 * <p><b>Addressed by {@code (externalSource, externalRef)}</b>, the same pair that
 * identifies the reservation in {@link ChannelReservationRequest}. A channel has never
 * seen an SK {@code bookingRef}; requiring one would force the distribution context to
 * keep its own booking↔channel mapping, which is precisely the second booking truth the
 * design refuses to create.
 *
 * <p>Provider-neutral like its sibling: booking-service still never learns which channel
 * is which.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelCancellationRequest {

    /**
     * Canonicalised on the way in, exactly as {@link ChannelReservationRequest} does.
     * A cancel that spelled the source differently from the reservation would look up
     * nothing and answer 404 while the booking sat there live.
     */
    @NotBlank(message = "externalSource is required for a channel cancellation")
    @Size(max = 64, message = "externalSource must be 64 characters or fewer")
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$",
        message = "externalSource must be a slug of letters, digits, dot, dash or underscore")
    private String externalSource;

    public void setExternalSource(String externalSource) {
        this.externalSource = ChannelReservationRequest.canonicalSource(externalSource);
    }

    /** Trimmed only; case is significant for a provider's own reference. */
    @NotBlank(message = "externalRef is required for a channel cancellation")
    @Size(max = 128, message = "externalRef must be 128 characters or fewer")
    private String externalRef;

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef == null ? null : externalRef.trim();
    }

    /** Recorded on the cancellation for the venue's benefit. Optional. */
    @Size(max = 300, message = "reason limited to 300 characters")
    private String reason;
}
