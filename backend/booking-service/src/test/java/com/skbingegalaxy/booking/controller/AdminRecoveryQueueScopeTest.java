package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.entity.Binge;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.SlotHoldRepository;
import com.skbingegalaxy.booking.service.AdminBingeScopeService;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.SlotHoldService;
import com.skbingegalaxy.booking.service.VenueClockService;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-001 regression fence: recovery-queue reads leaked cross-binge customer
 * PII because the repository queries had no bingeId predicate and the
 * controller never validated ownership of the selected binge.
 *
 * <p>The contract pinned here:</p>
 * <ul>
 *   <li>a binge ADMIN's read resolves through
 *       {@code requireManagedBinge} (ownership, not just header presence) and
 *       the OWNED binge id — never the raw header — feeds the query;</li>
 *   <li>an admin who does not own the selected binge is rejected before any
 *       repository call;</li>
 *   <li>only a SUPER_ADMIN with no binge selected gets the platform-wide
 *       (null-scoped) view.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminRecoveryQueueScopeTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private SlotHoldRepository slotHoldRepository;
    @Mock private BookingService bookingService;
    @Mock private SlotHoldService slotHoldService;
    @Mock private AdminBingeScopeService adminBingeScopeService;
    @Mock private VenueClockService venueClock;

    @InjectMocks private AdminRecoveryQueueController controller;

    private static final Long ADMIN_ID = 42L;
    private static final Long OWNED_BINGE = 7L;

    @BeforeEach
    void setUp() {
        BingeContext.clear();
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    private Binge ownedBinge() {
        Binge b = new Binge();
        b.setId(OWNED_BINGE);
        b.setAdminId(ADMIN_ID);
        return b;
    }

    @Test
    @DisplayName("binge admin read is ownership-checked and query is scoped to the OWNED binge id")
    void adminRead_isOwnershipScoped() {
        BingeContext.setBingeId(OWNED_BINGE);
        when(adminBingeScopeService.requireManagedBinge(eq(ADMIN_ID), eq("ADMIN"), anyString()))
            .thenReturn(ownedBinge());
        when(bookingRepository.findStuckPending(eq(OWNED_BINGE), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(Page.empty());

        controller.stuckPending(ADMIN_ID, "ADMIN", 60, 0, 50);

        verify(adminBingeScopeService).requireManagedBinge(eq(ADMIN_ID), eq("ADMIN"), anyString());
        verify(bookingRepository).findStuckPending(eq(OWNED_BINGE), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    @DisplayName("admin selecting a binge they do not own is rejected before any repository read")
    void adminRead_foreignBinge_rejected() {
        BingeContext.setBingeId(99L); // binge B — not owned by this admin
        when(adminBingeScopeService.requireManagedBinge(eq(ADMIN_ID), eq("ADMIN"), anyString()))
            .thenThrow(new BusinessException("Access denied: you do not own this binge", HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> controller.stuckPending(ADMIN_ID, "ADMIN", 60, 0, 50))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("do not own");

        verify(bookingRepository, org.mockito.Mockito.never())
            .findStuckPending(any(), any(), any());
    }

    @Test
    @DisplayName("SUPER_ADMIN with no selected binge gets the platform-wide (null-scoped) view")
    void superAdmin_noBinge_platformWide() {
        // No BingeContext set — the super-admin dashboard case.
        when(bookingRepository.findStuckPending(isNull(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(Page.empty());

        controller.stuckPending(ADMIN_ID, "SUPER_ADMIN", 60, 0, 50);

        verify(bookingRepository).findStuckPending(isNull(), any(LocalDateTime.class), any(Pageable.class));
        verify(adminBingeScopeService, org.mockito.Mockito.never())
            .requireManagedBinge(any(), any(), anyString());
    }

    @Test
    @DisplayName("expired-holds read follows the same scoping contract")
    void expiredHolds_isOwnershipScoped() {
        BingeContext.setBingeId(OWNED_BINGE);
        when(adminBingeScopeService.requireManagedBinge(eq(ADMIN_ID), eq("ADMIN"), anyString()))
            .thenReturn(ownedBinge());
        when(slotHoldRepository.findExpiredNotReleased(eq(OWNED_BINGE), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(Page.empty());

        controller.expiredHolds(ADMIN_ID, "ADMIN", 0, 50);

        verify(slotHoldRepository).findExpiredNotReleased(eq(OWNED_BINGE), any(LocalDateTime.class), any(Pageable.class));
    }
}
