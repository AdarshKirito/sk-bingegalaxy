package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.PasswordHistoryEntry;
import com.skbingegalaxy.auth.repository.PasswordHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Prevents users from recycling their recent passwords. The history depth (default 5)
 * is configurable via {@code app.security.password-history.keep}. Checking a candidate
 * is O(keep) BCrypt matches — bounded and deliberately slow to also throttle automated
 * probing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordHistoryService {

    private final PasswordHistoryRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.password-history.keep:5}")
    private int keep;

    @Transactional(readOnly = true)
    public boolean isRecentlyUsed(Long userId, String candidatePlaintext) {
        if (candidatePlaintext == null || candidatePlaintext.isBlank() || keep <= 0) return false;
        List<PasswordHistoryEntry> recent =
            repository.findRecent(userId, PageRequest.of(0, Math.max(1, keep)));
        for (PasswordHistoryEntry e : recent) {
            if (passwordEncoder.matches(candidatePlaintext, e.getPasswordHash())) {
                return true;
            }
        }
        return false;
    }

    /** Records the newly-set hash and prunes anything older than the retention window. */
    @Transactional
    public void record(Long userId, String newHash) {
        if (newHash == null || newHash.isBlank()) return;
        repository.save(PasswordHistoryEntry.builder()
            .userId(userId)
            .passwordHash(newHash)
            .build());
        pruneOlder(userId);
    }

    private void pruneOlder(Long userId) {
        if (keep <= 0) return;
        // Keep `keep + 1` rows (the current + `keep` previous) so history always checks
        // against the user's last N distinct credentials.
        int maxRows = keep + 1;
        List<PasswordHistoryEntry> all = repository.findRecent(userId, PageRequest.of(0, maxRows * 2));
        if (all.size() <= maxRows) return;
        List<PasswordHistoryEntry> toDelete = all.subList(maxRows, all.size());
        repository.deleteAllInBatch(toDelete);
    }
}
