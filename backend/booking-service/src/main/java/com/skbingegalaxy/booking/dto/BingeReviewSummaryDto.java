package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BingeReviewSummaryDto {
    private Long bingeId;
    /** Unweighted arithmetic mean — kept for backward compatibility. */
    private double averageRating;
    /**
     * Weighted mean that accounts for the reviewer's membership tier
     * and the admin-community's trust signal in that reviewer.  This is
     * what the public "stars" on the binge page should show going
     * forward, so a single disgruntled / penalty-flagged account can't
     * tank a binge's rating and top-tier loyal members get a modest
     * (capped) influence bump.  Equals {@link #averageRating} when
     * there is no trust data yet.
     */
    private double weightedAverageRating;
    private long totalReviews;
    private Map<Integer, Long> ratingDistribution; // star -> count
}
