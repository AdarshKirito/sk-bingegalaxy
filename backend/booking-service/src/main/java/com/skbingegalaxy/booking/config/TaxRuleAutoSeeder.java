package com.skbingegalaxy.booking.config;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.service.BingeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time (but idempotent, so safe on every boot) backfill: venues created before
 * country-tax auto-assignment existed get their country's standard tax rule seeded,
 * exactly as new venues do at creation/approval. Without this, pre-existing venues
 * keep computing zero tax — the original "admin bookings don't apply taxes" bug.
 *
 * <p>{@link BingeService#ensureDefaultTaxRule} skips venues already covered by any
 * active rule (scoped or global) and never touches admin-authored rules, so re-runs
 * are no-ops. Failures are logged and skipped — seeding must never block startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxRuleAutoSeeder implements ApplicationRunner {

    private final BingeRepository bingeRepository;
    private final BingeService bingeService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int seeded = 0;
            for (Binge binge : bingeRepository.findAll()) {
                if (binge.getStatus() != null && binge.getStatus() != BingeApprovalStatus.APPROVED) {
                    continue; // pending/rejected venues get seeded at approval time
                }
                bingeService.ensureDefaultTaxRule(binge);
                seeded++;
            }
            log.info("Tax auto-seed pass complete over {} approved venue(s)", seeded);
        } catch (Exception ex) {
            log.warn("Tax auto-seed pass failed (non-fatal): {}", ex.getMessage());
        }
    }
}
