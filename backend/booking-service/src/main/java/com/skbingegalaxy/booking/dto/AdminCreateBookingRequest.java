package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminCreateBookingRequest {

    // Customer details - either existing customer ID or new customer info
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
    private LocalDate bookingDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @Min(value = 1, message = "Duration must be at least 1 hour")
    @Max(value = 24, message = "Duration cannot exceed 24 hours")
    private int durationHours;

    private Integer durationMinutes;

    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    @Builder.Default
    private int numberOfGuests = 1;

    private List<AddOnSelection> addOns;

    @Size(max = 1000)
    private String specialNotes;

    @Size(max = 1000)
    private String adminNotes;

    private String paymentMethod; // UPI, CARD, CASH, etc.

    // Admin price overrides (optional — overrides resolved pricing)
    private BigDecimal overrideBaseAmount;
    private BigDecimal overrideTotalAmount;
}
