package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeReviewSummaryDto {
    private Long bingeId;
    private double averageRating;
    private long totalReviews;
    private Map<Integer, Long> ratingDistribution; // star -> count
}
