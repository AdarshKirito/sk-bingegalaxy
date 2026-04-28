package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.BingeDto;
import com.skbingegalaxy.booking.dto.BingeSaveRequest;
import com.skbingegalaxy.booking.dto.CustomerAboutExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerAboutHighlightDto;
import com.skbingegalaxy.booking.dto.CustomerAboutPolicyDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardExperienceDto;
import com.skbingegalaxy.booking.dto.CustomerDashboardSlideDto;
import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.CustomerPricingProfileRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.RateCodeRepository;
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
import java.time.LocalTime;
import java.util.ArrayList;
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
    public List<BingeDto> getAllActiveBinges() {
        return bingeRepository.findCustomerVisibleBinges()
            .stream().map(this::toDto).toList();
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

    public BingeDto getBingeById(Long id) {
        return toDto(bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id)));
    }

    public CustomerDashboardExperienceDto getCustomerDashboardExperience(Long id) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        return readDashboardExperience(binge.getCustomerDashboardConfigJson());
    }

    public CustomerDashboardExperienceDto getAdminCustomerDashboardExperience(Long id, Long adminId, String role) {
        return readDashboardExperience(getManagedBinge(id, adminId, role).getCustomerDashboardConfigJson());
    }

    public CustomerAboutExperienceDto getCustomerAboutExperience(Long id) {
        Binge binge = bingeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", id));
        return readAboutExperience(binge.getCustomerAboutConfigJson());
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

        LocalDate opDate = clientDate != null ? clientDate : LocalDate.now();
        LocalTime openT = request.getOpenTime() != null ? request.getOpenTime() : LocalTime.of(10, 0);
        LocalTime closeT = request.getCloseTime() != null ? request.getCloseTime() : LocalTime.of(23, 0);
        validateOperatingHours(openT, closeT);

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
            .postalCode(trimToNull(request.getPostalCode()))
            .adminId(adminId)
            .active(initialActive)
            .status(initialStatus)
            .operationalDate(opDate)
            .supportEmail(trimToNull(request.getSupportEmail()))
            .supportPhone(trimToNull(request.getSupportPhone()))
            .supportPhoneCountryCode(trimToNull(request.getSupportPhoneCountryCode()))
            .supportWhatsapp(trimToNull(request.getSupportWhatsapp()))
            .supportWhatsappCountryCode(trimToNull(request.getSupportWhatsappCountryCode()))
            .customerCancellationEnabled(request.getCustomerCancellationEnabled() == null || request.getCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(request.getCustomerCancellationCutoffMinutes() == null ? 180 : request.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(request.getMaxConcurrentBookings())
            .openTime(openT)
            .closeTime(closeT)
            .build();

        if (isSuperAdmin) {
            binge.setApprovalDecidedBy(adminId);
            binge.setApprovalDecidedAt(LocalDateTime.now());
        }

        binge = bingeRepository.save(binge);
        log.info("Binge created: '{}' by user {} (role={}, status={})",
            binge.getName(), adminId, role, initialStatus);

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
    public BingeDto approveBinge(Long id, Long superAdminId, String role) {
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
        binge.setApprovalDecidedBy(superAdminId);
        binge.setApprovalDecidedAt(LocalDateTime.now());
        binge.setApprovalRejectionReason(null);
        binge.setAutoDeactivatedAt(null);
        binge.setGraceWarningSentAt(null);

        // If the binge already has at least one active event (e.g. because a
        // super-admin pre-seeded one), mark it operational immediately so the
        // grace-period scheduler skips it.
        if (binge.getFirstEventCreatedAt() == null
                && eventTypeRepository.findByBingeIdAndActiveTrue(binge.getId()).size() > 0) {
            binge.setFirstEventCreatedAt(LocalDateTime.now());
        }
        binge = bingeRepository.save(binge);
        log.info("Binge {} ('{}') approved by super-admin {}", id, binge.getName(), superAdminId);

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
        binge.setApprovalDecidedAt(LocalDateTime.now());
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
                b.setFirstEventCreatedAt(LocalDateTime.now());
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
        LocalDateTime now = LocalDateTime.now();
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
    public BingeDto updateBinge(Long id, BingeSaveRequest request, Long adminId, String role) {
        Binge binge = getManagedBinge(id, adminId, role);

        binge.setName(request.getName());
        binge.setAddress(composeAddressDisplay(request));
        binge.setAddressLine1(trimToNull(request.getAddressLine1()));
        binge.setAddressLine2(trimToNull(request.getAddressLine2()));
        binge.setCity(trimToNull(request.getCity()));
        binge.setState(trimToNull(request.getState()));
        binge.setCountry(trimToNull(request.getCountry()));
        binge.setPostalCode(trimToNull(request.getPostalCode()));
        binge.setSupportEmail(trimToNull(request.getSupportEmail()));
        binge.setSupportPhone(trimToNull(request.getSupportPhone()));
        binge.setSupportPhoneCountryCode(trimToNull(request.getSupportPhoneCountryCode()));
        binge.setSupportWhatsapp(trimToNull(request.getSupportWhatsapp()));
        binge.setSupportWhatsappCountryCode(trimToNull(request.getSupportWhatsappCountryCode()));
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
        binge = bingeRepository.save(binge);
        log.info("Binge updated: '{}' (ID: {})", binge.getName(), id);
        return toDto(binge);
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
            .postalCode(b.getPostalCode())
            .adminId(b.getAdminId())
            .active(b.isActive())
            .operationalDate(b.getOperationalDate())
            .supportEmail(b.getSupportEmail())
            .supportPhone(b.getSupportPhone())
            .supportPhoneCountryCode(b.getSupportPhoneCountryCode())
            .supportWhatsapp(b.getSupportWhatsapp())
            .supportWhatsappCountryCode(b.getSupportWhatsappCountryCode())
            .customerCancellationEnabled(b.isCustomerCancellationEnabled())
            .customerCancellationCutoffMinutes(b.getCustomerCancellationCutoffMinutes())
            .maxConcurrentBookings(b.getMaxConcurrentBookings())
            .openTime(b.getOpenTime())
            .closeTime(b.getCloseTime())
            .createdAt(b.getCreatedAt())
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
}
