package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.LedgerEntry;
import com.skbingegalaxy.booking.repository.LedgerEntryRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.money.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Append-only ledger for every monetary movement. Idempotent via
 * {@code entry_uuid}: re-recording the same UUID returns the existing row.
 *
 * <p>Use {@link #recordPair} for double-entry transactions (debit + credit).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class LedgerService {

    private final LedgerEntryRepository repo;
    private final CurrencyService currencyService;

    /** Idempotent record: returns existing row when {@code entryUuid} already exists. */
    public LedgerEntry record(LedgerEntry entry) {
        if (entry.getEntryUuid() == null || entry.getEntryUuid().isBlank()) {
            throw new BusinessException("entry_uuid is required for ledger writes");
        }
        return repo.findByEntryUuid(entry.getEntryUuid()).orElseGet(() -> {
            LedgerEntry toSave = entry;
            // Auto-fill amountInBase / fxRateToBase if missing
            if (toSave.getAmountInBase() == null && toSave.getAmount() != null && toSave.getCurrencyCode() != null) {
                BigDecimal base = currencyService.convertToBase(toSave.getAmount(), toSave.getCurrencyCode());
                BigDecimal fx = currencyService.getRate(toSave.getCurrencyCode());
                toSave = toSave.toBuilder()
                    .amountInBase(base)
                    .fxRateToBase(fx)
                    .build();
            }
            LedgerEntry saved = repo.save(toSave);
            log.info("Ledger entry recorded uuid={} type={} dir={} amount={}{}",
                saved.getEntryUuid(), saved.getEntryType(), saved.getDirection(),
                saved.getAmount(), saved.getCurrencyCode());
            return saved;
        });
    }

    /** Convenience for double-entry: writes a paired DEBIT+CREDIT. */
    public List<LedgerEntry> recordPair(LedgerEntry debit, LedgerEntry credit) {
        if (debit.getDirection() != LedgerEntry.Direction.DEBIT) {
            throw new BusinessException("First entry must be DEBIT");
        }
        if (credit.getDirection() != LedgerEntry.Direction.CREDIT) {
            throw new BusinessException("Second entry must be CREDIT");
        }
        if (MoneyUtil.zeroIfNull(debit.getAmount()).compareTo(MoneyUtil.zeroIfNull(credit.getAmount())) != 0) {
            throw new BusinessException("Debit/Credit amounts must match");
        }
        return List.of(record(debit), record(credit));
    }

    public List<LedgerEntry> historyForBooking(String bookingRef) {
        return repo.findByBookingRefOrderByOccurredAtAsc(bookingRef);
    }

    public static String newUuid() { return UUID.randomUUID().toString(); }
}
