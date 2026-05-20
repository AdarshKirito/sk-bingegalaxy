package com.skbingegalaxy.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingEvent extends EventEnvelope {
    private String bookingRef;
    private Long bingeId;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    /** E.164 dial prefix without subscriber number (e.g. "+91"). Populated alongside customerPhone since Apr 2026. */
    private String customerPhoneCountryCode;
    private String eventTypeName;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationHours;
    private Integer durationMinutes;
    private BigDecimal totalAmount;
    private String status;
    private String specialNotes;
    /**
     * Per-binge customer-cancellation cutoff (minutes before start time) at the
     * moment this event was emitted. Consumers (e.g. notification-service)
     * use this to schedule a "deadline approaching" reminder ahead of the
     * cut-off. Nullable for backward compatibility with old events on the wire.
     */
    private Integer customerCancellationCutoffMinutes;
}
