package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.dto.TaxComputationResult;
import com.skbingegalaxy.booking.dto.TaxRuleDto;
import com.skbingegalaxy.booking.entity.TaxRule;
import com.skbingegalaxy.booking.repository.TaxRuleRepository;
import com.skbingegalaxy.booking.tax.provider.TaxContext;
import com.skbingegalaxy.booking.tax.provider.TaxProvider;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes taxes for a booking and exposes admin CRUD for tax rules.
 *
 * <p>Resolution algorithm: for the current binge, fetch all active rules whose
 * {@code binge_id} equals the binge OR is NULL (platform default). Group by
 * priority — when both binge-scoped and platform rules exist with the same
 * priority and same name, the binge-scoped one wins (override semantics).
 * Otherwise all matching rules are summed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TaxService {

    private final TaxRuleRepository taxRuleRepository;
    private final ObjectMapper objectMapper;
    private final TaxProvider taxProvider;

    private static final BigDecimal BPS_DIVISOR = new BigDecimal("10000");

    /** Backward-compatible compute: delegates to {@link TaxProvider} with a
     *  minimal context (binge-only). New callers should use
     *  {@link #compute(TaxContext, BigDecimal, BigDecimal, BigDecimal, BigDecimal)}. */
    public TaxComputationResult compute(Long bingeId,
                                        BigDecimal subtotal,
                                        BigDecimal baseAmount,
                                        BigDecimal addOnAmount,
                                        BigDecimal guestAmount) {
        return compute(TaxContext.builder().bingeId(bingeId).build(),
            subtotal, baseAmount, addOnAmount, guestAmount);
    }

    /** Jurisdiction-aware compute. */
    public TaxComputationResult compute(TaxContext ctx,
                                        BigDecimal subtotal,
                                        BigDecimal baseAmount,
                                        BigDecimal addOnAmount,
                                        BigDecimal guestAmount) {
        return taxProvider.computeTaxes(ctx, subtotal, baseAmount, addOnAmount, guestAmount);
    }

    // ─── Admin CRUD ────────────────────────────────────────────────────────

    public List<TaxRuleDto> listRulesForCurrentBinge() {
        Long bid = BingeContext.getBingeId();
        List<TaxRule> rules = bid != null
            ? taxRuleRepository.findActiveForBinge(bid)
            : taxRuleRepository.findAll();
        // For admin we want both active+inactive — re-fetch inactive scoped rules too:
        if (bid != null) {
            List<TaxRule> scoped = taxRuleRepository.findByBingeIdOrderByPriorityAscIdAsc(bid);
            List<TaxRule> globals = taxRuleRepository.findGlobalRules();
            // dedup by id
            java.util.Map<Long, TaxRule> map = new java.util.LinkedHashMap<>();
            scoped.forEach(r -> map.put(r.getId(), r));
            globals.forEach(r -> map.putIfAbsent(r.getId(), r));
            rules = new ArrayList<>(map.values());
        }
        return rules.stream().map(this::toDto).toList();
    }

    public List<TaxRuleDto> listGlobalRules() {
        return taxRuleRepository.findGlobalRules().stream().map(this::toDto).toList();
    }

    /** True iff the rule with this id has a NULL binge_id (platform-wide). */
    public boolean isGlobalRule(Long id) {
        return taxRuleRepository.findById(id)
            .map(r -> r.getBingeId() == null)
            .orElse(false);
    }

    @Transactional
    public TaxRuleDto createRule(TaxRuleDto request, boolean global) {
        validateRule(request);
        TaxRule rule = TaxRule.builder()
            .bingeId(global ? null : BingeContext.requireBingeId())
            .name(request.getName().trim())
            .description(request.getDescription())
            .rateBps(request.getRateBps())
            .appliesTo(request.getAppliesTo() != null ? request.getAppliesTo() : TaxRule.AppliesTo.TOTAL)
            .inclusive(request.isInclusive())
            .countryCode(request.getCountryCode())
            .regionCode(request.getRegionCode())
            .stateCode(request.getStateCode())
            .city(request.getCity())
            .postalCode(request.getPostalCode())
            .productType(request.getProductType())
            .customerType(request.getCustomerType())
            .taxType(request.getTaxType() != null && !request.getTaxType().isBlank()
                     ? request.getTaxType().toUpperCase() : "GENERIC")
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .ruleVersion(request.getRuleVersion() != null ? request.getRuleVersion() : 1)
            .priority(request.getPriority() != null ? request.getPriority() : 100)
            .active(request.isActive())
            .build();
        rule = taxRuleRepository.save(rule);
        log.info("Tax rule created: id={} name={} bingeId={} taxType={}",
            rule.getId(), rule.getName(), rule.getBingeId(), rule.getTaxType());
        return toDto(rule);
    }

    @Transactional
    public TaxRuleDto updateRule(Long id, TaxRuleDto request) {
        validateRule(request);
        TaxRule rule = taxRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("TaxRule", "id", id));
        // Authorization: a binge admin cannot edit a global rule.
        Long currentBinge = BingeContext.getBingeId();
        if (rule.getBingeId() != null && currentBinge != null && !rule.getBingeId().equals(currentBinge)) {
            throw new BusinessException("Cannot edit a tax rule belonging to another binge");
        }
        rule.setName(request.getName().trim());
        rule.setDescription(request.getDescription());
        rule.setRateBps(request.getRateBps());
        rule.setAppliesTo(request.getAppliesTo() != null ? request.getAppliesTo() : rule.getAppliesTo());
        rule.setInclusive(request.isInclusive());
        rule.setCountryCode(request.getCountryCode());
        rule.setRegionCode(request.getRegionCode());
        rule.setStateCode(request.getStateCode());
        rule.setCity(request.getCity());
        rule.setPostalCode(request.getPostalCode());
        rule.setProductType(request.getProductType());
        rule.setCustomerType(request.getCustomerType());
        if (request.getTaxType() != null && !request.getTaxType().isBlank()) {
            rule.setTaxType(request.getTaxType().toUpperCase());
        }
        rule.setEffectiveFrom(request.getEffectiveFrom());
        rule.setEffectiveTo(request.getEffectiveTo());
        if (request.getRuleVersion() != null) rule.setRuleVersion(request.getRuleVersion() + 1);
        else rule.setRuleVersion(rule.getRuleVersion() == null ? 2 : rule.getRuleVersion() + 1);
        if (request.getPriority() != null) rule.setPriority(request.getPriority());
        rule.setActive(request.isActive());
        rule = taxRuleRepository.save(rule);
        return toDto(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        TaxRule rule = taxRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("TaxRule", "id", id));
        Long currentBinge = BingeContext.getBingeId();
        if (rule.getBingeId() != null && currentBinge != null && !rule.getBingeId().equals(currentBinge)) {
            throw new BusinessException("Cannot delete a tax rule belonging to another binge");
        }
        taxRuleRepository.delete(rule);
    }

    private void validateRule(TaxRuleDto r) {
        if (r.getName() == null || r.getName().isBlank()) throw new BusinessException("Tax rule name is required");
        if (r.getRateBps() == null || r.getRateBps() < 0 || r.getRateBps() > 100000) {
            throw new BusinessException("Rate (bps) must be between 0 and 100000 (0%–1000%)");
        }
    }

    public TaxRuleDto toDto(TaxRule r) {
        return TaxRuleDto.builder()
            .id(r.getId())
            .bingeId(r.getBingeId())
            .name(r.getName())
            .description(r.getDescription())
            .rateBps(r.getRateBps())
            .appliesTo(r.getAppliesTo())
            .inclusive(r.isInclusive())
            .countryCode(r.getCountryCode())
            .regionCode(r.getRegionCode())
            .stateCode(r.getStateCode())
            .city(r.getCity())
            .postalCode(r.getPostalCode())
            .productType(r.getProductType())
            .customerType(r.getCustomerType())
            .taxType(r.getTaxType())
            .effectiveFrom(r.getEffectiveFrom())
            .effectiveTo(r.getEffectiveTo())
            .ruleVersion(r.getRuleVersion())
            .priority(r.getPriority())
            .active(r.isActive())
            .build();
    }
}
