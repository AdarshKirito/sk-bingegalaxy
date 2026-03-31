package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.Size;
import lombok.*;

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
}
