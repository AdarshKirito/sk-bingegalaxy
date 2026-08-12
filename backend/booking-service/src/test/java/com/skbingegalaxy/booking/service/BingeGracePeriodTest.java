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
        // The incident's venues held 13 ACTIVE event types, so they were bookable —
        // which is why nothing about the catalogue explained their disappearance.
        when(eventTypeRepository.existsByBingeIdAndActiveTrue(42L)).thenReturn(true);

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

    /**
     * The other way a venue disappears (V89).
     *
     * <p>The grace period asks whether event types EXIST. Customer discovery asks
     * whether any is ACTIVE. A venue holding thirteen switched-off event types satisfies
     * the first and fails the second: exempt from the grace period, shown as active in
     * the console, and unfindable by any customer — the V87 failure again, through a
     * different flag.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("an operational venue with nothing bookable")
    class NothingBookable {

        private Binge operational() {
            Binge binge = approvedHoursAgo(720);                       // long past onboarding
            binge.setFirstEventCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
            return binge;
        }

        @Test
        @DisplayName("is warned, and explicitly NOT paused")
        void warnsButNeverPauses() {
            Binge binge = operational();
            givenApproved(binge);
            when(eventTypeRepository.existsByBingeIdAndActiveTrue(42L)).thenReturn(false);

            int deactivated = bingeService.enforceGracePeriod();

            assertThat(deactivated).isZero();
            // Turning every event type off is a legitimate operator action — a seasonal
            // closure, a catalogue rebuild. Pausing on top of it would repeat the exact
            // overreach the V87 incident was about.
            assertThat(binge.isActive()).isTrue();
            assertThat(binge.getAutoDeactivatedAt()).isNull();
            assertThat(binge.getNoActiveEventsWarnedAt()).isNotNull();
            verify(adminNotificationService).notifyUser(
                anyLong(), anyString(), org.mockito.ArgumentMatchers.eq("BINGE_NOT_BOOKABLE"),
                anyString(), anyString(), anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("is not warned twice for the same episode")
        void warnsOncePerEpisode() {
            Binge binge = operational();
            givenApproved(binge);
            when(eventTypeRepository.existsByBingeIdAndActiveTrue(42L)).thenReturn(false);

            bingeService.enforceGracePeriod();
            bingeService.enforceGracePeriod();

            verify(adminNotificationService, org.mockito.Mockito.times(1)).notifyUser(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString());
        }

        @Test
        @DisplayName("clears its warning once something is bookable again, so a relapse is heard")
        void recoveryClearsTheStamp() {
            Binge binge = operational();
            binge.setNoActiveEventsWarnedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
            givenApproved(binge);
            when(eventTypeRepository.existsByBingeIdAndActiveTrue(42L)).thenReturn(true);

            bingeService.enforceGracePeriod();

            // A stamp left behind would silence the NEXT time this happens, which is the
            // failure mode of every "notify once" flag that is never reset.
            assertThat(binge.getNoActiveEventsWarnedAt()).isNull();
            verifyNoInteractions(adminNotificationService);
        }

        @Test
        @DisplayName("a venue still inside its grace period is left to the grace period")
        void onboardingVenuesAreNotDoubleReported() {
            Binge binge = approvedHoursAgo(2);
            binge.setFirstEventCreatedAt(null);          // never had an event type
            givenApproved(binge);
            when(eventTypeRepository.existsByBingeId(42L)).thenReturn(false);

            bingeService.enforceGracePeriod();

            // Two notifications about the same fact, from two different rules, is how an
            // alert channel becomes one people stop reading.
            verifyNoInteractions(adminNotificationService);
            verify(eventTypeRepository, never()).existsByBingeIdAndActiveTrue(anyLong());
        }
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

    /**
     * The auto-pause was permanent, and that was the whole defect.
     *
     * <p>V87 stopped the sweep pausing a venue that has events, and repaired the rows
     * already wrong. Neither half ever set {@code active} back to TRUE afterwards, so a
     * venue paused at the 24-hour mark — correctly, for having no events — stayed out of
     * customer discovery permanently once it did have them. Its admin sees a full
     * catalogue and a venue nobody can book, which is indistinguishable from the V87
     * incident and is what "binges are auto-pausing even though they have events"
     * actually describes.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("lifting an auto-pause once the cause is gone")
    class LiftingTheAutoPause {

        private Binge autoPaused() {
            Binge binge = approvedHoursAgo(48);
            binge.setActive(false);
            binge.setAutoDeactivatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(24));
            binge.setGraceWarningSentAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(36));
            return binge;
        }

        @Test
        @DisplayName("THE BUG: creating an event type puts an auto-paused venue back on sale")
        void firstEventLiftsThePause() {
            Binge binge = autoPaused();
            when(bingeRepository.findById(42L)).thenReturn(java.util.Optional.of(binge));

            bingeService.recordFirstEventIfNeeded(42L);

            assertThat(binge.isActive())
                .as("the pause existed because there were no events; there are now")
                .isTrue();
            assertThat(binge.getAutoDeactivatedAt()).isNull();
            // Cleared so a venue that goes through the cycle again gets its courtesy
            // warning again rather than jumping straight to a second pause.
            assertThat(binge.getGraceWarningSentAt()).isNull();
            verify(bingeRepository).save(binge);
            verify(adminNotificationService).notifyUser(
                anyLong(), anyString(), org.mockito.ArgumentMatchers.eq("BINGE_AUTO_REACTIVATED"),
                anyString(), anyString(), anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("a MANUALLY paused venue is never resurrected by adding an event type")
        void manualPauseIsUntouched() {
            Binge binge = approvedHoursAgo(720);
            binge.setActive(false);
            binge.setAutoDeactivatedAt(null);     // the discriminator: a human paused this
            when(bingeRepository.findById(42L)).thenReturn(java.util.Optional.of(binge));

            bingeService.recordFirstEventIfNeeded(42L);

            // Putting a venue back on sale that someone deliberately took off it is a
            // worse failure than the one being fixed here.
            assertThat(binge.isActive()).isFalse();
            verifyNoInteractions(adminNotificationService);
        }

        @Test
        @DisplayName("the sweep's heal path lifts the pause too, not just the flag")
        void healingAlsoLiftsThePause() {
            Binge binge = autoPaused();
            binge.setFirstEventCreatedAt(null);
            givenApproved(binge);
            when(eventTypeRepository.existsByBingeId(42L)).thenReturn(true);
            when(eventTypeRepository.existsByBingeIdAndActiveTrue(42L)).thenReturn(true);

            bingeService.enforceGracePeriod();

            // Healing the flag alone left the venue exactly as broken, AND removed it
            // from the candidate set — so nothing would ever look at it again.
            assertThat(binge.isActive()).isTrue();
            assertThat(binge.getAutoDeactivatedAt()).isNull();
            assertThat(binge.getFirstEventCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("a manual toggle clears the marker, so the auto/manual distinction stays true")
        void manualToggleClearsTheMarker() {
            Binge binge = autoPaused();
            when(bingeRepository.findById(42L)).thenReturn(java.util.Optional.of(binge));

            bingeService.toggleBinge(42L, 7L, "SUPER_ADMIN");

            assertThat(binge.isActive()).isTrue();
            // Without this, a venue that was auto-paused, manually re-activated, and
            // later deliberately paused still carried the marker — so every repair,
            // including the one above, would put it back on sale against the operator's
            // explicit choice. The discriminator has to be maintained by whoever changes
            // `active`, or it means nothing.
            assertThat(binge.getAutoDeactivatedAt()).isNull();
            assertThat(binge.getGraceWarningSentAt()).isNull();
        }
    }
}
