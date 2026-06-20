package com.skbingegalaxy.booking.repository;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.entity.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-level coverage for the proximity bounding-box queries against a real
 * (H2 in PostgreSQL mode) database — the service unit tests only mock these, and the
 * integration test only validates that the JPQL parses. This verifies the actual SQL
 * semantics: the box range predicates, the geocoded (lat/lng NOT NULL) filter, the
 * active + APPROVED gate, and the "has an active event type" EXISTS subquery.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BingeRepositoryGeoTest {

    @Autowired private BingeRepository bingeRepository;
    @Autowired private EventTypeRepository eventTypeRepository;

    // A generous box around Bengaluru (12.9716, 77.5946).
    private static final double MIN_LAT = 12.0, MAX_LAT = 13.5, MIN_LNG = 77.0, MAX_LNG = 78.0;

    @Test
    void boundingBoxQuery_returnsOnlyVisibleGeocodedInBoxBingesWithEvents() {
        Long inBox      = saveBinge("In box", true, BingeApprovalStatus.APPROVED, 12.97, 77.59, true);
        Long outOfBox   = saveBinge("Delhi (out of box)", true, BingeApprovalStatus.APPROVED, 28.61, 77.20, true);
        Long noEvents   = saveBinge("In box, no events", true, BingeApprovalStatus.APPROVED, 12.95, 77.60, false);
        Long pending    = saveBinge("In box, pending", true, BingeApprovalStatus.PENDING_APPROVAL, 12.96, 77.58, true);
        Long inactive   = saveBinge("In box, inactive", false, BingeApprovalStatus.APPROVED, 12.94, 77.57, true);
        Long ungeocoded = saveBinge("In box conceptually, no coords", true, BingeApprovalStatus.APPROVED, null, null, true);

        var result = bingeRepository.findVisibleGeocodedBingesInBox(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG);

        // Only the venue that is in-box AND active AND approved AND geocoded AND has an
        // active event type comes back; every other row is excluded for a distinct reason.
        assertThat(result).extracting(Binge::getId).containsExactly(inBox);
        assertThat(result).extracting(Binge::getId)
            .doesNotContain(outOfBox, noEvents, pending, inactive, ungeocoded);
    }

    @Test
    void latBandQuery_ignoresLongitudeButKeepsEveryOtherFilter() {
        Long inBand    = saveBinge("In band", true, BingeApprovalStatus.APPROVED, 12.97, 100.0, true); // far-east lng
        Long outOfBand = saveBinge("Out of band", true, BingeApprovalStatus.APPROVED, 40.0, 77.59, true);
        Long noEvents  = saveBinge("In band, no events", true, BingeApprovalStatus.APPROVED, 13.1, 5.0, false);

        var result = bingeRepository.findVisibleGeocodedBingesInLatBand(MIN_LAT, MAX_LAT);

        assertThat(result).extracting(Binge::getId).containsExactly(inBand);
        assertThat(result).extracting(Binge::getId).doesNotContain(outOfBand, noEvents);
    }

    private Long saveBinge(String name, boolean active, BingeApprovalStatus status,
                           Double lat, Double lng, boolean withEvent) {
        Binge binge = bingeRepository.save(Binge.builder()
            .name(name).adminId(1L).active(active).status(status)
            .latitude(lat).longitude(lng).timezone("Asia/Kolkata")
            .build());
        if (withEvent) {
            eventTypeRepository.save(EventType.builder()
                .bingeId(binge.getId()).name(name + " event").active(true)
                .basePrice(new BigDecimal("1000")).hourlyRate(new BigDecimal("200"))
                .pricePerGuest(BigDecimal.ZERO).minHours(2).maxHours(6)
                .build());
        }
        return binge.getId();
    }
}
