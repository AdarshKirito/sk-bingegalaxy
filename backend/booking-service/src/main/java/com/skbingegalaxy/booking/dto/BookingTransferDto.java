package com.skbingegalaxy.booking.dto;

import com.skbingegalaxy.booking.entity.BookingTransfer;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BookingTransferDto {

    private Long id;
    private String bookingRef;
    private Long fromCustomerId;
    private String fromCustomerName;
    private String fromCustomerEmail;
    private String toName;
    private String toEmail;
    private String toPhone;
    private String toPhoneCountryCode;
    private Long toCustomerId;
    private BookingTransfer.Status status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime declinedAt;
    private LocalDateTime revokedAt;
    private String declineReason;
}
