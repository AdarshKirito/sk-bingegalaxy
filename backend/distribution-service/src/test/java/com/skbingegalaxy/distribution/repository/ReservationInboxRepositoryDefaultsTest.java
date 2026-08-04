package com.skbingegalaxy.distribution.repository;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one piece of behaviour that lives in a repository rather than in a query.
 *
 * <p>The high-water mark that decides message ordering must be taken over
 * <b>APPLIED</b> messages only. Widening it to "any message we have seen" would let a
 * {@code RECEIVED} message that was later rejected raise the bar, and the legitimate
 * retry behind it would then be discarded as {@code SUPERSEDED} — a silently lost
 * reservation, which is precisely the failure the inbox exists to make impossible.
 */
@DisplayName("ReservationInboxRepository default methods")
class ReservationInboxRepositoryDefaultsTest {

    @Test
    @DisplayName("the ordering high-water mark is taken over APPLIED messages only")
    void highWaterMarkCountsAppliedMessagesOnly() {
        ReservationInboxRepository repository = mock(ReservationInboxRepository.class);
        when(repository.findHighestSequenceWithStatus(any(), any(), any()))
            .thenReturn(Optional.of(42L));
        doCallRealMethod().when(repository).findHighestAppliedSequence(any(), any());

        Optional<Long> highest = repository.findHighestAppliedSequence(7L, "VIATOR-991");

        // The status is stubbed with any(), so this verify is the assertion — it is the
        // only thing standing between APPLIED and a wider, wrong choice.
        verify(repository).findHighestSequenceWithStatus(
            7L, "VIATOR-991", ReservationInboxEntry.Status.APPLIED);
        assertThat(highest).contains(42L);
    }
}
