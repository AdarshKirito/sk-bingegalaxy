package com.skbingegalaxy.booking.loyalty.v2.ops;

import com.skbingegalaxy.booking.config.LoyaltyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loyalty v2 — PARITY audit job (shadow-period observability).
 *
 * <p>During the dual-write period between M1 (shadow mode) and M12
 * (cut-over), both the legacy {@code loyalty_accounts} table (v1) and
 * {@code loyalty_points_wallet} (v2) are being kept in lock-step.
 * Any divergence is a silent data-integrity bug.  This job catches
 * it nightly by joining the two ledgers on {@code customer_id} and
 * logging every membership whose balance does not match.
 *
 * <p>Metrics published (so dashboards/alerts can trigger):
 * <ul>
 *   <li>{@code loyalty.parity.customers_checked} — total rows compared.</li>
 *   <li>{@code loyalty.parity.mismatch{direction="v1_ahead"}} — v1 balance &gt; v2.</li>
 *   <li>{@code loyalty.parity.mismatch{direction="v2_ahead"}} — v2 balance &gt; v1.</li>
 *   <li>{@code loyalty.parity.missing{side="v1"}} — customer in v2 but no v1 row.</li>
 *   <li>{@code loyalty.parity.missing{side="v2"}} — customer in v1 but no v2 membership.</li>
 * </ul>
 *
 * <p><b>Read-only</b> — this job never writes to either table.  If a
 * mismatch is found, it is up to the operator to investigate via
 * the admin UI (exposed as {@code POST /api/v2/loyalty/super-admin/parity/run}
 * for an on-demand run).
 *
 * <p>Runs at 04:00 UTC daily — after v1 expiry (02:00), v2 expiry (02:15),
 * and guest-shadow purge (02:45), so the comparison sees a quiesced
 * state.  Auto-disables when {@link LoyaltyProperties#isV2Primary()} is
 * {@code true} (post-cutover there is no v1 to compare against).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LoyaltyParityJob {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final LoyaltyProperties loyaltyProps;

    /** Maximum number of detailed mismatch rows to write to the log per run. */
    private static final int LOG_SAMPLE_SIZE = 50;

    @Scheduled(cron = "0 0 4 * * *")                     // 04:00 UTC daily
    @SchedulerLock(name = "loyaltyV2Parity", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void runScheduled() {
        if (loyaltyProps.isV2Primary()) {
            log.debug("[loyalty-v2-parity] skipped — v2 is primary, no legacy ledger to compare");
            return;
        }
        try {
            Summary s = runOnce();
            log.info("[loyalty-v2-parity] scheduled run complete: {}", s);
        } catch (Exception ex) {
            log.error("[loyalty-v2-parity] scheduled run failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Execute the parity comparison synchronously and return a summary
     * counts object.  Safe to call on-demand from the admin endpoint.
     *
     * <p>The SQL uses a FULL OUTER JOIN so we see rows missing on either
     * side.  We also tolerate NULL balances (treating NULL as 0).
     */
    public Summary runOnce() {
        LocalDateTime start = LocalDateTime.now();
        Summary summary = new Summary();
        summary.startedAt = start;

        // Full outer join: every customer that has a v1 account OR a v2 membership.
        // Aggregates by customer so a customer with multiple bookings still
        // yields a single comparison row.
        String sql = """
                SELECT
                    COALESCE(v1.customer_id, v2.customer_id) AS customer_id,
                    COALESCE(v1.current_balance, 0)          AS v1_balance,
                    COALESCE(v2.v2_balance, 0)               AS v2_balance,
                    CASE WHEN v1.customer_id IS NULL THEN 1 ELSE 0 END AS v1_missing,
                    CASE WHEN v2.customer_id IS NULL THEN 1 ELSE 0 END AS v2_missing
                FROM loyalty_accounts v1
                FULL OUTER JOIN (
                    SELECT m.customer_id, w.current_balance AS v2_balance
                    FROM loyalty_membership m
                    JOIN loyalty_points_wallet w ON w.membership_id = m.id
                    WHERE m.active = TRUE
                ) v2 ON v1.customer_id = v2.customer_id
                """;

        int sampleLogged = 0;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        summary.customersChecked = rows.size();

        for (Map<String, Object> row : rows) {
            Number customerId = (Number) row.get("customer_id");
            long v1 = ((Number) row.get("v1_balance")).longValue();
            long v2 = ((Number) row.get("v2_balance")).longValue();
            boolean v1Missing = ((Number) row.get("v1_missing")).intValue() == 1;
            boolean v2Missing = ((Number) row.get("v2_missing")).intValue() == 1;

            if (v1Missing) {
                summary.v1Missing++;
                meterRegistry.counter("loyalty.parity.missing", "side", "v1").increment();
                if (sampleLogged++ < LOG_SAMPLE_SIZE) {
                    log.warn("[loyalty-v2-parity] MISSING v1 customer={} v2Balance={}", customerId, v2);
                }
            } else if (v2Missing) {
                summary.v2Missing++;
                meterRegistry.counter("loyalty.parity.missing", "side", "v2").increment();
                if (sampleLogged++ < LOG_SAMPLE_SIZE) {
                    log.warn("[loyalty-v2-parity] MISSING v2 customer={} v1Balance={}", customerId, v1);
                }
            } else if (v1 != v2) {
                long diff = Math.abs(v1 - v2);
                summary.totalDiff += diff;
                if (v1 > v2) {
                    summary.v1Ahead++;
                    meterRegistry.counter("loyalty.parity.mismatch", "direction", "v1_ahead").increment();
                } else {
                    summary.v2Ahead++;
                    meterRegistry.counter("loyalty.parity.mismatch", "direction", "v2_ahead").increment();
                }
                if (sampleLogged++ < LOG_SAMPLE_SIZE) {
                    log.warn("[loyalty-v2-parity] MISMATCH customer={} v1={} v2={} diff={}",
                            customerId, v1, v2, diff);
                }
            } else {
                summary.matches++;
            }
        }

        meterRegistry.counter("loyalty.parity.customers_checked").increment(summary.customersChecked);
        summary.finishedAt = LocalDateTime.now();

        log.info("[loyalty-v2-parity] summary: checked={} matches={} v1Ahead={} v2Ahead={} " +
                        "v1Missing={} v2Missing={} totalDiff={} durationMs={}",
                summary.customersChecked, summary.matches, summary.v1Ahead, summary.v2Ahead,
                summary.v1Missing, summary.v2Missing, summary.totalDiff,
                java.time.Duration.between(start, summary.finishedAt).toMillis());

        return summary;
    }

    /** Simple DTO returned to the admin endpoint. */
    public static class Summary {
        public LocalDateTime startedAt;
        public LocalDateTime finishedAt;
        public int customersChecked;
        public int matches;
        public int v1Ahead;
        public int v2Ahead;
        public int v1Missing;
        public int v2Missing;
        public long totalDiff;

        /** JSON-friendly map for ApiResponse serialisation. */
        public Map<String, Object> asMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("startedAt", startedAt);
            m.put("finishedAt", finishedAt);
            m.put("customersChecked", customersChecked);
            m.put("matches", matches);
            m.put("v1Ahead", v1Ahead);
            m.put("v2Ahead", v2Ahead);
            m.put("v1Missing", v1Missing);
            m.put("v2Missing", v2Missing);
            m.put("totalDiff", totalDiff);
            return m;
        }

        @Override public String toString() { return asMap().toString(); }
    }
}
