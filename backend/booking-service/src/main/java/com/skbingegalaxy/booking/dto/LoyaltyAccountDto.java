package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyAccountDto {
    private Long id;
    private Long customerId;
    private long totalPointsEarned;
    private long currentBalance;
    private String tierLevel;
    /** Points needed to reach the next tier. Null if already at max tier. */
    private Long pointsToNextTier;
    private String nextTierLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int redemptionRate;
    private List<LoyaltyTransactionDto> recentTransactions;
}
