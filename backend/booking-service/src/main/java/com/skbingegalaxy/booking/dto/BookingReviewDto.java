package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingReviewDto {
    private Long id;
    private String bookingRef;
    private Long customerId;
    private String customerName;
    private Long adminId;
    private String reviewerRole;
    private Integer rating;
    private String comment;
    private boolean skipped;
    private boolean visibleToCustomer;
    private String eventTypeName;
    private LocalDateTime createdAt;
}
