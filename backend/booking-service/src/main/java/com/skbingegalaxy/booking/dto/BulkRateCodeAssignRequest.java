package com.skbingegalaxy.booking.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkRateCodeAssignRequest {
    private List<Long> customerIds;
    private Long rateCodeId;  // null to unassign
}
