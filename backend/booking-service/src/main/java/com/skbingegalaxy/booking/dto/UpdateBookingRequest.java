package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBookingRequest {
    private String status;       // BookingStatus value
    private Boolean checkedIn;

    @Size(max = 1000)
    private String adminNotes;

    @Size(max = 150)
    private String customerName;

    @jakarta.validation.constraints.Email(message = "Customer email must be valid")
    @Size(max = 150)
    private String customerEmail;

    @jakarta.validation.constraints.Pattern(regexp = "^$|^\\d{10}$", message = "Phone must be 10 digits")
    @Size(max = 15)
    private String customerPhone;

    @Size(max = 1000)
    private String specialNotes;

    // ── Pricing-relevant fields (triggers recalculation when set) ──
    private Long eventTypeId;
    private Integer durationMinutes;
    private LocalTime startTime;
    private LocalDate bookingDate;
    @Min(value = 1, message = "At least 1 guest required")
    @Max(value = 100, message = "Maximum 100 guests")
    private Integer numberOfGuests;
    private List<AddOnSelection> addOns;
}
