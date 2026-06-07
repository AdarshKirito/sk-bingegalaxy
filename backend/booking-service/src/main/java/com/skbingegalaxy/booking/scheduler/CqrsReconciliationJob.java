package com.skbingegalaxy.booking.scheduler;

import com.skbingegalaxy.booking.repository.BookingReadModelRepository;
import com.skbingegalaxy.booking.service.BookingProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects and heals drift between the CQRS read model (booking_read_model)
 * and the source-of-truth bookings table.
 *
 * Three classes of drift are detected:
 * <ol>
 *   <li><b>Orphaned projections</b> — rows in booking_read_model with no corresponding booking.</li>
 *   <li><b>Missing projections</b> — bookings with no read model row.</li>
 *   <li><b>Status mismatches</b> — projection status differs from source booking status.</li>
 * </ol>
 *
 * Runs daily at 02:15. ShedLock prevents concurrent execution across replicas.
 * NOT @Transactional at the job level — each replayBooking call runs in its own
 * transaction so one failure doesn't roll back other successful repairs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CqrsReconciliationJob {

    private final BookingReadModelRepository readModelRepository;
    private final BookingProjectionService projectionService;

    @Scheduled(cron = "${app.cqrs.reconciliation-cron:0 15 2 * * *}",
               zone  = "${app.cqrs.reconciliation-zone:Asia/Kolkata}")
    @SchedulerLock(name = "cqrsReconciliation", lockAtLeastFor = "1m", lockAtMostFor = "10m")
    public void reconcile() {
        log.info("CQRS reconciliation job started");

        int totalIssues = 0;

        // ── 1. Orphaned projections (no source booking) ─────────────────────
        List<String> orphaned = readModelRepository.findOrphanedProjections();
        if (!orphaned.isEmpty()) {
            log.warn("CQRS drift — {} orphaned projection(s) with no source booking: {}",
                orphaned.size(), orphaned);
            totalIssues += orphaned.size();
        }

        // ── 2. Missing projections (booking exists, no read model) ───────────
        List<String> missing = readModelRepository.findBookingsWithoutProjection();
        if (!missing.isEmpty()) {
            log.warn("CQRS drift — {} booking(s) without a projection; rebuilding: {}",
                missing.size(), missing);
            for (String ref : missing) {
                try {
                    projectionService.replayBooking(ref);
                    log.info("CQRS drift healed: rebuilt projection for {}", ref);
                } catch (Exception e) {
                    log.error("CQRS drift: failed to rebuild projection for {}: {}", ref, e.getMessage());
                }
            }
            totalIssues += missing.size();
        }

        // ── 3. Status mismatches ─────────────────────────────────────────────
        List<String> mismatched = readModelRepository.findStatusMismatchedRefs();
        if (!mismatched.isEmpty()) {
            log.warn("CQRS drift — {} booking(s) with status mismatch vs projection; rebuilding: {}",
                mismatched.size(), mismatched);
            for (String ref : mismatched) {
                try {
                    projectionService.replayBooking(ref);
                    log.info("CQRS drift healed: status synced for {}", ref);
                } catch (Exception e) {
                    log.error("CQRS drift: failed to sync status for {}: {}", ref, e.getMessage());
                }
            }
            totalIssues += mismatched.size();
        }

        if (totalIssues == 0) {
            log.info("CQRS reconciliation: no drift detected");
        } else {
            log.warn("CQRS reconciliation complete — {} issue(s) total (orphaned={}, missing={}, mismatched={})",
                totalIssues, orphaned.size(), missing.size(), mismatched.size());
        }
    }
}
