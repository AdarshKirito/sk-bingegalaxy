package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringBookingResult {

    private String recurringGroupId;
    private int requestedOccurrences;
    private int successfulOccurrences;
    private int skippedOccurrences;
    private List<BookingDto> createdBookings;
    private List<SkippedOccurrence> skipped;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkippedOccurrence {
        private LocalDate date;
        private String reason;
    }
}
