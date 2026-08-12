package com.skbingegalaxy.distribution.controller;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.dto.EvaluateListingRequest;
import com.skbingegalaxy.distribution.dto.ListingDto;
import com.skbingegalaxy.distribution.listing.ListingReadinessPolicy;
import com.skbingegalaxy.distribution.service.ListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Listings and per-destination readiness (slice 4).
 *
 * <p>As with connections, the venue comes from {@code X-Binge-Id} and never from the
 * request, so a caller cannot evaluate or publish another venue's listings.
 */
@RestController
@RequestMapping("/api/v1/distribution")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;
    private final ListingReadinessPolicy readinessPolicy;

    private Long requireBinge(Long bingeId) {
        if (bingeId == null) {
            throw new BusinessException("Select a venue before managing listings.",
                HttpStatus.BAD_REQUEST);
        }
        return bingeId;
    }

    @GetMapping("/listings")
    public ResponseEntity<ApiResponse<List<ListingDto>>> list(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId) {
        return ResponseEntity.ok(ApiResponse.ok(listingService.listForBinge(requireBinge(bingeId))));
    }

    /**
     * What a destination requires before a listing may go live there.
     *
     * <p><b>Without this the evaluate endpoint was guess-and-check.</b> The requirements
     * differ per marketplace and existed only inside {@code ListingReadinessPolicy}, so
     * the console could post content and be told what was missing, but could not ask what
     * to collect in the first place. An operator would have had to submit an empty
     * listing to discover the field list.
     *
     * <p>Deliberately not binge-scoped: a destination's content requirements are a
     * property of the marketplace, identical for every venue. It stays behind the same
     * admin authorization as the rest of the surface.
     */
    @GetMapping("/listings/requirements")
    public ResponseEntity<ApiResponse<List<ListingReadinessPolicy.Requirement>>> requirements(
            @RequestParam(required = false) String destinationCode) {
        return ResponseEntity.ok(ApiResponse.ok(
            readinessPolicy.requirementsFor(destinationCode)));
    }

    /** Evaluate content against a destination's requirements and record the verdict. */
    @PostMapping("/listings/evaluate")
    public ResponseEntity<ApiResponse<ListingDto>> evaluate(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @Valid @RequestBody EvaluateListingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Readiness updated",
            listingService.evaluate(requireBinge(bingeId), request)));
    }

    @PostMapping("/listings/{id}/publish")
    public ResponseEntity<ApiResponse<ListingDto>> publish(
            @RequestHeader(value = "X-Binge-Id", required = false) Long bingeId,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Listing published",
            listingService.publish(requireBinge(bingeId), id)));
    }
}
