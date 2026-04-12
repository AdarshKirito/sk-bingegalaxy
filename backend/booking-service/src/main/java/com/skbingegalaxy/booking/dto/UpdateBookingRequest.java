package com.skbingegalaxy.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBookingRequest {
    private String status;       // BookingStatus value
    private Boolean checkedIn;

    @Size(max = 1000, message = "Admin notes must be under 1000 characters")
    private String adminNotes;

    @Size(max = 150, message = "Customer name must be under 150 characters")
    private String customerName;

    @jakarta.validation.constraints.Email(message = "Customer email must be valid")
    @Size(max = 150, message = "Customer email must be under 150 characters")
    private String customerEmail;

    @jakarta.validation.constraints.Pattern(regexp = "^$|^\\d{10}$", message = "Phone must be 10 digits")
    @Size(max = 15, message = "Phone must be under 15 characters")
    private String customerPhone;

    @Size(max = 1000, message = "Special notes must be under 1000 characters")
    private String specialNotes;

    // ── Pricing-relevant fields (triggers recalculation when set) ──
    private Long eventTypeId;
    private Integer durationMinutes;
    private LocalTime startTime;
    private LocalDate bookingDate;
    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    private Integer numberOfGuests;
    @Valid
    private List<AddOnSelection> addOns;

    // ── Direct price override (admin manual adjustment) ──
    @Min(value = 0, message = "Base amount cannot be negative")
    private BigDecimal baseAmount;
    @Min(value = 0, message = "Add-on amount cannot be negative")
    private BigDecimal addOnAmount;
    @Min(value = 0, message = "Guest amount cannot be negative")
    private BigDecimal guestAmount;
    @Size(max = 500, message = "Adjustment reason must be under 500 characters")
    private String priceAdjustmentReason;
}
