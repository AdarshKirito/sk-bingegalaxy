package com.skbingegalaxy.distribution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * Content the caller has for one event type, evaluated against a destination's
 * requirements.
 *
 * <p>Content is supplied rather than fetched because the authoritative copy lives in
 * booking-service and this context must not hold a second copy of it — the same rule
 * that keeps availability and pricing out of here. Distribution stores the VERDICT
 * (readiness and what is missing), never the content it judged.
 */
@Data
public class EvaluateListingRequest {

    @NotNull(message = "eventTypeId is required")
    private Long eventTypeId;

    @NotNull(message = "connectionDestinationId is required")
    private Long connectionDestinationId;

    /** field → value. A blank value counts as missing. */
    private Map<String, String> content;
}
