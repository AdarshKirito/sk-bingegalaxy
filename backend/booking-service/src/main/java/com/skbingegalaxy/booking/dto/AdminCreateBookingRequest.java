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
    @jakarta.validation.constraints.Email(message = "Customer email must be valid")
    @Size(max = 150)
    private String customerEmail;
    @jakarta.validation.constraints.Pattern(regexp = "^$|^\\d{10}$", message = "Phone must be 10 digits")
    private String customerPhone;

    @NotNull(message = "Event type ID is required")
    private Long eventTypeId;

    @NotNull(message = "Booking date is required")
    private LocalDate bookingDate;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    private int durationHours;

    @Min(value = 30, message = "Duration must be at least 30 minutes")
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
    @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "Override base amount must be non-negative")
    @jakarta.validation.constraints.DecimalMax(value = "999999.99", message = "Override base amount exceeds maximum")
    private BigDecimal overrideBaseAmount;

    @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "Override total amount must be non-negative")
    @jakarta.validation.constraints.DecimalMax(value = "999999.99", message = "Override total amount exceeds maximum")
    private BigDecimal overrideTotalAmount;
}
