package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetTokenCleanupServiceTest {

    @Mock
    private PasswordResetTokenRepository resetTokenRepository;

    @InjectMocks
    private PasswordResetTokenCleanupService service;

    @Test
    @DisplayName("purges tokens with expiry older than the 7-day retention window")
    void purgesUsingSevenDayCutoff() {
        LocalDateTime before = LocalDateTime.now();
        when(resetTokenRepository.deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
            .thenReturn(3);

        service.purgeExpiredResetTokens();

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(resetTokenRepository, times(1)).deleteAllByExpiresAtBefore(cutoffCaptor.capture());

        LocalDateTime cutoff = cutoffCaptor.getValue();
        // Cutoff must be ~7 days ago, sandwiched by the clock readings above.
        assertThat(cutoff).isBefore(after.minusDays(7).plus(1, ChronoUnit.SECONDS));
        assertThat(cutoff).isAfter(before.minusDays(7).minus(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("silently tolerates zero-row sweeps (no log spam, no failure)")
    void handlesEmptySweep() {
        when(resetTokenRepository.deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
            .thenReturn(0);

        service.purgeExpiredResetTokens();

        verify(resetTokenRepository, times(1))
            .deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("never invokes any other repository method — scope is narrow")
    void doesNothingBeyondPurge() {
        when(resetTokenRepository.deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
            .thenReturn(0);
        service.purgeExpiredResetTokens();
        verify(resetTokenRepository, times(1))
            .deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any());
        // No findAll / save / anything else.
        org.mockito.Mockito.verifyNoMoreInteractions(resetTokenRepository);
    }
}
