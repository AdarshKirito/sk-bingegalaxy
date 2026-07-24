package com.skbingegalaxy.payment.scheduler;

import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.payment.client.RazorpayGatewayClient;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.PaymentStatusHistory;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.PaymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.util.List;

/**
 * Reconciliation jobs. Structure (REL-002): candidate rows are read up front,
 * every provider call happens OUTSIDE any DB transaction, and each row's
 * outcome commits in its own short transaction via {@code PaymentService}
 * row-ops — a slow provider can no longer pin a connection for minutes or
 * roll back a whole batch.
 *
 * <p>State semantics (PAY-007): "the provider could not be reached" is NOT
 * "the payment failed". Only an AUTHORITATIVE provider answer transitions a
 * payment; transport failures leave the row INITIATED and are re-checked on
 * the next pass, with an ops alert once the ambiguity has aged past the
 * callback window.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository statusHistoryRepository;
    private final RazorpayGatewayClient razorpayGatewayClient;
    private final com.skbingegalaxy.payment.repository.RefundRepository refundRepository;
    private final com.skbingegalaxy.payment.service.PaymentService paymentService;

    @org.springframework.beans.factory.annotation.Value("${app.payment.settlement-reconciliation-zone:Asia/Kolkata}")
    private String settlementZoneStr;

    /** Max rows a single reconciliation pass processes — bounds run time. */
    @org.springframework.beans.factory.annotation.Value("${app.payment.reconciliation-batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelay = 300_000) // 5 minutes
    @SchedulerLock(name = "paymentReconciliation", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void reconcileStalePayments() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusMinutes(30);
        List<Payment> stalePayments = paymentRepository.findStaleInitiatedPayments(cutoff);
        if (stalePayments.isEmpty()) return;
        if (stalePayments.size() > batchSize) {
            log.warn("Payment reconciliation: {} stale rows, processing first {} this pass",
                stalePayments.size(), batchSize);
            stalePayments = stalePayments.subList(0, batchSize);
        }

        int failed = 0;
        int gatewayPaid = 0;
        int unknown = 0;
        for (Payment payment : stalePayments) {
            String orderId = payment.getGatewayOrderId();

            // Intent rows that never got a provider order attached (crash
            // between reserve and attach): no callback can ever reference
            // them, so failing them is unconditionally safe.
            if (orderId == null || orderId.isBlank()) {
                if (paymentService.markStaleInitiatedFailed(payment.getId(),
                        "Reconciliation: initiation intent never received a gateway order (>30 min)")) {
                    failed++;
                }
                continue;
            }

            // Simulated / admin-synthetic orders ("ORD-", "CASH-ORD-", …) have
            // no provider side at all — never send them to Razorpay (they'd
            // 400 and pollute the ambiguity metrics). Stale means abandoned.
            if (!orderId.startsWith("order_")) {
                if (paymentService.markStaleInitiatedFailed(payment.getId(),
                        "Reconciliation: payment not completed within 30 minutes (no gateway order)")) {
                    failed++;
                }
                continue;
            }

            // Provider call OUTSIDE any transaction.
            RazorpayGatewayClient.OrderStatusLookup lookup =
                razorpayGatewayClient.fetchOrderStatusAuthoritative(orderId);

            if (!lookup.authoritative()) {
                // PAY-007: unknown is NOT failure. Leave INITIATED; guard 2 in
                // initiation keeps returning this same payment, so checkout is
                // NOT reopened into a second order while the first can capture.
                unknown++;
                if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(now.minusHours(24))) {
                    log.error("RECONCILIATION_STUCK: payment {} (order {}) unresolved for >24h — provider "
                        + "unreachable on every pass. Ops must verify manually in the Razorpay dashboard.",
                        payment.getTransactionId(), orderId);
                }
                continue;
            }

            if ("paid".equalsIgnoreCase(lookup.status())) {
                // Gateway captured but callback never arrived — flag for manual investigation
                log.warn("RECONCILIATION MISMATCH: payment {} (order {}) is PAID at gateway but INITIATED locally. "
                    + "Flagging for manual review — possible missed callback.",
                    payment.getTransactionId(), orderId);
                if (paymentService.flagGatewayPaidMismatch(payment.getId())) gatewayPaid++;
                continue;
            }

            // "attempted" means a payment attempt exists at the provider and a
            // delayed capture/callback is still possible inside the 24 h
            // callback window — do not fail it yet (a FAILED row would reopen
            // checkout and invite a duplicate charge).
            if ("attempted".equalsIgnoreCase(lookup.status())
                    && payment.getCreatedAt() != null
                    && payment.getCreatedAt().isAfter(now.minusHours(24))) {
                unknown++;
                continue;
            }

            // Authoritative not-paid ("created", aged "attempted", "not_found")
            // → abandoned; safe to fail.
            if (paymentService.markStaleInitiatedFailed(payment.getId(),
                    "Reconciliation: payment not completed within 30 minutes (gateway status: "
                        + lookup.status() + ")")) {
                failed++;
            }
        }

        if (failed > 0 || gatewayPaid > 0 || unknown > 0) {
            log.info("Payment reconciliation: {} marked FAILED, {} flagged gateway-paid (needs review), "
                + "{} left INITIATED (provider unknown / capture still possible)",
                failed, gatewayPaid, unknown);
        }
    }

    /**
     * Settles in-flight gateway refunds ({@code RefundStatus.PROCESSING}) whose
     * {@code refund.processed}/{@code refund.failed} webhook never arrived, and
     * completes stranded refund INTENTS ({@code RefundStatus.INITIATED}) whose
     * provider leg was ambiguous or never ran (PAY-006). Intents are resolved
     * receipt-first: the provider is asked whether the refund already exists
     * before anything new is created — at-most-once money movement.
     */
    @Scheduled(fixedDelay = 600_000) // 10 minutes
    @SchedulerLock(name = "refundSettlementReconciliation", lockAtLeastFor = "30s", lockAtMostFor = "10m")
    public void reconcilePendingRefunds() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(15);

        // 1) PROCESSING rows: accepted by the gateway, waiting for settlement.
        List<com.skbingegalaxy.payment.entity.Refund> pending = refundRepository
            .findByRefundStatusAndCreatedAtBefore(
                com.skbingegalaxy.payment.entity.RefundStatus.PROCESSING, cutoff);
        int settled = 0;
        for (com.skbingegalaxy.payment.entity.Refund refund : pending) {
            String gatewayRefundId = refund.getGatewayRefundId();
            if (gatewayRefundId == null || !gatewayRefundId.startsWith("rfnd_")) continue;
            try {
                String status = razorpayGatewayClient.fetchRefundStatus(gatewayRefundId);
                if (status == null) continue;
                String result = paymentService.settleRefundFromGateway(
                    gatewayRefundId, status, "reconciliation");
                if ("refund_settled".equals(result) || "refund_failed".equals(result)) settled++;
            } catch (Exception e) {
                log.warn("Refund reconciliation failed for {}: {}", gatewayRefundId, e.getMessage());
            }
        }

        // 2) Stranded INITIATED intents (crash/timeout during the provider leg).
        List<com.skbingegalaxy.payment.entity.Refund> strandedIntents = refundRepository
            .findByRefundStatusAndCreatedAtBefore(
                com.skbingegalaxy.payment.entity.RefundStatus.INITIATED, cutoff);
        int recovered = 0;
        for (com.skbingegalaxy.payment.entity.Refund intent : strandedIntents) {
            try {
                var dto = paymentService.processReservedRefund(intent.getId(), true);
                if (dto.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.INITIATED) {
                    recovered++;
                }
            } catch (Exception e) {
                log.warn("Refund intent {} recovery failed: {}", intent.getId(), e.getMessage());
            }
        }

        if (!pending.isEmpty() || !strandedIntents.isEmpty()) {
            log.info("Refund reconciliation: {} PROCESSING checked ({} settled), {} stranded intents ({} resolved)",
                pending.size(), settled, strandedIntents.size(), recovered);
        }
    }

    /**
     * Daily ledger reconciliation: for every SUCCESS payment recorded yesterday,
     * verify with Razorpay that the gateway order/payment was actually captured.
     *
     * Discrepancies are logged as ERRORs and flagged in PaymentStatusHistory for
     * ops review. This catches:
     * - Callback spoofing (we recorded SUCCESS but gateway never captured)
     * - Settled payments we never received a callback for (revenue leakage)
     *
     * Runs at 03:00 daily. ShedLock prevents duplicate runs across replicas.
     * No batch transaction: provider calls run bare and each mismatch flag
     * commits on its own (REL-002).
     */
    @Scheduled(cron = "${app.payment.settlement-reconciliation-cron:0 0 3 * * *}",
               zone  = "${app.payment.settlement-reconciliation-zone:Asia/Kolkata}")
    @SchedulerLock(name = "dailySettlementReconciliation", lockAtLeastFor = "1m", lockAtMostFor = "20m")
    public void reconcileDailySettlement() {
        ZoneId settlementZone = ZoneId.of(settlementZoneStr);
        LocalDate yesterday = LocalDate.now(settlementZone).minusDays(1);
        LocalDateTime from = LocalDateTime.of(yesterday, LocalTime.MIDNIGHT);
        LocalDateTime to   = LocalDateTime.of(yesterday, LocalTime.MAX);

        List<Payment> successPayments = paymentRepository.findSuccessPaymentsInWindow(from, to);
        if (successPayments.isEmpty()) {
            log.info("Daily settlement reconciliation: no SUCCESS payments for {} — nothing to verify", yesterday);
            return;
        }

        log.info("Daily settlement reconciliation: verifying {} SUCCESS payment(s) for {}", successPayments.size(), yesterday);

        int verified = 0;
        int mismatch = 0;
        int unverifiable = 0;
        BigDecimal verifiedTotal = BigDecimal.ZERO;

        for (Payment payment : successPayments) {
            String orderId = payment.getGatewayOrderId();
            // Cash / admin-recorded / simulated payments have synthetic order
            // ids ("CASH-ORD-", "ADM-ORD-", "ORD-") — there is nothing at the
            // provider to verify, and querying would 400 (REL-002).
            if (orderId == null || orderId.isBlank() || !orderId.startsWith("order_")) {
                verified++;
                verifiedTotal = verifiedTotal.add(payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO);
                continue;
            }
            try {
                RazorpayGatewayClient.OrderStatusLookup lookup =
                    razorpayGatewayClient.fetchOrderStatusAuthoritative(orderId);
                if (!lookup.authoritative()) {
                    // Transport failure is NOT a settlement mismatch (PAY-007)
                    // — it would page ops falsely on every provider blip.
                    unverifiable++;
                    continue;
                }
                if (!lookup.paid()) {
                    log.error("SETTLEMENT_MISMATCH: payment {} (booking {}, order {}) recorded as SUCCESS locally "
                        + "but Razorpay reports '{}'. Possible fraud or callback spoofing.",
                        payment.getTransactionId(), payment.getBookingRef(),
                        orderId, lookup.status());
                    statusHistoryRepository.save(PaymentStatusHistory.builder()
                        .paymentId(payment.getId())
                        .bookingRef(payment.getBookingRef())
                        .fromStatus(PaymentStatus.SUCCESS)
                        .toStatus(PaymentStatus.SUCCESS)
                        .reason("SETTLEMENT_MISMATCH: local=SUCCESS but Razorpay='" + lookup.status()
                            + "'. Manual investigation required.")
                        .build());
                    mismatch++;
                } else {
                    verified++;
                    verifiedTotal = verifiedTotal.add(payment.getAmount() != null ? payment.getAmount() : BigDecimal.ZERO);
                }
            } catch (Exception e) {
                log.warn("Settlement reconciliation: could not verify payment {} via Razorpay: {}",
                    payment.getTransactionId(), e.getMessage());
                unverifiable++;
            }
        }

        log.info("Daily settlement reconciliation for {}: verified={} (₹{}), mismatches={}, unverifiable={}",
            yesterday, verified, verifiedTotal.toPlainString(), mismatch, unverifiable);
        if (mismatch > 0) {
            log.error("SETTLEMENT_ALERT: {} payment(s) on {} have gateway/internal discrepancies — ops review required",
                mismatch, yesterday);
        }
    }
}
