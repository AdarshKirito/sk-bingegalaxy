package com.skbingegalaxy.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.WaitlistEntry;
import com.skbingegalaxy.booking.entity.WaitlistEntry.WaitlistStatus;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.repository.OutboxEventRepository;
import com.skbingegalaxy.booking.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Race-safety fence for {@link WaitlistService#promoteWaitlistOnCancellation}.
 *
 * <p>The observable invariants we protect:</p>
 * <ul>
 *   <li>The advisory slot lock is acquired <em>before</em> reading the
 *       waiting list, so that concurrent cancellations serialise.</li>
 *   <li>At most one waiting entry is promoted to {@code OFFERED} per call
 *       (prevents double-offer when two cancellations land simultaneously
 *       and the slot only opened up once).</li>
 *   <li>An empty waiting list is a no-op — no save, no outbox event.</li>
 * </ul>
 *
 * <p>Without the advisory lock and single-promotion loop, two parallel
 * cancellations could both read "position 1 is waiting, slot has capacity"
 * and both flip the same entry (or two different entries) to OFFERED for
 * the same free slot — one of the customers would then race to book and
 * the other would get a 409.</p>
 */
@ExtendWith(MockitoExtension.class)
class WaitlistServicePromotionRaceTest {

    @Mock private WaitlistRepository waitlistRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private BookingService bookingService;
    @Mock private BookingRepository bookingRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private WaitlistService waitlistService;

    private static final Long BINGE_ID = 77L;
    private static final LocalDate DATE = LocalDate.of(2026, 5, 1);

    @BeforeEach
    void injectConfig() throws Exception {
        // @Value fields — set directly so we don't spin up Spring.
        var expiryField = WaitlistService.class.getDeclaredField("offerExpiryMinutes");
        expiryField.setAccessible(true);
        expiryField.setInt(waitlistService, 30);
    }

    @Test
    @DisplayName("acquires advisory slot lock BEFORE querying the waiting list")
    void lockIsAcquiredFirst() {
        when(waitlistRepository.findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
                eq(BINGE_ID), eq(DATE), eq(WaitlistStatus.WAITING)))
            .thenReturn(List.of());

        waitlistService.promoteWaitlistOnCancellation(BINGE_ID, DATE);

        InOrder order = inOrder(bookingRepository, waitlistRepository);
        order.verify(bookingRepository).acquireSlotLock(anyLong());
        order.verify(waitlistRepository).findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
            eq(BINGE_ID), eq(DATE), eq(WaitlistStatus.WAITING));
    }

    @Test
    @DisplayName("empty waiting list → no save, no outbox event")
    void emptyQueueIsNoOp() {
        when(waitlistRepository.findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
                any(), any(), any()))
            .thenReturn(List.of());

        waitlistService.promoteWaitlistOnCancellation(BINGE_ID, DATE);

        verify(waitlistRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("promotes AT MOST one waiting entry even when three are eligible")
    void promotesOnlyOneEvenWithMultipleEligible() {
        WaitlistEntry e1 = waitingEntry(1L, 1);
        WaitlistEntry e2 = waitingEntry(2L, 2);
        WaitlistEntry e3 = waitingEntry(3L, 3);
        when(waitlistRepository.findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
                any(), any(), any()))
            .thenReturn(List.of(e1, e2, e3));

        // Every slot has capacity — but only the FIRST entry should be offered.
        Map<String, Object> capacity = new HashMap<>();
        capacity.put("isFull", false);
        when(bookingService.getSlotCapacityForBinge(anyLong(), any(), anyInt(), anyInt()))
            .thenReturn(capacity);

        waitlistService.promoteWaitlistOnCancellation(BINGE_ID, DATE);

        // Exactly one save (the first entry flipped to OFFERED).
        verify(waitlistRepository, times(1)).save(any(WaitlistEntry.class));
    }

    @Test
    @DisplayName("skips promotion if the freed slot is already full again")
    void noPromotionWhenSlotFull() {
        WaitlistEntry e1 = waitingEntry(1L, 1);
        when(waitlistRepository.findByBingeIdAndPreferredDateAndStatusOrderByPositionAsc(
                any(), any(), any()))
            .thenReturn(List.of(e1));

        Map<String, Object> capacity = new HashMap<>();
        capacity.put("isFull", true);
        when(bookingService.getSlotCapacityForBinge(anyLong(), any(), anyInt(), anyInt()))
            .thenReturn(capacity);

        waitlistService.promoteWaitlistOnCancellation(BINGE_ID, DATE);

        verify(waitlistRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    private WaitlistEntry waitingEntry(Long id, int position) {
        return WaitlistEntry.builder()
            .id(id)
            .bingeId(BINGE_ID)
            .customerId(100L + id)
            .customerName("Customer " + id)
            .customerEmail("c" + id + "@example.com")
            .preferredDate(DATE)
            .preferredStartTime(LocalTime.of(19, 0))
            .durationMinutes(120)
            .numberOfGuests(2)
            .status(WaitlistStatus.WAITING)
            .position(position)
            .build();
    }
}
