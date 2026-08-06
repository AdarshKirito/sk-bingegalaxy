package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Point an existing connection at a sales destination, on stated commercial terms.
 *
 * <p>This is the step that was missing: a venue could create a connection but never
 * attach a destination to it, and since a listing requires a {@code connectionDestinationId}
 * the whole publish chain dead-ended.
 */
@Data
public class EnableDestinationRequest {

    @NotBlank(message = "Destination is required")
    @Size(max = 40)
    private String destinationCode;

    /** Basis points (2000 = 20%), matching the platform's existing rate convention. */
    @Min(value = 0, message = "Commission cannot be negative")
    @Max(value = 10000, message = "Commission cannot exceed 100%")
    private Integer commissionBps;

    /**
     * Defaults to CHANNEL_COLLECTS because that is how the two most relevant
     * destinations actually work — Viator and GetYourGuide are both merchant of record.
     * Letting this default to "the venue collects" would tell an operator to expect cash
     * at checkout that never arrives.
     */
    private ConnectionDestination.PaymentResponsibility paymentResponsibility =
        ConnectionDestination.PaymentResponsibility.CHANNEL_COLLECTS;

    private ConnectionDestination.SettlementModel settlementModel =
        ConnectionDestination.SettlementModel.COMMISSION_SETTLEMENT;

    /** Hold back N concurrent slots from this destination. */
    @Min(value = 0, message = "Safety inventory cannot be negative")
    private int safetyInventory = 0;

    /**
     * Off by default. Enabling the destination on the connection and putting it on sale
     * are two decisions, and collapsing them would publish the moment a venue configured
     * terms it was still negotiating.
     */
    private boolean enabled = false;
}
