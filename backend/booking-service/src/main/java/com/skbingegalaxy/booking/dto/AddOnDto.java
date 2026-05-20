package com.skbingegalaxy.booking.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddOnDto {
    private Long id;
    private Long bingeId;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private List<String> imageUrls;
    private boolean active;
    /** Daily inventory cap (null = unlimited). */
    private Integer stockPerDay;
    /** Minimum minutes ahead of booking start time the add-on must be ordered (null = none). */
    private Integer advanceNoticeMinutes;
}
