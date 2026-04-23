package com.skbingegalaxy.booking.loyalty.v2.service;

import com.skbingegalaxy.booking.loyalty.v2.engine.PointsWalletService;
import com.skbingegalaxy.booking.loyalty.v2.engine.TierEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.*;
import com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyGuestShadowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GuestShadowService}.
 *
 * <p>Verifies hashing privacy contract, accrual + merge + expiry paths.
 */
class GuestShadowServiceTest {

    private LoyaltyConfigService configService;
    private EnrollmentService enrollmentService;
    private PointsWalletService walletService;
    private TierEngine tierEngine;
    private LoyaltyGuestShadowRepository guestShadowRepository;

    private GuestShadowService service;

    @BeforeEach
    void setup() {
        configService = mock(LoyaltyConfigService.class);
        enrollmentService = mock(EnrollmentService.class);
        walletService = mock(PointsWalletService.class);
        tierEngine = mock(TierEngine.class);
        guestShadowRepository = mock(LoyaltyGuestShadowRepository.class);

        service = new GuestShadowService(configService, enrollmentService, walletService,
                tierEngine, guestShadowRepository);

        when(configService.requireDefaultProgram()).thenReturn(
                LoyaltyProgram.builder().id(1L).code("SK").retroactiveCreditDays(60).pointsExpiryDays(540).build()
        );
        when(guestShadowRepository.save(any())).thenAnswer(inv -> {
            LoyaltyGuestShadow s = inv.getArgument(0);
            if (s.getId() == null) s.setId(999L);
            return s;
        });
        when(walletService.credit(any())).thenReturn(LoyaltyLedgerEntry.builder().id(7L).build());
    }

    @Test
    void hashWithSalt_is_deterministic_and_salted_by_program() {
        String a = GuestShadowService.hashWithSalt("Guest@Example.com", "SK");
        String b = GuestShadowService.hashWithSalt("guest@example.com ", "SK");
        String c = GuestShadowService.hashWithSalt("guest@example.com", "OTHER");

        assertThat(a).isNotNull().hasSize(64);          // SHA-256 hex = 64 chars
        assertThat(a).isEqualTo(b);                     // case + trim normalised
        assertThat(a).isNotEqualTo(c);                  // different salt → different hash
        assertThat(GuestShadowService.hashWithSalt(null, "SK")).isNull();
        assertThat(GuestShadowService.hashWithSalt("   ", "SK")).isNull();
    }

    @Test
    void accrueForGuest_creates_new_shadow_when_none_exists() {
        when(guestShadowRepository.findUnmergedByIdentityHash(any(), any()))
                .thenReturn(List.of());

        LoyaltyGuestShadow saved = service.accrueForGuest(
                "g@x.com", "+911111", "device-abc", 500L, 500L, "BK-1");

        assertThat(saved.getPendingPoints()).isEqualTo(500L);
        assertThat(saved.getPendingQualifyingCredits()).isEqualTo(500L);
        assertThat(saved.getEmailHash()).isNotNull();
        assertThat(saved.getLastBookingRef()).isEqualTo("BK-1");
    }

    @Test
    void accrueForGuest_aggregates_into_existing_shadow() {
        LoyaltyGuestShadow existing = LoyaltyGuestShadow.builder()
                .id(1L).emailHash("dummy").pendingPoints(300L).pendingQualifyingCredits(200L)
                .expiresAt(LocalDateTime.now().plusDays(30)).build();
        when(guestShadowRepository.findUnmergedByIdentityHash(any(), any()))
                .thenReturn(List.of(existing));

        LoyaltyGuestShadow result = service.accrueForGuest(
                "g@x.com", null, null, 200L, 100L, "BK-2");

        assertThat(result.getPendingPoints()).isEqualTo(500L);
        assertThat(result.getPendingQualifyingCredits()).isEqualTo(300L);
    }

    @Test
    void mergeOnSignup_credits_pending_points_with_idempotency_key() {
        LoyaltyGuestShadow shadow = LoyaltyGuestShadow.builder()
                .id(55L).pendingPoints(750L).pendingQualifyingCredits(750L)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .lastBookingRef("BK-X").build();
        when(guestShadowRepository.findUnmergedByIdentityHash(any(), any()))
                .thenReturn(List.of(shadow));
        when(enrollmentService.ensureEnrolledForBooking(100L)).thenReturn(
                LoyaltyMembership.builder().id(42L).customerId(100L).programId(1L).tenantId(1L).build()
        );

        long credited = service.mergeOnSignup(100L, "g@x.com", "+91");

        assertThat(credited).isEqualTo(750L);

        ArgumentCaptor<PointsWalletService.CreditRequest> cap =
                ArgumentCaptor.forClass(PointsWalletService.CreditRequest.class);
        verify(walletService).credit(cap.capture());
        assertThat(cap.getValue().idempotencyKey()).isEqualTo("guest-merge:shadow=55");
        assertThat(cap.getValue().points()).isEqualTo(750L);

        // Shadow marked merged
        assertThat(shadow.getMergedMembershipId()).isEqualTo(42L);
        assertThat(shadow.getPendingPoints()).isZero();
        verify(tierEngine).recalculateTier(eq(42L), any());
    }

    @Test
    void mergeOnSignup_skips_expired_shadows() {
        LoyaltyGuestShadow shadow = LoyaltyGuestShadow.builder()
                .id(66L).pendingPoints(1000L).pendingQualifyingCredits(0L)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(guestShadowRepository.findUnmergedByIdentityHash(any(), any()))
                .thenReturn(List.of(shadow));
        when(enrollmentService.ensureEnrolledForBooking(100L)).thenReturn(
                LoyaltyMembership.builder().id(42L).programId(1L).tenantId(1L).build()
        );

        long credited = service.mergeOnSignup(100L, "g@x.com", null);

        assertThat(credited).isZero();
        verifyNoInteractions(walletService);
    }

    @Test
    void mergeOnSignup_is_noop_when_no_shadows_match() {
        when(guestShadowRepository.findUnmergedByIdentityHash(any(), any()))
                .thenReturn(List.of());

        long credited = service.mergeOnSignup(100L, "g@x.com", "+91");

        assertThat(credited).isZero();
        verifyNoInteractions(enrollmentService);
        verifyNoInteractions(walletService);
    }

    @Test
    void purgeExpiredAsOf_deletes_all_expired() {
        List<LoyaltyGuestShadow> expired = List.of(
                LoyaltyGuestShadow.builder().id(1L).build(),
                LoyaltyGuestShadow.builder().id(2L).build()
        );
        when(guestShadowRepository.findExpired(any())).thenReturn(expired);

        int n = service.purgeExpiredAsOf(LocalDateTime.now());

        assertThat(n).isEqualTo(2);
        verify(guestShadowRepository).deleteAll(expired);
    }

    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
}
