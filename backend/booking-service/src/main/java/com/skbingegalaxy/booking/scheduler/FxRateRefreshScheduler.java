package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.service.FxRateRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls fresh FX reference rates every 6 hours (ShedLock-guarded so exactly one
 * instance runs in a clustered deployment). Failures are logged and retried on
 * the next tick — rates simply stay at their last known value in between, and
 * the Currencies admin page surfaces staleness via {@code lastUpdated}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FxRateRefreshScheduler {

    private final FxRateRefreshService fxRateRefreshService;

    @Scheduled(cron = "${app.fx.refresh-cron:0 10 */6 * * *}")
    @SchedulerLock(name = "fxRateRefresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void refresh() {
        try {
            fxRateRefreshService.refreshAll("SCHEDULED");
        } catch (Exception ex) {
            log.error("[fx] scheduled refresh failed: {}", ex.getMessage(), ex);
        }
    }
}
