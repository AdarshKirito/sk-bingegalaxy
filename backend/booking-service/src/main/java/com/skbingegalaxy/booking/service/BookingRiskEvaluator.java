package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.BookingRiskFlagDto;
import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingRiskFlag;
import com.skbingegalaxy.booking.entity.BookingRiskFlag.RuleCode;
import com.skbingegalaxy.booking.entity.BookingRiskFlag.Severity;
import com.skbingegalaxy.booking.entity.BookingRiskFlag.Source;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.BookingRiskFlagRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Booking-level risk / abuse evaluation.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>{@link #evaluate(Booking)}</b> — runs at booking creation, applies a
 *       handful of cheap heuristics, and persists a {@link BookingRiskFlag}
 *       row for each rule that fires. Best-effort: any exception is swallowed
 *       so a flaky risk rule never blocks booking creation. The flag is
 *       <em>informational</em> — it never prevents the booking.</li>
 *   <li><b>CRUD for the operator queue</b> — list / acknowledge / manual-flag.</li>
 * </ol>
 *
 * <p>The rules are deliberately conservative: they err on the side of NOT
 * flagging legitimate customers (false positives erode operator trust faster
 * than missed signals). All thresholds are tunable via {@code app.risk.*}
 * properties so ops can adjust without a code change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingRiskEvaluator {

    private final BookingRiskFlagRepository riskFlagRepository;
    private final BookingRepository bookingRepository;

    @Value("${app.risk.shared-phone-threshold:3}")
    private int sharedPhoneThreshold;

    @Value("${app.risk.shared-email-threshold:3}")
    private int sharedEmailThreshold;

    @Value("${app.risk.repeated-cancels-threshold:5}")
    private int repeatedCancelsThreshold;

    @Value("${app.risk.repeated-no-shows-threshold:3}")
    private int repeatedNoShowsThreshold;

    @Value("${app.risk.high-value-threshold-rupees:50000}")
    private BigDecimal highValueThreshold;

    @Value("${app.risk.rapid-burst-window-minutes:30}")
    private int rapidBurstWindowMinutes;

    @Value("${app.risk.rapid-burst-threshold:4}")
    private int rapidBurstThreshold;

    @Value("${app.risk.cross-binge-window-days:30}")
    private int crossBingeWindowDays;

    // ── Evaluator ────────────────────────────────────────────────────────────

    /**
     * Run all rules against a freshly-created booking and persist a flag for
     * each match. Runs in a separate transaction so a flag write never rolls
     * back the booking creation it was triggered from.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(Booking booking) {
        if (booking == null || booking.getBookingRef() == null) return;
        try {
            // Rule 1: shared phone number across many accounts.
            if (booking.getCustomerPhone() != null && !booking.getCustomerPhone().isBlank()) {
                long distinctCustomers = bookingRepository.countDistinctCustomersByPhone(booking.getCustomerPhone());
                if (distinctCustomers >= sharedPhoneThreshold) {
                    record(booking, RuleCode.SHARED_PHONE_MULTIPLE_ACCOUNTS, Severity.MEDIUM,
                        "Phone " + maskPhone(booking.getCustomerPhone()) + " is associated with "
                            + distinctCustomers + " distinct customer accounts (threshold "
                            + sharedPhoneThreshold + ").",
                        "{\"phone\":\"" + booking.getCustomerPhone() + "\",\"distinctCustomers\":" + distinctCustomers + "}");
                }
            }

            // Rule 2: shared email across many accounts.
            if (booking.getCustomerEmail() != null && !booking.getCustomerEmail().isBlank()) {
                long distinctCustomers = bookingRepository.countDistinctCustomersByEmail(booking.getCustomerEmail());
                if (distinctCustomers >= sharedEmailThreshold) {
                    record(booking, RuleCode.SHARED_EMAIL_MULTIPLE_ACCOUNTS, Severity.MEDIUM,
                        "Email " + booking.getCustomerEmail() + " is associated with "
                            + distinctCustomers + " distinct customer accounts.",
                        "{\"email\":\"" + booking.getCustomerEmail() + "\",\"distinctCustomers\":" + distinctCustomers + "}");
                }
            }

            // Rule 3: repeated customer cancellations across binges.
            LocalDateTime windowStart = LocalDateTime.now().minusDays(crossBingeWindowDays);
            long cancels = bookingRepository.countCustomerCancelsAcrossBingesSince(booking.getCustomerId(), windowStart);
            if (cancels >= repeatedCancelsThreshold) {
                record(booking, RuleCode.REPEATED_PENDING_CANCELLATIONS, Severity.HIGH,
                    "Customer cancelled " + cancels + " bookings across binges in the last "
                        + crossBingeWindowDays + " days.",
                    "{\"cancels\":" + cancels + ",\"windowDays\":" + crossBingeWindowDays + "}");
            }

            // Rule 4: repeated no-shows across binges.
            long noShows = bookingRepository.countNoShowsAcrossBingesSince(booking.getCustomerId(), windowStart);
            if (noShows >= repeatedNoShowsThreshold) {
                record(booking, RuleCode.REPEATED_NO_SHOWS, Severity.HIGH,
                    "Customer was marked NO_SHOW " + noShows + " times across binges in the last "
                        + crossBingeWindowDays + " days.",
                    "{\"noShows\":" + noShows + ",\"windowDays\":" + crossBingeWindowDays + "}");
            }

            // Rule 5: unusually high booking value.
            if (booking.getTotalAmount() != null
                && booking.getTotalAmount().compareTo(highValueThreshold) >= 0) {
                record(booking, RuleCode.UNUSUALLY_HIGH_VALUE, Severity.LOW,
                    "Total amount ₹" + booking.getTotalAmount().toPlainString()
                        + " ≥ threshold ₹" + highValueThreshold.toPlainString(),
                    "{\"totalAmount\":" + booking.getTotalAmount().toPlainString() + "}");
            }

            // Rule 6: rapid rebooking burst.
            LocalDateTime burstStart = LocalDateTime.now().minusMinutes(rapidBurstWindowMinutes);
            long recent = bookingRepository.countByCustomerIdCreatedSince(booking.getCustomerId(), burstStart);
            if (recent >= rapidBurstThreshold) {
                record(booking, RuleCode.RAPID_REBOOKING_BURST, Severity.MEDIUM,
                    recent + " bookings created in the last " + rapidBurstWindowMinutes + " minutes.",
                    "{\"recent\":" + recent + ",\"windowMinutes\":" + rapidBurstWindowMinutes + "}");
            }
        } catch (Exception e) {
            log.warn("Risk evaluation failed for booking {}: {}", booking.getBookingRef(), e.getMessage());
        }
    }

    private void record(Booking booking, RuleCode rule, Severity severity, String reason, String evidenceJson) {
        if (riskFlagRepository.existsOpenForBookingAndRule(booking.getBookingRef(), rule)) {
            // Idempotent: don't pile up duplicate flags for the same finding.
            return;
        }
        BookingRiskFlag flag = BookingRiskFlag.builder()
            .bookingRef(booking.getBookingRef())
            .bingeId(booking.getBingeId())
            .customerId(booking.getCustomerId())
            .ruleCode(rule)
            .severity(severity)
            .source(Source.SYSTEM)
            .reason(reason)
            .evidence(evidenceJson)
            .build();
        riskFlagRepository.save(flag);
        log.info("risk-flag created bookingRef={} rule={} severity={} reason={}",
            booking.getBookingRef(), rule, severity, reason);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return phone;
        return "***" + phone.substring(Math.max(0, phone.length() - 4));
    }

    // ── Operator API ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BookingRiskFlagDto> listForBinge(Long bingeId, boolean openOnly, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<BookingRiskFlag> rows = openOnly
            ? riskFlagRepository.findByBingeIdAndAcknowledgedFalseOrderBySeverityDescCreatedAtDesc(bingeId, pageable)
            : riskFlagRepository.findByBingeIdOrderByCreatedAtDesc(bingeId, pageable);
        return rows.map(BookingRiskFlagDto::from);
    }

    @Transactional(readOnly = true)
    public List<BookingRiskFlagDto> listForBooking(String bookingRef) {
        return riskFlagRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef)
            .stream().map(BookingRiskFlagDto::from).toList();
    }

    @Transactional
    public BookingRiskFlagDto acknowledge(Long flagId, Long adminId, String note) {
        BookingRiskFlag flag = riskFlagRepository.findById(flagId)
            .orElseThrow(() -> new ResourceNotFoundException("BookingRiskFlag", "id", flagId));
        if (flag.isAcknowledged()) {
            throw new BusinessException("Risk flag is already acknowledged");
        }
        flag.setAcknowledged(true);
        flag.setAcknowledgedByAdminId(adminId);
        flag.setAcknowledgedAt(LocalDateTime.now());
        flag.setAcknowledgedNote(note);
        return BookingRiskFlagDto.from(riskFlagRepository.save(flag));
    }

    @Transactional
    public BookingRiskFlagDto createManual(String bookingRef, Severity severity, String reason, Long adminId) {
        Booking b = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "bookingRef", bookingRef));
        if (severity == null) severity = Severity.MEDIUM;
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("reason is required for manual risk flag");
        }
        BookingRiskFlag flag = BookingRiskFlag.builder()
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            .customerId(b.getCustomerId())
            .ruleCode(RuleCode.MANUAL)
            .severity(severity)
            .source(Source.ADMIN)
            .reason(reason)
            .createdByAdminId(adminId)
            .build();
        return BookingRiskFlagDto.from(riskFlagRepository.save(flag));
    }
}
