package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkRateCodeAssignRequest {
    @NotEmpty(message = "At least one customer ID is required")
    private List<Long> customerIds;
    private Long rateCodeId;  // null to unassign
    @Size(max = 100, message = "Member label must be under 100 characters")
    private String memberLabel;  // optional display name for member snapshot
}
