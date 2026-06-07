package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.HttpAuthorityLockClient;
import com.skbingegalaxy.booking.client.HttpAuthorityLockClient.ResourceLockSummary;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorityLockGuardTest {

    @Mock private HttpAuthorityLockClient lockClient;
    @InjectMocks private AuthorityLockGuard guard;

    @AfterEach
    void clear() {
        BingeContext.clear();
    }

    private static ResourceLockSummary lock(String reason, String by) {
        ResourceLockSummary s = new ResourceLockSummary();
        s.setReason(reason);
        s.setLockedByName(by);
        return s;
    }

    @Test
    void superAdmin_bypasses_evenWhenLocked() {
        BingeContext.setBingeId(7L);
        // Never even consults the lock table for a native super-admin.
        assertThatCode(() -> guard.requireUnlocked("PRICING", "SUPER_ADMIN")).doesNotThrowAnyException();
        verifyNoInteractions(lockClient);
    }

    @Test
    void admin_blockedByBingeSpecificLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(lock("frozen by policy", "Boss"));

        assertThatThrownBy(() -> guard.requireUnlocked("PRICING", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void admin_blockedByAllBingesLock_whenNoBingeSpecificLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(lock("frozen everywhere", "Boss"));

        assertThatThrownBy(() -> guard.requireUnlocked("PRICING", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void admin_passes_whenNoLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(null);

        assertThatCode(() -> guard.requireUnlocked("PRICING", "ADMIN")).doesNotThrowAnyException();
    }

    @Test
    void admin_passes_whenLockServiceUnreachable_failOpen() {
        // lookup returns null on transient failure → treat as unlocked so a single
        // outage can't freeze the whole admin console.
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(null);

        assertThat(BingeContext.getBingeId()).isEqualTo(7L);
        assertThatCode(() -> guard.requireUnlocked("PRICING", "ADMIN")).doesNotThrowAnyException();
    }
}
