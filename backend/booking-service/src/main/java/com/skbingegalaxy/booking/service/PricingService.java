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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private final RateCodeRepository rateCodeRepository;
    private final CustomerPricingProfileRepository customerPricingProfileRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;

    // ═══════════════════════════════════════════════════════════
    //  RATE CODE CRUD
    // ═══════════════════════════════════════════════════════════

    public List<RateCodeDto> getAllRateCodes() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? rateCodeRepository.findByBingeId(bid) : rateCodeRepository.findAll())
            .stream().map(this::toRateCodeDto).toList();
    }

    public List<RateCodeDto> getActiveRateCodes() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? rateCodeRepository.findByBingeIdAndActiveTrue(bid) : rateCodeRepository.findByActiveTrue())
            .stream().map(this::toRateCodeDto).toList();
    }

    public RateCodeDto getRateCodeById(Long id) {
        return toRateCodeDto(rateCodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", id)));
    }

    @Transactional
    public RateCodeDto createRateCode(RateCodeSaveRequest request) {
        Long bid = BingeContext.getBingeId();
        if (bid != null ? rateCodeRepository.existsByNameAndBingeId(request.getName(), bid) : rateCodeRepository.existsByName(request.getName())) {
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
        RateCode rateCode = rateCodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", id));

        // Check name uniqueness if changed
        Long bid = BingeContext.getBingeId();
        if (!rateCode.getName().equals(request.getName())) {
            boolean exists = bid != null ? rateCodeRepository.existsByNameAndBingeId(request.getName(), bid) : rateCodeRepository.existsByName(request.getName());
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
        RateCode rateCode = rateCodeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", id));
        rateCode.setActive(!rateCode.isActive());
        rateCodeRepository.save(rateCode);
        log.info("Rate code {} toggled to active={}", rateCode.getName(), rateCode.isActive());
    }

    // ═══════════════════════════════════════════════════════════
    //  CUSTOMER PRICING PROFILE CRUD
    // ═══════════════════════════════════════════════════════════

    public CustomerPricingDto getCustomerPricing(Long customerId) {
        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profile = bid != null
            ? customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bid)
            : customerPricingProfileRepository.findByCustomerId(customerId);
        if (profile.isEmpty()) {
            return CustomerPricingDto.builder()
                .customerId(customerId)
                .eventPricings(List.of())
                .addonPricings(List.of())
                .build();
        }
        return toCustomerPricingDto(profile.get());
    }

    @Transactional
    public CustomerPricingDto saveCustomerPricing(CustomerPricingSaveRequest request) {
        Long bid = BingeContext.getBingeId();
        CustomerPricingProfile profile = (bid != null
            ? customerPricingProfileRepository.findByCustomerIdAndBingeId(request.getCustomerId(), bid)
            : customerPricingProfileRepository.findByCustomerId(request.getCustomerId()))
            .orElseGet(() -> {
                CustomerPricingProfile p = CustomerPricingProfile.builder()
                    .customerId(request.getCustomerId())
                    .bingeId(bid)
                    .build();
                return customerPricingProfileRepository.save(p);
            });

        // Assign/unassign rate code
        if (request.getRateCodeId() != null) {
            RateCode rc = rateCodeRepository.findById(request.getRateCodeId())
                .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", request.getRateCodeId()));
            profile.setRateCode(rc);
        } else {
            profile.setRateCode(null);
        }

        // Clear and re-apply custom event pricing
        profile.getEventPricings().clear();
        profile.getAddonPricings().clear();
        customerPricingProfileRepository.saveAndFlush(profile);

        if (request.getEventPricings() != null) {
            for (var ep : request.getEventPricings()) {
                EventType et = eventTypeRepository.findById(ep.getEventTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", ep.getEventTypeId()));
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
                AddOn addon = addOnRepository.findById(ap.getAddOnId())
                    .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", ap.getAddOnId()));
                profile.getAddonPricings().add(CustomerAddonPricing.builder()
                    .customerPricingProfile(profile)
                    .addOn(addon)
                    .price(ap.getPrice())
                    .build());
            }
        }

        profile = customerPricingProfileRepository.save(profile);
        log.info("Customer pricing saved for customerId={}", request.getCustomerId());
        return toCustomerPricingDto(profile);
    }

    @Transactional
    public int bulkAssignRateCode(BulkRateCodeAssignRequest request) {
        RateCode rateCode = null;
        if (request.getRateCodeId() != null) {
            rateCode = rateCodeRepository.findById(request.getRateCodeId())
                .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", request.getRateCodeId()));
        }

        int count = 0;
        for (Long customerId : request.getCustomerIds()) {
            CustomerPricingProfile profile = customerPricingProfileRepository
                .findByCustomerId(customerId)
                .orElseGet(() -> {
                    CustomerPricingProfile p = CustomerPricingProfile.builder()
                        .customerId(customerId)
                        .build();
                    return customerPricingProfileRepository.save(p);
                });
            profile.setRateCode(rateCode);
            customerPricingProfileRepository.save(profile);
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
        Long bid = BingeContext.getBingeId();
        List<EventType> allEvents = bid != null ? eventTypeRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : eventTypeRepository.findByActiveTrue();
        List<AddOn> allAddOns = bid != null ? addOnRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : addOnRepository.findByActiveTrue();

        Optional<CustomerPricingProfile> profileOpt = bid != null
            ? customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bid)
            : customerPricingProfileRepository.findByCustomerId(customerId);

        // Build maps for customer-specific overrides
        Map<Long, CustomerEventPricing> custEventMap = new HashMap<>();
        Map<Long, CustomerAddonPricing> custAddonMap = new HashMap<>();
        RateCode rateCode = null;

        if (profileOpt.isPresent()) {
            CustomerPricingProfile profile = profileOpt.get();
            rateCode = profile.getRateCode();
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
                rateCodeName = rateCode.getName();
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
                rateCodeName = rateCode.getName();
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
            .eventPricings(eventPricings)
            .addonPricings(addonPricings)
            .build();
    }

    /**
     * Resolves the effective price for a SINGLE event type for a given customer.
     * Returns {basePrice, hourlyRate, pricePerGuest, source, rateCodeName}.
     */
    public ResolvedEventPrice resolveEventPrice(Long customerId, Long eventTypeId) {
        EventType et = eventTypeRepository.findById(eventTypeId)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", eventTypeId));

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = bid != null
            ? customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bid)
            : customerPricingProfileRepository.findByCustomerId(customerId);

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
        AddOn addon = addOnRepository.findById(addOnId)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", addOnId));

        Long bid = BingeContext.getBingeId();
        Optional<CustomerPricingProfile> profileOpt = bid != null
            ? customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bid)
            : customerPricingProfileRepository.findByCustomerId(customerId);

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
    //  HELPER RECORDS & MAPPERS
    // ═══════════════════════════════════════════════════════════

    public record ResolvedEventPrice(BigDecimal basePrice, BigDecimal hourlyRate, BigDecimal pricePerGuest, String source, String rateCodeName) {}
    public record ResolvedAddonPrice(BigDecimal price, String source, String rateCodeName) {}

    /**
     * Resolves pricing based purely on a rate code (no customer context).
     * Used by the admin booking wizard when overriding a customer's pricing with a specific rate code.
     */
    public ResolvedPricingDto resolveRateCodePricing(Long rateCodeId) {
        RateCode rateCode = rateCodeRepository.findById(rateCodeId)
            .orElseThrow(() -> new ResourceNotFoundException("RateCode", "id", rateCodeId));
        if (!rateCode.isActive()) {
            throw new BusinessException("Rate code '" + rateCode.getName() + "' is not active");
        }

        Long bid = BingeContext.getBingeId();
        List<EventType> allEvents = bid != null ? eventTypeRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : eventTypeRepository.findByActiveTrue();
        List<AddOn> allAddOns = bid != null ? addOnRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : addOnRepository.findByActiveTrue();

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
                EventType et = eventTypeRepository.findById(ep.getEventTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", ep.getEventTypeId()));
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
                AddOn addon = addOnRepository.findById(ap.getAddOnId())
                    .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", ap.getAddOnId()));
                rateCode.getAddonPricings().add(RateCodeAddonPricing.builder()
                    .rateCode(rateCode)
                    .addOn(addon)
                    .price(ap.getPrice())
                    .build());
            }
        }
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

    private CustomerPricingDto toCustomerPricingDto(CustomerPricingProfile profile) {
        return CustomerPricingDto.builder()
            .customerId(profile.getCustomerId())
            .rateCodeId(profile.getRateCode() != null ? profile.getRateCode().getId() : null)
            .rateCodeName(profile.getRateCode() != null ? profile.getRateCode().getName() : null)
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
}
