package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The grace-period sweep, which had <b>no tests at all</b> — and a defect that took five
 * of six venues in the development database offline.
 *
 * <p>The sweep auto-deactivates an APPROVED binge that has not created an event type
 * within 24 hours of approval. It used to decide purely on the denormalised
 * {@code binges.first_event_created_at} flag. Two write paths create event types and
 * only one of them stamped that flag: {@code DataSeeder}, which runs on every boot for
 * every binge, did not. So venues holding a full 13-event catalogue carried a NULL flag,
 * and a day after approval the sweep set {@code active = false} and pulled them out of
 * customer discovery.
 *
 * <p>The first test below is that exact scenario. The rest exist so the fix cannot be
 * over-applied: an approved venue that genuinely has no events must still be paused, or
 * the guard is worthless.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Binge grace-period sweep")
class BingeGracePeriodTest {

    @Mock private BingeRepository bingeRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AdminNotificationService adminNotificationService;

    @InjectMocks private BingeService bingeService;

    private static Binge approvedHoursAgo(long hoursSinceApproval) {
        return Binge.builder()
            .id(42L)
            .adminId(7L)
            .name("Downtown Screening Room")
            .active(true)
            .status(BingeApprovalStatus.APPROVED)
            .approvalDecidedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(hoursSinceApproval))
            .build();
    }

    private void givenApproved(Binge binge) {
        when(bingeRepository.findByStatusOrderByCreatedAtDesc(BingeApprovalStatus.APPROVED))
            .thenReturn(List.of(binge));
    }

    @Test
    @DisplayName("a venue that HAS event types is never paused, and its stale flag is healed")
    void doesNotPauseAVenueThatHasEventTypes() {
        Binge binge = approvedHoursAgo(48);          // well past the 24h deadline
        binge.setFirstEventCreatedAt(null);          // the flag the seeder never stamped
        givenApproved(binge);
        when(eventTypeRepository.existsByBingeId(42L)).thenReturn(true);   // but events exist

        int deactivated = bingeService.enforceGracePeriod();

        assertThat(deactivated).isZero();
        assertThat(binge.isActive())
            .as("a venue with a full catalogue must stay visible to customers")
            .isTrue();
        assertThat(binge.getAutoDeactivatedAt()).isNull();
        assertThat(binge.getFirstEventCreatedAt())
            .as("the stale flag is repaired in passing, so the warning path stays honest")
            .isNotNull();
        verifyNoInteractions(adminNotificationService);
    }

    @Test
    @DisplayName("an approved venue with no event types is still paused after 24h")
    void pausesAnApprovedVenueWithNoEventTypes() {
        Binge binge = approvedHoursAgo(25);
        givenApproved(binge);
        when(eventTypeRepository.existsByBingeId(42L)).thenReturn(false);

        int deactivated = bingeService.enforceGracePeriod();

        assertThat(deactivated).isEqualTo(1);
        assertThat(binge.isActive()).isFalse();
        assertThat(binge.getAutoDeactivatedAt()).isNotNull();
        verify(adminNotificationService).notifyUser(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyLong(), anyString());
        verify(adminNotificationService).broadcastToRole(
            anyString(), anyString(), anyString(), anyString(), anyString(),
            anyLong(), anyString());
    }

    @Test
    @DisplayName("the halfway warning fires once and is not repeated on the next sweep")
    void warnsOnceAtTheHalfwayMark() {
        Binge binge = approvedHoursAgo(13);          // past the 12h warning, before 24h
        givenApproved(binge);
        when(eventTypeRepository.existsByBingeId(42L)).thenReturn(false);

        int deactivated = bingeService.enforceGracePeriod();

        assertThat(deactivated).isZero();
        assertThat(binge.isActive()).as("a warning must not pause anything").isTrue();
        assertThat(binge.getGraceWarningSentAt()).isNotNull();
        verify(adminNotificationService).notifyUser(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyLong(), anyString());

        // Second sweep, same binge, warning already recorded.
        bingeService.enforceGracePeriod();

        verify(adminNotificationService, /* still exactly one */ org.mockito.Mockito.times(1))
            .notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a binge that was never approved is out of scope entirely")
    void ignoresBingesWithNoApprovalDecision() {
        Binge binge = approvedHoursAgo(48);
        binge.setApprovalDecidedAt(null);            // approved status without a decision stamp
        givenApproved(binge);

        int deactivated = bingeService.enforceGracePeriod();

        assertThat(deactivated).isZero();
        assertThat(binge.isActive()).isTrue();
        verifyNoInteractions(adminNotificationService);
        verify(eventTypeRepository, never()).existsByBingeId(any());
    }

    @Test
    @DisplayName("recordFirstEventIfNeeded never overwrites an existing timestamp")
    void firstEventStampIsIdempotent() {
        LocalDateTime original = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        Binge binge = approvedHoursAgo(720);
        binge.setFirstEventCreatedAt(original);
        when(bingeRepository.findById(42L)).thenReturn(java.util.Optional.of(binge));

        bingeService.recordFirstEventIfNeeded(42L);

        assertThat(binge.getFirstEventCreatedAt())
            .as("the original 'became operational' moment is audit data, not a cache")
            .isEqualTo(original);
        verify(bingeRepository, never()).save(any());
    }
}
