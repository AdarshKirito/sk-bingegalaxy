package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingDto {
    private Long id;
    private String bookingRef;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private EventTypeDto eventType;
    private LocalDate bookingDate;
    private LocalTime startTime;
    private int durationHours;
    private Integer durationMinutes;
    private List<BookingAddOnDto> addOns;
    private String specialNotes;
    private String adminNotes;
    private BigDecimal baseAmount;
    private BigDecimal addOnAmount;
    private BigDecimal guestAmount;
    private BigDecimal totalAmount;
    private BigDecimal collectedAmount;
    private int numberOfGuests;
    private BookingStatus status;
    private PaymentStatus paymentStatus;
    private boolean checkedIn;
    private LocalDateTime actualCheckoutTime;
    private Integer actualUsedMinutes;
    private String earlyCheckoutNote;
    private String pricingSource;
    private String rateCodeName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
