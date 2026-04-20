package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.dto.*;
import com.skbingegalaxy.availability.entity.BlockedDate;
import com.skbingegalaxy.availability.entity.BlockedSlot;
import com.skbingegalaxy.availability.repository.BlockedDateRepository;
import com.skbingegalaxy.availability.repository.BlockedSlotRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock private BlockedDateRepository blockedDateRepository;
    @Mock private BlockedSlotRepository blockedSlotRepository;

    @InjectMocks private AvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(availabilityService, "openingHour", 9);
        ReflectionTestUtils.setField(availabilityService, "closingHour", 22);
    }

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    // ══════════════════════════════════════════════════════
    //  GET AVAILABILITY
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAvailability")
    class GetAvailabilityTests {

        @Test
        @DisplayName("Returns availability for date range with no blocked dates")
        void noBlockedDates_allAvailable() {
            LocalDate from = LocalDate.now().plusDays(1);
            LocalDate to = LocalDate.now().plusDays(3);

            when(blockedDateRepository.findByBlockedDateBetween(from, to)).thenReturn(Collections.emptyList());
            when(blockedSlotRepository.findBySlotDateBetween(from, to)).thenReturn(Collections.emptyList());

            List<DayAvailabilityDto> result = availabilityService.getAvailability(from, to, null);

            assertThat(result).hasSize(3);
            assertThat(result).allSatisfy(day -> {
                assertThat(day.isFullyBlocked()).isFalse();
                assertThat(day.getAvailableSlots()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("Blocked date appears as fullyBlocked")
        void blockedDate_markedFullyBlocked() {
            LocalDate from = LocalDate.now().plusDays(1);
            LocalDate to = LocalDate.now().plusDays(1);
            BlockedDate blocked = BlockedDate.builder()
                    .id(1L).blockedDate(from).reason("Holiday").blockedBy(1L).build();

            when(blockedDateRepository.findByBlockedDateBetween(from, to)).thenReturn(List.of(blocked));
            when(blockedSlotRepository.findBySlotDateBetween(from, to)).thenReturn(Collections.emptyList());

            List<DayAvailabilityDto> result = availabilityService.getAvailability(from, to, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isFullyBlocked()).isTrue();
            assertThat(result.get(0).getAvailableSlots()).isEmpty();
        }

        @Test
        @DisplayName("End date before start date throws exception")
        void endBeforeStart_throwsException() {
            LocalDate from = LocalDate.now().plusDays(5);
            LocalDate to = LocalDate.now().plusDays(2);

            assertThatThrownBy(() -> availabilityService.getAvailability(from, to, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("End date must be after start date");
        }

        @Test
        @DisplayName("Past dates are clamped to today")
        void pastDates_clampedToToday() {
            LocalDate from = LocalDate.now().minusDays(5);
            LocalDate to = LocalDate.now().plusDays(1);

            when(blockedDateRepository.findByBlockedDateBetween(any(), eq(to))).thenReturn(Collections.emptyList());
            when(blockedSlotRepository.findBySlotDateBetween(any(), eq(to))).thenReturn(Collections.emptyList());

            List<DayAvailabilityDto> result = availabilityService.getAvailability(from, to, null);

            // from is clamped to today, so result should cover today..to
            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getDate()).isAfterOrEqualTo(LocalDate.now());
        }
    }

    // ══════════════════════════════════════════════════════
    //  GET SLOTS FOR DATE
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSlotsForDate")
    class GetSlotsForDateTests {

        @Test
        @DisplayName("Returns available slots when no blocks")
        void noBlocks_returnsAllSlots() {
            LocalDate date = LocalDate.now().plusDays(1);

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedSlotRepository.findBySlotDate(date)).thenReturn(Collections.emptyList());

            DayAvailabilityDto result = availabilityService.getSlotsForDate(date);

            assertThat(result.isFullyBlocked()).isFalse();
            // 9am to 10pm = 13 hours = 26 half-hour slots
            assertThat(result.getAvailableSlots()).hasSize(26);
            assertThat(result.getBlockedSlots()).isEmpty();
        }

        @Test
        @DisplayName("Fully blocked date returns no available slots")
        void blockedDate_returnsNoSlots() {
            LocalDate date = LocalDate.now().plusDays(1);

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(true);

            DayAvailabilityDto result = availabilityService.getSlotsForDate(date);

            assertThat(result.isFullyBlocked()).isTrue();
            assertThat(result.getAvailableSlots()).isEmpty();
        }

        @Test
        @DisplayName("Blocked slot reduces available slots")
        void blockedSlot_reducesAvailability() {
            LocalDate date = LocalDate.now().plusDays(1);
            BlockedSlot blocked = BlockedSlot.builder()
                    .slotDate(date).startHour(600).endHour(660).build();

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedSlotRepository.findBySlotDate(date)).thenReturn(List.of(blocked));

            DayAvailabilityDto result = availabilityService.getSlotsForDate(date);

            assertThat(result.isFullyBlocked()).isFalse();
            // 2 half-hours blocked (10:00-10:30, 10:30-11:00)
            assertThat(result.getBlockedSlots()).hasSize(2);
            assertThat(result.getAvailableSlots()).hasSize(24);
        }
    }

    // ══════════════════════════════════════════════════════
    //  IS SLOT AVAILABLE (internal check)
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("isSlotAvailable")
    class IsSlotAvailableTests {

        @Test
        @DisplayName("Available slot returns true")
        void unblocked_returnsTrue() {
            LocalDate date = LocalDate.now().plusDays(1);

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedSlotRepository.findBySlotDate(date)).thenReturn(Collections.emptyList());

            boolean result = availabilityService.isSlotAvailable(date, 600, 120); // 10:00, 2 hours

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Blocked date returns false")
        void blockedDate_returnsFalse() {
            LocalDate date = LocalDate.now().plusDays(1);

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(true);

            boolean result = availabilityService.isSlotAvailable(date, 600, 120);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Overlapping blocked slot returns false")
        void overlappingBlock_returnsFalse() {
            LocalDate date = LocalDate.now().plusDays(1);
            BlockedSlot blocked = BlockedSlot.builder()
                    .slotDate(date).startHour(600).endHour(720).build();

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedSlotRepository.findBySlotDate(date)).thenReturn(List.of(blocked));

            // Request: 11:00 (660 min) for 60 min — overlaps blocked 10:00-12:00
            boolean result = availabilityService.isSlotAvailable(date, 660, 60);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Non-overlapping blocked slot returns true")
        void nonOverlappingBlock_returnsTrue() {
            LocalDate date = LocalDate.now().plusDays(1);
            BlockedSlot blocked = BlockedSlot.builder()
                    .slotDate(date).startHour(600).endHour(720).build();

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedSlotRepository.findBySlotDate(date)).thenReturn(List.of(blocked));

            // Request: 14:00 (840 min) for 60 min — doesn't overlap blocked 10:00-12:00
            boolean result = availabilityService.isSlotAvailable(date, 840, 60);

            assertThat(result).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════
    //  ADMIN: BLOCK/UNBLOCK OPERATIONS
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("Block/Unblock operations")
    class BlockUnblockTests {

        @Test
        @DisplayName("blockDate saves and returns DTO")
        void blockDate_success() {
            LocalDate date = LocalDate.now().plusDays(5);
            BlockDateRequest request = BlockDateRequest.builder()
                    .date(date).reason("Maintenance").build();

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(false);
            when(blockedDateRepository.save(any(BlockedDate.class)))
                    .thenAnswer(i -> {
                        BlockedDate bd = i.getArgument(0);
                        bd.setId(1L);
                        return bd;
                    });

            BlockedDateDto result = availabilityService.blockDate(request, 1L);

            assertThat(result.getDate()).isEqualTo(date);
            assertThat(result.getReason()).isEqualTo("Maintenance");
            verify(blockedDateRepository).save(any(BlockedDate.class));
        }

        @Test
        @DisplayName("blockDate for already blocked date throws DuplicateResourceException")
        void blockDate_duplicate_throwsException() {
            LocalDate date = LocalDate.now().plusDays(5);
            BlockDateRequest request = BlockDateRequest.builder().date(date).build();

            when(blockedDateRepository.existsByBlockedDate(date)).thenReturn(true);

            assertThatThrownBy(() -> availabilityService.blockDate(request, 1L))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("unblockDate deletes blocked date")
        void unblockDate_success() {
            LocalDate date = LocalDate.now().plusDays(5);

            availabilityService.unblockDate(date);

            verify(blockedDateRepository).deleteByBlockedDate(date);
        }

        @Test
        @DisplayName("blockSlot saves and returns DTO")
        void blockSlot_success() {
            LocalDate date = LocalDate.now().plusDays(5);
            BlockSlotRequest request = BlockSlotRequest.builder()
                .date(date).startMinute(600).endMinute(720).reason("Reserved").build();

            when(blockedSlotRepository.existsBySlotDateAndStartHour(date, 600)).thenReturn(false);
            when(blockedSlotRepository.save(any(BlockedSlot.class)))
                    .thenAnswer(i -> {
                        BlockedSlot bs = i.getArgument(0);
                        bs.setId(1L);
                        return bs;
                    });

            BlockedSlotDto result = availabilityService.blockSlot(request, 1L);

            assertThat(result.getStartMinute()).isEqualTo(600);
            assertThat(result.getEndMinute()).isEqualTo(720);
            verify(blockedSlotRepository).save(any(BlockedSlot.class));
        }

        @Test
        @DisplayName("blockSlot for already blocked slot throws DuplicateResourceException")
        void blockSlot_duplicate_throwsException() {
            LocalDate date = LocalDate.now().plusDays(5);
            BlockSlotRequest request = BlockSlotRequest.builder()
                .date(date).startMinute(600).endMinute(720).build();

            when(blockedSlotRepository.existsBySlotDateAndStartHour(date, 600)).thenReturn(true);

            assertThatThrownBy(() -> availabilityService.blockSlot(request, 1L))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("unblockSlot deletes blocked slot")
        void unblockSlot_success() {
            LocalDate date = LocalDate.now().plusDays(5);

            availabilityService.unblockSlot(date, 600);

            verify(blockedSlotRepository).deleteBySlotDateAndStartHour(date, 600);
        }
    }

    // ══════════════════════════════════════════════════════
    //  ADMIN LIST OPERATIONS
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAllBlockedDates/Slots")
    class ListOperationsTests {

        @Test
        @DisplayName("getAllBlockedDates returns all blocked dates")
        void getAllBlockedDates_returnsList() {
            BingeContext.setBingeId(1L);
            BlockedDate bd = BlockedDate.builder()
                    .id(1L).blockedDate(LocalDate.now().plusDays(1))
                    .reason("Holiday").blockedBy(1L).build();

            when(blockedDateRepository.findByBingeId(1L)).thenReturn(List.of(bd));

            List<BlockedDateDto> result = availabilityService.getAllBlockedDates();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getReason()).isEqualTo("Holiday");
        }
    }
}
