package com.skbingegalaxy.booking.loyalty.v2.engine;

import com.skbingegalaxy.booking.loyalty.v2.LoyaltyV2Constants;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Loyalty v2 — central points-wallet mutation service.
 *
 * <p>This is the ONLY component that mutates {@link LoyaltyPointsWallet},
 * {@link LoyaltyPointsLot}, and {@link LoyaltyLedgerEntry}.  All four of
 * the domain engines (Earn / Redeem / Expiry / Admin-Adjust) route through
 * its {@code credit*} / {@code debit*} methods, which guarantee:
 *
 * <ul>
 *   <li>a pessimistic row lock on the wallet for the duration of the
 *       enclosing transaction — serialises concurrent writes to a single
 *       customer;</li>
 *   <li>an idempotency check against the ledger before any mutation —
 *       retries are safe;</li>
 *   <li>a ledger row for every mutation, with signed {@code pointsDelta}
 *       that sums exactly to {@code currentBalance};</li>
 *   <li>the wallet's lifetime-counter (earned / redeemed / expired /
 *       adjusted) is kept in lock-step with the ledger.</li>
 * </ul>
 *
 * <p>Any service that bypasses this class and writes the tables directly
 * is a bug — enforce via code review and integration tests.
 *
 * <p><b>Thread-safety:</b> relies on {@code SELECT … FOR UPDATE}, so it
 * is safe across JVMs.  But the caller MUST be inside a {@code @Transactional}
 * method — the pessimistic lock only holds for the duration of the
 * enclosing tx.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PointsWalletService {

    private final LoyaltyPointsWalletRepository walletRepository;
    private final LoyaltyPointsLotRepository lotRepository;
    private final LoyaltyLedgerEntryRepository ledgerRepository;

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Credit points to the member's wallet.  Creates exactly one
     * {@link LoyaltyPointsLot} (FIFO bucket) and one {@link LoyaltyLedgerEntry}.
     *
     * @return the persisted ledger entry (contains the lot id for callers
     *         that want to correlate).  Returns the existing entry if this
     *         idempotency key was already processed.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public LoyaltyLedgerEntry credit(CreditRequest req) {
        validateCreditRequest(req);

        LoyaltyPointsWallet wallet = walletRepository.findByMembershipIdForUpdate(req.membershipId())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet not found for membership " + req.membershipId()));

        // Idempotency — retrieve existing row, do not double-write.
        if (ledgerRepository.existsByWalletIdAndEntryTypeAndIdempotencyKey(
                wallet.getId(), req.entryType(), req.idempotencyKey())) {
            log.info("[loyalty-v2] idempotent skip of credit ({}, {}, {}) for wallet {}",
                    req.entryType(), req.idempotencyKey(), req.points(), wallet.getId());
            return findExisting(wallet.getId(), req.entryType(), req.idempotencyKey());
        }

        LoyaltyPointsLot lot = LoyaltyPointsLot.builder()
                .tenantId(wallet.getTenantId())
                .walletId(wallet.getId())
                .bingeId(req.bingeId())
                .sourceType(req.sourceType())
                .sourceRef(req.sourceRef())
                .originalPoints(req.points())
                .remainingPoints(req.points())
                .earnedAt(req.at())
                .expiresAt(req.expiresAt())
                .build();
        lot = lotRepository.save(lot);

        LoyaltyLedgerEntry entry = LoyaltyLedgerEntry.builder()
                .tenantId(wallet.getTenantId())
                .walletId(wallet.getId())
                .entryType(req.entryType())
                .pointsDelta(req.points())
                .lotId(lot.getId())
                .bingeId(req.bingeId())
                .bookingRef(req.bookingRef())
                .actorId(req.actorId())
                .actorRole(req.actorRole())
                .reasonCode(req.reasonCode())
                .description(req.description())
                .correlationId(req.correlationId())
                .idempotencyKey(req.idempotencyKey())
                .build();
        entry = ledgerRepository.save(entry);

        wallet.setCurrentBalance(wallet.getCurrentBalance() + req.points());
        applyLifetimeCounter(wallet, req.entryType(), req.points());
        walletRepository.save(wallet);

        log.info("[loyalty-v2] CREDIT wallet={} type={} points={} lot={} booking={}",
                wallet.getId(), req.entryType(), req.points(), lot.getId(), req.bookingRef());
        return entry;
    }

    /**
     * Debit (consume) points FIFO across lots.
     *
     * <p>Writes ONE ledger entry per lot touched — this lets the customer
     * see exactly which lots were consumed for a given redemption and
     * makes reversal mechanically simple (compensating per-lot entries).
     *
     * @return the list of ledger entries written, in the order they were
     *         applied.  Empty list = idempotent no-op.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<LoyaltyLedgerEntry> debit(DebitRequest req) {
        validateDebitRequest(req);

        LoyaltyPointsWallet wallet = walletRepository.findByMembershipIdForUpdate(req.membershipId())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet not found for membership " + req.membershipId()));

        if (ledgerRepository.existsByWalletIdAndEntryTypeAndIdempotencyKey(
                wallet.getId(), req.entryType(), req.idempotencyKey())) {
            log.info("[loyalty-v2] idempotent skip of debit ({}, {}, {}) for wallet {}",
                    req.entryType(), req.idempotencyKey(), req.points(), wallet.getId());
            return ledgerRepository.findByBookingRef(req.bookingRef()); // best-effort for callers
        }

        if (wallet.getCurrentBalance() < req.points()) {
            throw new InsufficientPointsException(
                    "Wallet " + wallet.getId() + " balance " + wallet.getCurrentBalance()
                            + " < requested " + req.points());
        }

        List<LoyaltyPointsLot> fifo = lotRepository.findFifoOpen(wallet.getId());
        long remainingToConsume = req.points();
        List<LoyaltyLedgerEntry> written = new ArrayList<>();
        int lotIdx = 0;

        for (LoyaltyPointsLot lot : fifo) {
            if (remainingToConsume <= 0) break;

            long take = Math.min(remainingToConsume, lot.getRemainingPoints());
            if (take <= 0) continue;

            lot.setRemainingPoints(lot.getRemainingPoints() - take);
            lotRepository.save(lot);

            // Distinct idempotency key per lot so retries stay safe and
            // each row in the ledger has the guaranteed unique key.
            String perLotKey = req.idempotencyKey() + "#lot=" + lot.getId();
            LoyaltyLedgerEntry entry = LoyaltyLedgerEntry.builder()
                    .tenantId(wallet.getTenantId())
                    .walletId(wallet.getId())
                    .entryType(req.entryType())
                    .pointsDelta(-take)
                    .lotId(lot.getId())
                    .bingeId(req.bingeId())
                    .bookingRef(req.bookingRef())
                    .actorId(req.actorId())
                    .actorRole(req.actorRole())
                    .reasonCode(req.reasonCode())
                    .description(req.description()
                            + " (lot " + (++lotIdx) + "/" + fifo.size() + ")")
                    .correlationId(req.correlationId())
                    .idempotencyKey(perLotKey)
                    .build();
            written.add(ledgerRepository.save(entry));

            remainingToConsume -= take;
        }

        if (remainingToConsume > 0) {
            // Defensive — shouldn't happen if the balance check above was correct.
            throw new IllegalStateException(
                    "Wallet " + wallet.getId() + " FIFO exhausted with "
                            + remainingToConsume + " points still unconsumed");
        }

        // Aggregate idempotency marker — written last so the per-lot writes
        // landed first; gives a single row to key against on retries.
        LoyaltyLedgerEntry marker = LoyaltyLedgerEntry.builder()
                .tenantId(wallet.getTenantId())
                .walletId(wallet.getId())
                .entryType(req.entryType())
                .pointsDelta(0)           // zero — this is a marker, not a second debit
                .bingeId(req.bingeId())
                .bookingRef(req.bookingRef())
                .actorId(req.actorId())
                .actorRole(req.actorRole())
                .reasonCode("MARKER")
                .description("Debit marker aggregating " + written.size() + " lot(s)")
                .correlationId(req.correlationId())
                .idempotencyKey(req.idempotencyKey())
                .build();
        ledgerRepository.save(marker);

        wallet.setCurrentBalance(wallet.getCurrentBalance() - req.points());
        applyLifetimeCounter(wallet, req.entryType(), req.points());
        walletRepository.save(wallet);

        log.info("[loyalty-v2] DEBIT wallet={} type={} points={} lots={} booking={}",
                wallet.getId(), req.entryType(), req.points(), written.size(), req.bookingRef());
        return written;
    }

    /**
     * Expire a specific lot — used by the nightly ExpiryEngine.  Writes
     * exactly one EXPIRE ledger entry covering the lot's remaining
     * points.  Safe to call repeatedly for the same lot; idempotency key
     * keys on lot id.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public LoyaltyLedgerEntry expireLot(Long membershipId, Long lotId, LocalDateTime at) {
        LoyaltyPointsWallet wallet = walletRepository.findByMembershipIdForUpdate(membershipId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found for membership " + membershipId));

        LoyaltyPointsLot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new IllegalStateException("Lot " + lotId + " not found"));

        if (!lot.getWalletId().equals(wallet.getId())) {
            throw new IllegalStateException(
                    "Lot " + lotId + " does not belong to wallet " + wallet.getId());
        }
        if (lot.getRemainingPoints() <= 0) {
            return null; // already fully consumed / expired
        }

        String idempotencyKey = "expire:lot=" + lot.getId();
        if (ledgerRepository.existsByWalletIdAndEntryTypeAndIdempotencyKey(
                wallet.getId(), LoyaltyV2Constants.LEDGER_EXPIRE, idempotencyKey)) {
            return findExisting(wallet.getId(), LoyaltyV2Constants.LEDGER_EXPIRE, idempotencyKey);
        }

        long expired = lot.getRemainingPoints();

        lot.setRemainingPoints(0);
        lotRepository.save(lot);

        LoyaltyLedgerEntry entry = LoyaltyLedgerEntry.builder()
                .tenantId(wallet.getTenantId())
                .walletId(wallet.getId())
                .entryType(LoyaltyV2Constants.LEDGER_EXPIRE)
                .pointsDelta(-expired)
                .lotId(lot.getId())
                .bingeId(lot.getBingeId())
                .reasonCode("LOT_EXPIRED")
                .description("Lot expired at " + at + " (originalPoints=" + lot.getOriginalPoints() + ")")
                .idempotencyKey(idempotencyKey)
                .actorRole("SYSTEM")
                .build();
        entry = ledgerRepository.save(entry);

        wallet.setCurrentBalance(wallet.getCurrentBalance() - expired);
        wallet.setLifetimeExpired(wallet.getLifetimeExpired() + expired);
        walletRepository.save(wallet);

        log.info("[loyalty-v2] EXPIRE wallet={} lot={} points={}",
                wallet.getId(), lot.getId(), expired);
        return entry;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void applyLifetimeCounter(LoyaltyPointsWallet wallet, String entryType, long absPoints) {
        switch (entryType) {
            case LoyaltyV2Constants.LEDGER_EARN,
                 LoyaltyV2Constants.LEDGER_BONUS,
                 LoyaltyV2Constants.LEDGER_STATUS_MATCH_GRANT,
                 LoyaltyV2Constants.LEDGER_TRANSFER_IN ->
                    wallet.setLifetimeEarned(wallet.getLifetimeEarned() + absPoints);

            case LoyaltyV2Constants.LEDGER_REDEEM,
                 LoyaltyV2Constants.LEDGER_TRANSFER_OUT ->
                    wallet.setLifetimeRedeemed(wallet.getLifetimeRedeemed() + absPoints);

            case LoyaltyV2Constants.LEDGER_ADJUST,
                 LoyaltyV2Constants.LEDGER_REVERSE_EARN,
                 LoyaltyV2Constants.LEDGER_REVERSE_REDEEM ->
                    wallet.setLifetimeAdjusted(wallet.getLifetimeAdjusted() + absPoints);

            default -> { /* EXPIRE is handled inline in expireLot */ }
        }
    }

    private LoyaltyLedgerEntry findExisting(Long walletId, String entryType, String idempotencyKey) {
        return ledgerRepository.findByWalletIdAndEntryTypeAndIdempotencyKey(walletId, entryType, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key indicated duplicate but entry not found: wallet="
                                + walletId + " type=" + entryType + " key=" + idempotencyKey));
    }

    private void validateCreditRequest(CreditRequest r) {
        if (r.points() <= 0) throw new IllegalArgumentException("Credit points must be > 0");
        if (r.expiresAt() == null || !r.expiresAt().isAfter(r.at()))
            throw new IllegalArgumentException("Credit expiresAt must be after earnedAt");
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank())
            throw new IllegalArgumentException("idempotencyKey is required");
    }

    private void validateDebitRequest(DebitRequest r) {
        if (r.points() <= 0) throw new IllegalArgumentException("Debit points must be > 0");
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank())
            throw new IllegalArgumentException("idempotencyKey is required");
    }

    // ── Request DTOs ─────────────────────────────────────────────────────

    public record CreditRequest(
            Long membershipId,
            long points,
            String entryType,       // EARN / BONUS / STATUS_MATCH_GRANT / TRANSFER_IN / ADJUST
            String sourceType,      // EARN_BOOKING / BONUS_WELCOME / BONUS_BIRTHDAY / ADMIN_ADJUSTMENT ...
            String sourceRef,
            Long   bingeId,
            String bookingRef,
            LocalDateTime at,
            LocalDateTime expiresAt,
            String idempotencyKey,
            String correlationId,
            String reasonCode,
            String description,
            Long   actorId,
            String actorRole        // SYSTEM / ADMIN / CUSTOMER
    ) { }

    public record DebitRequest(
            Long membershipId,
            long points,
            String entryType,       // REDEEM / TRANSFER_OUT / REVERSE_EARN
            Long   bingeId,
            String bookingRef,
            String idempotencyKey,
            String correlationId,
            String reasonCode,
            String description,
            Long   actorId,
            String actorRole
    ) { }

    public static class InsufficientPointsException extends RuntimeException {
        public InsufficientPointsException(String msg) { super(msg); }
    }
}
