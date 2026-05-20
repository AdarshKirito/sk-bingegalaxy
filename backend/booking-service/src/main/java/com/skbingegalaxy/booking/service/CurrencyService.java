package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CurrencyRateDto;
import com.skbingegalaxy.booking.entity.CurrencyRate;
import com.skbingegalaxy.booking.repository.CurrencyRateRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Multi-currency engine.
 * <ul>
 *   <li>Storage: bookings.total_amount + tax_amount are persisted in the BASE
 *       currency (INR). The booking row also stores currency_code + fx_rate at
 *       the moment of quote so historical receipts are reproducible.</li>
 *   <li>Display: any UI passes through {@link #convertFromBase(BigDecimal,String)}.</li>
 *   <li>Charging: payment-service receives the customer-facing currency + amount
 *       and forwards it to Razorpay (which supports USD, EUR, GBP, etc).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CurrencyService {

    public static final String BASE_CURRENCY = "INR";

    private final CurrencyRateRepository currencyRateRepository;

    public List<CurrencyRateDto> listActive() {
        return currencyRateRepository.findByActiveTrueOrderByCodeAsc()
            .stream().map(this::toDto).toList();
    }

    public List<CurrencyRateDto> listAll() {
        return currencyRateRepository.findAll()
            .stream().sorted((a, b) -> a.getCode().compareTo(b.getCode()))
            .map(this::toDto).toList();
    }

    public CurrencyRate findOrThrow(String code) {
        return currencyRateRepository.findById(normalize(code))
            .orElseThrow(() -> new ResourceNotFoundException("Currency", "code", code));
    }

    /** Resolve rate; falls back to base (1.0) if currency unknown / inactive. */
    public BigDecimal getRate(String code) {
        if (code == null || code.equalsIgnoreCase(BASE_CURRENCY)) return BigDecimal.ONE;
        return currencyRateRepository.findById(normalize(code))
            .filter(CurrencyRate::isActive)
            .map(CurrencyRate::getRateToBase)
            .orElse(BigDecimal.ONE);
    }

    /** baseAmount (INR) → display currency. */
    public BigDecimal convertFromBase(BigDecimal baseAmount, String targetCode) {
        if (baseAmount == null) return BigDecimal.ZERO;
        if (targetCode == null || targetCode.equalsIgnoreCase(BASE_CURRENCY)) {
            return baseAmount.setScale(2, RoundingMode.HALF_UP);
        }
        CurrencyRate rate = findOrThrow(targetCode);
        return baseAmount.multiply(rate.getRateToBase())
            .setScale(rate.getDecimalDigits(), RoundingMode.HALF_UP);
    }

    /** display currency amount → INR. */
    public BigDecimal convertToBase(BigDecimal displayAmount, String sourceCode) {
        if (displayAmount == null) return BigDecimal.ZERO;
        if (sourceCode == null || sourceCode.equalsIgnoreCase(BASE_CURRENCY)) {
            return displayAmount.setScale(2, RoundingMode.HALF_UP);
        }
        CurrencyRate rate = findOrThrow(sourceCode);
        if (rate.getRateToBase().signum() <= 0) {
            throw new BusinessException("Currency rate for " + sourceCode + " is not positive");
        }
        return displayAmount.divide(rate.getRateToBase(), 2, RoundingMode.HALF_UP);
    }

    /** Quick-lookup for templating: code → symbol. */
    public Map<String, String> symbolMap() {
        return currencyRateRepository.findByActiveTrueOrderByCodeAsc().stream()
            .collect(Collectors.toMap(CurrencyRate::getCode, CurrencyRate::getSymbol));
    }

    // ─── Admin CRUD ────────────────────────────────────────────────────────

    @Transactional
    public CurrencyRateDto upsert(CurrencyRateDto request) {
        validate(request);
        String code = normalize(request.getCode());
        Optional<CurrencyRate> existing = currencyRateRepository.findById(code);
        CurrencyRate rate = existing.orElseGet(() -> CurrencyRate.builder().code(code).build());
        rate.setName(request.getName());
        rate.setSymbol(request.getSymbol());
        rate.setRateToBase(request.getRateToBase());
        rate.setDecimalDigits(request.getDecimalDigits() != null ? request.getDecimalDigits() : 2);
        rate.setActive(request.isActive());
        rate.setBase(request.isBase());
        rate.setManualOverride(true); // edits via admin always mark as manual
        if (request.getFxSource() != null && !request.getFxSource().isBlank()) {
            rate.setFxSource(request.getFxSource().trim().toUpperCase());
        }
        rate.setSupportsDisplay(request.isSupportsDisplay() || existing.isEmpty()); // default true on create
        rate.setSupportsPayment(request.isSupportsPayment());
        rate.setSupportsSettlement(request.isSupportsSettlement());
        // If marking as base, ensure exactly one base row.
        if (rate.isBase()) {
            currencyRateRepository.findByBaseTrue()
                .filter(r -> !r.getCode().equals(code))
                .ifPresent(r -> { r.setBase(false); currencyRateRepository.save(r); });
            // base must always have rate 1.0
            rate.setRateToBase(BigDecimal.ONE);
        }
        rate = currencyRateRepository.save(rate);
        return toDto(rate);
    }

    @Transactional
    public void delete(String code) {
        CurrencyRate rate = findOrThrow(code);
        if (rate.isBase()) throw new BusinessException("Cannot delete the base currency");
        currencyRateRepository.delete(rate);
    }

    @Transactional
    public CurrencyRateDto toggleActive(String code) {
        CurrencyRate rate = findOrThrow(code);
        if (rate.isBase() && rate.isActive()) {
            throw new BusinessException("Cannot deactivate the base currency");
        }
        rate.setActive(!rate.isActive());
        return toDto(currencyRateRepository.save(rate));
    }

    private void validate(CurrencyRateDto r) {
        if (r.getCode() == null || r.getCode().isBlank()) throw new BusinessException("Currency code is required");
        if (r.getName() == null || r.getName().isBlank()) throw new BusinessException("Currency name is required");
        if (r.getSymbol() == null || r.getSymbol().isBlank()) throw new BusinessException("Currency symbol is required");
        if (r.getRateToBase() == null || r.getRateToBase().signum() <= 0) {
            throw new BusinessException("Rate-to-base must be positive");
        }
    }

    private static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    public CurrencyRateDto toDto(CurrencyRate r) {
        return CurrencyRateDto.builder()
            .code(r.getCode())
            .name(r.getName())
            .symbol(r.getSymbol())
            .rateToBase(r.getRateToBase())
            .decimalDigits(r.getDecimalDigits())
            .active(r.isActive())
            .base(r.isBase())
            .manualOverride(r.isManualOverride())
            .lastUpdated(r.getLastUpdated() != null ? r.getLastUpdated().toString() : null)
            .supportsDisplay(r.isSupportsDisplay())
            .supportsPayment(r.isSupportsPayment())
            .supportsSettlement(r.isSupportsSettlement())
            .fxSource(r.getFxSource())
            .build();
    }
}
