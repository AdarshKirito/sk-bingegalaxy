package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Super-admin request to assign one IANA timezone to one or more venues at once
 * (the "Auto" / "Manual" venue-timezones console). Unlike {@link BingeSaveRequest},
 * this carries only the fields relevant to the timezone — the caller does not need
 * to know or resend the rest of each binge's data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkBingeTimezoneAssignRequest {

    @NotEmpty(message = "Select at least one venue")
    private List<Long> bingeIds;

    /**
     * IANA zone ID, e.g. "Asia/Kolkata", "America/New_York". Whether the caller derived
     * this via "Auto" (the super-admin's own browser-detected zone) or "Manual" (picked
     * from the world timezone list) is a frontend-only distinction — by the time it
     * reaches this API it is just a validated zone id either way.
     */
    @NotBlank(message = "Timezone is required")
    @Size(max = 64)
    private String timezone;
}
