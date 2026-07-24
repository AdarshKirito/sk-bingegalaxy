package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.CurrencyRate;
import com.skbingegalaxy.booking.fx.FxRateProvider;
import com.skbingegalaxy.booking.repository.CurrencyRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps {@code currency_rates} current automatically so admins never have to
 * hand-maintain FX rates again.
 *
 * <p>Providers are tried in bean order ({@code ER_API} primary, {@code ECB}
 * fallback); the first successful fetch wins. Rows are updated only when:
 * <ul>
 *   <li>the row is NOT the base currency (base is 1.0 by definition), and</li>
 *   <li>{@code manualOverride} is false — an admin-pinned rate is never
 *       silently overwritten; the admin unpins it from the Currencies page
 *       when they want automation back.</li>
 * </ul>
 *
 * <p>Both the 6-hourly scheduler and the admin "Refresh now" button call
 * {@link #refreshAll}. The returned summary powers the admin UI toast and the
 * ops log line.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FxRateRefreshService {

    private final CurrencyRateRepository currencyRateRepository;
    private final List<FxRateProvider> providers;

    @Value("${app.fx.refresh-enabled:true}")
    private boolean refreshEnabled;

    public record RefreshSummary(
        boolean success,
        String provider,
        String baseCode,
        int updated,
        int skippedManual,
        int missingFromProvider,
        String error,
        LocalDateTime refreshedAt
    ) {}

    @Transactional
    public RefreshSummary refreshAll(String trigger) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!refreshEnabled) {
            return new RefreshSummary(false, null, null, 0, 0, 0,
                "FX auto-refresh is disabled (app.fx.refresh-enabled=false)", now);
        }

        String baseCode = currencyRateRepository.findByBaseTrue()
            .map(CurrencyRate::getCode)
            .orElse(CurrencyService.BASE_CURRENCY);

        Map<String, BigDecimal> rates = null;
        String providerUsed = null;
        List<String> failures = new ArrayList<>();
        for (FxRateProvider provider : providers) {
            try {
                rates = provider.fetchRates(baseCode);
                providerUsed = provider.sourceCode();
                break;
            } catch (Exception ex) {
                failures.add(provider.sourceCode() + ": " + ex.getMessage());
                log.warn("[fx] provider {} failed for base {}: {}",
                    provider.sourceCode(), baseCode, ex.getMessage());
            }
        }
        if (rates == null) {
            log.error("[fx] ALL providers failed (trigger={}): {}", trigger, failures);
            return new RefreshSummary(false, null, baseCode, 0, 0, 0,
                "All FX providers failed: " + String.join(" | ", failures), now);
        }

        int updated = 0, skippedManual = 0, missing = 0;
        for (CurrencyRate row : currencyRateRepository.findAll()) {
            if (row.isBase()) continue;
            if (row.isManualOverride()) { skippedManual++; continue; }
            BigDecimal rate = rates.get(row.getCode());
            if (rate == null) {
                missing++;
                log.debug("[fx] provider {} has no rate for {} — leaving existing value",
                    providerUsed, row.getCode());
                continue;
            }
            row.setRateToBase(rate.setScale(8, RoundingMode.HALF_UP));
            row.setFxSource(providerUsed);
            row.setUpdatedBy("FX_AUTO");
            currencyRateRepository.save(row);
            updated++;
        }

        log.info("[fx] refresh complete (trigger={}): provider={} base={} updated={} manual-skipped={} missing={}",
            trigger, providerUsed, baseCode, updated, skippedManual, missing);
        return new RefreshSummary(true, providerUsed, baseCode, updated, skippedManual, missing, null, now);
    }
}
