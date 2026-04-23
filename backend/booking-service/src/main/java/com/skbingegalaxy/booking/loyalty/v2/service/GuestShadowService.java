package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.TierEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyGuestShadow;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyProgram;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyGuestShadowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty v2 — Guest Shadow service.
 *
 * <p>A guest who hasn't signed up still earns credits against a
 * <b>hashed</b> identifier (email SHA-256 / phone SHA-256 / device
 * fingerprint SHA-256).  On signup within the program's
 * {@code retroactive_credit_days} window, those pending credits merge
 * into the fresh membership — the customer's very first wallet view
 * shows <i>earned</i> points, not zero.
 *
 * <p><b>Privacy:</b> we never store cleartext email/phone.  The hash
 * is salted with the program code to reduce rainbow-table risk — two
 * programs with the same email see different hashes.
 *
 * <p><b>Expiry:</b> a nightly job deletes guest shadows whose
 * {@code expires_at} has passed (we honor the program's retroactive
 * window precisely — credits earned 61 days ago with a 60-day window
 * are gone at midnight on day 61).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GuestShadowService {

    private final LoyaltyConfigService configService;
    private final EnrollmentService enrollmentService;
    private final PointsWalletService walletService;
    private final TierEngine tierEngine;
    private final LoyaltyGuestShadowRepository guestShadowRepository;

    // ─────────────────────────────────────────────────────────────────────
    // ACCRUAL: record a guest booking that earned points
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Record pending points/QC for a guest booking.  Matches or creates
     * a shadow row keyed by email-hash (preferred) or phone-hash.
     * Expiry is set to {@code now + program.retroactiveCreditDays}.
     */
    @Transactional
    public LoyaltyGuestShadow accrueForGuest(String email, String phone, String deviceFingerprint,
                                             long pointsAccrued, long qcAccrued,
                                             String bookingRef) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        String emailH  = hashWithSalt(email,  program.getCode());
        String phoneH  = hashWithSalt(phone,  program.getCode());
        String deviceH = hashWithSalt(deviceFingerprint, program.getCode());

        LoyaltyGuestShadow shadow = findUnmerged(emailH, phoneH).orElse(null);
        if (shadow == null) {
            shadow = LoyaltyGuestShadow.builder()
                    .emailHash(emailH)
                    .phoneHash(phoneH)
                    .deviceFingerprintHash(deviceH)
                    .pendingPoints(0L)
                    .pendingQualifyingCredits(0L)
                    .expiresAt(LocalDateTime.now().plusDays(
                            Math.max(program.getRetroactiveCreditDays(), 1)))
                    .build();
        }
        shadow.setPendingPoints(shadow.getPendingPoints() + pointsAccrued);
        shadow.setPendingQualifyingCredits(shadow.getPendingQualifyingCredits() + qcAccrued);
        shadow.setLastBookingRef(bookingRef);
        // If we had only a phone before and now we have an email, enrich.
        if (shadow.getEmailHash() == null && emailH != null) shadow.setEmailHash(emailH);
        if (shadow.getPhoneHash() == null && phoneH != null) shadow.setPhoneHash(phoneH);
        if (shadow.getDeviceFingerprintHash() == null && deviceH != null) shadow.setDeviceFingerprintHash(deviceH);

        LoyaltyGuestShadow saved = guestShadowRepository.save(shadow);
        log.info("[loyalty-v2] guest shadow accrual: +{} pts +{} QC booking={} shadow={} expires={}",
                pointsAccrued, qcAccrued, bookingRef, saved.getId(), saved.getExpiresAt());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────
    // MERGE: called on signup
    // ─────────────────────────────────────────────────────────────────────

    /**
     * On signup, look up every unmerged shadow matching the customer's
     * email/phone hashes, credit the cumulative pending points into
     * their fresh wallet, and mark the shadows merged.
     *
     * @return total points credited (0 if no shadow matched).
     */
    @Transactional
    public long mergeOnSignup(Long customerId, String email, String phone) {
        LoyaltyProgram program = configService.requireDefaultProgram();
        String emailH = hashWithSalt(email, program.getCode());
        String phoneH = hashWithSalt(phone, program.getCode());

        List<LoyaltyGuestShadow> shadows = guestShadowRepository
                .findUnmergedByIdentityHash(emailH, phoneH);
        if (shadows.isEmpty()) return 0L;

        LoyaltyMembership membership = enrollmentService.ensureEnrolledForBooking(customerId);
        LocalDateTime now = LocalDateTime.now();
        long totalCredited = 0L;

        for (LoyaltyGuestShadow s : shadows) {
            if (s.getExpiresAt().isBefore(now)) continue;                   // expired since last sweep

            if (s.getPendingPoints() > 0) {
                walletService.credit(new PointsWalletService.CreditRequest(
                        membership.getId(),
                        s.getPendingPoints(),
                        LoyaltyV2Constants.LEDGER_BONUS,
                        "GUEST_MERGE",
                        "shadow=" + s.getId(),
                        null,
                        s.getLastBookingRef(),
                        now,
                        now.plusDays(program.getPointsExpiryDays()),
                        "guest-merge:shadow=" + s.getId(),
                        "shadow=" + s.getId(),
                        "GUEST_MERGE",
                        "Retroactive credit from guest activity (" + s.getPendingPoints() + " pts)",
                        null,
                        "SYSTEM"
                ));
                totalCredited += s.getPendingPoints();
            }

            s.setMergedMembershipId(membership.getId());
            s.setMergedAt(now);
            s.setPendingPoints(0L);
            s.setPendingQualifyingCredits(0L);
            guestShadowRepository.save(s);
        }

        if (totalCredited > 0) {
            tierEngine.recalculateTier(membership.getId(), now);
            log.info("[loyalty-v2] guest merge: customer={} membership={} +{} pts from {} shadow(s)",
                    customerId, membership.getId(), totalCredited, shadows.size());
        }
        return totalCredited;
    }

    // ─────────────────────────────────────────────────────────────────────
    // SCHEDULED: purge expired shadows
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 45 2 * * *")                    // 02:45 UTC daily
    @SchedulerLock(name = "loyaltyV2GuestShadowExpiry",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runExpiryJob() {
        purgeExpiredAsOf(LocalDateTime.now());
    }

    @Transactional
    public int purgeExpiredAsOf(LocalDateTime cutoff) {
        List<LoyaltyGuestShadow> expired = guestShadowRepository.findExpired(cutoff);
        if (expired.isEmpty()) return 0;
        guestShadowRepository.deleteAll(expired);
        log.info("[loyalty-v2] purged {} expired guest shadow(s) at {}", expired.size(), cutoff);
        return expired.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    private Optional<LoyaltyGuestShadow> findUnmerged(String emailH, String phoneH) {
        List<LoyaltyGuestShadow> list = guestShadowRepository.findUnmergedByIdentityHash(emailH, phoneH);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * SHA-256 of (salt || ":" || value).  Returns null for null/blank
     * input so shadow rows can be partially-identified.
     *
     * <p>The salt is the program code — it prevents two programs with
     * the same email from sharing shadow rows and also makes rainbow-
     * table attacks specific to a given program.
     */
    static String hashWithSalt(String value, String salt) {
        if (value == null || value.isBlank()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + ":").getBytes(StandardCharsets.UTF_8));
            byte[] out = md.digest(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
