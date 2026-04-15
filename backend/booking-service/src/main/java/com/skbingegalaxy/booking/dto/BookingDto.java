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
    private Long bingeId;
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
    private BigDecimal balanceDue;      // totalAmount − collectedAmount
    private int numberOfGuests;
    private BookingStatus status;
    private PaymentStatus paymentStatus;
    private String paymentMethod;
    private boolean checkedIn;
    private LocalDateTime actualCheckoutTime;
    private Integer actualUsedMinutes;
    private String earlyCheckoutNote;
    private Boolean canCustomerCancel;
    private String customerCancelMessage;
    private Integer cancellationRefundPercentage;
    private String pricingSource;
    private String rateCodeName;
    private int rescheduleCount;
    private String originalBookingRef;
    private boolean transferred;
    private String originalCustomerName;
    private String recurringGroupId;
    /** Whether the customer is allowed to reschedule this booking. */
    private Boolean canCustomerReschedule;
    /** Whether the customer is allowed to transfer this booking. */
    private Boolean canCustomerTransfer;
    // ── Venue Room ───
    private Long venueRoomId;
    private String venueRoomName;
    // ── Loyalty Points ───
    private long loyaltyPointsEarned;
    private long loyaltyPointsRedeemed;
    private BigDecimal loyaltyDiscountAmount;
    // ── Surge Pricing ───
    private BigDecimal surgeMultiplier;
    private String surgeLabel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
