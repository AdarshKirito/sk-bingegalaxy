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
     */
    public ResolvedEventPrice resolveEventPrice(Long customerId, Long eventTypeId) {
        EventType et = findAccessibleEventType(eventTypeId);

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = findReadableCustomerProfile(customerId, bid);

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();

            // Check customer-specific first
            Optional<CustomerEventPricing> custPrice = profile.getEventPricings().stream()
                .filter(ep -> ep.getEventType().getId().equals(eventTypeId)).findFirst();
            if (custPrice.isPresent()) {
                CustomerEventPricing cep = custPrice.get();
                return new ResolvedEventPrice(cep.getBasePrice(), cep.getHourlyRate(), cep.getPricePerGuest(), "CUSTOMER", null);
            }

            // Check rate code
            RateCode rc = profile.getRateCode();
            if (rc != null && rc.isActive()) {
                Optional<RateCodeEventPricing> rcPrice = rc.getEventPricings().stream()
                    .filter(ep -> ep.getEventType().getId().equals(eventTypeId)).findFirst();
                if (rcPrice.isPresent()) {
                    RateCodeEventPricing rcp = rcPrice.get();
                    return new ResolvedEventPrice(rcp.getBasePrice(), rcp.getHourlyRate(), rcp.getPricePerGuest(), "RATE_CODE", rc.getName());
                }
            }
        }

        // Default
        return new ResolvedEventPrice(et.getBasePrice(), et.getHourlyRate(), et.getPricePerGuest(), "DEFAULT", null);
    }

    /**
     * Resolves the effective price for a SINGLE add-on for a given customer.
     */
    public ResolvedAddonPrice resolveAddonPrice(Long customerId, Long addOnId) {
        AddOn addon = findAccessibleAddOn(addOnId);

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = findReadableCustomerProfile(customerId, bid);

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();

            Optional<CustomerAddonPricing> custPrice = profile.getAddonPricings().stream()
                .filter(ap -> ap.getAddOn().getId().equals(addOnId)).findFirst();
            if (custPrice.isPresent()) {
                return new ResolvedAddonPrice(custPrice.get().getPrice(), "CUSTOMER", null);
            }

            RateCode rc = profile.getRateCode();
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
     * Used by the admin booking wizard when overriding a customer's pricing with a specific rate code.
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

        return CustomerDetailDto.builder()
            .customerId(customerId)
            .currentRateCodeId(currentRateCodeId)
            .currentRateCodeName(currentRateCodeName)
            .memberLabel(memberLabel)
            .rateCodeChanges(changeList)
            .totalReservations(bookings.size())
            .reservations(reservations)
            .build();
    }
}
