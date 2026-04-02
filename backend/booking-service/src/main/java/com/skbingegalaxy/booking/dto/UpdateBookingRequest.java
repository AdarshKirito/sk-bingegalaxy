package com.skbingegalaxy.booking.dto;

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

    @Size(max = 150)
    private String customerEmail;

    @Size(max = 15)
    private String customerPhone;

    @Size(max = 1000)
    private String specialNotes;

    // ── Pricing-relevant fields (triggers recalculation when set) ──
    private Long eventTypeId;
    private Integer durationMinutes;
    private LocalTime startTime;
    private LocalDate bookingDate;
    private Integer numberOfGuests;
    private List<AddOnSelection> addOns;
}
