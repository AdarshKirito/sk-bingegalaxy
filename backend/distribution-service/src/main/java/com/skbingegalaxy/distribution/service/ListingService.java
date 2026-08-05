package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.dto.EvaluateListingRequest;
import com.skbingegalaxy.distribution.dto.ListingDto;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.listing.ListingReadinessPolicy;
import com.skbingegalaxy.distribution.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Listings published to destinations, and why they cannot go live yet (slice 4).
 *
 * <p>The point of the slice is that {@code BLOCKED} reaches the person who can fix it.
 * A listing that silently fails to publish is indistinguishable from a channel with no
 * demand, which is the same confusion the credential-expiry warning exists to prevent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

    private final ListingMappingRepository listingRepository;
    private final ConnectionDestinationRepository connectionDestinationRepository;
    private final ConnectionRepository connectionRepository;
    private final DestinationRepository destinationRepository;
    private final ListingReadinessPolicy readinessPolicy;

    public List<ListingDto> listForBinge(Long bingeId) {
        List<ListingMapping> listings = listingRepository.findByBingeId(bingeId);
        if (listings.isEmpty()) return List.of();

        Map<Long, String> destinationByMapping = destinationCodesFor(listings);
        Map<String, Destination> catalogue = destinationRepository.findAll().stream()
            .collect(Collectors.toMap(Destination::getCode, d -> d));

        return listings.stream()
            .map(l -> toDto(l, destinationByMapping.get(l.getId()), catalogue))
            .toList();
    }

    /**
     * Evaluate content against a destination's requirements and persist the verdict.
     *
     * <p>Creates the mapping if it does not exist, so an operator sees "here is what is
     * missing" the first time they look, rather than having to create an empty listing
     * and then discover it is incomplete.
     */
    @Transactional
    public ListingDto evaluate(Long bingeId, EvaluateListingRequest request) {
        ConnectionDestination cd = ownedConnectionDestination(bingeId, request.getConnectionDestinationId());
        String destinationCode = cd.getDestinationCode();

        ListingReadinessPolicy.Readiness readiness =
            readinessPolicy.evaluate(destinationCode, request.getContent());

        ListingMapping listing = listingRepository
            .findByConnectionDestinationIdAndEventTypeId(cd.getId(), request.getEventTypeId())
            .orElseGet(() -> ListingMapping.builder()
                .connectionDestinationId(cd.getId())
                .eventTypeId(request.getEventTypeId())
                .bingeId(bingeId)
                .publishState(ListingMapping.PublishState.DRAFT)
                .build());

        listing.setReadinessPct(readiness.percent());
        listing.setBlockingReasons(readiness.blockingReasons().toArray(String[]::new));

        // The state follows the verdict, but never demotes something already LIVE just
        // because content was re-evaluated: pulling a live listing down is an explicit
        // act (pause/unpublish), not a side effect of a readiness check.
        if (listing.getPublishState() != ListingMapping.PublishState.LIVE) {
            listing.setPublishState(readiness.publishable()
                ? ListingMapping.PublishState.READY
                : ListingMapping.PublishState.BLOCKED);
        }

        ListingMapping saved = listingRepository.save(listing);
        return toDto(saved, destinationCode,
            destinationRepository.findAll().stream()
                .collect(Collectors.toMap(Destination::getCode, d -> d)));
    }

    /**
     * Publish a listing that is actually ready.
     *
     * <p>The 100% check is duplicated here and in {@code ck_live_requires_ready}. That is
     * deliberate: the service check produces a sentence an operator can act on, and the
     * database CHECK makes it true regardless of which service, script or future code
     * path attempts the write.
     */
    @Transactional
    public ListingDto publish(Long bingeId, Long listingId) {
        ListingMapping listing = listingRepository.findById(listingId)
            .filter(l -> Objects.equals(l.getBingeId(), bingeId))
            .orElseThrow(() -> new ResourceNotFoundException("Listing", "id", listingId));

        if (listing.getReadinessPct() != 100) {
            String reasons = listing.getBlockingReasons() == null ? ""
                : String.join(" ", listing.getBlockingReasons());
            throw new BusinessException(
                "This listing is " + listing.getReadinessPct() + "% ready and cannot be published. "
                + reasons);
        }

        ConnectionDestination cd = connectionDestinationRepository
            .findById(listing.getConnectionDestinationId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "ConnectionDestination", "id", listing.getConnectionDestinationId()));

        // A live listing on a paused connection would advertise inventory nobody can
        // book, so the connection's state gates the listing's.
        Connection connection = connectionRepository.findById(cd.getConnectionId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Connection", "id", cd.getConnectionId()));
        if (connection.getStatus() != Connection.ConnectionStatus.ACTIVE) {
            throw new BusinessException("The connection is "
                + connection.getStatus() + " — activate it before publishing listings.");
        }
        // `enabled` defaults to FALSE — distribution is opt-in at every level, and this
        // is the level the VENUE opts in at. Without this check a listing could go LIVE
        // on a destination the venue never turned on, which is the one outcome the
        // opt-in default exists to prevent. Checked separately from stop-sell because
        // they mean different things: never enabled, versus enabled and since halted.
        if (!cd.isEnabled()) {
            throw new BusinessException(
                "This destination is not enabled for the connection — enable it before publishing.");
        }
        if (cd.isStopSell()) {
            throw new BusinessException("Stop-sell is on for this destination.");
        }

        listing.setPublishState(ListingMapping.PublishState.LIVE);
        listing.setLastPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        log.info("Binge {} published listing {} to {}", bingeId, listingId, cd.getDestinationCode());

        return toDto(listingRepository.save(listing), cd.getDestinationCode(),
            destinationRepository.findAll().stream()
                .collect(Collectors.toMap(Destination::getCode, d -> d)));
    }

    /** Scoped by binge through the owning connection — never by the id alone. */
    private ConnectionDestination ownedConnectionDestination(Long bingeId, Long connectionDestinationId) {
        ConnectionDestination cd = connectionDestinationRepository.findById(connectionDestinationId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "ConnectionDestination", "id", connectionDestinationId));
        connectionRepository.findByIdAndBingeId(cd.getConnectionId(), bingeId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "ConnectionDestination", "id", connectionDestinationId));
        return cd;
    }

    private Map<Long, String> destinationCodesFor(List<ListingMapping> listings) {
        Set<Long> cdIds = listings.stream()
            .map(ListingMapping::getConnectionDestinationId).collect(Collectors.toSet());
        Map<Long, String> byCdId = connectionDestinationRepository.findAllById(cdIds).stream()
            .collect(Collectors.toMap(ConnectionDestination::getId,
                ConnectionDestination::getDestinationCode));
        return listings.stream().collect(Collectors.toMap(ListingMapping::getId,
            l -> byCdId.getOrDefault(l.getConnectionDestinationId(), "UNKNOWN")));
    }

    private ListingDto toDto(ListingMapping l, String destinationCode,
                             Map<String, Destination> catalogue) {
        Destination d = destinationCode == null ? null : catalogue.get(destinationCode);
        return ListingDto.builder()
            .id(l.getId())
            .eventTypeId(l.getEventTypeId())
            .destinationCode(destinationCode)
            .destinationName(d == null ? destinationCode : d.getDisplayName())
            .publishState(l.getPublishState())
            .readinessPct(l.getReadinessPct())
            .blockingReasons(l.getBlockingReasons() == null
                ? List.of() : Arrays.asList(l.getBlockingReasons()))
            .externalProductId(l.getExternalProductId())
            .lastPublishedAt(l.getLastPublishedAt())
            .build();
    }
}
