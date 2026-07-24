package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.dto.BulkBingeTimezoneAssignRequest;
import com.skbingegalaxy.booking.dto.BulkBingeTimezoneAssignResult;
import com.skbingegalaxy.booking.dto.CustomerAboutExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerAboutHighlightDto;
import com.skbingegalaxy.booking.dto.CustomerAboutPolicyDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardSlideDto;
import com.skbingegalaxy.booking.dto.PublicBingeDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
import com.skbingegalaxy.booking.util.GeoUtils;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class BingeService {

    private static final String DEFAULT_DASHBOARD_EYEBROW = "Explore Experiences";
    private static final String DEFAULT_DASHBOARD_TITLE = "Pick a setup that matches the mood";
    private static final String DEFAULT_DASHBOARD_LAYOUT = "GRID";
    private static final String DEFAULT_SLIDE_BADGE = "Featured";
    private static final String DEFAULT_SLIDE_HEADLINE = "Custom setup";
    private static final String DEFAULT_SLIDE_DESCRIPTION = "Guide customers toward the atmosphere, offers, or experiences you want this venue to highlight first.";
    private static final String DEFAULT_SLIDE_CTA = "Open Booking";
    private static final String DEFAULT_SLIDE_THEME = "celebration";
    private static final String DEFAULT_ABOUT_EYEBROW = "Before You Book";
    private static final String DEFAULT_ABOUT_TITLE = "Know your binge before event day";
    private static final String DEFAULT_ABOUT_HERO_TITLE = "Everything customers should know, in one place";
    private static final String DEFAULT_ABOUT_HERO_DESCRIPTION = "Set expectations clearly with venue highlights, house rules, and policies so guests walk in prepared and confident.";
    private static final String DEFAULT_ABOUT_HIGHLIGHTS_TITLE = "Why guests choose this binge";
    private static final String DEFAULT_ABOUT_HOUSE_RULES_TITLE = "House rules";
    private static final String DEFAULT_ABOUT_POLICY_TITLE = "Policies and regulations";
    private static final String DEFAULT_ABOUT_CONTACT_HEADING = "Need help before your slot?";
    private static final String DEFAULT_ABOUT_CONTACT_DESCRIPTION = "Use the support contacts listed for this binge and include your booking reference for quicker help.";

    private final BingeRepository bingeRepository;
    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final RateCodeRepository rateCodeRepository;
    private final CustomerPricingProfileRepository customerPricingProfileRepository;
    private final ObjectMapper objectMapper;
    private final AdminNotificationService adminNotificationService;
    private final VenueClockService venueClock;
    private final AuthorityLockGuard authorityLockGuard;
    private final com.skbingegalaxy.booking.repository.BingeChangeRequestRepository changeRequestRepository;
    private final com.skbingegalaxy.booking.repository.TaxRuleRepository taxRuleRepository;
    private final com.skbingegalaxy.booking.permission.BingeModulePermissionService modulePermissionService;

    /** Hours an approved binge has to create its first event before auto-deactivation. */
    public static final int GRACE_PERIOD_HOURS = 24;
    /** Warning is delivered when this many hours of the grace period remain. */
    public static final int GRACE_WARNING_AT_HOURS_REMAINING = 12;

    public List<BingeDto> getAdminBinges(Long adminId, String role) {
        if ("SUPER_ADMIN".equals(role)) {
            return bingeRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).toList();
        }
        return bingeRepository.findByAdminIdOrderByCreatedAtDesc(adminId)
            .stream().map(this::toDto).toList();
    }

    /**
     * Customer-facing listing.
     *
     * <p>A binge is shown to customers only if all three are true:
     * <ol>
     *   <li>{@code active = true} (admin hasn't paused it)</li>
     *   <li>{@code status = APPROVED} (super-admin has approved it)</li>
     *   <li>It has at least one active {@code event_type} — empty venues never
     *       appear so customers don't land on a binge they can't book.</li>
     * </ol>
     */
    @org.springframework.cache.annotation.Cacheable(value = "activeBinges")
    public List<PublicBingeDto> getAllActiveBinges() {
        return bingeRepository.findCustomerVisibleBinges()
            .stream().map(this::toPublicDto).toList();
    }

    /** Largest radius a proximity query may request (km). Clamps hostile inputs. */
    public static final double MAX_NEARBY_RADIUS_KM = 500.0;
    /** Default radius when the caller does not specify one (km). */
    public static final double DEFAULT_NEARBY_RADIUS_KM = 50.0;
    /** Hard cap on results returned by a single proximity query. */
    public static final int MAX_NEARBY_RESULTS = 100;

    /**
     * Proximity discovery — "venues near me".
     *
     * <p>Returns the same customer-visible venues as {@link #getAllActiveBinges()}
     * (active + APPROVED + at least one active event), additionally restricted to
     * venues that have been geocoded and fall within {@code radiusKm} of the query
     * point, ordered nearest-first. Each returned DTO carries {@code distanceKm}.
     *
     * <p>Inputs are validated and clamped: out-of-range coordinates are rejected
     * (400), while {@code radiusKm} and {@code limit} are clamped to sane bounds so
     * a crafted request can never force an unbounded scan-and-sort. Un-geocoded
     * venues are silently skipped — they remain reachable via the alphabetical list.
     *
     * <p>The candidate set is the small, already-indexed customer-visible listing,
     * so the in-memory Haversine refinement is trivial. If per-deployment venue
     * counts ever reach the tens of thousands, push the bounding-box filter into the
     * repository (or PostGIS) without changing this method's contract.
     */
    public List<PublicBingeDto> getNearbyBinges(double lat, double lng, double radiusKm, int limit) {
        if (!GeoUtils.isValidLatitude(lat)) {
            throw new BusinessException("Latitude must be between -90 and 90", HttpStatus.BAD_REQUEST);
        }
        if (!GeoUtils.isValidLongitude(lng)) {
            throw new BusinessException("Longitude must be between -180 and 180", HttpStatus.BAD_REQUEST);
        }
        double radius = Double.isFinite(radiusKm) && radiusKm > 0
            ? Math.min(radiusKm, MAX_NEARBY_RADIUS_KM)
            : DEFAULT_NEARBY_RADIUS_KM;
        int cap = Math.min(Math.max(limit, 1), MAX_NEARBY_RESULTS);

        // Stage 1 — bounding box: let the DB index narrow to candidates near the point
        // (an index range scan), instead of pulling every visible venue into the app.
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radius);
        List<Binge> candidates = box.isLongitudeBounded()
            ? bingeRepository.findVisibleGeocodedBingesInBox(
                box.minLat(), box.maxLat(), box.minLng(), box.maxLng())
            : bingeRepository.findVisibleGeocodedBingesInLatBand(box.minLat(), box.maxLat());

        // Stage 2 — exact refine: the box is a superset (its corners exceed the radius),
        // so compute the true great-circle distance, keep only those within radius, sort
        // nearest-first, and cap. The defensive coordinate check tolerates any row that
        // slipped past the DB CHECK constraints rather than letting Haversine throw.
        return candidates.stream()
            .filter(b -> GeoUtils.isValidLatitude(b.getLatitude())
                      && GeoUtils.isValidLongitude(b.getLongitude()))
            .map(b -> {
                PublicBingeDto dto = toPublicDto(b);
                dto.setDistanceKm(GeoUtils.roundKm(
                    GeoUtils.haversineKm(lat, lng, b.getLatitude(), b.getLongitude())));
                return dto;
            })
            .filter(dto -> dto.getDistanceKm() <= radius)
            .sorted(Comparator.comparingDouble(PublicBingeDto::getDistanceKm))
            .limit(cap)
            .toList();
    }

    /** Super-admin-only: list every binge currently awaiting approval. */
    public List<BingeDto> getPendingBinges(String role) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException(
                "Only super-admins can view pending binge approvals", HttpStatus.FORBIDDEN);
        }
        return bingeRepository.findByStatusOrderByCreatedAtDesc(BingeApprovalStatus.PENDING_APPROVAL)
            .stream().map(this::toDto).toList();
    }

    public List<BingeDto> getBingesByAdminId(Long adminId) {
        return bingeRepository.findByAdminIdOrderByCreatedAtDesc(adminId)
            .stream().map(this::toDto).toList();
    }

    /**
     * Public single-venue lookup. Two layers of protection for anonymous callers:
     * <ol>
     *   <li>only <b>published</b> venues (active + APPROVED) resolve — pending,
     *       rejected, and deactivated binges 404 (see {@link #requirePubliclyVisibleBinge}),
     *       so their existence is never disclosed by id enumeration;</li>
     *   <li>the result is the customer-safe {@link PublicBingeDto}, never the admin-only
     *       {@link BingeDto}, so owner id, approval audit, and anti-abuse thresholds
     *       never reach an unauthenticated caller.</li>
     * </ol>
     */
    public PublicBingeDto getBingeById(Long id) {
        return toPublicDto(requirePubliclyVisibleBinge(id));
    }

    /**
     * Visibility gate for every anonymous "view binge by id" surface.
     *
     * <p>Returns the binge only if it is <b>published</b> — {@code active} and
     * {@link BingeApprovalStatus#APPROVED}. Pending, rejected, and deactivated binges
     * raise the same {@link ResourceNotFoundException} as a non-existent id, so the
     * response never discloses that such a binge exists (no enumeration of pending
     * venue names, addresses, or marketing config). Admin previews go through the
     * {@code getManaged*} / {@code getAdmin*} ownership paths and are unaffected.
     */
    private Binge requirePubliclyVisibleBinge(Long id) {
        return bingeRepository.findById(id)
            .filter(b -> b.isActive() && b.getStatus() == BingeApprovalStatus.APPROVED)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
    }

    public CustomerDashboardExperienceDto getCustomerDashboardExperience(Long id) {
        return readDashboardExperience(requirePubliclyVisibleBinge(id).getCustomerDashboardConfigJson());
    }

    public CustomerDashboardExperienceDto getAdminCustomerDashboardExperience(Long id, Long adminId, String role) {
        return readDashboardExperience(getManagedBinge(id, adminId, role).getCustomerDashboardConfigJson());
    }

    public CustomerAboutExperienceDto getCustomerAboutExperience(Long id) {
        return readAboutExperience(requirePubliclyVisibleBinge(id).getCustomerAboutConfigJson());
    }

    public CustomerAboutExperienceDto getAdminCustomerAboutExperience(Long id, Long adminId, String role) {
        return readAboutExperience(getManagedBinge(id, adminId, role).getCustomerAboutConfigJson());
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BingeDto createBinge(BingeSaveRequest request, Long adminId, String role, LocalDate clientDate) {
        if (bingeRepository.existsByNameAndAdminId(request.getName(), adminId)) {
            throw new DuplicateResourceException("Binge", "name", request.getName());
        }

        LocalDate opDate = clientDate != null ? clientDate : LocalDate.now(venueClock.defaultZone());
        LocalTime openT = request.getOpenTime() != null ? request.getOpenTime() : LocalTime.of(10, 0);
        LocalTime closeT = request.getCloseTime() != null ? request.getCloseTime() : LocalTime.of(23, 0);
        validateOperatingHours(openT, closeT);
        validateCoordinatePair(request.getLatitude(), request.getLongitude());

        // SUPER_ADMIN-created binges go live immediately. Regular ADMIN-created
        // binges enter the pending-approval queue and stay invisible to customers
        // until a super-admin approves them.
        boolean isSuperAdmin = "SUPER_ADMIN".equalsIgnoreCase(role);
        BingeApprovalStatus initialStatus = isSuperAdmin
            ? BingeApprovalStatus.APPROVED
            : BingeApprovalStatus.PENDING_APPROVAL;
        boolean initialActive = isSuperAdmin; // pending binges are inactive until approved

        Binge binge = Binge.builder()
            .name(request.getName())
            .address(composeAddressDisplay(request))
            .addressLine1(trimToNull(request.getAddressLine1()))
            .addressLine2(trimToNull(request.getAddressLine2()))
            .city(trimToNull(request.getCity()))
            .state(trimToNull(request.getState()))
            .country(trimToNull(request.getCountry()))
            // Currency is DERIVED from the country and is the only currency this binge
            // ever prices/charges in — never a customer choice.
            .currency(com.skbingegalaxy.booking.util.CountryCurrency.forCountry(request.getCountry()))
            .postalCode(trimToNull(request.getPostalCode()))
            .latitude(request.getLatitude())
            .longitude(request.getLongitude())
            .timezone(resolveTimezone(request.getTimezone(),
                request.getCountry(), request.getState(), request.getCity()))
            .adminId(adminId)
            .active(initialActive)
            .status(initialStatus)
            .operationalDate(opDate)
            .supportEmail(trimToNull(request.getSupportEmail()))
            .supportPhone(trimToNull(request.getSupportPhone()))
            .supportPhoneCountryCode(trimToNull(request.getSupportPhoneCountryCode()))
            .supportWhatsapp(trimToNull(request.getSupportWhatsapp()))
            .supportWhatsappCountryCode(trimToNull(request.getSupportWhatsappCountryCode()))
            .supportPhoneIsWhatsapp(Boolean.TRUE.equals(request.getSupportPhoneIsWhatsapp()))
            .ownerEmail(trimToNull(request.getOwnerEmail()))
            .ownerPhone(trimToNull(request.getOwnerPhone()))
            .ownerPhoneCountryCode(trimToNull(request.getOwnerPhoneCountryCode()))
            .ownerPhoneIsWhatsapp(Boolean.TRUE.equals(request.getOwnerPhoneIsWhatsapp()))
            .customerCancellationEnabled(request.getCustomerCancellationEnabled() == null || request.getCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(request.getCustomerCancellationCutoffMinutes() == null ? 180 : request.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(request.getMaxConcurrentBookings())
            .openTime(openT)
            .closeTime(closeT)
            .openingHoursJson(normalizeOpeningHours(request.getOpeningHours()))
            .roomSelectionRequired(Boolean.TRUE.equals(request.getRoomSelectionRequired()))
            .build();
        if (isSuperAdmin) {
            binge.setApprovalDecidedBy(adminId);
            binge.setApprovalDecidedAt(LocalDateTime.now(ZoneOffset.UTC));
        }

        binge = bingeRepository.save(binge);
        log.info("Binge created: '{}' by user {} (role={}, status={})",
            binge.getName(), adminId, role, initialStatus);

        // Auto-assign the venue country's standard tax (e.g. IN → GST 18%) so bookings
        // at this venue are taxed correctly from day one. Idempotent — skips when any
        // active rule (venue-scoped or global) already covers the country.
        ensureDefaultTaxRule(binge);

        // Notify super-admins that a new approval request is waiting in the
        // queue. Skipped when a super-admin self-creates (auto-approved).
        if (initialStatus == BingeApprovalStatus.PENDING_APPROVAL) {
            adminNotificationService.broadcastToRole(
                "SUPER_ADMIN",
                "BINGE_APPROVAL_REQUESTED",
                "INFO",
                "New binge awaiting approval",
                String.format("Admin #%d submitted '%s' for approval. Review it on the entrance dashboard.",
                    adminId, binge.getName()),
                binge.getId(),
                "/admin/platform");
        }
        return toDto(binge);
    }

    /** Super-admin approves a pending binge — flips status to APPROVED + active=true. */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BingeDto approveBinge(Long id, Long superAdminId, String role, Boolean taxesEnabled,
                                 java.util.List<String> disabledModules, String accessRemarks,
                                 String timezoneOverride, String countryOverride) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException(
                "Only super-admins can approve binge requests", HttpStatus.FORBIDDEN);
        }
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        if (binge.getStatus() == BingeApprovalStatus.APPROVED) {
            throw new BusinessException("Binge is already approved");
        }
        binge.setStatus(BingeApprovalStatus.APPROVED);
        binge.setActive(true);

        // Approval-time corrections. The super-admin is the genuineness gate for a
        // new venue and may fix these before it goes live:
        //   country → re-derives the payment currency and the tax jurisdiction;
        //   timezone → the zone the admin could not set themselves;
        //   taxes → whether the tax system runs here at all.
        // A country change is applied FIRST so the derived currency is correct
        // before tax seeding below.
        boolean countryChanged = false;
        if (countryOverride != null && !countryOverride.isBlank()) {
            String c = countryOverride.trim().toUpperCase();
            if (!c.matches("^[A-Z]{2}$")) {
                throw new BusinessException(
                    "Country must be a 2-letter ISO code (e.g. US, IN, AE).");
            }
            if (!c.equalsIgnoreCase(binge.getCountry())) {
                binge.setCountry(c);
                binge.setCurrency(com.skbingegalaxy.booking.util.CountryCurrency.forCountry(c));
                countryChanged = true;
            }
        }
        if (timezoneOverride != null && !timezoneOverride.isBlank()) {
            try {
                binge.setTimezone(java.time.ZoneId.of(timezoneOverride.trim()).getId());
            } catch (Exception ex) {
                throw new BusinessException(
                    "'" + timezoneOverride + "' isn't a valid IANA timezone (e.g. America/New_York).");
            }
        }
        if (taxesEnabled != null) {
            binge.setTaxesEnabled(taxesEnabled);
        }
        binge.setApprovalDecidedBy(superAdminId);
        binge.setApprovalDecidedAt(LocalDateTime.now(ZoneOffset.UTC));
        binge.setApprovalRejectionReason(null);
        binge.setAutoDeactivatedAt(null);
        binge.setGraceWarningSentAt(null);

        // If the binge already has at least one active event (e.g. because a
        // super-admin pre-seeded one), mark it operational immediately so the
        // grace-period scheduler skips it.
        if (binge.getFirstEventCreatedAt() == null
                && eventTypeRepository.findByBingeIdAndActiveTrue(binge.getId()).size() > 0) {
            binge.setFirstEventCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        if (accessRemarks != null && !accessRemarks.isBlank()) {
            binge.setAccessRemarks(accessRemarks.length() > 1000
                ? accessRemarks.substring(0, 1000) : accessRemarks.trim());
        }
        binge = bingeRepository.save(binge);
        // Safety net for venues created before tax auto-assignment existed.
        if (binge.isTaxesEnabled()) {
            ensureDefaultTaxRule(binge);
        }
        // Approval-time module setup (V71): the super-admin decides which
        // binge-operation options the owning admin starts with. Null means
        // "no restriction" — everything stays enabled (deny-list model).
        if (disabledModules != null) {
            modulePermissionService.applyModuleSelection(
                binge.getId(), binge.getAdminId(), disabledModules,
                "Set at binge approval", superAdminId, role);
        }
        log.info("Binge {} ('{}') approved by super-admin {} (taxes {}, {} module(s) disabled)",
            id, binge.getName(), superAdminId, binge.isTaxesEnabled() ? "on" : "off",
            disabledModules == null ? 0 : disabledModules.size());

        // Notify the requesting admin so they know to add an event within the
        // 24-hour SLA. We only notify when first_event_created_at is unset
        // — if events already exist, no further action is required.
        if (binge.getFirstEventCreatedAt() == null) {
            adminNotificationService.notifyUser(
                binge.getAdminId(),
                "ADMIN",
                "BINGE_APPROVED",
                "INFO",
                "Binge approved — add an event within " + GRACE_PERIOD_HOURS + " hours",
                String.format("Your binge '%s' was approved. Create at least one event type within "
                        + "%d hours, otherwise it will be automatically paused.",
                    binge.getName(), GRACE_PERIOD_HOURS),
                binge.getId(),
                "/admin/platform");
        } else {
            adminNotificationService.notifyUser(
                binge.getAdminId(),
                "ADMIN",
                "BINGE_APPROVED",
                "INFO",
                "Binge approved",
                String.format("Your binge '%s' was approved and is now visible to customers.",
                    binge.getName()),
                binge.getId(),
                "/admin/platform");
        }
        return toDto(binge);
    }

    /** Super-admin rejects a pending binge — keeps row for audit but marks REJECTED. */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BingeDto rejectBinge(Long id, Long superAdminId, String role, String reason) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException(
                "Only super-admins can reject binge requests", HttpStatus.FORBIDDEN);
        }
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        if (binge.getStatus() == BingeApprovalStatus.REJECTED) {
            throw new BusinessException("Binge is already rejected");
        }
        binge.setStatus(BingeApprovalStatus.REJECTED);
        binge.setActive(false);
        binge.setApprovalDecidedBy(superAdminId);
        binge.setApprovalDecidedAt(LocalDateTime.now(ZoneOffset.UTC));
        binge.setApprovalRejectionReason(trimToNull(reason));
        binge = bingeRepository.save(binge);
        log.info("Binge {} ('{}') rejected by super-admin {} (reason='{}')",
            id, binge.getName(), superAdminId, reason);

        // Notify the requesting admin with the rejection reason for transparency.
        String reasonLine = (reason == null || reason.isBlank())
            ? ""
            : " Reason: " + reason.trim();
        adminNotificationService.notifyUser(
            binge.getAdminId(),
            "ADMIN",
            "BINGE_REJECTED",
            "WARNING",
            "Binge request rejected",
            String.format("Your binge request '%s' was not approved.%s",
                binge.getName(), reasonLine),
            binge.getId(),
            "/admin/platform");
        return toDto(binge);
    }

    /**
     * The owning admin re-requests approval for a previously REJECTED binge —
     * flips it back to {@link BingeApprovalStatus#PENDING_APPROVAL}, clears the
     * prior rejection decision, and re-notifies super-admins so it re-enters the
     * review queue. Only the admin who owns the binge (or a super-admin) may do
     * this, and only from the REJECTED state.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BingeDto resubmitBinge(Long id, Long adminId, String role) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        boolean isSuperAdmin = "SUPER_ADMIN".equalsIgnoreCase(role);
        boolean isOwner = binge.getAdminId() != null && binge.getAdminId().equals(adminId);
        if (!isSuperAdmin && !isOwner) {
            throw new BusinessException(
                "You can only re-request approval for your own binges", HttpStatus.FORBIDDEN);
        }
        if (binge.getStatus() != BingeApprovalStatus.REJECTED) {
            throw new BusinessException(
                "Only a rejected binge can be re-requested (current status: " + binge.getStatus() + ")");
        }
        binge.setStatus(BingeApprovalStatus.PENDING_APPROVAL);
        binge.setApprovalDecidedBy(null);
        binge.setApprovalDecidedAt(null);
        binge.setApprovalRejectionReason(null);
        binge = bingeRepository.save(binge);
        log.info("Binge {} ('{}') re-requested for approval by user {} (role={})",
            id, binge.getName(), adminId, role);

        // Put it back in front of the super-admins.
        adminNotificationService.broadcastToRole(
            "SUPER_ADMIN",
            "BINGE_APPROVAL_REQUESTED",
            "INFO",
            "Binge re-submitted for approval",
            String.format("Admin #%d re-requested approval for '%s' after an earlier rejection. "
                    + "Review it on the entrance dashboard.", binge.getAdminId(), binge.getName()),
            binge.getId(),
            "/admin/platform");
        return toDto(binge);
    }

    // ── Grace-period helpers (called by scheduler + event-type creation hook) ──

    /**
     * Hook: stamp {@code firstEventCreatedAt} the very first time an active
     * event type lands on a binge. Idempotent — once set, we never overwrite,
     * so the original "became operational" timestamp is preserved for audit.
     */
    @Transactional
    public void recordFirstEventIfNeeded(Long bingeId) {
        if (bingeId == null) return;
        bingeRepository.findById(bingeId).ifPresent(b -> {
            if (b.getFirstEventCreatedAt() == null) {
                b.setFirstEventCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                bingeRepository.save(b);
                log.info("Binge {} ('{}') marked operational (first event created)",
                    bingeId, b.getName());
            }
        });
    }

    /**
     * Scheduler entry point. For every APPROVED binge that hasn't yet seen
     * its first event, deliver a 12-hour warning and auto-deactivate at 24h.
     * Returns the count of binges that were auto-deactivated this sweep.
     */
    @Transactional
    public int enforceGracePeriod() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Binge> candidates = bingeRepository.findByStatusOrderByCreatedAtDesc(BingeApprovalStatus.APPROVED)
            .stream()
            .filter(b -> b.getFirstEventCreatedAt() == null)
            .filter(b -> b.getApprovalDecidedAt() != null)
            .toList();

        int deactivated = 0;
        for (Binge b : candidates) {
            long minutesSinceApproval = java.time.Duration
                .between(b.getApprovalDecidedAt(), now).toMinutes();
            long warningAtMinutes = (long) (GRACE_PERIOD_HOURS - GRACE_WARNING_AT_HOURS_REMAINING) * 60;
            long deadlineMinutes = (long) GRACE_PERIOD_HOURS * 60;

            if (minutesSinceApproval >= deadlineMinutes && b.isActive()) {
                // Past deadline — auto-deactivate.
                b.setActive(false);
                b.setAutoDeactivatedAt(now);
                bingeRepository.save(b);
                deactivated++;
                log.warn("Binge {} ('{}') auto-deactivated: no events created within {}h grace period",
                    b.getId(), b.getName(), GRACE_PERIOD_HOURS);

                adminNotificationService.notifyUser(
                    b.getAdminId(),
                    "ADMIN",
                    "BINGE_AUTO_DEACTIVATED",
                    "CRITICAL",
                    "Binge auto-paused",
                    String.format("Your binge '%s' was auto-paused because no event types were created within "
                            + "%d hours of approval. Add an event type and re-activate it to go live.",
                        b.getName(), GRACE_PERIOD_HOURS),
                    b.getId(),
                    "/admin/platform");

                adminNotificationService.broadcastToRole(
                    "SUPER_ADMIN",
                    "BINGE_AUTO_DEACTIVATED",
                    "WARNING",
                    "Approved binge auto-paused",
                    String.format("'%s' (admin #%d) was auto-paused: no events created within %d hours of approval.",
                        b.getName(), b.getAdminId(), GRACE_PERIOD_HOURS),
                    b.getId(),
                    "/admin/platform");
            } else if (minutesSinceApproval >= warningAtMinutes && b.getGraceWarningSentAt() == null) {
                // Mid-grace warning — deliver once.
                b.setGraceWarningSentAt(now);
                bingeRepository.save(b);
                log.info("Binge {} ('{}') grace-period warning issued ({}h remaining)",
                    b.getId(), b.getName(), GRACE_WARNING_AT_HOURS_REMAINING);

                adminNotificationService.notifyUser(
                    b.getAdminId(),
                    "ADMIN",
                    "BINGE_GRACE_WARNING",
                    "WARNING",
                    "Add an event soon",
                    String.format("Your binge '%s' will be auto-paused in about %d hours unless you create "
                            + "at least one event type.",
                        b.getName(), GRACE_WARNING_AT_HOURS_REMAINING),
                    b.getId(),
                    "/admin/platform");
            }
        }
        return deactivated;
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BingeDto updateBinge(Long id, BingeSaveRequest request, Long adminId, String role, boolean delegated) {
        Binge binge = getManagedBinge(id, adminId, role);

        binge.setName(request.getName());
        binge.setAddress(composeAddressDisplay(request));
        binge.setAddressLine1(trimToNull(request.getAddressLine1()));
        binge.setAddressLine2(trimToNull(request.getAddressLine2()));
        binge.setCity(trimToNull(request.getCity()));
        binge.setState(trimToNull(request.getState()));
        applyCountryIfChanged(binge, request.getCountry(), role, delegated, adminId);
        binge.setPostalCode(trimToNull(request.getPostalCode()));
        validateCoordinatePair(request.getLatitude(), request.getLongitude());
        binge.setLatitude(request.getLatitude());
        binge.setLongitude(request.getLongitude());
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            applyTimezoneIfChanged(binge, request.getTimezone(), role, delegated);
        }
        binge.setSupportEmail(trimToNull(request.getSupportEmail()));
        binge.setSupportPhone(trimToNull(request.getSupportPhone()));
        binge.setSupportPhoneCountryCode(trimToNull(request.getSupportPhoneCountryCode()));
        binge.setSupportWhatsapp(trimToNull(request.getSupportWhatsapp()));
        binge.setSupportWhatsappCountryCode(trimToNull(request.getSupportWhatsappCountryCode()));
        if (request.getSupportPhoneIsWhatsapp() != null) {
            binge.setSupportPhoneIsWhatsapp(request.getSupportPhoneIsWhatsapp());
        }
        binge.setOwnerEmail(trimToNull(request.getOwnerEmail()));
        binge.setOwnerPhone(trimToNull(request.getOwnerPhone()));
        binge.setOwnerPhoneCountryCode(trimToNull(request.getOwnerPhoneCountryCode()));
        if (request.getOwnerPhoneIsWhatsapp() != null) {
            binge.setOwnerPhoneIsWhatsapp(request.getOwnerPhoneIsWhatsapp());
        }
        binge.setCustomerCancellationEnabled(request.getCustomerCancellationEnabled() == null || request.getCustomerCancellationEnabled());
        binge.setCustomerCancellationCutoffMinutes(request.getCustomerCancellationCutoffMinutes() == null ? binge.getCustomerCancellationCutoffMinutes() : request.getCustomerCancellationCutoffMinutes());
        binge.setMaxConcurrentBookings(request.getMaxConcurrentBookings());
        // Per-binge operating hours: null in the request means "leave unchanged".
        // We re-validate the resulting (open, close) pair to reject e.g. close <= open.
        if (request.getOpenTime() != null) {
            binge.setOpenTime(request.getOpenTime());
        }
        if (request.getCloseTime() != null) {
            binge.setCloseTime(request.getCloseTime());
        }
        validateOperatingHours(binge.getOpenTime(), binge.getCloseTime());
        // Per-day operating hours: null request means "leave unchanged"; an explicit
        // (possibly empty) list replaces the schedule. Empty list clears per-day overrides.
        if (request.getOpeningHours() != null) {
            binge.setOpeningHoursJson(normalizeOpeningHours(request.getOpeningHours()));
        }
        // V56: null leaves the existing value unchanged.
        if (request.getRoomSelectionRequired() != null) {
            binge.setRoomSelectionRequired(request.getRoomSelectionRequired());
        }
        binge = bingeRepository.save(binge);
        log.info("Binge updated: '{}' (ID: {})", binge.getName(), id);
        return toDto(binge);
    }

    /**
     * Validates/canonicalizes {@code requestedTz} and, if it actually differs from the
     * binge's current zone, gates the change behind {@link AuthorityLockGuard} and evicts
     * the cached {@link VenueClockService} entry. Re-saving the same zone is a no-op that
     * skips the grant check — shared by {@link #updateBinge} and
     * {@link #bulkAssignTimezone} so both paths enforce identically.
     */
    private void applyTimezoneIfChanged(Binge binge, String requestedTz, String role, boolean delegated) {
        // Validate AND canonicalize so the change-detection below compares like-for-like.
        final String normalizedTz = normalizeTimezoneOrThrow(requestedTz);
        // Only an ACTUAL change is gated + re-evicts. Re-saving the same zone
        // (common when the form round-trips every field) must not require the
        // grant — otherwise routine venue edits would 423 for unprivileged admins.
        if (!normalizedTz.equals(binge.getTimezone())) {
            // High-blast-radius: changing the zone reinterprets every existing
            // booking's wall-clock. Default-deny unless super-admin-granted —
            // but only once the venue is APPROVED. Pre-approval the owning admin
            // may still correct the derived zone freely; the super-admin
            // re-confirms it in the approval modal anyway, and no customer
            // bookings can exist against an unapproved venue.
            if (binge.getStatus() == BingeApprovalStatus.APPROVED) {
                authorityLockGuard.requireTimezoneChangePermitted(role, delegated, binge.getId());
            }
            binge.setTimezone(normalizedTz);
            venueClock.evict(binge.getId()); // flush cached zone for this venue
        }
    }

    /**
     * A regular admin cannot change a binge's country directly (it reprices the whole
     * binge). Instead they submit this request, which persists a PENDING
     * {@link com.skbingegalaxy.booking.entity.BingeChangeRequest} (full audit trail) and
     * notifies every super-admin, who approves or rejects it from the review panel.
     * Ownership of the binge is enforced so an admin can only request changes for their
     * own venues. At most one PENDING request per binge is allowed at a time.
     */
    @Transactional
    public com.skbingegalaxy.booking.dto.BingeChangeRequestDto requestCountryChange(
            Long bingeId, String requestedCountry, String reason, Long adminId, String role) {
        Binge binge = getManagedBinge(bingeId, adminId, role);
        String rc = trimToNull(requestedCountry);
        if (rc == null || rc.length() != 2 || !rc.chars().allMatch(Character::isLetter)) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Provide a valid 2-letter ISO country code (e.g. US, CN, AE).");
        }
        rc = rc.toUpperCase();
        if (rc.equalsIgnoreCase(binge.getCountry())) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "This binge is already in " + rc + ".");
        }
        changeRequestRepository.findFirstByBingeIdAndRequestTypeAndStatus(
                bingeId, com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.COUNTRY_CHANGE,
                com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING)
            .ifPresent(existing -> {
                throw new com.skbingegalaxy.common.exception.BusinessException(
                    "A country-change request for this binge is already pending super-admin review "
                    + "(requested " + existing.getRequestedValue() + "). Cancel it first to submit a new one.",
                    HttpStatus.CONFLICT);
            });
        String derivedCurrency = com.skbingegalaxy.booking.util.CountryCurrency.forCountry(rc);
        com.skbingegalaxy.booking.entity.BingeChangeRequest request =
            com.skbingegalaxy.booking.entity.BingeChangeRequest.builder()
                .bingeId(bingeId)
                .requestType(com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.COUNTRY_CHANGE)
                .currentValue(binge.getCountry())
                .requestedValue(rc)
                .requestedCurrency(derivedCurrency)
                .reason(trimToNull(reason))
                .requestedByAdminId(adminId)
                .status(com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING)
                .build();
        request = changeRequestRepository.save(request);

        String reasonPart = request.getReason() == null ? "" : " Reason: " + request.getReason();
        adminNotificationService.broadcastToRole(
            "SUPER_ADMIN",
            "BINGE_COUNTRY_CHANGE_REQUESTED",
            "WARNING",
            "Binge country-change request",
            String.format(
                "Admin #%d requests '%s' (currently %s, %s) be moved to %s — currency would become %s.%s "
                + "Review it under Venues → Change requests.",
                adminId, binge.getName(),
                binge.getCountry() == null ? "—" : binge.getCountry(),
                binge.getCurrency(), rc, derivedCurrency, reasonPart),
            binge.getId(),
            "/admin/binges");
        log.info("Country-change request #{} for binge {} -> {} (currency {}) by admin {}",
            request.getId(), bingeId, rc, derivedCurrency, adminId);
        return toChangeRequestDto(request, binge);
    }

    /**
     * A regular admin flags the auto-derived timezone as wrong and asks a
     * super-admin to resolve it. The admin never sets the zone directly (the
     * picker is read-only for them); the reason is mandatory, and a suggested zone
     * is optional. A super-admin applies the final zone on approval.
     */
    @Transactional
    public com.skbingegalaxy.booking.dto.BingeChangeRequestDto requestTimezoneChange(
            Long bingeId, String suggestedTimezone, String reason, Long adminId, String role) {
        Binge binge = getManagedBinge(bingeId, adminId, role);

        String cleanReason = trimToNull(reason);
        if (cleanReason == null || cleanReason.length() < 5) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Explain why the auto-detected timezone is wrong (this is required so a "
                + "super-admin can resolve it).");
        }
        // A suggested zone is optional, but if given it must be a real IANA zone —
        // otherwise the super-admin has nothing valid to apply.
        String suggestion = trimToNull(suggestedTimezone);
        if (suggestion != null) {
            try {
                suggestion = java.time.ZoneId.of(suggestion).getId();
            } catch (Exception ex) {
                throw new com.skbingegalaxy.common.exception.BusinessException(
                    "'" + suggestedTimezone + "' isn't a valid IANA timezone (e.g. America/New_York). "
                    + "Leave it blank to let the super-admin choose.");
            }
        }
        if (suggestion != null && suggestion.equalsIgnoreCase(binge.getTimezone())) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "That is already this venue's timezone.");
        }
        changeRequestRepository.findFirstByBingeIdAndRequestTypeAndStatus(
                bingeId, com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.TIMEZONE_CHANGE,
                com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING)
            .ifPresent(existing -> {
                throw new com.skbingegalaxy.common.exception.BusinessException(
                    "A timezone review for this venue is already pending super-admin resolution. "
                    + "Cancel it first to submit a new one.", HttpStatus.CONFLICT);
            });

        com.skbingegalaxy.booking.entity.BingeChangeRequest request =
            com.skbingegalaxy.booking.entity.BingeChangeRequest.builder()
                .bingeId(bingeId)
                .requestType(com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.TIMEZONE_CHANGE)
                .currentValue(binge.getTimezone())
                .requestedValue(suggestion)  // may be null — super-admin picks on approval
                .reason(cleanReason)
                .requestedByAdminId(adminId)
                .status(com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING)
                .build();
        request = changeRequestRepository.save(request);

        adminNotificationService.broadcastToRole(
            "SUPER_ADMIN",
            "BINGE_TIMEZONE_CHANGE_REQUESTED",
            "WARNING",
            "Binge timezone review requested",
            String.format(
                "Admin #%d reports the timezone for '%s' (currently %s) looks wrong%s. Reason: %s "
                + "Resolve it under Venues → Change requests.",
                adminId, binge.getName(),
                binge.getTimezone() == null ? "—" : binge.getTimezone(),
                suggestion == null ? "" : " — suggested " + suggestion,
                cleanReason),
            binge.getId(), "/admin/binges");
        log.info("Timezone-change request #{} for binge {} (suggested {}) by admin {}",
            request.getId(), bingeId, suggestion, adminId);
        return toChangeRequestDto(request, binge);
    }

    /**
     * List change requests: a super-admin sees every request (optionally filtered by
     * status); a regular admin sees only the requests they submitted.
     */
    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.BingeChangeRequestDto> listChangeRequests(
            Long userId, String role, String status) {
        List<com.skbingegalaxy.booking.entity.BingeChangeRequest> rows;
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) {
            if (status != null && !status.isBlank()) {
                rows = changeRequestRepository.findByStatusOrderByCreatedAtDesc(
                    parseChangeRequestStatus(status));
            } else {
                rows = changeRequestRepository.findAllByOrderByCreatedAtDesc();
            }
        } else {
            rows = changeRequestRepository.findByRequestedByAdminIdOrderByCreatedAtDesc(userId);
        }
        // Batch-resolve binge names (avoid N+1)
        java.util.Set<Long> bingeIds = rows.stream()
            .map(com.skbingegalaxy.booking.entity.BingeChangeRequest::getBingeId)
            .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, Binge> byId = bingeRepository.findAllById(bingeIds).stream()
            .collect(java.util.stream.Collectors.toMap(Binge::getId, b -> b));
        return rows.stream().map(r -> toChangeRequestDto(r, byId.get(r.getBingeId()))).toList();
    }

    /**
     * SUPER-ADMIN: approve a pending change request. Applies the country + re-derived
     * currency atomically in this transaction, records the decision audit fields, and
     * notifies the requesting admin personally.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public com.skbingegalaxy.booking.dto.BingeChangeRequestDto approveChangeRequest(
            Long requestId, Long superAdminId, String role, String note, String resolvedValue) {
        requireSuperAdminRole(role, "approve change requests");
        com.skbingegalaxy.booking.entity.BingeChangeRequest request = getPendingChangeRequest(requestId);
        Binge binge = bingeRepository.findById(request.getBingeId())
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", request.getBingeId()));

        // Timezone reviews resolve to a zone the super-admin sets (or the admin's
        // suggestion), not a country/currency change — handle them separately.
        if (request.getRequestType() == com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.TIMEZONE_CHANGE) {
            return approveTimezoneChange(request, binge, superAdminId, note, resolvedValue);
        }

        // Re-normalise rather than trusting the stored value. requestCountryChange
        // uppercases and validates on the way in, but this row may have been
        // written before that rule existed (or by a direct DB edit), and since V79
        // the column carries a CHECK ~ '^[A-Z]{2}$' — a stray lowercase value would
        // fail the constraint here and surface as a 500 on an admin's approval click.
        String newCountry = request.getRequestedValue() == null
            ? null : request.getRequestedValue().trim().toUpperCase();
        if (newCountry == null || !newCountry.matches("^[A-Z]{2}$")) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "This change request holds an invalid country code ('" + request.getRequestedValue()
                    + "'). Reject it and ask the admin to submit a new request.");
        }
        binge.setCountry(newCountry);
        binge.setCurrency(com.skbingegalaxy.booking.util.CountryCurrency.forCountry(newCountry));
        bingeRepository.save(binge);
        // The tax jurisdiction moved with the country — retire the old auto-seeded
        // rule and seed the new country's standard tax (idempotent).
        ensureDefaultTaxRule(binge);

        decideChangeRequest(request, com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.APPROVED,
            superAdminId, note);
        adminNotificationService.notifyUser(
            request.getRequestedByAdminId(), "ADMIN",
            "BINGE_COUNTRY_CHANGE_APPROVED", "INFO",
            "Country-change request approved",
            String.format("Your request to move '%s' to %s was approved. The venue now charges in %s.%s",
                binge.getName(), newCountry, binge.getCurrency(),
                (note == null || note.isBlank()) ? "" : " Note: " + note.trim()),
            binge.getId(), "/admin/binges");
        log.info("Change request #{} APPROVED by super-admin {} — binge {} now {} / {}",
            requestId, superAdminId, binge.getId(), newCountry, binge.getCurrency());
        return toChangeRequestDto(request, binge);
    }

    /**
     * Resolve a timezone review. The applied zone is the super-admin's explicit
     * {@code resolvedValue} when given, otherwise the admin's suggestion on the
     * request. One of the two must be a valid IANA zone — an approval that would
     * leave the venue on the same (disputed) zone with nothing to apply is rejected
     * so the super-admin doesn't silently close a request without fixing anything.
     */
    private com.skbingegalaxy.booking.dto.BingeChangeRequestDto approveTimezoneChange(
            com.skbingegalaxy.booking.entity.BingeChangeRequest request, Binge binge,
            Long superAdminId, String note, String resolvedValue) {
        String candidate = trimToNull(resolvedValue) != null
            ? resolvedValue.trim() : request.getRequestedValue();
        if (candidate == null || candidate.isBlank()) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Choose the correct timezone to apply before approving this review.");
        }
        String zone;
        try {
            zone = java.time.ZoneId.of(candidate.trim()).getId();
        } catch (Exception ex) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "'" + candidate + "' isn't a valid IANA timezone (e.g. America/New_York).");
        }

        binge.setTimezone(zone);
        bingeRepository.save(binge);
        decideChangeRequest(request, com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.APPROVED,
            superAdminId, note);
        adminNotificationService.notifyUser(
            request.getRequestedByAdminId(), "ADMIN",
            "BINGE_TIMEZONE_CHANGE_APPROVED", "INFO",
            "Timezone review resolved",
            String.format("The timezone for '%s' was set to %s.%s",
                binge.getName(), zone,
                (note == null || note.isBlank()) ? "" : " Note: " + note.trim()),
            binge.getId(), "/admin/binges");
        log.info("Timezone review #{} APPROVED by super-admin {} — binge {} now {}",
            request.getId(), superAdminId, binge.getId(), zone);
        return toChangeRequestDto(request, binge);
    }

    /** SUPER-ADMIN: reject a pending change request with an optional note. */
    @Transactional
    public com.skbingegalaxy.booking.dto.BingeChangeRequestDto rejectChangeRequest(
            Long requestId, Long superAdminId, String role, String note) {
        requireSuperAdminRole(role, "reject change requests");
        com.skbingegalaxy.booking.entity.BingeChangeRequest request = getPendingChangeRequest(requestId);
        Binge binge = bingeRepository.findById(request.getBingeId()).orElse(null);

        decideChangeRequest(request, com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.REJECTED,
            superAdminId, note);
        boolean isTz = request.getRequestType()
            == com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.TIMEZONE_CHANGE;
        String bingeName = binge != null ? binge.getName() : ("binge #" + request.getBingeId());
        String notePart = (note == null || note.isBlank()) ? "" : " Note: " + note.trim();
        adminNotificationService.notifyUser(
            request.getRequestedByAdminId(), "ADMIN",
            isTz ? "BINGE_TIMEZONE_CHANGE_REJECTED" : "BINGE_COUNTRY_CHANGE_REJECTED", "WARNING",
            isTz ? "Timezone review rejected" : "Country-change request rejected",
            isTz
                ? String.format("Your timezone review for '%s' was rejected.%s", bingeName, notePart)
                : String.format("Your request to move '%s' to %s was rejected.%s",
                    bingeName, request.getRequestedValue(), notePart),
            request.getBingeId(), "/admin/binges");
        log.info("Change request #{} REJECTED by super-admin {}", requestId, superAdminId);
        return toChangeRequestDto(request, binge);
    }

    /** Requester (or a super-admin) withdraws a pending request. */
    @Transactional
    public com.skbingegalaxy.booking.dto.BingeChangeRequestDto cancelChangeRequest(
            Long requestId, Long userId, String role) {
        com.skbingegalaxy.booking.entity.BingeChangeRequest request = getPendingChangeRequest(requestId);
        boolean isSuper = "SUPER_ADMIN".equalsIgnoreCase(role);
        if (!isSuper && !request.getRequestedByAdminId().equals(userId)) {
            throw new BusinessException("You can only cancel your own change requests.", HttpStatus.FORBIDDEN);
        }
        decideChangeRequest(request, com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.CANCELLED,
            userId, isSuper ? "Cancelled by super-admin" : "Withdrawn by requester");
        log.info("Change request #{} CANCELLED by user {} ({})", requestId, userId, role);
        return toChangeRequestDto(request, bingeRepository.findById(request.getBingeId()).orElse(null));
    }

    /**
     * When a super-admin edits a binge's country directly while a request is pending,
     * the request would go stale — mark it CANCELLED (superseded) so the audit trail
     * reflects what actually happened. Called from {@link #applyCountryIfChanged}.
     */
    private void supersedePendingCountryRequests(Long bingeId, Long actingUserId) {
        List<com.skbingegalaxy.booking.entity.BingeChangeRequest> pending =
            changeRequestRepository.findByBingeIdAndRequestTypeAndStatus(
                bingeId, com.skbingegalaxy.booking.entity.BingeChangeRequest.Type.COUNTRY_CHANGE,
                com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING);
        for (var request : pending) {
            decideChangeRequest(request, com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.CANCELLED,
                actingUserId, "Superseded by a direct super-admin country edit");
            log.info("Change request #{} superseded by direct country edit on binge {}",
                request.getId(), bingeId);
        }
    }

    // ── Taxes: auto-assignment + per-binge master switch ───────────────────

    /** Marker so auto-seeded rules are distinguishable from admin-authored ones. */
    private static final String AUTO_TAX_MARKER = "[auto-assigned from venue country]";

    /**
     * Ensures the venue has its country's standard tax rule (e.g. IN → GST 18%).
     * Idempotent: skips when ANY active rule — venue-scoped or global — already
     * covers the venue's country (or is jurisdiction-agnostic). When the venue's
     * country CHANGES, previously auto-seeded rules for the old country are
     * deactivated (never admin-authored ones) before the new country's rule seeds.
     */
    @Transactional
    public void ensureDefaultTaxRule(Binge binge) {
        try {
            String country = binge.getCountry() == null ? null : binge.getCountry().trim().toUpperCase();
            List<com.skbingegalaxy.booking.entity.TaxRule> scoped =
                taxRuleRepository.findByBingeIdOrderByPriorityAscIdAsc(binge.getId());

            // Retire auto-seeded rules that no longer match the venue country.
            for (var rule : scoped) {
                boolean autoSeeded = AUTO_TAX_MARKER.equals(rule.getDescription());
                boolean countryMismatch = rule.getCountryCode() != null
                    && (country == null || !rule.getCountryCode().equalsIgnoreCase(country));
                if (autoSeeded && rule.isActive() && countryMismatch) {
                    rule.setActive(false);
                    rule.setUpdatedBy("SYSTEM");
                    taxRuleRepository.save(rule);
                    log.info("Auto-seeded tax rule {} deactivated for binge {} (country now {})",
                        rule.getId(), binge.getId(), country);
                }
            }

            var template = com.skbingegalaxy.booking.util.CountryTaxDefaults.forCountry(country);
            if (template == null) return; // no national default for this country

            boolean coveredScoped = scoped.stream().anyMatch(r -> r.isActive()
                && (r.getCountryCode() == null || r.getCountryCode().equalsIgnoreCase(country)));
            boolean coveredGlobal = taxRuleRepository.findGlobalRules().stream().anyMatch(r -> r.isActive()
                && (r.getCountryCode() == null || r.getCountryCode().equalsIgnoreCase(country)));
            if (coveredScoped || coveredGlobal) return;

            var rule = com.skbingegalaxy.booking.entity.TaxRule.builder()
                .bingeId(binge.getId())
                .name(template.name())
                .description(AUTO_TAX_MARKER)
                .rateBps(template.rateBps())
                .appliesTo(com.skbingegalaxy.booking.entity.TaxRule.AppliesTo.TOTAL)
                .inclusive(false)
                .countryCode(country)
                .taxType(template.taxType())
                .priority(100)
                .ruleVersion(1)
                .active(true)
                .createdBy("SYSTEM")
                .build();
            rule = taxRuleRepository.save(rule);
            log.info("Auto-assigned {} {}bps tax rule {} to binge {} ({})",
                template.name(), template.rateBps(), rule.getId(), binge.getId(), country);
        } catch (Exception ex) {
            // Best-effort: tax seeding must never break venue creation/approval.
            log.warn("ensureDefaultTaxRule failed for binge {}: {}", binge.getId(), ex.getMessage());
        }
    }

    /**
     * SUPER-ADMIN: master tax switch for a venue. Gated here (not just the gateway)
     * so a regular admin can never self-exempt their venue from taxes.
     */
    @Transactional
    public BingeDto setTaxesEnabled(Long bingeId, boolean enabled, Long actingUserId, String role) {
        requireSuperAdminRole(role, "enable or disable a venue's taxes");
        Binge binge = bingeRepository.findById(bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));
        binge.setTaxesEnabled(enabled);
        binge = bingeRepository.save(binge);
        log.info("[ops-audit] Taxes {} for binge {} ('{}') by super-admin {}",
            enabled ? "ENABLED" : "DISABLED", bingeId, binge.getName(), actingUserId);
        adminNotificationService.notifyUser(
            binge.getAdminId(), "ADMIN",
            "BINGE_TAXES_TOGGLED", "INFO",
            "Venue taxes " + (enabled ? "enabled" : "disabled"),
            String.format("A super-admin turned taxes %s for '%s'. New bookings %s.",
                enabled ? "ON" : "OFF", binge.getName(),
                enabled ? "will include applicable taxes" : "will not add tax on top of prices"),
            binge.getId(), "/admin/taxes");
        return toDto(binge);
    }

    private com.skbingegalaxy.booking.entity.BingeChangeRequest.Status parseChangeRequestStatus(String status) {
        try {
            return com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown change-request status '" + status
                + "'. Use PENDING, APPROVED, REJECTED or CANCELLED.");
        }
    }

    private com.skbingegalaxy.booking.entity.BingeChangeRequest getPendingChangeRequest(Long requestId) {
        com.skbingegalaxy.booking.entity.BingeChangeRequest request = changeRequestRepository.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException("BingeChangeRequest", "id", requestId));
        if (request.getStatus() != com.skbingegalaxy.booking.entity.BingeChangeRequest.Status.PENDING) {
            throw new BusinessException(
                "This request has already been decided (" + request.getStatus() + ").", HttpStatus.CONFLICT);
        }
        return request;
    }

    private void decideChangeRequest(com.skbingegalaxy.booking.entity.BingeChangeRequest request,
                                     com.skbingegalaxy.booking.entity.BingeChangeRequest.Status status,
                                     Long decidedBy, String note) {
        request.setStatus(status);
        request.setDecidedByUserId(decidedBy);
        request.setDecidedAt(LocalDateTime.now(ZoneOffset.UTC));
        request.setDecisionNote(trimToNull(note));
        changeRequestRepository.save(request);
    }

    private void requireSuperAdminRole(String role, String action) {
        if (!"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("Only super admins can " + action, HttpStatus.FORBIDDEN);
        }
    }

    private com.skbingegalaxy.booking.dto.BingeChangeRequestDto toChangeRequestDto(
            com.skbingegalaxy.booking.entity.BingeChangeRequest r, Binge binge) {
        return com.skbingegalaxy.booking.dto.BingeChangeRequestDto.builder()
            .id(r.getId())
            .bingeId(r.getBingeId())
            .bingeName(binge != null ? binge.getName() : null)
            .requestType(r.getRequestType() != null ? r.getRequestType().name() : null)
            .currentValue(r.getCurrentValue())
            .requestedValue(r.getRequestedValue())
            .currentCurrency(binge != null ? binge.getCurrency() : null)
            .requestedCurrency(r.getRequestedCurrency())
            .reason(r.getReason())
            .requestedByAdminId(r.getRequestedByAdminId())
            .status(r.getStatus() != null ? r.getStatus().name() : null)
            .decidedByUserId(r.getDecidedByUserId())
            .decidedAt(r.getDecidedAt())
            .decisionNote(r.getDecisionNote())
            .createdAt(r.getCreatedAt())
            .build();
    }

    /**
     * Apply a country edit, gating it by role and re-deriving the binge's currency.
     *
     * <p>Changing the country reprices the entire binge (its currency changes), so it is
     * high-blast-radius and SUPER-ADMIN-only: a regular admin who tries to change an
     * existing binge's country is rejected with a message pointing at the country-change
     * request workflow. Re-saving the SAME country (routine form round-trips) is a no-op
     * and never gated. Only an actual change re-derives {@link CountryCurrency}.
     */
    private void applyCountryIfChanged(Binge binge, String requestedCountry, String role,
                                       boolean delegated, Long actingUserId) {
        String normalized = trimToNull(requestedCountry);
        String current = binge.getCountry();
        boolean changed = !java.util.Objects.equals(
            normalized == null ? null : normalized.toUpperCase(),
            current == null ? null : current.toUpperCase());
        if (!changed) return;

        boolean isSuperAdmin = "SUPER_ADMIN".equalsIgnoreCase(role) || delegated;
        if (!isSuperAdmin) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Only a super-admin can change a binge's country (and therefore its currency). "
                + "Submit a country-change request for a super-admin to review.");
        }
        binge.setCountry(normalized);
        binge.setCurrency(com.skbingegalaxy.booking.util.CountryCurrency.forCountry(normalized));
        // A direct edit makes any pending request for this binge stale — close it out
        // (CANCELLED, note "superseded") so the audit trail reflects reality.
        supersedePendingCountryRequests(binge.getId(), actingUserId);
        // Tax jurisdiction follows the country — retire old auto-seeded rule, seed new.
        ensureDefaultTaxRule(binge);
        log.info("Binge {} country changed to {} — currency re-derived to {}",
            binge.getId(), normalized, binge.getCurrency());
    }

    /** Validate + canonicalize an IANA zone id (trims, normalizes form), or throw 400. */
    private String normalizeTimezoneOrThrow(String requestedTz) {
        try {
            return java.time.ZoneId.of(requestedTz.trim()).getId();
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Invalid timezone '" + requestedTz + "'. Use an IANA zone ID such as 'Asia/Kolkata' or 'America/New_York'.");
        }
    }

    /**
     * Super-admin console: assign one IANA timezone to many venues at once (the
     * "Auto"-detect-my-location / "Manual"-pick-any-zone bulk timezone page). Best-effort
     * across the batch — a venue id that no longer exists (e.g. deleted between page load
     * and submit) is skipped and reported rather than failing the whole request.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public BulkBingeTimezoneAssignResult bulkAssignTimezone(BulkBingeTimezoneAssignRequest request, String role, boolean delegated) {
        // Validate the zone up-front so invalid input fails fast and DETERMINISTICALLY —
        // regardless of whether any selected venue id still resolves. (Without this, a batch
        // where every id was deleted would skip the per-venue validation and wrongly 200.)
        normalizeTimezoneOrThrow(request.getTimezone());
        List<Long> updated = new ArrayList<>();
        List<Long> notFound = new ArrayList<>();
        for (Long bingeId : request.getBingeIds()) {
            Binge binge = bingeRepository.findById(bingeId).orElse(null);
            if (binge == null) {
                notFound.add(bingeId);
                continue;
            }
            applyTimezoneIfChanged(binge, request.getTimezone(), role, delegated);
            bingeRepository.save(binge);
            updated.add(bingeId);
        }
        log.info("Bulk timezone assign: {} venue(s) set to '{}' ({} not found)",
            updated.size(), request.getTimezone(), notFound.size());
        return BulkBingeTimezoneAssignResult.builder()
            .updatedCount(updated.size())
            .updatedBingeIds(updated)
            .notFoundBingeIds(notFound)
            .build();
    }

    @Transactional
    public CustomerDashboardExperienceDto updateCustomerDashboardExperience(Long id,
                                                                            CustomerDashboardExperienceDto request,
                                                                            Long adminId,
                                                                            String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        CustomerDashboardExperienceDto normalized = normalizeDashboardExperience(request);
        binge.setCustomerDashboardConfigJson(writeDashboardExperience(normalized));
        bingeRepository.save(binge);
        log.info("Customer dashboard experience updated for binge {}", id);
        return normalized;
    }

    @Transactional
    public CustomerAboutExperienceDto updateCustomerAboutExperience(Long id,
                                                                    CustomerAboutExperienceDto request,
                                                                    Long adminId,
                                                                    String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        CustomerAboutExperienceDto normalized = normalizeAboutExperience(request);
        binge.setCustomerAboutConfigJson(writeAboutExperience(normalized));
        bingeRepository.save(binge);
        log.info("Customer about experience updated for binge {}", id);
        return normalized;
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "activeBinges", allEntries = true)
    public void toggleBinge(Long id, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);

        binge.setActive(!binge.isActive());
        bingeRepository.save(binge);
        log.info("Binge toggled: '{}' active={}", binge.getName(), binge.isActive());
    }

    @Transactional
    public void deleteBinge(Long id, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);
        if (binge.isActive()) {
            throw new BusinessException("Deactivate the binge before deleting it");
        }
        if (bookingRepository.existsByBingeId(id)) {
            throw new BusinessException("Cannot delete this binge because it already has bookings");
        }
        if (eventTypeRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's event types before deleting the binge");
        }
        if (addOnRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's add-ons before deleting the binge");
        }
        if (rateCodeRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's rate codes before deleting the binge");
        }
        if (customerPricingProfileRepository.existsByBingeId(id)) {
            throw new BusinessException("Delete this binge's customer pricing profiles before deleting the binge");
        }

        bingeRepository.delete(binge);
        log.info("Binge deleted: '{}' (ID: {})", binge.getName(), id);
    }

    private Binge getManagedBinge(Long id, Long adminId, String role) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));

        if (!"SUPER_ADMIN".equals(role) && !binge.getAdminId().equals(adminId)) {
            throw new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN);
        }
        return binge;
    }

    private CustomerDashboardExperienceDto readDashboardExperience(String rawConfigJson) {
        if (rawConfigJson == null || rawConfigJson.isBlank()) {
            return normalizeDashboardExperience(null);
        }
        try {
            CustomerDashboardExperienceDto parsed = objectMapper.readValue(rawConfigJson, CustomerDashboardExperienceDto.class);
            return normalizeDashboardExperience(parsed);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse customer dashboard config JSON. Falling back to defaults.", ex);
            return normalizeDashboardExperience(null);
        }
    }

    private String writeDashboardExperience(CustomerDashboardExperienceDto config) {
        try {
            return objectMapper.writeValueAsString(normalizeDashboardExperience(config));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to store customer dashboard experience", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CustomerAboutExperienceDto readAboutExperience(String rawConfigJson) {
        if (rawConfigJson == null || rawConfigJson.isBlank()) {
            return normalizeAboutExperience(null);
        }
        try {
            CustomerAboutExperienceDto parsed = objectMapper.readValue(rawConfigJson, CustomerAboutExperienceDto.class);
            return normalizeAboutExperience(parsed);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse customer about config JSON. Falling back to defaults.", ex);
            return normalizeAboutExperience(null);
        }
    }

    private String writeAboutExperience(CustomerAboutExperienceDto config) {
        try {
            return objectMapper.writeValueAsString(normalizeAboutExperience(config));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to store customer about experience", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CustomerDashboardExperienceDto normalizeDashboardExperience(CustomerDashboardExperienceDto raw) {
        List<CustomerDashboardSlideDto> normalizedSlides = new ArrayList<>();
        if (raw != null && raw.getSlides() != null) {
            for (CustomerDashboardSlideDto slide : raw.getSlides()) {
                CustomerDashboardSlideDto normalizedSlide = normalizeSlide(slide);
                if (normalizedSlide != null) {
                    normalizedSlides.add(normalizedSlide);
                }
                if (normalizedSlides.size() == 6) {
                    break;
                }
            }
        }

        return CustomerDashboardExperienceDto.builder()
            .sectionEyebrow(defaultIfBlank(raw != null ? raw.getSectionEyebrow() : null, DEFAULT_DASHBOARD_EYEBROW))
            .sectionTitle(defaultIfBlank(raw != null ? raw.getSectionTitle() : null, DEFAULT_DASHBOARD_TITLE))
            .sectionSubtitle(trimToNull(raw != null ? raw.getSectionSubtitle() : null))
            .layout(normalizeLayout(raw != null ? raw.getLayout() : null))
            .slides(normalizedSlides)
            .build();
    }

    private CustomerDashboardSlideDto normalizeSlide(CustomerDashboardSlideDto raw) {
        if (raw == null) {
            return null;
        }

        String badge = trimToNull(raw.getBadge());
        String headline = trimToNull(raw.getHeadline());
        String description = trimToNull(raw.getDescription());
        String ctaLabel = trimToNull(raw.getCtaLabel());
        String imageUrl = trimToNull(raw.getImageUrl());

        if (badge == null && headline == null && description == null && ctaLabel == null && imageUrl == null) {
            return null;
        }

        return CustomerDashboardSlideDto.builder()
            .badge(defaultIfBlank(badge, DEFAULT_SLIDE_BADGE))
            .headline(defaultIfBlank(headline, DEFAULT_SLIDE_HEADLINE))
            .description(defaultIfBlank(description, DEFAULT_SLIDE_DESCRIPTION))
            .ctaLabel(defaultIfBlank(ctaLabel, DEFAULT_SLIDE_CTA))
            .imageUrl(imageUrl)
            .theme(normalizeTheme(raw.getTheme()))
            .linkedEventTypeId(raw.getLinkedEventTypeId())
            .build();
    }

    private CustomerAboutExperienceDto normalizeAboutExperience(CustomerAboutExperienceDto raw) {
        List<CustomerAboutHighlightDto> normalizedHighlights = normalizeHighlights(raw != null ? raw.getHighlights() : null);
        List<String> normalizedHouseRules = normalizeHouseRules(raw != null ? raw.getHouseRules() : null);
        List<CustomerAboutPolicyDto> normalizedPolicies = normalizePolicies(raw != null ? raw.getPolicies() : null);

        return CustomerAboutExperienceDto.builder()
            .sectionEyebrow(defaultIfBlank(raw != null ? raw.getSectionEyebrow() : null, DEFAULT_ABOUT_EYEBROW))
            .sectionTitle(defaultIfBlank(raw != null ? raw.getSectionTitle() : null, DEFAULT_ABOUT_TITLE))
            .sectionSubtitle(trimToNull(raw != null ? raw.getSectionSubtitle() : null))
            .heroTitle(defaultIfBlank(raw != null ? raw.getHeroTitle() : null, DEFAULT_ABOUT_HERO_TITLE))
            .heroDescription(defaultIfBlank(raw != null ? raw.getHeroDescription() : null, DEFAULT_ABOUT_HERO_DESCRIPTION))
            .highlightsTitle(defaultIfBlank(raw != null ? raw.getHighlightsTitle() : null, DEFAULT_ABOUT_HIGHLIGHTS_TITLE))
            .highlights(normalizedHighlights)
            .houseRulesTitle(defaultIfBlank(raw != null ? raw.getHouseRulesTitle() : null, DEFAULT_ABOUT_HOUSE_RULES_TITLE))
            .houseRules(normalizedHouseRules)
            .policyTitle(defaultIfBlank(raw != null ? raw.getPolicyTitle() : null, DEFAULT_ABOUT_POLICY_TITLE))
            .policies(normalizedPolicies)
            .contactHeading(defaultIfBlank(raw != null ? raw.getContactHeading() : null, DEFAULT_ABOUT_CONTACT_HEADING))
            .contactDescription(defaultIfBlank(raw != null ? raw.getContactDescription() : null, DEFAULT_ABOUT_CONTACT_DESCRIPTION))
            .build();
    }

    private List<CustomerAboutHighlightDto> normalizeHighlights(List<CustomerAboutHighlightDto> rawHighlights) {
        List<CustomerAboutHighlightDto> normalized = new ArrayList<>();
        if (rawHighlights != null) {
            for (CustomerAboutHighlightDto highlight : rawHighlights) {
                if (highlight == null) {
                    continue;
                }
                String title = trimToNull(highlight.getTitle());
                String description = trimToNull(highlight.getDescription());
                if (title == null && description == null) {
                    continue;
                }
                normalized.add(CustomerAboutHighlightDto.builder()
                    .title(defaultIfBlank(title, "Guest-first service"))
                    .description(defaultIfBlank(description, "Use this section to highlight what makes your binge special."))
                    .build());
                if (normalized.size() == 8) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Private cinematic setup")
                .description("Your booking includes a private room flow designed for your event's mood and timing.")
                .build());
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Flexible celebration planning")
                .description("Add-ons, guest counts, and event details can be tuned around your exact occasion.")
                .build());
            normalized.add(CustomerAboutHighlightDto.builder()
                .title("Clear support channel")
                .description("Reach the venue team quickly with your booking reference before and on event day.")
                .build());
        }
        return normalized;
    }

    private List<String> normalizeHouseRules(List<String> rawHouseRules) {
        List<String> normalized = new ArrayList<>();
        if (rawHouseRules != null) {
            for (String rule : rawHouseRules) {
                String item = trimToNull(rule);
                if (item == null) {
                    continue;
                }
                normalized.add(item);
                if (normalized.size() == 12) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add("Arrive at least 15 minutes before your slot to complete check-in smoothly.");
            normalized.add("Carry your booking reference for support and on-site verification.");
            normalized.add("Outside food, decor, or equipment must follow the venue's prior approval policy.");
        }

        return normalized;
    }

    private List<CustomerAboutPolicyDto> normalizePolicies(List<CustomerAboutPolicyDto> rawPolicies) {
        List<CustomerAboutPolicyDto> normalized = new ArrayList<>();
        if (rawPolicies != null) {
            for (CustomerAboutPolicyDto policy : rawPolicies) {
                if (policy == null) {
                    continue;
                }
                String title = trimToNull(policy.getTitle());
                String description = trimToNull(policy.getDescription());
                if (title == null && description == null) {
                    continue;
                }
                normalized.add(CustomerAboutPolicyDto.builder()
                    .title(defaultIfBlank(title, "Policy"))
                    .description(defaultIfBlank(description, "Describe this policy clearly so customers know what to expect."))
                    .build());
                if (normalized.size() == 8) {
                    break;
                }
            }
        }

        if (normalized.isEmpty()) {
            normalized.add(CustomerAboutPolicyDto.builder()
                .title("Payment policy")
                .description("Bookings stay reserved based on the payment status shown in your booking and payments portal.")
                .build());
            normalized.add(CustomerAboutPolicyDto.builder()
                .title("Rescheduling and cancellation")
                .description("Cancellation and rescheduling options depend on your binge's configured timing rules.")
                .build());
        }

        return normalized;
    }

    private String normalizeLayout(String layout) {
        return "CAROUSEL".equalsIgnoreCase(trimToNull(layout)) ? "CAROUSEL" : DEFAULT_DASHBOARD_LAYOUT;
    }

    private String normalizeTheme(String theme) {
        String normalized = trimToNull(theme);
        if (normalized == null) {
            return DEFAULT_SLIDE_THEME;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "celebration", "romance", "cinema", "team", "family", "luxury" -> normalized.toLowerCase(Locale.ROOT);
            default -> DEFAULT_SLIDE_THEME;
        };
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized != null ? normalized : fallback;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BingeDto toDto(Binge b) {
        return BingeDto.builder()
            .id(b.getId())
            .name(b.getName())
            .address(b.getAddress())
            .addressLine1(b.getAddressLine1())
            .addressLine2(b.getAddressLine2())
            .city(b.getCity())
            .state(b.getState())
            .country(b.getCountry())
            .currency(b.getCurrency())
            .postalCode(b.getPostalCode())
            .latitude(b.getLatitude())
            .longitude(b.getLongitude())
            .timezone(b.getTimezone())
            .adminId(b.getAdminId())
            .active(b.isActive())
            .operationalDate(b.getOperationalDate())
            .supportEmail(b.getSupportEmail())
            .supportPhone(b.getSupportPhone())
            .supportPhoneCountryCode(b.getSupportPhoneCountryCode())
            .supportWhatsapp(b.getSupportWhatsapp())
            .supportWhatsappCountryCode(b.getSupportWhatsappCountryCode())
            .supportPhoneIsWhatsapp(b.isSupportPhoneIsWhatsapp())
            .ownerEmail(b.getOwnerEmail())
            .ownerPhone(b.getOwnerPhone())
            .ownerPhoneCountryCode(b.getOwnerPhoneCountryCode())
            .ownerPhoneIsWhatsapp(b.isOwnerPhoneIsWhatsapp())
            .customerCancellationEnabled(b.isCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(b.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(b.getMaxConcurrentBookings())
            .openTime(b.getOpenTime())
            .closeTime(b.getCloseTime())
            .openingHours(com.skbingegalaxy.booking.util.OpeningHoursCodec.parse(b.getOpeningHoursJson()))
            .createdAt(b.getCreatedAt())
            .roomSelectionRequired(b.isRoomSelectionRequired())
            .freezePolicyEnabled(b.isFreezePolicyEnabled())
            .freezeDurationMinutes(b.getFreezeDurationMinutes())
            .maxPendingCancelsBeforeFreeze(b.getMaxPendingCancelsBeforeFreeze())
            .maxPendingPaymentTimeoutsBeforeFreeze(b.getMaxPendingPaymentTimeoutsBeforeFreeze())
            .refundOnSuccessfulPaymentCancel(b.isRefundOnSuccessfulPaymentCancel())
            .refundOnPendingPaymentCancel(b.isRefundOnPendingPaymentCancel())
            .taxesEnabled(b.isTaxesEnabled())
            .status(b.getStatus() != null ? b.getStatus().name() : BingeApprovalStatus.APPROVED.name())
            .approvalDecidedBy(b.getApprovalDecidedBy())
            .approvalDecidedAt(b.getApprovalDecidedAt())
            .approvalRejectionReason(b.getApprovalRejectionReason())
            .firstEventCreatedAt(b.getFirstEventCreatedAt())
            .graceWarningSentAt(b.getGraceWarningSentAt())
            .autoDeactivatedAt(b.getAutoDeactivatedAt())
            .build();
    }

    /**
     * Coordinates are stored as an all-or-nothing pair: a venue is either geocoded
     * (both latitude and longitude present and in range) or not (both null). A lone
     * coordinate is a client bug that would silently exclude the venue from proximity
     * results, so reject it loudly. Range is already enforced by bean validation on
     * {@link BingeSaveRequest}; this re-checks defensively for non-HTTP callers.
     */
    private void validateCoordinatePair(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessException(
                "Latitude and longitude must be provided together", HttpStatus.BAD_REQUEST);
        }
        if (latitude != null && (!GeoUtils.isValidLatitude(latitude) || !GeoUtils.isValidLongitude(longitude))) {
            throw new BusinessException(
                "Coordinates are out of range (latitude -90..90, longitude -180..180)",
                HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Map to the customer-safe projection used by every anonymous endpoint. Carries
     * only fields a customer legitimately needs (identity, address, geo, support,
     * timezone, hours, cancellation knobs) and drops all admin/lifecycle/anti-abuse
     * fields present on {@link #toDto}.
     */
    private PublicBingeDto toPublicDto(Binge b) {
        return PublicBingeDto.builder()
            .id(b.getId())
            .name(b.getName())
            .address(b.getAddress())
            .addressLine1(b.getAddressLine1())
            .addressLine2(b.getAddressLine2())
            .city(b.getCity())
            .state(b.getState())
            .country(b.getCountry())
            .currency(b.getCurrency())
            .postalCode(b.getPostalCode())
            .latitude(b.getLatitude())
            .longitude(b.getLongitude())
            .timezone(b.getTimezone())
            .supportEmail(b.getSupportEmail())
            .supportPhone(b.getSupportPhone())
            .supportPhoneCountryCode(b.getSupportPhoneCountryCode())
            .supportWhatsapp(b.getSupportWhatsapp())
            .supportWhatsappCountryCode(b.getSupportWhatsappCountryCode())
            // V78: flag only — owner_* personal contact fields are deliberately
            // NOT mapped here; they are a platform↔admin channel, never public.
            .supportPhoneIsWhatsapp(b.isSupportPhoneIsWhatsapp())
            .customerCancellationEnabled(b.isCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(b.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(b.getMaxConcurrentBookings())
            .openTime(b.getOpenTime())
            .closeTime(b.getCloseTime())
            .openingHours(com.skbingegalaxy.booking.util.OpeningHoursCodec.parse(b.getOpeningHoursJson()))
            .roomSelectionRequired(b.isRoomSelectionRequired())
            .build();
    }

    /**
     * Creation-time zone resolution. An explicit request wins (validated);
     * otherwise the zone is DERIVED from the venue's structured address
     * (country/state/city) — never from the server JVM or the admin's browser.
     * Only when the address gives no answer does the platform default apply.
     */
    private String resolveTimezone(String requested, String country, String state, String city) {
        if (requested == null || requested.isBlank()) {
            String derived = com.skbingegalaxy.booking.util.CountryTimezoneDefaults
                .forLocation(country, state, city);
            return derived != null ? derived : venueClock.defaultZone().getId();
        }
        try {
            return java.time.ZoneId.of(requested.trim()).getId();
        } catch (java.time.DateTimeException e) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "Invalid timezone '" + requested + "'. Use an IANA zone ID such as 'Asia/Kolkata' or 'America/New_York'.");
        }
    }

    /**
     * Compose a human-readable single-line address from the structured fields,
     * falling back to the legacy free-form {@code address} the caller supplied.
     * Stored on the entity so existing UI surfaces that read {@code address}
     * (admin emails, customer dashboards, About page) continue to render
     * without needing to know about the new fields.
     */
    private String composeAddressDisplay(BingeSaveRequest request) {
        java.util.List<String> parts = new java.util.ArrayList<>(6);
        java.util.function.Consumer<String> add = v -> {
            String t = trimToNull(v);
            if (t != null) parts.add(t);
        };
        add.accept(request.getAddressLine1());
        add.accept(request.getAddressLine2());
        add.accept(request.getCity());
        add.accept(request.getState());
        add.accept(request.getPostalCode());
        add.accept(request.getCountry());
        if (parts.isEmpty()) {
            return trimToNull(request.getAddress());
        }
        String composed = String.join(", ", parts);
        return composed.length() > 500 ? composed.substring(0, 500) : composed;
    }

    /**
     * Reject mis-configured operating hours up front so booking-service never has
     * to deal with closeTime &le; openTime at runtime. Both args may be null
     * (caller has already supplied defaults at create time, or kept the existing
     * value at update time); we only validate when both are present.
     */
    private void validateOperatingHours(LocalTime openTime, LocalTime closeTime) {
        if (openTime == null || closeTime == null) {
            return;
        }
        if (!closeTime.isAfter(openTime)) {
            throw new BusinessException(
                "Closing time (" + closeTime + ") must be strictly after opening time (" + openTime + ").");
        }
    }

    private static final String[] DAY_NAMES =
        {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    /**
     * Validate a per-day opening-hours list and serialize it to storage JSON.
     * Returns {@code null} for a null or empty list (meaning "no per-day schedule";
     * the single open/close pair then applies). Rules per entry:
     * <ul>
     *   <li>{@code dayOfWeek} must be 1..7 (Mon..Sun) and unique across the list;</li>
     *   <li>an <em>open</em> day must provide both open and close, with close &gt; open;</li>
     *   <li>a <em>closed</em> day ignores its times.</li>
     * </ul>
     */
    private String normalizeOpeningHours(java.util.List<com.skbingegalaxy.booking.dto.BingeDayHours> days) {
        if (days == null || days.isEmpty()) return null;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (com.skbingegalaxy.booking.dto.BingeDayHours d : days) {
            Integer dow = d.getDayOfWeek();
            if (dow == null || dow < 1 || dow > 7) {
                throw new BusinessException("Each opening-hours entry needs a day of week between 1 (Mon) and 7 (Sun).");
            }
            if (!seen.add(dow)) {
                throw new BusinessException("Duplicate opening-hours entry for " + DAY_NAMES[dow] + ".");
            }
            if (!d.isClosed()) {
                if (d.getOpenTime() == null || d.getCloseTime() == null) {
                    throw new BusinessException(DAY_NAMES[dow] + " is open — set both an opening and closing time, or mark it closed.");
                }
                if (!d.getCloseTime().isAfter(d.getOpenTime())) {
                    throw new BusinessException(DAY_NAMES[dow] + " closing time (" + d.getCloseTime()
                        + ") must be strictly after its opening time (" + d.getOpenTime() + ").");
                }
            }
        }
        return com.skbingegalaxy.booking.util.OpeningHoursCodec.serialize(days);
    }

    // ── Cancellation policy (binge-level: freeze + refund flags) ───────────

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.skbingegalaxy.booking.dto.CancellationPolicyDto getCancellationPolicy(Long bingeId) {
        Binge binge = bingeRepository.findById(bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));
        return com.skbingegalaxy.booking.dto.CancellationPolicyDto.builder()
            .freezePolicyEnabled(binge.isFreezePolicyEnabled())
            .freezeDurationMinutes(binge.getFreezeDurationMinutes())
            .maxPendingCancelsBeforeFreeze(binge.getMaxPendingCancelsBeforeFreeze())
            .maxPendingPaymentTimeoutsBeforeFreeze(binge.getMaxPendingPaymentTimeoutsBeforeFreeze())
            .maxUnpaidBookingsPerCustomer(binge.getMaxUnpaidBookingsPerCustomer())
            .refundOnSuccessfulPaymentCancel(binge.isRefundOnSuccessfulPaymentCancel())
            .refundOnPendingPaymentCancel(binge.isRefundOnPendingPaymentCancel())
            .build();
    }

    @org.springframework.transaction.annotation.Transactional
    public com.skbingegalaxy.booking.dto.CancellationPolicyDto saveCancellationPolicy(
            Long bingeId, com.skbingegalaxy.booking.dto.CancellationPolicyDto request) {
        Binge binge = bingeRepository.findById(bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));
        binge.setFreezePolicyEnabled(Boolean.TRUE.equals(request.getFreezePolicyEnabled()));
        binge.setFreezeDurationMinutes(request.getFreezeDurationMinutes());
        binge.setMaxPendingCancelsBeforeFreeze(request.getMaxPendingCancelsBeforeFreeze());
        binge.setMaxPendingPaymentTimeoutsBeforeFreeze(request.getMaxPendingPaymentTimeoutsBeforeFreeze());
        // Null = client predates this field (stale PWA bundle) — keep the current value.
        // Otherwise clamp to a sane merchant range: at least 1 (a customer must be able
        // to hold one in-flight booking), at most 50 (effectively "no limit").
        if (request.getMaxUnpaidBookingsPerCustomer() != null) {
            binge.setMaxUnpaidBookingsPerCustomer(
                Math.max(1, Math.min(50, request.getMaxUnpaidBookingsPerCustomer())));
        }
        binge.setRefundOnSuccessfulPaymentCancel(Boolean.TRUE.equals(request.getRefundOnSuccessfulPaymentCancel()));
        binge.setRefundOnPendingPaymentCancel(Boolean.TRUE.equals(request.getRefundOnPendingPaymentCancel()));
        bingeRepository.save(binge);
        log.info("Cancellation policy updated for binge {}", bingeId);
        return getCancellationPolicy(bingeId);
    }
}
