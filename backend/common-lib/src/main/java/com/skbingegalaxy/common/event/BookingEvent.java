package com.skbingegalaxy.common.event;

import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingEvent implements Serializable {
    private String bookingRef;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String eventTypeName;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationHours;
    private Integer durationMinutes;
    private BigDecimal totalAmount;
    private String status;
    private String specialNotes;
}
