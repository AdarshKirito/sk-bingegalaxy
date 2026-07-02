package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.FxRateLock;
import com.skbingegalaxy.booking.repository.FxRateLockRepository;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxLockServiceTest {

    @Mock private FxRateLockRepository repo;
    @Mock private CurrencyService currencyService;
    @InjectMocks private FxLockService service;

    private FxRateLock activeLock(Long customerId) {
        return FxRateLock.builder()
            .lockToken("tok-1")
            .customerId(customerId)
            .status(FxRateLock.Status.ACTIVE)
            .lockedUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10))
            .build();
    }

    @Test
    void consumeLock_matchingOwner_succeeds() {
        when(repo.findByLockToken("tok-1")).thenReturn(Optional.of(activeLock(42L)));
        when(repo.save(any(FxRateLock.class))).thenAnswer(i -> i.getArgument(0));

        FxRateLock out = service.consumeLock("tok-1", 42L);

        assertThat(out.getStatus()).isEqualTo(FxRateLock.Status.CONSUMED);
    }

    @Test
    void consumeLock_mismatchedOwner_throwsForbidden() {
        when(repo.findByLockToken("tok-1")).thenReturn(Optional.of(activeLock(42L)));

        assertThatThrownBy(() -> service.consumeLock("tok-1", 99L))
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void consumeLock_legacyNullOwnerLock_skipsCheck() {
        // Locks created before the ownership fix carry a null customerId — still consumable.
        when(repo.findByLockToken("tok-1")).thenReturn(Optional.of(activeLock(null)));
        when(repo.save(any(FxRateLock.class))).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> service.consumeLock("tok-1", 99L)).doesNotThrowAnyException();
    }

    @Test
    void consumeLock_noExpectedCustomer_skipsCheck() {
        // Domestic-INR / admin paths don't supply a customer id — must not be blocked.
        when(repo.findByLockToken("tok-1")).thenReturn(Optional.of(activeLock(42L)));
        when(repo.save(any(FxRateLock.class))).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> service.consumeLock("tok-1")).doesNotThrowAnyException();
    }
}
