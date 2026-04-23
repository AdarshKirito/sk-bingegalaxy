package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PricingService {

    private final RateCodeRepository rateCodeRepository;
    private final CustomerPricingProfileRepository customerPricingProfileRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final RateCodeChangeLogRepository rateCodeChangeLogRepository;
    private final BookingRepository bookingRepository;
    private final SurgePricingRuleRepository surgePricingRuleRepository;
    private final LoyaltyService loyaltyService;
    private final BookingReviewRepository bookingReviewRepository;

    /** Thread-local admin ID for audit logging (set by controllers). */
    private static final ThreadLocal<Long> currentAdminId = new ThreadLocal<>();

    public void setCurrentAdminId(Long adminId) {
        currentAdminId.set(adminId);
    }

    public void clearCurrentAdminId() {
        currentAdminId.remove();
    }

    // ═══════════════════════════════════════════════════════════
    //  RATE CODE CRUD
    // ═══════════════════════════════════════════════════════════

    public List<RateCodeDto> getAllRateCodes() {
        Long bid = requireSelectedBinge("managing rate codes");
        return rateCodeRepository.findByBingeId(bid)
            .stream().map(this::toRateCodeDto).toList();
    }

    public List<RateCodeDto> getActiveRateCodes() {
        Long bid = requireSelectedBinge("viewing rate codes");
        return rateCodeRepository.findByBingeIdAndActiveTrue(bid)
            .stream().map(this::toRateCodeDto).toList();
    }

    public RateCodeDto getRateCodeById(Long id) {
        return toRateCodeDto(findScopedRateCode(id));
    }

    @Transactional
    public RateCodeDto createRateCode(RateCodeSaveRequest request) {
        Long bid = requireSelectedBinge("creating a rate code");
        if (rateCodeRepository.existsByNameAndBingeId(request.getName(), bid)) {
            throw new DuplicateResourceException("RateCode", "name", request.getName());
        }

        RateCode rateCode = RateCode.builder()
            .name(request.getName())
            .description(request.getDescription())
            .active(true)
            .bingeId(bid)
            .build();

        rateCode = rateCodeRepository.save(rateCode);
        applyRateCodePricings(rateCode, request);
        rateCode = rateCodeRepository.save(rateCode);

        log.info("Rate code created: {}", rateCode.getName());
        return toRateCodeDto(rateCode);
    }

    @Transactional
    public RateCodeDto updateRateCode(Long id, RateCodeSaveRequest request) {
        RateCode rateCode = findScopedRateCode(id);

        // Check name uniqueness if changed
        Long bid = requireSelectedBinge("updating a rate code");
        if (!rateCode.getName().equals(request.getName())) {
            boolean exists = rateCodeRepository.existsByNameAndBingeId(request.getName(), bid);
            if (exists) throw new DuplicateResourceException("RateCode", "name", request.getName());
        }

        rateCode.setName(request.getName());
        rateCode.setDescription(request.getDescription());

        // Clear and re-apply pricing entries
        rateCode.getEventPricings().clear();
        rateCode.getAddonPricings().clear();
        rateCodeRepository.saveAndFlush(rateCode);

        applyRateCodePricings(rateCode, request);
        rateCode = rateCodeRepository.save(rateCode);

        log.info("Rate code updated: {}", rateCode.getName());
        return toRateCodeDto(rateCode);
    }

    @Transactional
    public void toggleRateCode(Long id) {
        RateCode rateCode = findScopedRateCode(id);
        rateCode.setActive(!rateCode.isActive());
        rateCodeRepository.save(rateCode);
        log.info("Rate code {} toggled to active={}", rateCode.getName(), rateCode.isActive());
    }

    @Transactional
    public void deleteRateCode(Long id) {
        RateCode rateCode = findScopedRateCode(id);
        if (rateCode.isActive()) {
            throw new BusinessException("Deactivate the rate code before deleting it");
        }
        if (customerPricingProfileRepository.existsByRateCodeId(id)) {
            throw new BusinessException("Cannot delete this rate code because customer pricing profiles still use it");
        }

        rateCodeRepository.delete(rateCode);
        log.info("Rate code deleted: {}", rateCode.getName());
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER PRICING PROFILE CRUD
    // ═══════════════════════════════════════════════════════════

    public CustomerPricingDto getCustomerPricing(Long customerId) {
        Long bid = requireSelectedBinge("viewing customer pricing");
        Optional<CustomerPricingProfile> scopedProfile = findWritableCustomerProfile(customerId, bid);
        Optional<CustomerPricingProfile> profile = scopedProfile.isPresent()
            ? scopedProfile
            : findReadableCustomerProfile(customerId, bid);
        if (profile.isEmpty()) {
            return CustomerPricingDto.builder()
                .customerId(customerId)
                .scopedProfile(false)
                .eventPricings(List.of())
                .addonPricings(List.of())
                .build();
        }
        boolean isScopedProfile = scopedProfile.isPresent();
        return toCustomerPricingDto(profile.get(), isScopedProfile);
    }

    @Transactional
    public CustomerPricingDto saveCustomerPricing(CustomerPricingSaveRequest request) {
        Long bid = requireSelectedBinge("saving customer pricing");
        CustomerPricingProfile profile = findWritableCustomerProfile(request.getCustomerId(), bid)
            .orElseGet(() -> {
                CustomerPricingProfile p = CustomerPricingProfile.builder()
                    .customerId(request.getCustomerId())
                    .bingeId(bid)
                    .build();
                return customerPricingProfileRepository.save(p);
            });

        // Assign/unassign rate code (with audit logging)
        RateCode oldRateCode = profile.getRateCode();
        RateCode newRateCode = null;
        if (request.getRateCodeId() != null) {
            newRateCode = findScopedRateCode(request.getRateCodeId());
            profile.setRateCode(newRateCode);
        } else {
            profile.setRateCode(null);
        }
        logRateCodeChange(request.getCustomerId(), bid, oldRateCode, newRateCode);

        // Clear and re-apply custom event pricing
        profile.getEventPricings().clear();
        profile.getAddonPricings().clear();
        customerPricingProfileRepository.saveAndFlush(profile);

        if (request.getEventPricings() != null) {
            for (var ep : request.getEventPricings()) {
                EventType et = findAccessibleEventType(ep.getEventTypeId());
                profile.getEventPricings().add(CustomerEventPricing.builder()
                    .customerPricingProfile(profile)
                    .eventType(et)
                    .basePrice(ep.getBasePrice())
                    .hourlyRate(ep.getHourlyRate())
                    .pricePerGuest(ep.getPricePerGuest() != null ? ep.getPricePerGuest() : BigDecimal.ZERO)
                    .build());
            }
        }

        if (request.getAddonPricings() != null) {
            for (var ap : request.getAddonPricings()) {
                AddOn addon = findAccessibleAddOn(ap.getAddOnId());
                profile.getAddonPricings().add(CustomerAddonPricing.builder()
                    .customerPricingProfile(profile)
                    .addOn(addon)
                    .price(ap.getPrice())
                    .build());
            }
        }

        profile = customerPricingProfileRepository.save(profile);
        log.info("Customer pricing saved for customerId={}", request.getCustomerId());
        return toCustomerPricingDto(profile, true);
    }

    @Transactional
    public void deleteCustomerPricing(Long customerId) {
        Long bid = requireSelectedBinge("deleting customer pricing");
        CustomerPricingProfile profile = findWritableCustomerProfile(customerId, bid)
            .orElseThrow(() -> new ResourceNotFoundException("CustomerPricingProfile", "customerId", customerId));

        customerPricingProfileRepository.delete(profile);
        log.info("Customer pricing deleted for customerId={} bingeId={}", customerId, bid);
    }

    @Transactional
    public void updateMemberLabel(Long customerId, String label) {
        Long bid = requireSelectedBinge("updating member label");
        CustomerPricingProfile profile = findWritableCustomerProfile(customerId, bid)
            .orElseGet(() -> {
                CustomerPricingProfile p = CustomerPricingProfile.builder()
                    .customerId(customerId)
                    .bingeId(bid)
                    .build();
                return customerPricingProfileRepository.save(p);
            });
        profile.setMemberLabel(label != null && !label.isBlank() ? label.trim() : null);
        customerPricingProfileRepository.save(profile);
        log.info("Updated member label for customer {} to '{}'", customerId, label);
    }

    @Transactional
    public int bulkAssignRateCode(BulkRateCodeAssignRequest request) {
        Long bid = requireSelectedBinge("bulk assigning rate codes");
        RateCode rateCode = null;
        if (request.getRateCodeId() != null) {
            rateCode = findScopedRateCode(request.getRateCodeId());
        }

        int count = 0;
        for (Long customerId : request.getCustomerIds()) {
            CustomerPricingProfile profile = findWritableCustomerProfile(customerId, bid)
                .orElseGet(() -> {
                    CustomerPricingProfile p = CustomerPricingProfile.builder()
                        .customerId(customerId)
                        .bingeId(bid)
                        .build();
                    return customerPricingProfileRepository.save(p);
                });
            RateCode oldRateCode = profile.getRateCode();
            profile.setRateCode(rateCode);
            if (request.getMemberLabel() != null) {
                profile.setMemberLabel(request.getMemberLabel().isBlank() ? null : request.getMemberLabel().trim());
            }
            customerPricingProfileRepository.save(profile);
            logRateCodeChange(customerId, bid, oldRateCode, rateCode, "BULK_ASSIGN");
            count++;
        }
        log.info("Bulk rate code assignment: {} customers -> rateCodeId={}", count, request.getRateCodeId());
        return count;
    }

    // ═══════════════════════════════════════════════════════════
    //  PRICING RESOLUTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Resolves the effective pricing for a customer.
     * Priority: customer-specific > rate code > default.
     * (Booking-level admin override is applied at booking creation time, not here.)
     */
    public ResolvedPricingDto resolveCustomerPricing(Long customerId) {
        Long bid = requireSelectedBinge("resolving customer pricing");
        List<EventType> allEvents = eventTypeRepository.findByBingeIdAndActiveTrue(bid);
        List<AddOn> allAddOns = addOnRepository.findByBingeIdAndActiveTrue(bid);

        Optional<CustomerPricingProfile> profileOpt = findReadableCustomerProfile(customerId, bid);

        // Build maps for customer-specific overrides
        Map<Long, CustomerEventPricing> custEventMap = new HashMap<>();
        Map<Long, CustomerAddonPricing> custAddonMap = new HashMap<>();
        RateCode rateCode = null;
        String memberLabel = null;

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();
            rateCode = profile.getRateCode();
            memberLabel = profile.getMemberLabel();
            profile.getEventPricings().forEach(ep -> custEventMap.put(ep.getEventType().getId(), ep));
            profile.getAddonPricings().forEach(ap -> custAddonMap.put(ap.getAddOn().getId(), ap));
        }

        // Build rate code maps
        Map<Long, RateCodeEventPricing> rcEventMap = new HashMap<>();
        Map<Long, RateCodeAddonPricing> rcAddonMap = new HashMap<>();
        if (rateCode != null && rateCode.isActive()) {
            rateCode.getEventPricings().forEach(ep -> rcEventMap.put(ep.getEventType().getId(), ep));
            rateCode.getAddonPricings().forEach(ap -> rcAddonMap.put(ap.getAddOn().getId(), ap));
        }

        String overallSource = "DEFAULT";
        String rateCodeName = null;

        // Resolve event pricing
        List<ResolvedPricingDto.EventPricing> eventPricings = new ArrayList<>();
        for (EventType et : allEvents) {
            if (custEventMap.containsKey(et.getId())) {
                CustomerEventPricing cep = custEventMap.get(et.getId());
                eventPricings.add(ResolvedPricingDto.EventPricing.builder()
                    .eventTypeId(et.getId()).eventTypeName(et.getName())
                    .basePrice(cep.getBasePrice()).hourlyRate(cep.getHourlyRate())
                    .pricePerGuest(cep.getPricePerGuest()).source("CUSTOMER").build());
                overallSource = "CUSTOMER";
            } else if (rcEventMap.containsKey(et.getId())) {
                RateCodeEventPricing rcp = rcEventMap.get(et.getId());
                eventPricings.add(ResolvedPricingDto.EventPricing.builder()
                    .eventTypeId(et.getId()).eventTypeName(et.getName())
                    .basePrice(rcp.getBasePrice()).hourlyRate(rcp.getHourlyRate())
                    .pricePerGuest(rcp.getPricePerGuest()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                if (rateCode != null) rateCodeName = rateCode.getName();
            } else {
                eventPricings.add(ResolvedPricingDto.EventPricing.builder()
                    .eventTypeId(et.getId()).eventTypeName(et.getName())
                    .basePrice(et.getBasePrice()).hourlyRate(et.getHourlyRate())
                    .pricePerGuest(et.getPricePerGuest()).source("DEFAULT").build());
            }
        }

        // Resolve add-on pricing
        List<ResolvedPricingDto.AddonPricing> addonPricings = new ArrayList<>();
        for (AddOn addon : allAddOns) {
            if (custAddonMap.containsKey(addon.getId())) {
                CustomerAddonPricing cap = custAddonMap.get(addon.getId());
                addonPricings.add(ResolvedPricingDto.AddonPricing.builder()
                    .addOnId(addon.getId()).addOnName(addon.getName())
                    .price(cap.getPrice()).source("CUSTOMER").build());
                overallSource = "CUSTOMER";
            } else if (rcAddonMap.containsKey(addon.getId())) {
                RateCodeAddonPricing rap = rcAddonMap.get(addon.getId());
                addonPricings.add(ResolvedPricingDto.AddonPricing.builder()
                    .addOnId(addon.getId()).addOnName(addon.getName())
                    .price(rap.getPrice()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                if (rateCode != null) rateCodeName = rateCode.getName();
            } else {
                addonPricings.add(ResolvedPricingDto.AddonPricing.builder()
                    .addOnId(addon.getId()).addOnName(addon.getName())
                    .price(addon.getPrice()).source("DEFAULT").build());
            }
        }

        return ResolvedPricingDto.builder()
            .customerId(customerId)
            .pricingSource(overallSource)
            .rateCodeName(rateCodeName)
            .memberLabel(memberLabel)
            .eventPricings(eventPricings)
            .addonPricings(addonPricings)
            .build();
    }

    /**
     * Resolves the effective price for a SINGLE event type for a given customer.
     * Returns {basePrice, hourlyRate, pricePerGuest, source, rateCodeName}.
     *
     * <p>Precedence (highest first):
     * <ol>
     *   <li>Customer-specific custom pricing (their personal deal, always wins).</li>
     *   <li>Customer profile's attached rate code.</li>
     *   <li>Event type default price.</li>
     * </ol>
     */
    public ResolvedEventPrice resolveEventPrice(Long customerId, Long eventTypeId) {
        return resolveEventPrice(customerId, eventTypeId, null);
    }

    /**
     * Resolves the effective price with an optional admin-supplied rate code override
     * (used during admin booking creation when admin picks a rate plan at booking time).
     *
     * <p>Precedence (highest first):
     * <ol>
     *   <li>Customer-specific custom pricing (their personal deal, always wins).</li>
     *   <li>{@code overrideRateCodeId} rate code pricing (admin's explicit pick for this booking).</li>
     *   <li>Customer profile's attached rate code.</li>
     *   <li>Event type default price.</li>
     * </ol>
     * An override rate code that has no entry for this event falls through to the
     * next step (profile rate code or default) rather than zeroing out the price.
     */
    public ResolvedEventPrice resolveEventPrice(Long customerId, Long eventTypeId, Long overrideRateCodeId) {
        EventType et = findAccessibleEventType(eventTypeId);

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = (customerId != null && customerId > 0)
            ? findReadableCustomerProfile(customerId, bid)
            : Optional.empty();

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();

            // 1. Customer-specific always wins.
            Optional<CustomerEventPricing> custPrice = profile.getEventPricings().stream()
                .filter(ep -> ep.getEventType().getId().equals(eventTypeId)).findFirst();
            if (custPrice.isPresent()) {
                CustomerEventPricing cep = custPrice.get();
                return new ResolvedEventPrice(cep.getBasePrice(), cep.getHourlyRate(), cep.getPricePerGuest(), "CUSTOMER", null);
            }
        }

        // 2. Admin-supplied override rate code (booking-time pick).
        if (overrideRateCodeId != null) {
            RateCode override = findScopedRateCode(overrideRateCodeId);
            if (override.isActive()) {
                Optional<RateCodeEventPricing> rcPrice = override.getEventPricings().stream()
                    .filter(ep -> ep.getEventType().getId().equals(eventTypeId)).findFirst();
                if (rcPrice.isPresent()) {
                    RateCodeEventPricing rcp = rcPrice.get();
                    return new ResolvedEventPrice(rcp.getBasePrice(), rcp.getHourlyRate(), rcp.getPricePerGuest(), "RATE_CODE", override.getName());
                }
            }
        }

        // 3. Customer profile's attached rate code.
        if (profileOpt.isPresent()) {
            RateCode rc = profileOpt.get().getRateCode();
            if (rc != null && rc.isActive()) {
                Optional<RateCodeEventPricing> rcPrice = rc.getEventPricings().stream()
                    .filter(ep -> ep.getEventType().getId().equals(eventTypeId)).findFirst();
                if (rcPrice.isPresent()) {
                    RateCodeEventPricing rcp = rcPrice.get();
                    return new ResolvedEventPrice(rcp.getBasePrice(), rcp.getHourlyRate(), rcp.getPricePerGuest(), "RATE_CODE", rc.getName());
                }
            }
        }

        // 4. Default.
        return new ResolvedEventPrice(et.getBasePrice(), et.getHourlyRate(), et.getPricePerGuest(), "DEFAULT", null);
    }

    /**
     * Resolves the effective price for a SINGLE add-on for a given customer.
     * Precedence matches {@link #resolveEventPrice(Long, Long)}.
     */
    public ResolvedAddonPrice resolveAddonPrice(Long customerId, Long addOnId) {
        return resolveAddonPrice(customerId, addOnId, null);
    }

    /**
     * Add-on variant of {@link #resolveEventPrice(Long, Long, Long)}: customer-specific
     * always wins over admin's override rate code and over the profile's attached rate code.
     */
    public ResolvedAddonPrice resolveAddonPrice(Long customerId, Long addOnId, Long overrideRateCodeId) {
        AddOn addon = findAccessibleAddOn(addOnId);

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = (customerId != null && customerId > 0)
            ? findReadableCustomerProfile(customerId, bid)
            : Optional.empty();

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();

            // 1. Customer-specific always wins.
            Optional<CustomerAddonPricing> custPrice = profile.getAddonPricings().stream()
                .filter(ap -> ap.getAddOn().getId().equals(addOnId)).findFirst();
            if (custPrice.isPresent()) {
                return new ResolvedAddonPrice(custPrice.get().getPrice(), "CUSTOMER", null);
            }
        }

        // 2. Admin override rate code.
        if (overrideRateCodeId != null) {
            RateCode override = findScopedRateCode(overrideRateCodeId);
            if (override.isActive()) {
                Optional<RateCodeAddonPricing> rcPrice = override.getAddonPricings().stream()
                    .filter(ap -> ap.getAddOn().getId().equals(addOnId)).findFirst();
                if (rcPrice.isPresent()) {
                    return new ResolvedAddonPrice(rcPrice.get().getPrice(), "RATE_CODE", override.getName());
                }
            }
        }

        // 3. Profile rate code.
        if (profileOpt.isPresent()) {
            RateCode rc = profileOpt.get().getRateCode();
            if (rc != null && rc.isActive()) {
                Optional<RateCodeAddonPricing> rcPrice = rc.getAddonPricings().stream()
                    .filter(ap -> ap.getAddOn().getId().equals(addOnId)).findFirst();
                if (rcPrice.isPresent()) {
                    return new ResolvedAddonPrice(rcPrice.get().getPrice(), "RATE_CODE", rc.getName());
                }
            }
        }

        return new ResolvedAddonPrice(addon.getPrice(), "DEFAULT", null);
    }

    // ═══════════════════════════════════════════════════════════
    //  SURGE PRICING RESOLUTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Resolves the applicable surge multiplier for a given booking date and start time.
     * Returns the highest matching active surge rule, or null if no surge applies.
     */
    public SurgeResult resolveSurge(java.time.LocalDate bookingDate, java.time.LocalTime startTime) {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return null;

        int dow = bookingDate.getDayOfWeek().getValue(); // 1=Monday...7=Sunday
        int minute = startTime.getHour() * 60 + startTime.getMinute();

        List<SurgePricingRule> rules = surgePricingRuleRepository.findMatchingRules(bid, dow, minute);
        if (rules.isEmpty()) return null;

        // Take the rule with the highest multiplier
        SurgePricingRule best = rules.stream()
            .max(java.util.Comparator.comparing(SurgePricingRule::getMultiplier))
            .orElse(null);
        if (best == null || best.getMultiplier().compareTo(BigDecimal.ONE) <= 0) return null;

        return new SurgeResult(best.getMultiplier(), best.getLabel() != null ? best.getLabel() : best.getName());
    }

    public record SurgeResult(BigDecimal multiplier, String label) {}

    // ═══════════════════════════════════════════════════════════
    //  SURGE PRICING RULE CRUD
    // ═══════════════════════════════════════════════════════════

    public List<com.skbingegalaxy.booking.dto.SurgePricingRuleDto> getSurgeRules() {
        Long bid = requireSelectedBinge("viewing surge pricing rules");
        return surgePricingRuleRepository.findByBingeIdOrderByDayOfWeekAscStartMinuteAsc(bid)
            .stream().map(this::toSurgeRuleDto).toList();
    }

    @org.springframework.cache.annotation.Cacheable(value = "surgeRules", key = "T(com.skbingegalaxy.common.context.BingeContext).getBingeId()")
    public List<com.skbingegalaxy.booking.dto.SurgePricingRuleDto> getActiveSurgeRules() {
        Long bid = requireSelectedBinge("viewing surge pricing rules");
        return surgePricingRuleRepository.findByBingeIdAndActiveTrueOrderByDayOfWeekAscStartMinuteAsc(bid)
            .stream().map(this::toSurgeRuleDto).toList();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "surgeRules", allEntries = true)
    public com.skbingegalaxy.booking.dto.SurgePricingRuleDto createSurgeRule(com.skbingegalaxy.booking.dto.SurgePricingRuleSaveRequest request) {
        Long bid = requireSelectedBinge("creating surge pricing rule");
        if (request.getStartMinute() >= request.getEndMinute()) {
            throw new BusinessException("Start minute must be less than end minute");
        }
        SurgePricingRule rule = SurgePricingRule.builder()
            .bingeId(bid)
            .name(request.getName())
            .dayOfWeek(request.getDayOfWeek())
            .startMinute(request.getStartMinute())
            .endMinute(request.getEndMinute())
            .multiplier(request.getMultiplier())
            .label(request.getLabel())
            .active(request.isActive())
            .build();
        rule = surgePricingRuleRepository.save(rule);
        log.info("Surge pricing rule created: {} ({}x)", rule.getName(), rule.getMultiplier());
        return toSurgeRuleDto(rule);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "surgeRules", allEntries = true)
    public com.skbingegalaxy.booking.dto.SurgePricingRuleDto updateSurgeRule(Long id, com.skbingegalaxy.booking.dto.SurgePricingRuleSaveRequest request) {
        Long bid = requireSelectedBinge("updating surge pricing rule");
        SurgePricingRule rule = surgePricingRuleRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("SurgePricingRule", "id", id));
        if (request.getStartMinute() >= request.getEndMinute()) {
            throw new BusinessException("Start minute must be less than end minute");
        }
        rule.setName(request.getName());
        rule.setDayOfWeek(request.getDayOfWeek());
        rule.setStartMinute(request.getStartMinute());
        rule.setEndMinute(request.getEndMinute());
        rule.setMultiplier(request.getMultiplier());
        rule.setLabel(request.getLabel());
        rule.setActive(request.isActive());
        rule = surgePricingRuleRepository.save(rule);
        log.info("Surge pricing rule updated: {}", rule.getName());
        return toSurgeRuleDto(rule);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "surgeRules", allEntries = true)
    public void toggleSurgeRule(Long id) {
        Long bid = requireSelectedBinge("toggling surge pricing rule");
        SurgePricingRule rule = surgePricingRuleRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("SurgePricingRule", "id", id));
        rule.setActive(!rule.isActive());
        surgePricingRuleRepository.save(rule);
        log.info("Surge pricing rule {} toggled to active={}", rule.getName(), rule.isActive());
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "surgeRules", allEntries = true)
    public void deleteSurgeRule(Long id) {
        Long bid = requireSelectedBinge("deleting surge pricing rule");
        SurgePricingRule rule = surgePricingRuleRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("SurgePricingRule", "id", id));
        surgePricingRuleRepository.delete(rule);
        log.info("Surge pricing rule deleted: {}", rule.getName());
    }

    private com.skbingegalaxy.booking.dto.SurgePricingRuleDto toSurgeRuleDto(SurgePricingRule r) {
        return com.skbingegalaxy.booking.dto.SurgePricingRuleDto.builder()
            .id(r.getId()).bingeId(r.getBingeId()).name(r.getName())
            .dayOfWeek(r.getDayOfWeek()).startMinute(r.getStartMinute()).endMinute(r.getEndMinute())
            .multiplier(r.getMultiplier()).label(r.getLabel()).active(r.isActive())
            .createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
            .build();
    }

    private Optional<CustomerPricingProfile> findReadableCustomerProfile(Long customerId, Long bingeId) {
        if (bingeId != null) {
            return customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId);
        }

        return customerPricingProfileRepository.findByCustomerIdAndBingeIdIsNull(customerId);
    }

    private Optional<CustomerPricingProfile> findWritableCustomerProfile(Long customerId, Long bingeId) {
        if (bingeId != null) {
            return customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId);
        }

        return customerPricingProfileRepository.findByCustomerIdAndBingeIdIsNull(customerId);
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPER RECORDS & MAPPERS
    // ═══════════════════════════════════════════════════════════

    public record ResolvedEventPrice(BigDecimal basePrice, BigDecimal hourlyRate, BigDecimal pricePerGuest, String source, String rateCodeName) {}
    public record ResolvedAddonPrice(BigDecimal price, String source, String rateCodeName) {}

    /**
     * Resolves pricing based purely on a rate code (no customer context).
     * Used by the admin booking wizard to preview a rate code's raw pricing. Note that
     * this does NOT apply customer-specific overrides &mdash; at booking creation time
     * the engine correctly falls back to the customer's personal pricing first. See
     * {@link #resolveCustomerPricingWithOverride(Long, Long)} for a preview that
     * reflects the booking-time precedence.
     */
    public ResolvedPricingDto resolveRateCodePricing(Long rateCodeId) {
        RateCode rateCode = findScopedRateCode(rateCodeId);
        if (!rateCode.isActive()) {
            throw new BusinessException("Rate code '" + rateCode.getName() + "' is not active");
        }

        Long bid = requireSelectedBinge("resolving rate code pricing");
        List<EventType> allEvents = eventTypeRepository.findByBingeIdAndActiveTrue(bid);
        List<AddOn> allAddOns = addOnRepository.findByBingeIdAndActiveTrue(bid);

        Map<Long, RateCodeEventPricing> rcEventMap = new HashMap<>();
        Map<Long, RateCodeAddonPricing> rcAddonMap = new HashMap<>();
        rateCode.getEventPricings().forEach(ep -> rcEventMap.put(ep.getEventType().getId(), ep));
        rateCode.getAddonPricings().forEach(ap -> rcAddonMap.put(ap.getAddOn().getId(), ap));

        List<ResolvedPricingDto.EventPricing> eventPricings = new ArrayList<>();
        for (EventType et : allEvents) {
            if (rcEventMap.containsKey(et.getId())) {
                RateCodeEventPricing rcp = rcEventMap.get(et.getId());
                eventPricings.add(ResolvedPricingDto.EventPricing.builder()
                    .eventTypeId(et.getId()).eventTypeName(et.getName())
                    .basePrice(rcp.getBasePrice()).hourlyRate(rcp.getHourlyRate())
                    .pricePerGuest(rcp.getPricePerGuest()).source("RATE_CODE").build());
            } else {
                eventPricings.add(ResolvedPricingDto.EventPricing.builder()
                    .eventTypeId(et.getId()).eventTypeName(et.getName())
                    .basePrice(et.getBasePrice()).hourlyRate(et.getHourlyRate())
                    .pricePerGuest(et.getPricePerGuest()).source("DEFAULT").build());
            }
        }

        List<ResolvedPricingDto.AddonPricing> addonPricings = new ArrayList<>();
        for (AddOn addon : allAddOns) {
            if (rcAddonMap.containsKey(addon.getId())) {
                RateCodeAddonPricing rap = rcAddonMap.get(addon.getId());
                addonPricings.add(ResolvedPricingDto.AddonPricing.builder()
                    .addOnId(addon.getId()).addOnName(addon.getName())
                    .price(rap.getPrice()).source("RATE_CODE").build());
            } else {
                addonPricings.add(ResolvedPricingDto.AddonPricing.builder()
                    .addOnId(addon.getId()).addOnName(addon.getName())
                    .price(addon.getPrice()).source("DEFAULT").build());
            }
        }

        return ResolvedPricingDto.builder()
            .pricingSource("RATE_CODE")
            .rateCodeName(rateCode.getName())
            .eventPricings(eventPricings)
            .addonPricings(addonPricings)
            .build();
    }

    /**
     * Resolves the full pricing grid for a customer with an admin-supplied rate code
     * override applied at booking time. Mirrors the precedence used in
     * {@link #resolveEventPrice(Long, Long, Long)} so the admin booking wizard can
     * show exactly what the server will charge:
     *
     * <pre>
     *   per event/addon:
     *     customer-specific &gt; override rate code &gt; profile rate code &gt; default
     * </pre>
     */
    public ResolvedPricingDto resolveCustomerPricingWithOverride(Long customerId, Long overrideRateCodeId) {
        if (overrideRateCodeId == null) {
            return resolveCustomerPricing(customerId);
        }
        Long bid = requireSelectedBinge("resolving customer pricing with override");
        List<EventType> allEvents = eventTypeRepository.findByBingeIdAndActiveTrue(bid);
        List<AddOn> allAddOns = addOnRepository.findByBingeIdAndActiveTrue(bid);

        Optional<CustomerPricingProfile> profileOpt = findReadableCustomerProfile(customerId, bid);
        RateCode override = findScopedRateCode(overrideRateCodeId);
        if (!override.isActive()) {
            throw new BusinessException("Rate code '" + override.getName() + "' is not active");
        }

        Map<Long, CustomerEventPricing> custEventMap = new HashMap<>();
        Map<Long, CustomerAddonPricing> custAddonMap = new HashMap<>();
        RateCode profileRateCode = null;
        String memberLabel = null;
        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();
            profile.getEventPricings().forEach(ep -> custEventMap.put(ep.getEventType().getId(), ep));
            profile.getAddonPricings().forEach(ap -> custAddonMap.put(ap.getAddOn().getId(), ap));
            profileRateCode = profile.getRateCode();
            memberLabel = profile.getMemberLabel();
        }

        Map<Long, RateCodeEventPricing> ovEventMap = new HashMap<>();
        Map<Long, RateCodeAddonPricing> ovAddonMap = new HashMap<>();
        override.getEventPricings().forEach(ep -> ovEventMap.put(ep.getEventType().getId(), ep));
        override.getAddonPricings().forEach(ap -> ovAddonMap.put(ap.getAddOn().getId(), ap));

        Map<Long, RateCodeEventPricing> profEventMap = new HashMap<>();
        Map<Long, RateCodeAddonPricing> profAddonMap = new HashMap<>();
        if (profileRateCode != null && profileRateCode.isActive()) {
            profileRateCode.getEventPricings().forEach(ep -> profEventMap.put(ep.getEventType().getId(), ep));
            profileRateCode.getAddonPricings().forEach(ap -> profAddonMap.put(ap.getAddOn().getId(), ap));
        }

        String overallSource = "DEFAULT";
        String displayedRateCode = null;
        List<ResolvedPricingDto.EventPricing> eventPricings = new ArrayList<>();
        for (EventType et : allEvents) {
            ResolvedPricingDto.EventPricing.EventPricingBuilder b = ResolvedPricingDto.EventPricing.builder()
                .eventTypeId(et.getId()).eventTypeName(et.getName());
            if (custEventMap.containsKey(et.getId())) {
                CustomerEventPricing c = custEventMap.get(et.getId());
                eventPricings.add(b.basePrice(c.getBasePrice()).hourlyRate(c.getHourlyRate())
                    .pricePerGuest(c.getPricePerGuest()).source("CUSTOMER").build());
                overallSource = "CUSTOMER";
            } else if (ovEventMap.containsKey(et.getId())) {
                RateCodeEventPricing r = ovEventMap.get(et.getId());
                eventPricings.add(b.basePrice(r.getBasePrice()).hourlyRate(r.getHourlyRate())
                    .pricePerGuest(r.getPricePerGuest()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                displayedRateCode = override.getName();
            } else if (profEventMap.containsKey(et.getId())) {
                RateCodeEventPricing r = profEventMap.get(et.getId());
                eventPricings.add(b.basePrice(r.getBasePrice()).hourlyRate(r.getHourlyRate())
                    .pricePerGuest(r.getPricePerGuest()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                if (displayedRateCode == null) displayedRateCode = profileRateCode.getName();
            } else {
                eventPricings.add(b.basePrice(et.getBasePrice()).hourlyRate(et.getHourlyRate())
                    .pricePerGuest(et.getPricePerGuest()).source("DEFAULT").build());
            }
        }

        List<ResolvedPricingDto.AddonPricing> addonPricings = new ArrayList<>();
        for (AddOn addon : allAddOns) {
            ResolvedPricingDto.AddonPricing.AddonPricingBuilder b = ResolvedPricingDto.AddonPricing.builder()
                .addOnId(addon.getId()).addOnName(addon.getName());
            if (custAddonMap.containsKey(addon.getId())) {
                addonPricings.add(b.price(custAddonMap.get(addon.getId()).getPrice()).source("CUSTOMER").build());
                overallSource = "CUSTOMER";
            } else if (ovAddonMap.containsKey(addon.getId())) {
                addonPricings.add(b.price(ovAddonMap.get(addon.getId()).getPrice()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                displayedRateCode = override.getName();
            } else if (profAddonMap.containsKey(addon.getId())) {
                addonPricings.add(b.price(profAddonMap.get(addon.getId()).getPrice()).source("RATE_CODE").build());
                if ("DEFAULT".equals(overallSource)) overallSource = "RATE_CODE";
                if (displayedRateCode == null) displayedRateCode = profileRateCode.getName();
            } else {
                addonPricings.add(b.price(addon.getPrice()).source("DEFAULT").build());
            }
        }

        return ResolvedPricingDto.builder()
            .customerId(customerId)
            .pricingSource(overallSource)
            .rateCodeName(displayedRateCode)
            .memberLabel(memberLabel)
            .eventPricings(eventPricings)
            .addonPricings(addonPricings)
            .build();
    }

    private void applyRateCodePricings(RateCode rateCode, RateCodeSaveRequest request) {
        if (request.getEventPricings() != null) {
            for (var ep : request.getEventPricings()) {
                EventType et = findAccessibleEventType(ep.getEventTypeId());
                rateCode.getEventPricings().add(RateCodeEventPricing.builder()
                    .rateCode(rateCode)
                    .eventType(et)
                    .basePrice(ep.getBasePrice())
                    .hourlyRate(ep.getHourlyRate())
                    .pricePerGuest(ep.getPricePerGuest() != null ? ep.getPricePerGuest() : BigDecimal.ZERO)
                    .build());
            }
        }
        if (request.getAddonPricings() != null) {
            for (var ap : request.getAddonPricings()) {
                AddOn addon = findAccessibleAddOn(ap.getAddOnId());
                rateCode.getAddonPricings().add(RateCodeAddonPricing.builder()
                    .rateCode(rateCode)
                    .addOn(addon)
                    .price(ap.getPrice())
                    .build());
            }
        }
    }

    private RateCode findScopedRateCode(Long id) {
        Long bid = requireSelectedBinge("accessing a rate code");
        return rateCodeRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", id));
    }

    private EventType findAccessibleEventType(Long id) {
        Long bid = requireSelectedBinge("using event types");
        return eventTypeRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
    }

    private AddOn findAccessibleAddOn(Long id) {
        Long bid = requireSelectedBinge("using add-ons");
        return addOnRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
    }

    private Long requireSelectedBinge(String action) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            throw new BusinessException("Select a binge before " + action);
        }
        return bingeId;
    }

    private RateCodeDto toRateCodeDto(RateCode rc) {
        return RateCodeDto.builder()
            .id(rc.getId())
            .name(rc.getName())
            .description(rc.getDescription())
            .active(rc.isActive())
            .eventPricings(rc.getEventPricings().stream().map(ep ->
                RateCodeDto.EventPricingEntry.builder()
                    .eventTypeId(ep.getEventType().getId())
                    .eventTypeName(ep.getEventType().getName())
                    .basePrice(ep.getBasePrice())
                    .hourlyRate(ep.getHourlyRate())
                    .pricePerGuest(ep.getPricePerGuest())
                    .build()).toList())
            .addonPricings(rc.getAddonPricings().stream().map(ap ->
                RateCodeDto.AddonPricingEntry.builder()
                    .addOnId(ap.getAddOn().getId())
                    .addOnName(ap.getAddOn().getName())
                    .price(ap.getPrice())
                    .build()).toList())
            .createdAt(rc.getCreatedAt())
            .updatedAt(rc.getUpdatedAt())
            .build();
    }

    private CustomerPricingDto toCustomerPricingDto(CustomerPricingProfile profile, boolean scopedProfile) {
        return CustomerPricingDto.builder()
            .customerId(profile.getCustomerId())
            .rateCodeId(profile.getRateCode() != null ? profile.getRateCode().getId() : null)
            .rateCodeName(profile.getRateCode() != null ? profile.getRateCode().getName() : null)
            .scopedProfile(scopedProfile)
            .eventPricings(profile.getEventPricings().stream().map(ep ->
                CustomerPricingDto.EventPricingEntry.builder()
                    .eventTypeId(ep.getEventType().getId())
                    .eventTypeName(ep.getEventType().getName())
                    .basePrice(ep.getBasePrice())
                    .hourlyRate(ep.getHourlyRate())
                    .pricePerGuest(ep.getPricePerGuest())
                    .build()).toList())
            .addonPricings(profile.getAddonPricings().stream().map(ap ->
                CustomerPricingDto.AddonPricingEntry.builder()
                    .addOnId(ap.getAddOn().getId())
                    .addOnName(ap.getAddOn().getName())
                    .price(ap.getPrice())
                    .build()).toList())
            .updatedAt(profile.getUpdatedAt())
            .build();
    }

    // ═══════════════════════════════════════════════════════════
    //  RATE CODE CHANGE AUDIT LOG
    // ═══════════════════════════════════════════════════════════

    private void logRateCodeChange(Long customerId, Long bingeId, RateCode oldRC, RateCode newRC) {
        String changeType;
        if (oldRC == null && newRC != null) changeType = "ASSIGN";
        else if (oldRC != null && newRC == null) changeType = "UNASSIGN";
        else if (oldRC != null && !oldRC.getId().equals(newRC != null ? newRC.getId() : null)) changeType = "REASSIGN";
        else return; // no change
        logRateCodeChange(customerId, bingeId, oldRC, newRC, changeType);
    }

    private void logRateCodeChange(Long customerId, Long bingeId, RateCode oldRC, RateCode newRC, String changeType) {
        Long adminId = currentAdminId.get();
        rateCodeChangeLogRepository.save(RateCodeChangeLog.builder()
            .customerId(customerId)
            .bingeId(bingeId)
            .previousRateCodeId(oldRC != null ? oldRC.getId() : null)
            .previousRateCodeName(oldRC != null ? oldRC.getName() : null)
            .newRateCodeId(newRC != null ? newRC.getId() : null)
            .newRateCodeName(newRC != null ? newRC.getName() : null)
            .changeType(changeType)
            .changedByAdminId(adminId)
            .build());
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER DETAIL (rate code + reservations)
    // ═══════════════════════════════════════════════════════════

    public CustomerDetailDto getCustomerDetail(Long customerId) {
        Long bid = requireSelectedBinge("viewing customer detail");

        // Current rate code
        Optional<CustomerPricingProfile> profileOpt = findReadableCustomerProfile(customerId, bid);
        Long currentRateCodeId = null;
        String currentRateCodeName = null;
        String memberLabel = null;
        if (profileOpt.isPresent()) {
            memberLabel = profileOpt.get().getMemberLabel();
            if (profileOpt.get().getRateCode() != null) {
                currentRateCodeId = profileOpt.get().getRateCode().getId();
                currentRateCodeName = profileOpt.get().getRateCode().getName();
            }
        }

        // Rate code change audit
        List<RateCodeChangeLog> changes = rateCodeChangeLogRepository
            .findByCustomerIdAndBingeIdOrderByChangedAtDesc(customerId, bid);
        List<CustomerDetailDto.RateCodeChange> changeList = changes.stream().map(c ->
            CustomerDetailDto.RateCodeChange.builder()
                .id(c.getId())
                .previousRateCodeName(c.getPreviousRateCodeName())
                .newRateCodeName(c.getNewRateCodeName())
                .changeType(c.getChangeType())
                .changedByAdminId(c.getChangedByAdminId())
                .changedAt(c.getChangedAt())
                .build()
        ).toList();

        // Reservation history for this binge
        List<Booking> bookings = bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bid, customerId);
        List<CustomerDetailDto.ReservationSummary> reservations = bookings.stream().map(b ->
            CustomerDetailDto.ReservationSummary.builder()
                .bookingRef(b.getBookingRef())
                .eventTypeName(b.getEventType() != null ? b.getEventType().getName() : "—")
                .bookingDate(b.getBookingDate())
                .startTime(b.getStartTime())
                .durationMinutes(b.getDurationMinutes() != null ? b.getDurationMinutes() : b.getDurationHours() * 60)
                .status(b.getStatus().name())
                .paymentStatus(b.getPaymentStatus().name())
                .totalAmount(b.getTotalAmount())
                .collectedAmount(b.getCollectedAmount())
                .pricingSource(b.getPricingSource())
                .rateCodeName(b.getRateCodeName())
                .createdAt(b.getCreatedAt())
                .build()
        ).toList();

        // ── Spend roll-up (lifetime collected vs outstanding balance) ──
        BigDecimal lifetimeSpend = bookings.stream()
            .map(b -> b.getCollectedAmount() != null ? b.getCollectedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingBalance = bookings.stream()
            .filter(b -> b.getStatus() != com.skbingegalaxy.common.enums.BookingStatus.CANCELLED
                      && b.getStatus() != com.skbingegalaxy.common.enums.BookingStatus.NO_SHOW)
            .map(b -> {
                BigDecimal tot = b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO;
                BigDecimal col = b.getCollectedAmount() != null ? b.getCollectedAmount() : BigDecimal.ZERO;
                BigDecimal diff = tot.subtract(col);
                return diff.signum() > 0 ? diff : BigDecimal.ZERO;
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Loyalty / membership snapshot ──
        LoyaltyAccountDto loyalty = null;
        try {
            loyalty = loyaltyService.getAccount(customerId);
        } catch (Exception ex) {
            log.warn("Loyalty lookup failed for customer {}: {}", customerId, ex.getMessage());
        }

        // ── Review signals ──
        double avgAdminRatingRaw = bookingReviewRepository.averageAdminRatingForCustomer(customerId);
        long adminReviewCount = bookingReviewRepository.countAdminReviewsForCustomer(customerId);
        long customerReviewCount = bookingReviewRepository
            .findByCustomerIdAndReviewerRoleOrderByCreatedAtDesc(customerId, "CUSTOMER").size();

        // Weighted admin average — each admin's rating is scaled by the
        // reputation of the binge they represent (admins from well-liked
        // binges carry more weight, admins from poorly-liked binges carry
        // less).  Mirrors the trust model applied to customer reviews
        // in BookingService.getBingeReviewSummary.
        double weightedAdminAvg = avgAdminRatingRaw;
        if (adminReviewCount > 0) {
            java.util.List<Object[]> adminRows =
                bookingReviewRepository.adminRatingAndBingeForCustomer(customerId);
            java.util.Set<Long> bingeIds = new java.util.HashSet<>();
            for (Object[] row : adminRows) {
                if (row[1] != null) bingeIds.add((Long) row[1]);
            }
            // Cache each binge's customer average so we only hit the DB once per binge.
            java.util.Map<Long, Double> bingeAvgCache = new java.util.HashMap<>();
            for (Long bid2 : bingeIds) {
                try {
                    bingeAvgCache.put(bid2, bookingReviewRepository.averageBingeRating(bid2));
                } catch (Exception ex) {
                    bingeAvgCache.put(bid2, 0.0);
                }
            }
            double sum = 0, totalWeight = 0;
            for (Object[] row : adminRows) {
                int rating = ((Number) row[0]).intValue();
                Long bid2 = (Long) row[1];
                double bingeAvg = bid2 != null ? bingeAvgCache.getOrDefault(bid2, 0.0) : 0.0;
                double weight = weightForAdminReviewer(bingeAvg);
                sum += rating * weight;
                totalWeight += weight;
            }
            if (totalWeight > 0) weightedAdminAvg = sum / totalWeight;
        }

        Double avgAdminRating = adminReviewCount > 0
            ? Math.round(weightedAdminAvg * 10.0) / 10.0
            : null;
        double reviewWeight = computeReviewWeight(
            loyalty != null ? loyalty.getTierLevel() : "BRONZE",
            weightedAdminAvg,
            adminReviewCount);

        return CustomerDetailDto.builder()
            .customerId(customerId)
            .currentRateCodeId(currentRateCodeId)
            .currentRateCodeName(currentRateCodeName)
            .memberLabel(memberLabel)
            .rateCodeChanges(changeList)
            .totalReservations(bookings.size())
            .reservations(reservations)
            .memberTier(loyalty != null ? loyalty.getTierLevel() : null)
            .loyaltyPoints(loyalty != null ? loyalty.getCurrentBalance() : null)
            .lifetimePointsEarned(loyalty != null ? loyalty.getTotalPointsEarned() : null)
            .pointsToNextTier(loyalty != null ? loyalty.getPointsToNextTier() : null)
            .nextTierLevel(loyalty != null ? loyalty.getNextTierLevel() : null)
            .memberSince(loyalty != null ? loyalty.getCreatedAt() : null)
            .avgAdminRating(avgAdminRating)
            .adminReviewCount(adminReviewCount)
            .customerReviewCount(customerReviewCount)
            .reviewWeight(reviewWeight)
            .lifetimeSpend(lifetimeSpend)
            .pendingBalance(pendingBalance)
            .build();
    }

    /**
     * Weight a customer's review influence.  Inputs:
     *   • tier → higher tiers get a small bump (capped at +25%).
     *   • avgAdminRating on this customer → acts as a trust signal; low
     *     ratings reduce influence so repeat offenders can't skew a
     *     binge's public rating.  No admin reviews yet → neutral-ish
     *     (0.9 — a touch below 1.0 so brand-new customers can still
     *     show up but don't dominate).
     * Result is bounded to [0.5, 1.25].
     */
    private static double computeReviewWeight(String tier, double avgAdminRating, long adminReviewCount) {
        double tierMultiplier = switch (tier == null ? "BRONZE" : tier.toUpperCase()) {
            case "PLATINUM" -> 1.15;
            case "GOLD" -> 1.10;
            case "SILVER" -> 1.05;
            default -> 1.00;
        };
        double trustMultiplier;
        if (adminReviewCount <= 0) {
            trustMultiplier = 0.90; // new / unrated customer
        } else if (avgAdminRating >= 4.5) {
            trustMultiplier = 1.00;
        } else if (avgAdminRating >= 3.5) {
            trustMultiplier = 0.95;
        } else if (avgAdminRating >= 2.5) {
            trustMultiplier = 0.85;
        } else {
            trustMultiplier = 0.60; // chronically poor behaviour
        }
        double w = tierMultiplier * trustMultiplier;
        return Math.max(0.5, Math.min(1.25, w));
    }

    /**
     * Weight applied to a single admin's private review of a customer,
     * derived from how well-rated the admin's binge is by its paying
     * customers.  Admins from popular, well-run binges carry slightly
     * more signal (up to 1.15×); admins from struggling binges carry
     * less (down to 0.80×).  Bounded to [0.5, 1.25] to match the
     * reviewer-weight envelope and prevent a single signal from
     * dominating.
     */
    private static double weightForAdminReviewer(double bingeCustomerAvg) {
        // No customer reviews yet on the binge → neutral 1.00.
        if (bingeCustomerAvg <= 0) return 1.00;
        double w;
        if (bingeCustomerAvg >= 4.5) w = 1.15;
        else if (bingeCustomerAvg >= 3.5) w = 1.05;
        else if (bingeCustomerAvg >= 2.5) w = 1.00;
        else if (bingeCustomerAvg >= 1.5) w = 0.85;
        else w = 0.70;
        return Math.max(0.5, Math.min(1.25, w));
    }
}
