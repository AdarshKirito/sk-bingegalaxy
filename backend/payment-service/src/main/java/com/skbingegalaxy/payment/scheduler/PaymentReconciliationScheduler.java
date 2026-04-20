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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reconciliation job: finds INITIATED payments older than 30 minutes,
 * checks Razorpay gateway for actual order status, and marks them accordingly.
 * Runs every 5 minutes with ShedLock to prevent duplicate processing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository statusHistoryRepository;
    private final RazorpayGatewayClient razorpayGatewayClient;

    @Scheduled(fixedDelay = 300_000) // 5 minutes
    @SchedulerLock(name = "paymentReconciliation", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    @Transactional
    public void reconcileStalePayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Payment> stalePayments = paymentRepository.findStaleInitiatedPayments(cutoff);

        if (stalePayments.isEmpty()) return;

        int reconciled = 0;
        int gatewayPaid = 0;
        for (Payment payment : stalePayments) {
            // Check Razorpay gateway for actual order status before marking FAILED
            String gatewayStatus = razorpayGatewayClient.fetchOrderStatus(payment.getGatewayOrderId());

            if ("paid".equalsIgnoreCase(gatewayStatus)) {
                // Gateway captured but callback never arrived — flag for manual investigation
                log.warn("RECONCILIATION MISMATCH: payment {} (order {}) is PAID at gateway but INITIATED locally. "
                    + "Flagging for manual review — possible missed callback.",
                    payment.getTransactionId(), payment.getGatewayOrderId());
                payment.setFailureReason("Reconciliation: gateway reports PAID but callback was missed — needs manual review");
                paymentRepository.save(payment);

                statusHistoryRepository.save(PaymentStatusHistory.builder()
                    .paymentId(payment.getId())
                    .bookingRef(payment.getBookingRef())
                    .fromStatus(payment.getStatus())
                    .toStatus(payment.getStatus()) // status unchanged — needs human intervention
                    .reason("Reconciliation: Razorpay order PAID but callback missed. Manual review required.")
                    .build());
                gatewayPaid++;
                continue;
            }

            // Gateway status is not "paid" — safe to mark as FAILED (abandoned/timed out)
            PaymentStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Reconciliation: payment not completed within 30 minutes"
                + (gatewayStatus != null ? " (gateway status: " + gatewayStatus + ")" : ""));
            paymentRepository.save(payment);

            statusHistoryRepository.save(PaymentStatusHistory.builder()
                .paymentId(payment.getId())
                .bookingRef(payment.getBookingRef())
                .fromStatus(oldStatus)
                .toStatus(PaymentStatus.FAILED)
                .reason("Reconciliation: stale INITIATED payment (>30 min)"
                    + (gatewayStatus != null ? " — gateway status: " + gatewayStatus : " — gateway unreachable"))
                .build());

            reconciled++;
        }

        if (reconciled > 0 || gatewayPaid > 0) {
            log.info("Payment reconciliation: marked {} stale payments as FAILED, {} flagged as gateway-paid (needs review)",
                reconciled, gatewayPaid);
        }
    }
}
