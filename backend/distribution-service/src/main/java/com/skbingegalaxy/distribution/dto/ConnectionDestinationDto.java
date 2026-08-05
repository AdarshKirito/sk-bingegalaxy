package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionDestinationDto {
    private Long id;
    private String destinationCode;
    private String destinationName;
    private boolean enabled;
    private Integer commissionBps;
    private ConnectionDestination.PaymentResponsibility paymentResponsibility;
    private ConnectionDestination.SettlementModel settlementModel;
    private int safetyInventory;
    private boolean stopSell;
    private String stopSellReason;

    /**
     * False for feed-only destinations such as Google Things to Do. The reservations
     * screen must render no rows for these — Google never delivers a booking back.
     */
    private boolean deliversReservations;
}
