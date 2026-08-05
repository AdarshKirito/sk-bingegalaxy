package com.skbingegalaxy.distribution.dto;

import com.skbingegalaxy.distribution.entity.ListingMapping;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingDto {
    private Long id;
    private Long eventTypeId;
    private String destinationCode;
    private String destinationName;
    private ListingMapping.PublishState publishState;
    private int readinessPct;
    /** Instructions for the person who can fix them, not field names. */
    private List<String> blockingReasons;
    private String externalProductId;
    private LocalDateTime lastPublishedAt;
}
