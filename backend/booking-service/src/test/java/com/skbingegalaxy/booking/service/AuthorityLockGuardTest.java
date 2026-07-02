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
        assertThatCode(() -> guard.requireUnlocked("PRICING", "SUPER_ADMIN", false)).doesNotThrowAnyException();
        verifyNoInteractions(lockClient);
    }

    @Test
    void delegatedSuperAdmin_isStillBlockedByLock() {
        // A delegated admin has effective SUPER_ADMIN but delegated=true — locks must still
        // apply (locks fence off capabilities even from delegated authority).
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(lock("frozen by policy", "Boss"));

        assertThatThrownBy(() -> guard.requireUnlocked("PRICING", "SUPER_ADMIN", true))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void admin_blockedByBingeSpecificLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(lock("frozen by policy", "Boss"));

        assertThatThrownBy(() -> guard.requireUnlocked("PRICING", "ADMIN", false))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void admin_blockedByAllBingesLock_whenNoBingeSpecificLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(lock("frozen everywhere", "Boss"));

        assertThatThrownBy(() -> guard.requireUnlocked("PRICING", "ADMIN", false))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void admin_passes_whenNoLock() {
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(null);

        assertThatCode(() -> guard.requireUnlocked("PRICING", "ADMIN", false)).doesNotThrowAnyException();
    }

    @Test
    void admin_passes_whenLockServiceUnreachable_failOpen() {
        // lookup returns null on transient failure → treat as unlocked so a single
        // outage can't freeze the whole admin console.
        BingeContext.setBingeId(7L);
        when(lockClient.lookup("PRICING", "7")).thenReturn(null);
        when(lockClient.lookup("PRICING", "ALL")).thenReturn(null);

        assertThat(BingeContext.getBingeId()).isEqualTo(7L);
        assertThatCode(() -> guard.requireUnlocked("PRICING", "ADMIN", false)).doesNotThrowAnyException();
    }

    // ── Timezone GRANT (default-deny, opposite polarity to the locks above) ──────

    @Test
    void timezone_nativeSuperAdmin_permitted_withoutAnyGrant() {
        // Native super-admin never needs a grant and never consults the store.
        assertThatCode(() -> guard.requireTimezoneChangePermitted("SUPER_ADMIN", false, 7L))
            .doesNotThrowAnyException();
        verifyNoInteractions(lockClient);
    }

    @Test
    void timezone_admin_deniedByDefault_whenNoGrant() {
        when(lockClient.lookup("TIMEZONE_CHANGE", "7")).thenReturn(null);
        when(lockClient.lookup("TIMEZONE_CHANGE", "ALL")).thenReturn(null);

        assertThatThrownBy(() -> guard.requireTimezoneChangePermitted("ADMIN", false, 7L))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void timezone_admin_permitted_withBingeSpecificGrant() {
        when(lockClient.lookup("TIMEZONE_CHANGE", "7")).thenReturn(lock("granted for relocation", "Boss"));

        assertThatCode(() -> guard.requireTimezoneChangePermitted("ADMIN", false, 7L))
            .doesNotThrowAnyException();
    }

    @Test
    void timezone_admin_permitted_withAllVenuesGrant_whenNoBingeSpecific() {
        when(lockClient.lookup("TIMEZONE_CHANGE", "7")).thenReturn(null);
        when(lockClient.lookup("TIMEZONE_CHANGE", "ALL")).thenReturn(lock("all venues may self-manage", "Boss"));

        assertThatCode(() -> guard.requireTimezoneChangePermitted("ADMIN", false, 7L))
            .doesNotThrowAnyException();
    }

    @Test
    void timezone_delegatedSuperAdmin_deniedWithoutGrant() {
        // Delegated authority is NOT native — timezone still needs an explicit grant.
        when(lockClient.lookup("TIMEZONE_CHANGE", "7")).thenReturn(null);
        when(lockClient.lookup("TIMEZONE_CHANGE", "ALL")).thenReturn(null);

        assertThatThrownBy(() -> guard.requireTimezoneChangePermitted("SUPER_ADMIN", true, 7L))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void timezone_admin_failClosed_whenGrantServiceUnreachable() {
        // Opposite posture to requireUnlocked: a null (outage / no grant) means
        // "not proven permitted" → deny. Only native super-admins proceed offline.
        when(lockClient.lookup("TIMEZONE_CHANGE", "7")).thenReturn(null);
        when(lockClient.lookup("TIMEZONE_CHANGE", "ALL")).thenReturn(null);

        assertThatThrownBy(() -> guard.requireTimezoneChangePermitted("ADMIN", false, 7L))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.LOCKED);
    }
}
