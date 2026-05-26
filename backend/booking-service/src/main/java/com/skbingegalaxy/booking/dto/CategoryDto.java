package com.skbingegalaxy.booking.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryDto {
    private Long id;
    /** NULL = global category. */
    private Long bingeId;
    private String name;
    private String description;
    private String imageUrl;
    private int sortOrder;
    private boolean active;
    /** Convenience flag for UI — true when {@code bingeId == null}. */
    private boolean global;
}
