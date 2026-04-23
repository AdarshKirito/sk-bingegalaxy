package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.engine.TierEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty v2 — Status-Match service.
 *
 * <p>A customer holding status in a competitor program (Bonvoy
 * Titanium, Hyatt Globalist, etc.) submits proof; upon admin approval
 * they're promoted into a <b>challenge</b> at the requested tier.
 * During the challenge window (program-configured, e.g. 90 days),
 * the member enjoys the matched tier's perks.  If they hit the
 * qualifying-credit target before the window closes, they retain the
 * tier through normal validity; if not, they soft-land back at their
 * natural tier.
 *
 * <p>Lifecycle: {@code PENDING} → {@code APPROVED} (admin reviews) →
 * {@code CHALLENGE_ACTIVE} (tier grant applied) →
 * {@code CHALLENGE_EXPIRED} (window closed, auto-reconcile).
 * The {@code REJECTED} branch is a terminal state with admin notes.
 *
 * <p>Idempotent at every boundary: a duplicate PENDING submit returns
 * the existing request; approving an already-APPROVED request is a
 * no-op; approving a REJECTED one is explicitly refused so admins
 * can't accidentally un-reject.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StatusMatchService {

    private final LoyaltyConfigService configService;
    private final TierEngine tierEngine;

    private final LoyaltyMembershipRepository membershipRepository;
    private final LoyaltyStatusMatchRequestRepository statusMatchRepository;
    private final LoyaltyMembershipEventRepository membershipEventRepository;

    // ─────────────────────────────────────────────────────────────────────
    // CUSTOMER: submit a request
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Submit a new status-match request for the given membership.
     * Refuses if there is already a PENDING or CHALLENGE_ACTIVE request
     * for that membership — avoids double-dipping / gaming.
     */
    @Transactional
    public LoyaltyStatusMatchRequest submit(Long membershipId,
                                            String competitorProgram,
                                            String competitorTier,
                                            String requestedTierCode,
                                            String proofUrl,
                                            String proofPayloadJson) {
        LoyaltyMembership m = requireMembership(membershipId);

        // Guard — no parallel active requests.
        List<LoyaltyStatusMatchRequest> active = statusMatchRepository
                .findByMembershipIdAndStatusIn(membershipId,
                        List.of("PENDING", "CHALLENGE_ACTIVE"));
        if (!active.isEmpty()) {
            log.info("[loyalty-v2] status-match already active for membership {}: {}",
                    membershipId, active.get(0).getId());
            return active.get(0);
        }

        LoyaltyStatusMatchRequest saved = statusMatchRepository.save(
                LoyaltyStatusMatchRequest.builder()
                        .tenantId(m.getTenantId())
                        .membershipId(membershipId)
                        .competitorProgramName(competitorProgram)
                        .competitorTierName(competitorTier)
                        .requestedTierCode(requestedTierCode)
                        .proofUrl(proofUrl)
                        .proofPayloadJson(proofPayloadJson)
                        .status("PENDING")
                        .build()
        );
        log.info("[loyalty-v2] status-match submitted: request={} membership={} competitor='{}' tier={}",
                saved.getId(), membershipId, competitorProgram, requestedTierCode);
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADMIN: approve / reject
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public LoyaltyStatusMatchRequest approve(Long requestId, Long adminUserId, String reviewNotes,
                                             int challengeWindowDays) {
        LoyaltyStatusMatchRequest req = requireRequest(requestId);
        if ("REJECTED".equals(req.getStatus())) {
            throw new IllegalStateException("request " + requestId + " is REJECTED — cannot approve");
        }
        if ("APPROVED".equals(req.getStatus()) || "CHALLENGE_ACTIVE".equals(req.getStatus())) {
            return req;                                                     // idempotent
        }

        LoyaltyMembership m = requireMembership(req.getMembershipId());
        LoyaltyProgram program = configService.requireDefaultProgram();
        LoyaltyTierDefinition target = configService.activeTier(program.getId(), req.getRequestedTierCode(), LocalDateTime.now())
                .orElseThrow(() -> new IllegalArgumentException(
                        "target tier " + req.getRequestedTierCode() + " is not active"));

        // Apply the challenge tier immediately.  tierEffectiveUntil is
        // explicitly short — the challenge window — to force a soft
        // landing after expiry if the member doesn't qualify organically.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime challengeExpiry = now.plusDays(challengeWindowDays);

        String fromTier = m.getCurrentTierCode();
        m.setCurrentTierCode(target.getCode());
        m.setTierEffectiveFrom(now);
        m.setTierEffectiveUntil(challengeExpiry);
        m.setSoftLandingEligible(true);
        membershipRepository.save(m);

        membershipEventRepository.save(
                LoyaltyMembershipEvent.builder()
                        .tenantId(m.getTenantId())
                        .membershipId(m.getId())
                        .eventType("STATUS_MATCH_GRANTED")
                        .fromValueJson("{\"tier\":\"" + fromTier + "\"}")
                        .toValueJson("{\"tier\":\"" + target.getCode()
                                + "\",\"competitorProgram\":\"" + req.getCompetitorProgramName()
                                + "\",\"challengeExpiresAt\":\"" + challengeExpiry + "\"}")
                        .triggeredBy("ADMIN")
                        .build()
        );

        req.setStatus("CHALLENGE_ACTIVE");
        req.setReviewedByAdminId(adminUserId);
        req.setReviewedAt(now);
        req.setReviewNotes(reviewNotes);
        req.setChallengeExpiresAt(challengeExpiry);
        LoyaltyStatusMatchRequest saved = statusMatchRepository.save(req);
        log.info("[loyalty-v2] status-match APPROVED: request={} membership={} {} → {} challengeUntil={}",
                requestId, m.getId(), fromTier, target.getCode(), challengeExpiry);
        return saved;
    }

    @Transactional
    public LoyaltyStatusMatchRequest reject(Long requestId, Long adminUserId, String reviewNotes) {
        LoyaltyStatusMatchRequest req = requireRequest(requestId);
        if (!"PENDING".equals(req.getStatus())) {
            return req;                                                     // idempotent on terminal states
        }
        req.setStatus("REJECTED");
        req.setReviewedByAdminId(adminUserId);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewNotes(reviewNotes);
        LoyaltyStatusMatchRequest saved = statusMatchRepository.save(req);
        log.info("[loyalty-v2] status-match REJECTED: request={} by admin {} notes='{}'",
                requestId, adminUserId, reviewNotes);
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────
    // SCHEDULED: expire stale challenges
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 30 2 * * *")                    // 02:30 UTC daily
    @SchedulerLock(name = "loyaltyV2StatusMatchExpiry",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runChallengeExpiryJob() {
        expireChallengesAsOf(LocalDateTime.now());
    }

    public int expireChallengesAsOf(LocalDateTime cutoff) {
        List<LoyaltyStatusMatchRequest> due = statusMatchRepository
                .findByStatusAndChallengeExpiresAtBefore("CHALLENGE_ACTIVE", cutoff);
        if (due.isEmpty()) return 0;
        log.info("[loyalty-v2] expiring {} status-match challenge(s) at {}", due.size(), cutoff);

        int expired = 0;
        for (LoyaltyStatusMatchRequest r : due) {
            try {
                expireOneChallenge(r.getId(), cutoff);
                expired++;
            } catch (Exception ex) {
                log.error("[loyalty-v2] failed to expire challenge {}: {}", r.getId(), ex.getMessage(), ex);
            }
        }
        return expired;
    }

    @Transactional
    protected void expireOneChallenge(Long requestId, LocalDateTime at) {
        LoyaltyStatusMatchRequest req = requireRequest(requestId);
        if (!"CHALLENGE_ACTIVE".equals(req.getStatus())) return;

        req.setStatus("CHALLENGE_EXPIRED");
        statusMatchRepository.save(req);

        // Force the tier engine to re-evaluate against organic qualifying
        // credits.  If the member hit the target during the challenge
        // window, TierEngine keeps them; if not, they soft-land.
        tierEngine.recalculateTier(req.getMembershipId(), at);
        log.info("[loyalty-v2] status-match challenge expired: request={} membership={}",
                requestId, req.getMembershipId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private LoyaltyMembership requireMembership(Long id) {
        return membershipRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("membership not found: " + id));
    }

    private LoyaltyStatusMatchRequest requireRequest(Long id) {
        return statusMatchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("status-match request not found: " + id));
    }

    @SuppressWarnings("unused")
    private void unusedReference() {
        // Forces the compiler to keep the Optional import should the
        // helpers evolve to return Optional-valued lookups.
        Optional<LoyaltyStatusMatchRequest> o = Optional.empty();
        if (o.isPresent()) log.debug("noop");
    }
}
