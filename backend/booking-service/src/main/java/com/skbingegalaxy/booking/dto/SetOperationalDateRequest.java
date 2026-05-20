package com.skbingegalaxy.booking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/bookings/admin/operational-date/set}.
 *
 * <p>SUPER_ADMIN-only operation that overrides the per-binge operational date
 * outside the normal nightly audit. Used for clock-drift recovery, late-night
 * cutovers, or test-environment day rollover.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SetOperationalDateRequest {

    /** New operational date to apply to the currently selected binge. */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate operationalDate;
}
