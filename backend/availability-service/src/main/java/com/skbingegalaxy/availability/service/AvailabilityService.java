package com.skbingegalaxy.availability.service;

import com.skbingegalaxy.availability.dto.*;
import com.skbingegalaxy.availability.entity.BlockedDate;
import com.skbingegalaxy.availability.entity.BlockedSlot;
import com.skbingegalaxy.availability.repository.BlockedDateRepository;
import com.skbingegalaxy.availability.repository.BlockedSlotRepository;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.DuplicateResourceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityService {

    private final BlockedDateRepository blockedDateRepository;
    private final BlockedSlotRepository blockedSlotRepository;

    @Value("${app.theater.opening-hour:0}")
    private int openingHour;

    @Value("${app.theater.closing-hour:24}")
    private int closingHour;

    // ── Public: get available dates in range ─────────────────
    public List<DayAvailabilityDto> getAvailability(LocalDate from, LocalDate to, LocalDate clientToday) {
        LocalDate today = (clientToday != null) ? clientToday : LocalDate.now();
        if (from.isBefore(today)) {
            from = today;
        }
        if (to.isBefore(from)) {
            throw new BusinessException("End date must be after start date");
        }

        Set<LocalDate> blockedDates;
        Map<LocalDate, List<BlockedSlot>> blockedSlotsMap;
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedDates = blockedDateRepository.findByBingeIdAndBlockedDateBetween(bid, from, to)
                .stream().map(BlockedDate::getBlockedDate).collect(Collectors.toSet());
            blockedSlotsMap = blockedSlotRepository.findByBingeIdAndSlotDateBetween(bid, from, to)
                .stream().collect(Collectors.groupingBy(BlockedSlot::getSlotDate));
        } else {
            blockedDates = blockedDateRepository.findByBlockedDateBetween(from, to)
                .stream().map(BlockedDate::getBlockedDate).collect(Collectors.toSet());
            blockedSlotsMap = blockedSlotRepository.findBySlotDateBetween(from, to)
                .stream().collect(Collectors.groupingBy(BlockedSlot::getSlotDate));
        }

        List<DayAvailabilityDto> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (blockedDates.contains(date)) {
                result.add(DayAvailabilityDto.builder()
                    .date(date)
                    .fullyBlocked(true)
                    .availableSlots(Collections.emptyList())
                    .blockedSlots(Collections.emptyList())
                    .build());
            } else {
                result.add(buildDayAvailability(date, blockedSlotsMap.getOrDefault(date, Collections.emptyList())));
            }
        }
        return result;
    }

    // ── Public: get slots for a single date ──────────────────
    public DayAvailabilityDto getSlotsForDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        boolean dateBlocked = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, date)
            : blockedDateRepository.existsByBlockedDate(date);
        if (dateBlocked) {
            return DayAvailabilityDto.builder()
                .date(date)
                .fullyBlocked(true)
                .availableSlots(Collections.emptyList())
                .blockedSlots(Collections.emptyList())
                .build();
        }
        List<BlockedSlot> blocked = bid != null
            ? blockedSlotRepository.findByBingeIdAndSlotDate(bid, date)
            : blockedSlotRepository.findBySlotDate(date);
        return buildDayAvailability(date, blocked);
    }

    // ── Admin: block full date ───────────────────────────────
    @Transactional
    public BlockedDateDto blockDate(BlockDateRequest request, Long adminId) {
        Long bid = BingeContext.getBingeId();
        boolean exists = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, request.getDate())
            : blockedDateRepository.existsByBlockedDate(request.getDate());
        if (exists) {
            throw new DuplicateResourceException("BlockedDate", "date", request.getDate());
        }
        BlockedDate entity = BlockedDate.builder()
            .blockedDate(request.getDate())
            .reason(request.getReason())
            .blockedBy(adminId)
            .bingeId(bid)
            .build();
        entity = blockedDateRepository.save(entity);
        log.info("Admin {} blocked date {}", adminId, request.getDate());
        return toBlockedDateDto(entity);
    }

    // ── Admin: unblock date ──────────────────────────────────
    @Transactional
    public void unblockDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedDateRepository.deleteByBingeIdAndBlockedDate(bid, date);
        } else {
            blockedDateRepository.deleteByBlockedDate(date);
        }
        log.info("Unblocked date {}", date);
    }

    // ── Admin: block slot ────────────────────────────────────
    @Transactional
    public BlockedSlotDto blockSlot(BlockSlotRequest request, Long adminId) {
        if (request.getEndMinute() <= request.getStartMinute()) {
            throw new BusinessException("End time must be after start time");
        }
        Long bid = BingeContext.getBingeId();
        boolean exists = bid != null
            ? blockedSlotRepository.existsByBingeIdAndSlotDateAndStartHour(bid, request.getDate(), request.getStartMinute())
            : blockedSlotRepository.existsBySlotDateAndStartHour(request.getDate(), request.getStartMinute());
        if (exists) {
            throw new DuplicateResourceException("BlockedSlot", "slot", request.getDate() + " " + request.getStartMinute());
        }
        BlockedSlot entity = BlockedSlot.builder()
            .slotDate(request.getDate())
            .startHour(request.getStartMinute())
            .endHour(request.getEndMinute())
            .reason(request.getReason())
            .blockedBy(adminId)
            .bingeId(bid)
            .build();
        entity = blockedSlotRepository.save(entity);
        log.info("Admin {} blocked slot {} on {}", adminId, request.getStartMinute(), request.getDate());
        return toBlockedSlotDto(entity);
    }

    // ── Admin: unblock slot ──────────────────────────────────
    @Transactional
    public void unblockSlot(LocalDate date, int startMinute) {
        Long bid = BingeContext.getBingeId();
        if (bid != null) {
            blockedSlotRepository.deleteByBingeIdAndSlotDateAndStartHour(bid, date, startMinute);
        } else {
            blockedSlotRepository.deleteBySlotDateAndStartHour(date, startMinute);
        }
        log.info("Unblocked slot {} on {}", startMinute, date);
    }

    // ── Admin: list all blocked dates ────────────────────────
    public List<BlockedDateDto> getAllBlockedDates() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return List.of();
        return blockedDateRepository.findByBingeId(bid)
            .stream().map(this::toBlockedDateDto).toList();
    }

    // ── Admin: list all blocked slots ────────────────────────
    public List<BlockedSlotDto> getAllBlockedSlots() {
        Long bid = BingeContext.getBingeId();
        if (bid == null) return List.of();
        return blockedSlotRepository.findByBingeId(bid)
            .stream().map(this::toBlockedSlotDto).toList();
    }

    // ── Internal: check if a date+slot range is available (minutes-based) ──
    public boolean isSlotAvailable(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.getBingeId();
        boolean dateBlocked = bid != null
            ? blockedDateRepository.existsByBingeIdAndBlockedDate(bid, date)
            : blockedDateRepository.existsByBlockedDate(date);
        if (dateBlocked) return false;
        List<BlockedSlot> blocked = bid != null
            ? blockedSlotRepository.findByBingeIdAndSlotDate(bid, date)
            : blockedSlotRepository.findBySlotDate(date);
        // Build set of blocked 30-min indices (startHour/endHour store minutes; divide by 30 for half-hour index)
        // Use ceiling division for endHour so partial half-hours are fully blocked
        Set<Integer> blockedHalfHours = blocked.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() / 30, (s.getEndHour() + 29) / 30).boxed())
            .collect(Collectors.toSet());

        int endMinute = startMinute + durationMinutes;
        for (int m = startMinute; m < endMinute; m += 30) {
            int halfHourIndex = m / 30;
            if (blockedHalfHours.contains(halfHourIndex)) return false;
        }
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────
    private DayAvailabilityDto buildDayAvailability(LocalDate date, List<BlockedSlot> blockedSlots) {
        // Build set of blocked half-hour indices (startHour/endHour store minutes; divide by 30 for half-hour index)
        // Use ceiling division for endHour so partial half-hours are fully blocked
        Set<Integer> blockedHalfHours = blockedSlots.stream()
            .flatMap(s -> java.util.stream.IntStream.range(s.getStartHour() / 30, (s.getEndHour() + 29) / 30).boxed())
            .collect(Collectors.toSet());

        List<SlotDto> available = new ArrayList<>();
        List<SlotDto> blocked = new ArrayList<>();

        // Generate 30-minute slots from opening to closing
        for (int minute = openingHour * 60; minute < closingHour * 60; minute += 30) {
            int endMinute = minute + 30;
            int halfHourIndex = minute / 30;
            boolean isAvailable = !blockedHalfHours.contains(halfHourIndex);

            SlotDto slot = SlotDto.builder()
                .startHour(minute / 60)
                .endHour(endMinute / 60)
                .startMinute(minute)
                .endMinute(endMinute)
                .label(String.format("%02d:%02d - %02d:%02d", minute / 60, minute % 60, endMinute / 60, endMinute % 60))
                .available(isAvailable)
                .build();

            if (isAvailable) {
                available.add(slot);
            } else {
                blocked.add(slot);
            }
        }

        return DayAvailabilityDto.builder()
            .date(date)
            .fullyBlocked(false)
            .availableSlots(available)
            .blockedSlots(blocked)
            .build();
    }

    private BlockedDateDto toBlockedDateDto(BlockedDate entity) {
        return BlockedDateDto.builder()
            .id(entity.getId())
            .date(entity.getBlockedDate())
            .reason(entity.getReason())
            .blockedBy(entity.getBlockedBy())
            .build();
    }

    private BlockedSlotDto toBlockedSlotDto(BlockedSlot entity) {
        return BlockedSlotDto.builder()
            .id(entity.getId())
            .date(entity.getSlotDate())
            .startMinute(entity.getStartHour())
            .endMinute(entity.getEndHour())
            .reason(entity.getReason())
            .blockedBy(entity.getBlockedBy())
            .build();
    }
}
