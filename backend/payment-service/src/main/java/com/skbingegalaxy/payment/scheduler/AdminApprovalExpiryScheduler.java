package com.skbingegalaxy.payment.scheduler;

import com.skbingegalaxy.payment.service.AdminApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps {@code PENDING} approval requests whose TTL has passed and flips
 * them to {@code EXPIRED}. Runs every 5 minutes under a ShedLock so a
 * multi-replica deploy never duplicates the work.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalExpiryScheduler {

    private final AdminApprovalService approvalService;

    @Scheduled(fixedRateString = "${app.approvals.expiry-interval-ms:300000}")
    @SchedulerLock(name = "adminApprovalExpiry",
        lockAtLeastFor = "30s", lockAtMostFor = "4m")
    public void run() {
        try {
            int n = approvalService.expireStale();
            if (n > 0) log.info("Approval expiry sweep marked {} requests EXPIRED", n);
        } catch (Exception e) {
            log.error("Approval expiry sweep failed: {}", e.getMessage(), e);
        }
    }
}
