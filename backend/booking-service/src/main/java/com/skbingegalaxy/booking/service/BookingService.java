package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BingeRepository bingeRepository;
    private final BookingReviewRepository bookingReviewRepository;
    private final BookingAddOnRepository bookingAddOnRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final RateCodeEventPricingRepository rateCodeEventPricingRepository;
    private final RateCodeAddonPricingRepository rateCodeAddonPricingRepository;
    private final CustomerEventPricingRepository customerEventPricingRepository;
    private final CustomerAddonPricingRepository customerAddonPricingRepository;
    private final CancellationTierRepository cancellationTierRepository;
    private final AvailabilityClient availabilityClient;
    private final AvailabilityClientFallback availabilityFallback;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SystemSettingsService systemSettingsService;
    private final PricingService pricingService;
    private final BookingEventLogService eventLogService;
    private final SagaOrchestrator sagaOrchestrator;
    private final VenueRoomRepository venueRoomRepository;
    private final LoyaltyService loyaltyService;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${app.booking.ref-prefix:SKBG}")
    private String refPrefix;

    @Value("${app.booking.max-pending-per-customer:2}")
    private int maxPendingPerCustomer;

    @Value("${app.booking.cooldown-minutes-after-timeout:10}")
    private int cooldownMinutesAfterTimeout;

    @Value("${app.booking.max-reschedules-per-booking:3}")
    private int maxReschedulesPerBooking;

    @Value("${app.booking.reschedule-cutoff-hours:2}")
    private int rescheduleCutoffHours;

    @Value("${app.booking.transfer-cutoff-hours:2}")
    private int transferCutoffHours;

    // ── Create booking ───────────────────────────────────────
    @Transactional
    public BookingDto createBooking(CreateBookingRequest request,
                                    Long customerId, String customerName,
                                    String customerEmail, String customerPhone) {

        // Anti-abuse: limit concurrent PENDING bookings per customer
        long pendingCount = bookingRepository.countPendingByCustomerId(customerId);
        if (pendingCount >= maxPendingPerCustomer) {
            throw new BusinessException(
                "You already have " + pendingCount + " pending booking(s). Please complete or cancel them before creating new ones.");
        }

        // Anti-abuse: cooldown after auto-cancelled (timed-out) bookings
        LocalDateTime cooldownSince = LocalDateTime.now().minusMinutes(cooldownMinutesAfterTimeout);
        long recentTimeouts = bookingRepository.countRecentTimeoutCancellations(customerId, cooldownSince);
        if (recentTimeouts >= 2) {
            throw new BusinessException(
                "Too many unpaid bookings were auto-cancelled recently. Please wait a few minutes before trying again.");
        }

        Long bingeId = BingeContext.requireBingeId();
        EventType eventType = findBookableEventType(request.getEventTypeId());

        // Reject bookings in the past
        if (request.getBookingDate().isBefore(LocalDate.now())) {
            throw new BusinessException("Booking date cannot be in the past");
        }

        // Resolve duration in minutes (30-min granularity)
        int durMin = resolveDurationMinutes(request.getDurationMinutes(), request.getDurationHours());
        if (durMin < 30 || durMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (durMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        // Check availability via internal HTTP call with fallback cache
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        Boolean available = availabilityClient.checkSlotAvailable(
            internalApiSecret, request.getBookingDate(), bingeId, startMinute, durMin);
        if (available != null) {
            availabilityFallback.cacheResult(request.getBookingDate(), startMinute, durMin, available);
        }
        if (available == null) {
            throw new BusinessException("Availability service is temporarily unavailable. Please try again.");
        }
        if (Boolean.FALSE.equals(available)) {
            throw new BusinessException("Selected date/time slot is not available");
        }

        // Acquire advisory lock on (bingeId, date) to serialise concurrent booking
        // attempts for the same slot.  This closes the race window where two requests
        // both see zero existing rows and both INSERT successfully.
        bookingRepository.acquireSlotLock(slotLockKey(bingeId, request.getBookingDate()));

        // Check for double-booking with existing reservations
        if (hasTimeConflict(request.getBookingDate(), startMinute, durMin)) {
            throw new BusinessException("Selected time slot conflicts with an existing booking");
        }

        // Capacity management: enforce max concurrent bookings per time slot
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (binge != null && binge.getMaxConcurrentBookings() != null) {
            int overlapping = countOverlappingBookings(request.getBookingDate(), startMinute, durMin);
            if (overlapping >= binge.getMaxConcurrentBookings()) {
                throw new BusinessException("CAPACITY_FULL:This time slot has reached maximum capacity ("
                    + binge.getMaxConcurrentBookings() + " bookings). You can join the waitlist to be notified when a spot opens up.");
            }
        }

        // Calculate pricing using resolved customer pricing
        PricingService.ResolvedEventPrice eventPrice = pricingService.resolveEventPrice(customerId, request.getEventTypeId());
        BigDecimal durationDecimalHours = BigDecimal.valueOf(durMin)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal baseAmount = eventPrice.basePrice()
            .add(eventPrice.hourlyRate().multiply(durationDecimalHours).setScale(2, RoundingMode.HALF_UP));

        // Process add-ons with resolved pricing
        List<BookingAddOn> bookingAddOns = new ArrayList<>();
        BigDecimal addOnTotal = BigDecimal.ZERO;

        if (request.getAddOns() != null) {
            for (AddOnSelection sel : request.getAddOns()) {
                AddOn addOn = findBookableAddOn(sel.getAddOnId());
                int qty = Math.max(sel.getQuantity(), 1);
                PricingService.ResolvedAddonPrice addonPrice = pricingService.resolveAddonPrice(customerId, sel.getAddOnId());
                BigDecimal linePrice = addonPrice.price().multiply(BigDecimal.valueOf(qty));
                addOnTotal = addOnTotal.add(linePrice);

                bookingAddOns.add(BookingAddOn.builder()
                    .addOn(addOn)
                    .quantity(qty)
                    .price(linePrice)
                    .build());
            }
        }

        // Guest charge with resolved pricing
        int guests = Math.max(request.getNumberOfGuests(), 1);
        BigDecimal guestAmount = eventPrice.pricePerGuest()
            .multiply(BigDecimal.valueOf(Math.max(guests - 1, 0)));

        // Determine pricing source for snapshot
        String pricingSource = eventPrice.source();
        String rateCodeName = eventPrice.rateCodeName();

        BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);

        // ── Surge pricing ────────────────────────────────────
        BigDecimal surgeMultiplier = null;
        String surgeLabel = null;
        PricingService.SurgeResult surge = pricingService.resolveSurge(request.getBookingDate(), request.getStartTime());
        if (surge != null) {
            surgeMultiplier = surge.multiplier();
            surgeLabel = surge.label();
            // Apply surge to total; keep component amounts (base, addOn, guest) as pre-surge
            // values so admins can see the breakdown clearly. The surgeMultiplier field
            // on the booking records the factor for transparency.
            totalAmount = totalAmount.multiply(surgeMultiplier).setScale(2, RoundingMode.HALF_UP);
        }

        // ── Venue room assignment ────────────────────────────
        Long venueRoomId = null;
        String venueRoomName = null;
        if (request.getVenueRoomId() != null) {
            VenueRoom room = venueRoomRepository.findByIdAndBingeId(request.getVenueRoomId(), bingeId)
                .orElseThrow(() -> new BusinessException("Selected room not found"));
            if (!room.isActive()) throw new BusinessException("Selected room is currently unavailable");
            // Check room capacity for the requested time slot
            int roomOccupancy = countRoomBookings(room.getId(), request.getBookingDate(), startMinute, durMin);
            if (roomOccupancy >= room.getCapacity()) {
                throw new BusinessException("Selected room '" + room.getName() + "' is fully booked for this time slot");
            }
            venueRoomId = room.getId();
            venueRoomName = room.getName();
        }

        // ── Loyalty redemption ───────────────────────────────
        String bookingRef = generateBookingRef();
        long loyaltyPointsRedeemed = 0;
        BigDecimal loyaltyDiscountAmount = BigDecimal.ZERO;
        if (request.getRedeemLoyaltyPoints() != null && request.getRedeemLoyaltyPoints() > 0) {
            LoyaltyService.RedemptionResult redemption = loyaltyService.redeemPoints(
                customerId, bookingRef,
                request.getRedeemLoyaltyPoints(), totalAmount);
            loyaltyPointsRedeemed = redemption.pointsRedeemed();
            loyaltyDiscountAmount = redemption.discountAmount();
            totalAmount = totalAmount.subtract(loyaltyDiscountAmount);
            if (totalAmount.compareTo(BigDecimal.ZERO) < 0) totalAmount = BigDecimal.ZERO;
        }

        Booking booking = Booking.builder()
            .bookingRef(bookingRef)
            .bingeId(bingeId)
            .customerId(customerId)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .customerPhone(customerPhone)
            .eventType(eventType)
            .bookingDate(request.getBookingDate())
            .startTime(request.getStartTime())
            .durationHours(durMin / 60)
            .durationMinutes(durMin)
            .numberOfGuests(guests)
            .specialNotes(request.getSpecialNotes())
            .baseAmount(baseAmount)
            .addOnAmount(addOnTotal)
            .guestAmount(guestAmount)
            .totalAmount(totalAmount)
            .pricingSource(pricingSource)
            .rateCodeName(rateCodeName)
            .venueRoomId(venueRoomId)
            .venueRoomName(venueRoomName)
            .surgeMultiplier(surgeMultiplier)
            .surgeLabel(surgeLabel)
            .loyaltyPointsRedeemed(loyaltyPointsRedeemed)
            .loyaltyDiscountAmount(loyaltyDiscountAmount)
            .status(BookingStatus.PENDING)
            .paymentStatus(PaymentStatus.PENDING)
            .build();

        bookingAddOns.forEach(ba -> ba.setBooking(booking));
        booking.setAddOns(bookingAddOns);

        Booking saved = bookingRepository.save(booking);
        log.info("Booking created: {} for customer {}", bookingRef, customerId);

        // Event log
        eventLogService.logEvent(saved, BookingEventType.CREATED, null, customerId, "CUSTOMER",
            "Booking created via customer portal");

        // Start saga
        sagaOrchestrator.startSaga(saved.getBookingRef());

        // Publish Kafka event
        publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);

        return toDto(saved);
    }

    // ── Get booking by ref ───────────────────────────────────
    @Transactional(readOnly = true)
    public BookingDto getByRef(String bookingRef) {
        return toDto(findScopedBookingByRef(bookingRef));
    }

    // ── Customer: my bookings ────────────────────────────────
    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerBookings(Long customerId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bid, customerId)
            : bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        return list.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerBookingsByStatus(Long customerId, BookingStatus status) {
        Long bid = BingeContext.getBingeId();
        return (bid != null
            ? bookingRepository.findByBingeIdAndCustomerIdAndStatus(bid, customerId, status)
            : bookingRepository.findByCustomerIdAndStatus(customerId, status))
            .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerCurrentBookings(Long customerId, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerCurrentBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerCurrentBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerPastBookings(Long customerId, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerPastBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerPastBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    // ── Admin: all bookings (paginated) ──────────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getAllBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeId(bid, pageable) : bookingRepository.findAll(pageable)).map(this::toDto);
    }

    // ── Admin: today's bookings (paginated) ──────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getTodayBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, today, pageable) : bookingRepository.findByBookingDate(today, pageable)).map(this::toDto);
    }

    // ── Admin: upcoming bookings (today+future, PENDING or CONFIRMED only) ─
    @Transactional(readOnly = true)
    public Page<BookingDto> getUpcomingBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findUpcomingBookingsByBinge(bid, today, pageable) : bookingRepository.findUpcomingBookings(today, pageable)).map(this::toDto);
    }

    // ── Admin: by date ─────────────────────────────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDate(LocalDate date, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, date, pageable) : bookingRepository.findByBookingDate(date, pageable)).map(this::toDto);
    }

    // ── Admin: by status ───────────────────────────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatus(BookingStatus status, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndStatus(bid, status, pageable) : bookingRepository.findByStatus(status, pageable)).map(this::toDto);
    }

    // ── Admin: by status scoped to operational day ────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatusForToday(BookingStatus status, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null
            ? bookingRepository.findByBingeIdAndBookingDateAndStatus(bid, today, status, pageable)
            : bookingRepository.findByBookingDateAndStatus(today, status, pageable)).map(this::toDto);
    }

    // ── Admin: by date range ──────────────────────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDateRange(LocalDate from, LocalDate to, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null
            ? bookingRepository.findByBingeIdAndBookingDateBetween(bid, from, to, pageable)
            : bookingRepository.findByBookingDateBetween(from, to, pageable)).map(this::toDto);
    }

    // ── Admin: search ────────────────────────────────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookings(String query, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.searchBookingsByBinge(bid, query, pageable) : bookingRepository.searchBookings(query, pageable)).map(this::toDto);
    }

    // ── Admin: search scoped to operational day ───────────────
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookingsForToday(String query, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null
            ? bookingRepository.searchBookingsByBingeAndDate(bid, today, query, pageable)
            : bookingRepository.searchBookingsByDate(today, query, pageable)).map(this::toDto);
    }

    // ── Admin: update booking ────────────────────────────────
    @Transactional
    public BookingDto updateBooking(String bookingRef, UpdateBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);
        String previousStatus = booking.getStatus().name();

        if (request.getStatus() != null) {
            BookingStatus newStatus;
            try {
                newStatus = BookingStatus.valueOf(request.getStatus());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Invalid booking status: " + request.getStatus());
            }
            if (!isValidTransition(booking.getStatus(), newStatus)) {
                throw new BusinessException("Cannot transition from " + booking.getStatus() + " to " + newStatus);
            }
            booking.setStatus(newStatus);
            if (newStatus == BookingStatus.CONFIRMED) {
                publishBookingEvent(booking, KafkaTopics.BOOKING_CONFIRMED);
            }
        }
        if (request.getCheckedIn() != null) {
            booking.setCheckedIn(request.getCheckedIn());
            if (request.getCheckedIn()) {
                booking.setStatus(BookingStatus.CHECKED_IN);
            }
        }
        if (request.getAdminNotes() != null) {
            booking.setAdminNotes(request.getAdminNotes());
        }
        if (request.getCustomerName() != null) {
            booking.setCustomerName(request.getCustomerName());
        }
        if (request.getCustomerEmail() != null) {
            booking.setCustomerEmail(request.getCustomerEmail());
        }
        if (request.getCustomerPhone() != null) {
            booking.setCustomerPhone(request.getCustomerPhone());
        }
        if (request.getSpecialNotes() != null) {
            booking.setSpecialNotes(request.getSpecialNotes());
        }

        // ── Pricing-relevant field updates ───────────────────
        boolean pricingChanged = false;

        // Event type change
        if (request.getEventTypeId() != null
                && !request.getEventTypeId().equals(booking.getEventType().getId())) {
            EventType newEventType = findBookableEventType(request.getEventTypeId());
            booking.setEventType(newEventType);
            pricingChanged = true;
        }

        // Duration change
        if (request.getDurationMinutes() != null) {
            int newDur = request.getDurationMinutes();
            int oldDur = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
            if (newDur != oldDur) {
                if (newDur < 30 || newDur > 720) {
                    throw new BusinessException("Duration must be between 30 minutes and 12 hours");
                }
                if (newDur % 30 != 0) {
                    throw new BusinessException("Duration must be in 30-minute increments");
                }
                booking.setDurationMinutes(newDur);
                booking.setDurationHours(newDur / 60);
                pricingChanged = true;
            }
        }

        // Date/time change — check availability
        boolean dateTimeChanged = false;
        if (request.getBookingDate() != null) {
            booking.setBookingDate(request.getBookingDate());
            dateTimeChanged = true;
        }
        if (request.getStartTime() != null) {
            booking.setStartTime(request.getStartTime());
            dateTimeChanged = true;
        }
        if (dateTimeChanged) {
            bookingRepository.acquireSlotLock(slotLockKey(booking.getBingeId(), booking.getBookingDate()));
            int startMinute = booking.getStartTime().getHour() * 60 + booking.getStartTime().getMinute();
            int durMin = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
            if (hasTimeConflict(booking.getBookingDate(), startMinute, durMin, booking.getId())) {
                throw new BusinessException("Selected time slot is no longer available");
            }
            // Validate venue room capacity at the new time slot
            if (booking.getVenueRoomId() != null) {
                VenueRoom room = venueRoomRepository.findById(booking.getVenueRoomId()).orElse(null);
                if (room == null || !room.isActive()) {
                    booking.setVenueRoomId(null);
                    booking.setVenueRoomName(null);
                } else {
                    int roomOcc = countRoomBookings(room.getId(), booking.getBookingDate(), startMinute, durMin, booking.getId());
                    if (roomOcc >= room.getCapacity()) {
                        throw new BusinessException("Room '" + room.getName() + "' is fully booked for the new time slot");
                    }
                }
            }
        }

        // Guest count change
        if (request.getNumberOfGuests() != null
                && request.getNumberOfGuests() != booking.getNumberOfGuests()) {
            booking.setNumberOfGuests(request.getNumberOfGuests());
            pricingChanged = true;
        }

        // Add-on changes
        if (request.getAddOns() != null) {
            pricingChanged = true;
        }

        // ── Direct admin price override (takes priority over recalculation) ──
        boolean directPriceOverride = request.getBaseAmount() != null
            || request.getAddOnAmount() != null
            || request.getGuestAmount() != null;

        if (directPriceOverride) {
            BigDecimal oldTotal = booking.getTotalAmount();
            if (request.getBaseAmount() != null)  booking.setBaseAmount(request.getBaseAmount());
            if (request.getAddOnAmount() != null) booking.setAddOnAmount(request.getAddOnAmount());
            if (request.getGuestAmount() != null) booking.setGuestAmount(request.getGuestAmount());

            BigDecimal base = booking.getBaseAmount() != null ? booking.getBaseAmount() : BigDecimal.ZERO;
            BigDecimal addOn = booking.getAddOnAmount() != null ? booking.getAddOnAmount() : BigDecimal.ZERO;
            BigDecimal guest = booking.getGuestAmount() != null ? booking.getGuestAmount() : BigDecimal.ZERO;
            BigDecimal newTotal = base.add(addOn).add(guest);
            booking.setTotalAmount(newTotal);
            booking.setPricingSource("ADMIN_OVERRIDE");

            String reason = (request.getPriceAdjustmentReason() != null && !request.getPriceAdjustmentReason().isBlank())
                ? request.getPriceAdjustmentReason()
                : "Admin price adjustment";
            BigDecimal diff = newTotal.subtract(oldTotal);
            String diffNote = String.format("PRICE OVERRIDE: ₹%s → ₹%s (%s₹%s). Reason: %s",
                oldTotal.toPlainString(), newTotal.toPlainString(),
                diff.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "-",
                diff.abs().toPlainString(), reason);
            String existing = booking.getAdminNotes() != null ? booking.getAdminNotes() + " | " : "";
            booking.setAdminNotes(existing + diffNote);

            log.info("Booking {} price overridden by admin: {} → {} (reason: {})",
                bookingRef, oldTotal, newTotal, reason);
            pricingChanged = false; // Skip system recalculation
        }

        // ── Recalculate pricing when relevant fields changed ──
        if (pricingChanged) {
            Long custId = booking.getCustomerId() != null ? booking.getCustomerId() : 0L;

            // Resolve event pricing
            PricingService.ResolvedEventPrice eventPrice;
            if (custId > 0) {
                eventPrice = pricingService.resolveEventPrice(custId, booking.getEventType().getId());
            } else {
                EventType et = booking.getEventType();
                eventPrice = new PricingService.ResolvedEventPrice(
                    et.getBasePrice(), et.getHourlyRate(), et.getPricePerGuest(), "DEFAULT", null);
            }

            int durMin = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
            BigDecimal durationDecimalHours = BigDecimal.valueOf(durMin)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
            BigDecimal baseAmount = eventPrice.basePrice()
                .add(eventPrice.hourlyRate().multiply(durationDecimalHours).setScale(2, RoundingMode.HALF_UP));

            // Recalculate add-ons
            BigDecimal addOnTotal = BigDecimal.ZERO;
            if (request.getAddOns() != null) {
                // Replace all add-ons with the new list
                booking.getAddOns().clear();
                for (AddOnSelection sel : request.getAddOns()) {
                    AddOn addOn = findBookableAddOn(sel.getAddOnId());
                    int qty = Math.max(sel.getQuantity(), 1);
                    BigDecimal resolvedPrice;
                    if (custId > 0) {
                        PricingService.ResolvedAddonPrice ap = pricingService.resolveAddonPrice(custId, sel.getAddOnId());
                        resolvedPrice = ap.price();
                    } else {
                        resolvedPrice = addOn.getPrice();
                    }
                    BigDecimal linePrice = resolvedPrice.multiply(BigDecimal.valueOf(qty));
                    addOnTotal = addOnTotal.add(linePrice);
                    BookingAddOn ba = BookingAddOn.builder()
                        .booking(booking)
                        .addOn(addOn)
                        .quantity(qty)
                        .price(linePrice)
                        .build();
                    booking.getAddOns().add(ba);
                }
            } else {
                // Keep existing add-ons, but recalculate prices
                for (BookingAddOn ba : booking.getAddOns()) {
                    addOnTotal = addOnTotal.add(ba.getPrice());
                }
            }

            // Guest charge
            int guests = booking.getNumberOfGuests();
            BigDecimal guestAmount = eventPrice.pricePerGuest()
                .multiply(BigDecimal.valueOf(Math.max(guests - 1, 0)));

            BigDecimal newTotal = baseAmount.add(addOnTotal).add(guestAmount);

            // Build modification note
            BigDecimal oldTotal = booking.getTotalAmount();
            BigDecimal diff = newTotal.subtract(oldTotal);
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                String diffNote = String.format("Price updated: ₹%s → ₹%s (%s₹%s)",
                    oldTotal.toPlainString(), newTotal.toPlainString(),
                    diff.compareTo(BigDecimal.ZERO) > 0 ? "+" : "-",
                    diff.abs().toPlainString());
                String existing = booking.getAdminNotes() != null ? booking.getAdminNotes() + " | " : "";
                booking.setAdminNotes(existing + diffNote);
            }

            booking.setBaseAmount(baseAmount);
            booking.setAddOnAmount(addOnTotal);
            booking.setGuestAmount(guestAmount);
            booking.setTotalAmount(newTotal);
            booking.setPricingSource(eventPrice.source());
            booking.setRateCodeName(eventPrice.rateCodeName());

            log.info("Booking {} pricing recalculated: {} → {}", bookingRef, oldTotal, newTotal);
        }

        // ── Recalculate surge when date/time changed OR pricing changed ──
        if (!directPriceOverride && (dateTimeChanged || pricingChanged)) {
            if (dateTimeChanged) {
                // Re-resolve surge for the new time slot
                PricingService.SurgeResult surge = pricingService.resolveSurge(
                    booking.getBookingDate(), booking.getStartTime());
                BigDecimal preSurgeTotal = booking.getBaseAmount()
                    .add(booking.getAddOnAmount()).add(booking.getGuestAmount());
                if (surge != null) {
                    booking.setSurgeMultiplier(surge.multiplier());
                    booking.setSurgeLabel(surge.label());
                    booking.setTotalAmount(preSurgeTotal.multiply(surge.multiplier()).setScale(2, RoundingMode.HALF_UP));
                } else {
                    booking.setSurgeMultiplier(null);
                    booking.setSurgeLabel(null);
                    booking.setTotalAmount(preSurgeTotal);
                }
            } else if (booking.getSurgeMultiplier() != null) {
                // Pricing fields changed but date/time didn't — reapply existing surge multiplier
                BigDecimal preSurgeTotal = booking.getBaseAmount()
                    .add(booking.getAddOnAmount()).add(booking.getGuestAmount());
                booking.setTotalAmount(preSurgeTotal.multiply(booking.getSurgeMultiplier()).setScale(2, RoundingMode.HALF_UP));
            }
        }

        Booking updated = bookingRepository.save(booking);

        // Award loyalty when admin transitions status to COMPLETED
        if (!previousStatus.equals("COMPLETED")
                && updated.getStatus() == BookingStatus.COMPLETED
                && updated.getLoyaltyPointsEarned() == 0) {
            awardLoyaltyPoints(updated);
        }

        BookingEventType eventType = booking.getStatus().name().equals(previousStatus)
            ? BookingEventType.MODIFIED
            : BookingEventType.valueOf(booking.getStatus().name());
        String eventDesc = directPriceOverride
            ? "Price adjusted by admin: " + (request.getPriceAdjustmentReason() != null && !request.getPriceAdjustmentReason().isBlank()
                ? request.getPriceAdjustmentReason() : "Admin price adjustment")
            : "Booking updated by admin";
        eventLogService.logEvent(updated, eventType, previousStatus, null, "ADMIN", eventDesc);
        return toDto(updated);
    }

    // ── Get raw booking entity (for controller-level checks) ──
    public Booking getBookingEntity(String bookingRef) {
        return findScopedBookingByRef(bookingRef);
    }

    // ── Get raw booking entity for background/system flows ──
    public Booking getBookingEntityForSystem(String bookingRef) {
        return findBookingByRef(bookingRef);
    }

    // ── Admin: cancel booking ────────────────────────────────
    @Transactional
    public BookingDto cancelBooking(String bookingRef) {
        return cancelBooking(findScopedBookingByRef(bookingRef), "ADMIN", "Booking cancelled by admin", 100);
    }

    // ── Customer: cancel own PENDING booking ─────────────────
    @Transactional
    public BookingDto cancelBookingByCustomer(String bookingRef, Long customerId) {
        Booking booking = findScopedBookingByRef(bookingRef);
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to cancel this booking");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException(
                "Only PENDING bookings can be cancelled by the customer. Current status: " + booking.getStatus());
        }
        CancellationPolicyDecision decision = evaluateCustomerCancellation(booking);
        if (!decision.allowed()) {
            throw new BusinessException(decision.message());
        }
        return cancelBooking(booking, "CUSTOMER", "Booking cancelled by customer", decision.refundPercentage());
    }

    // ── Customer: reschedule own booking ─────────────────────
    @Transactional
    public BookingDto rescheduleBooking(String bookingRef, Long customerId, RescheduleBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);

        // Ownership check
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to reschedule this booking");
        }

        // Status check: only PENDING or CONFIRMED can be rescheduled
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Only PENDING or CONFIRMED bookings can be rescheduled. Current status: " + booking.getStatus());
        }

        // Anti-abuse: max reschedule limit
        if (booking.getRescheduleCount() >= maxReschedulesPerBooking) {
            throw new BusinessException(
                "This booking has already been rescheduled " + maxReschedulesPerBooking
                    + " times. Please cancel and create a new booking instead.");
        }

        // Cutoff check: must be at least N hours before existing booking start
        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), eventStart);
        if (hoursUntilStart < rescheduleCutoffHours) {
            throw new BusinessException(
                "Rescheduling requires at least " + rescheduleCutoffHours
                    + " hours notice before the booking start time.");
        }

        // New date must be in the future
        if (request.getNewBookingDate().isBefore(LocalDate.now())) {
            throw new BusinessException("New booking date must be today or later");
        }

        // Resolve new duration
        int existingDuration = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
        int newDurMin = request.getNewDurationMinutes() != null ? request.getNewDurationMinutes() : existingDuration;
        if (newDurMin < 30 || newDurMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (newDurMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        Long bingeId = booking.getBingeId();

        // Check availability via internal HTTP call
        int startMinute = request.getNewStartTime().getHour() * 60 + request.getNewStartTime().getMinute();
        Boolean available = availabilityClient.checkSlotAvailable(
            internalApiSecret, request.getNewBookingDate(), bingeId, startMinute, newDurMin);
        if (available != null) {
            availabilityFallback.cacheResult(request.getNewBookingDate(), startMinute, newDurMin, available);
        }
        if (available == null) {
            throw new BusinessException("Availability service is temporarily unavailable. Please try again.");
        }
        if (Boolean.FALSE.equals(available)) {
            throw new BusinessException("The new date/time slot is not available");
        }

        // Acquire advisory lock for the new slot
        bookingRepository.acquireSlotLock(slotLockKey(bingeId, request.getNewBookingDate()));

        // Check for conflicts (exclude this booking from overlap check)
        if (hasTimeConflict(request.getNewBookingDate(), startMinute, newDurMin, booking.getId())) {
            throw new BusinessException("The new time slot conflicts with an existing booking");
        }

        // Capacity check for new slot
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (binge != null && binge.getMaxConcurrentBookings() != null) {
            // Exclude current booking from count if same date
            List<Booking> activeBookings = bookingRepository.findActiveBookingsByBingeAndDate(bingeId, request.getNewBookingDate());
            int newEnd = startMinute + newDurMin;
            int overlapping = 0;
            for (Booking b : activeBookings) {
                if (b.getId().equals(booking.getId())) continue;
                int effDur = getEffectiveDurationMinutes(b);
                if (effDur == 0) continue;
                int existingStart = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
                int existingEnd = existingStart + effDur;
                if (startMinute < existingEnd && newEnd > existingStart) overlapping++;
            }
            if (overlapping >= binge.getMaxConcurrentBookings()) {
                throw new BusinessException("CAPACITY_FULL:The new time slot has reached maximum capacity.");
            }
        }

        // Recalculate pricing if duration changed
        if (newDurMin != existingDuration) {
            PricingService.ResolvedEventPrice eventPrice = pricingService.resolveEventPrice(
                booking.getCustomerId(), booking.getEventType().getId());
            BigDecimal durationDecimalHours = BigDecimal.valueOf(newDurMin)
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
            BigDecimal newBaseAmount = eventPrice.basePrice()
                .add(eventPrice.hourlyRate().multiply(durationDecimalHours).setScale(2, RoundingMode.HALF_UP));
            BigDecimal totalAmount = newBaseAmount.add(booking.getAddOnAmount()).add(booking.getGuestAmount());
            booking.setBaseAmount(newBaseAmount);
            booking.setTotalAmount(totalAmount);
        }

        // Recalculate surge pricing for the new date/time
        PricingService.SurgeResult newSurge = pricingService.resolveSurge(
            request.getNewBookingDate(), request.getNewStartTime());
        if (newSurge != null) {
            booking.setSurgeMultiplier(newSurge.multiplier());
            booking.setSurgeLabel(newSurge.label());
            BigDecimal preSurgeTotal = booking.getBaseAmount()
                .add(booking.getAddOnAmount()).add(booking.getGuestAmount());
            booking.setTotalAmount(preSurgeTotal.multiply(newSurge.multiplier()).setScale(2, RoundingMode.HALF_UP));
        } else {
            // New slot has no surge — clear it and recalculate without surge
            if (booking.getSurgeMultiplier() != null) {
                booking.setSurgeMultiplier(null);
                booking.setSurgeLabel(null);
                booking.setTotalAmount(booking.getBaseAmount()
                    .add(booking.getAddOnAmount()).add(booking.getGuestAmount()));
            }
        }

        // Re-validate venue room if one is assigned (check active + capacity for new slot)
        if (booking.getVenueRoomId() != null) {
            VenueRoom room = venueRoomRepository.findById(booking.getVenueRoomId()).orElse(null);
            if (room == null || !room.isActive()) {
                booking.setVenueRoomId(null);
                booking.setVenueRoomName(null);
            } else {
                // Check room capacity for the new time slot (exclude this booking from the count)
                int newStartMinute = request.getNewStartTime().getHour() * 60 + request.getNewStartTime().getMinute();
                int roomOccupancy = countRoomBookings(room.getId(), request.getNewBookingDate(), newStartMinute, newDurMin, booking.getId());
                if (roomOccupancy >= room.getCapacity()) {
                    throw new BusinessException("Selected room '" + room.getName() + "' is fully booked for the new time slot");
                }
            }
        }

        // Record old values for audit
        String oldDetails = String.format("Date: %s, Time: %s, Duration: %d min",
            booking.getBookingDate(), booking.getStartTime(), existingDuration);

        // Apply changes
        booking.setBookingDate(request.getNewBookingDate());
        booking.setStartTime(request.getNewStartTime());
        booking.setDurationMinutes(newDurMin);
        booking.setDurationHours(newDurMin / 60);
        booking.setRescheduleCount(booking.getRescheduleCount() + 1);
        if (booking.getOriginalBookingRef() == null) {
            booking.setOriginalBookingRef(booking.getBookingRef());
        }

        Booking saved = bookingRepository.save(booking);

        String newDetails = String.format("Date: %s, Time: %s, Duration: %d min",
            saved.getBookingDate(), saved.getStartTime(), newDurMin);
        eventLogService.logEvent(saved, BookingEventType.RESCHEDULED, oldDetails, customerId,
            "CUSTOMER", "Rescheduled (attempt #" + saved.getRescheduleCount() + "): " + oldDetails + " → " + newDetails);
        publishBookingEvent(saved, KafkaTopics.BOOKING_RESCHEDULED);
        log.info("Booking rescheduled: {} (attempt #{})", bookingRef, saved.getRescheduleCount());

        return toDto(saved);
    }

    // ── Customer: transfer booking to another person ─────────
    @Transactional
    public BookingDto transferBooking(String bookingRef, Long customerId, TransferBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);

        // Ownership check
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to transfer this booking");
        }

        // Status check
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Only PENDING or CONFIRMED bookings can be transferred. Current status: " + booking.getStatus());
        }

        // Already transferred check
        if (booking.isTransferred()) {
            throw new BusinessException("This booking has already been transferred once. Further transfers are not allowed.");
        }

        // Cannot transfer to yourself
        if (booking.getCustomerEmail() != null && booking.getCustomerEmail().equalsIgnoreCase(request.getRecipientEmail())) {
            throw new BusinessException("Cannot transfer a booking to yourself");
        }

        // Cutoff check: must be at least N hours before start
        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), eventStart);
        if (hoursUntilStart < transferCutoffHours) {
            throw new BusinessException(
                "Transfers require at least " + transferCutoffHours + " hours notice before the booking start time.");
        }

        // Record original customer details
        String oldCustomerDetails = String.format("Customer: %s (%s)",
            booking.getCustomerName(), booking.getCustomerEmail());
        booking.setOriginalCustomerId(booking.getCustomerId());
        booking.setOriginalCustomerName(booking.getCustomerName());

        // Update to recipient details
        // Note: we keep the same customerId for the original booker since the recipient
        // may not have an account. The transfer is tracked via the original* fields.
        // For a real-world system, you might look up the recipient by email.
        booking.setCustomerName(request.getRecipientName());
        booking.setCustomerEmail(request.getRecipientEmail());
        if (request.getRecipientPhone() != null && !request.getRecipientPhone().isBlank()) {
            booking.setCustomerPhone(request.getRecipientPhone());
        }
        booking.setTransferred(true);

        Booking saved = bookingRepository.save(booking);

        String newCustomerDetails = String.format("Customer: %s (%s)",
            saved.getCustomerName(), saved.getCustomerEmail());
        eventLogService.logEvent(saved, BookingEventType.TRANSFERRED, oldCustomerDetails, customerId,
            "CUSTOMER", "Transferred: " + oldCustomerDetails + " → " + newCustomerDetails);
        publishBookingEvent(saved, KafkaTopics.BOOKING_TRANSFERRED);
        log.info("Booking transferred: {} from {} to {}", bookingRef,
            booking.getOriginalCustomerName(), request.getRecipientName());

        return toDto(saved);
    }

    // ── Customer: create recurring bookings ──────────────────
    @Transactional
    public RecurringBookingResult createRecurringBookings(RecurringBookingRequest request,
                                                          Long customerId, String customerName,
                                                          String customerEmail, String customerPhone) {
        Long bingeId = BingeContext.requireBingeId();

        // Anti-abuse: same limits as single booking creation
        long pendingCount = bookingRepository.countPendingByCustomerId(customerId);
        if (pendingCount >= maxPendingPerCustomer) {
            throw new BusinessException(
                "You already have " + pendingCount + " pending booking(s). Please complete or cancel them before creating new ones.");
        }
        LocalDateTime cooldownSince = LocalDateTime.now().minusMinutes(cooldownMinutesAfterTimeout);
        long recentTimeouts = bookingRepository.countRecentTimeoutCancellations(customerId, cooldownSince);
        if (recentTimeouts >= 2) {
            throw new BusinessException(
                "Too many unpaid bookings were auto-cancelled recently. Please wait a few minutes before trying again.");
        }

        String groupId = "RG-" + Year.now().getValue() + "-"
            + String.format("%08X", ThreadLocalRandom.current().nextInt());

        int durMin = request.getDurationMinutes();
        if (durMin < 30 || durMin > 720 || durMin % 30 != 0) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours in 30-minute increments");
        }

        // Anti-abuse: cap occurrences at 12 (3 months weekly)
        int occurrences = Math.min(request.getOccurrences(), 12);
        if (occurrences < 1) {
            throw new BusinessException("At least 1 occurrence is required");
        }

        EventType eventType = findBookableEventType(request.getEventTypeId());
        List<BookingDto> createdBookings = new ArrayList<>();
        List<RecurringBookingResult.SkippedOccurrence> skipped = new ArrayList<>();

        for (int i = 0; i < occurrences; i++) {
            LocalDate date = calculateRecurrenceDate(request.getStartDate(), request.getPattern(), i);

            // Skip dates in the past
            if (date.isBefore(LocalDate.now())) {
                skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                    .date(date).reason("Date is in the past").build());
                continue;
            }

            try {
                // Check availability
                int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
                Boolean available = availabilityClient.checkSlotAvailable(
                    internalApiSecret, date, bingeId, startMinute, durMin);
                if (available != null) {
                    availabilityFallback.cacheResult(date, startMinute, durMin, available);
                }
                if (Boolean.FALSE.equals(available) || available == null) {
                    skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                        .date(date).reason("Slot not available").build());
                    continue;
                }

                // Acquire slot lock + conflict check
                bookingRepository.acquireSlotLock(slotLockKey(bingeId, date));
                if (hasTimeConflict(date, startMinute, durMin)) {
                    skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                        .date(date).reason("Time slot conflicts with existing booking").build());
                    continue;
                }

                // Capacity check
                Binge binge = bingeRepository.findById(bingeId).orElse(null);
                if (binge != null && binge.getMaxConcurrentBookings() != null) {
                    int overlapping = countOverlappingBookings(date, startMinute, durMin);
                    if (overlapping >= binge.getMaxConcurrentBookings()) {
                        skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                            .date(date).reason("Slot at capacity").build());
                        continue;
                    }
                }

                // Calculate pricing
                PricingService.ResolvedEventPrice eventPrice = pricingService.resolveEventPrice(customerId, request.getEventTypeId());
                BigDecimal durationDecimalHours = BigDecimal.valueOf(durMin)
                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
                BigDecimal baseAmount = eventPrice.basePrice()
                    .add(eventPrice.hourlyRate().multiply(durationDecimalHours).setScale(2, RoundingMode.HALF_UP));

                // Process add-ons
                List<BookingAddOn> bookingAddOns = new ArrayList<>();
                BigDecimal addOnTotal = BigDecimal.ZERO;
                if (request.getAddOns() != null) {
                    for (AddOnSelection sel : request.getAddOns()) {
                        AddOn addOn = findBookableAddOn(sel.getAddOnId());
                        int qty = Math.max(sel.getQuantity(), 1);
                        PricingService.ResolvedAddonPrice addonPrice = pricingService.resolveAddonPrice(customerId, sel.getAddOnId());
                        BigDecimal linePrice = addonPrice.price().multiply(BigDecimal.valueOf(qty));
                        addOnTotal = addOnTotal.add(linePrice);
                        bookingAddOns.add(BookingAddOn.builder()
                            .addOn(addOn).quantity(qty).price(linePrice).build());
                    }
                }

                int guests = Math.max(request.getNumberOfGuests(), 1);
                BigDecimal guestAmount = eventPrice.pricePerGuest()
                    .multiply(BigDecimal.valueOf(Math.max(guests - 1, 0)));
                BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);

                // Apply surge pricing per occurrence date/time
                BigDecimal surgeMultiplier = null;
                String surgeLabel = null;
                PricingService.SurgeResult surge = pricingService.resolveSurge(date, request.getStartTime());
                if (surge != null) {
                    surgeMultiplier = surge.multiplier();
                    surgeLabel = surge.label();
                    totalAmount = totalAmount.multiply(surgeMultiplier).setScale(2, RoundingMode.HALF_UP);
                }

                // Validate venue room if requested
                Long venueRoomId = null;
                String venueRoomName = null;
                if (request.getVenueRoomId() != null) {
                    VenueRoom room = venueRoomRepository.findByIdAndBingeId(request.getVenueRoomId(), bingeId).orElse(null);
                    if (room != null && room.isActive()) {
                        int roomOccupancy = countRoomBookings(room.getId(), date, startMinute, durMin);
                        if (roomOccupancy < room.getCapacity()) {
                            venueRoomId = room.getId();
                            venueRoomName = room.getName();
                        }
                        // silently skip room assignment if at capacity (don't fail the whole occurrence)
                    }
                }

                Booking booking = Booking.builder()
                    .bookingRef(generateBookingRef())
                    .bingeId(bingeId)
                    .customerId(customerId)
                    .customerName(customerName)
                    .customerEmail(customerEmail)
                    .customerPhone(customerPhone)
                    .eventType(eventType)
                    .bookingDate(date)
                    .startTime(request.getStartTime())
                    .durationHours(durMin / 60)
                    .durationMinutes(durMin)
                    .numberOfGuests(guests)
                    .specialNotes(request.getSpecialNotes())
                    .baseAmount(baseAmount)
                    .addOnAmount(addOnTotal)
                    .guestAmount(guestAmount)
                    .totalAmount(totalAmount)
                    .surgeMultiplier(surgeMultiplier)
                    .surgeLabel(surgeLabel)
                    .pricingSource(eventPrice.source())
                    .rateCodeName(eventPrice.rateCodeName())
                    .venueRoomId(venueRoomId)
                    .venueRoomName(venueRoomName)
                    .status(BookingStatus.PENDING)
                    .paymentStatus(PaymentStatus.PENDING)
                    .recurringGroupId(groupId)
                    .build();

                for (BookingAddOn ba : bookingAddOns) {
                    ba.setBooking(booking);
                }
                booking.setAddOns(bookingAddOns);

                Booking saved = bookingRepository.save(booking);
                eventLogService.logEvent(saved, BookingEventType.CREATED, null, customerId,
                    "CUSTOMER", "Recurring booking created (group: " + groupId + ")");
                sagaOrchestrator.startSaga(saved.getBookingRef());
                publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);
                createdBookings.add(toDto(saved));

            } catch (BusinessException e) {
                skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                    .date(date).reason(e.getMessage()).build());
            } catch (Exception e) {
                log.warn("Unexpected error creating recurring booking for date {}: {}", date, e.getMessage());
                skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                    .date(date).reason("Unexpected error — please retry this date individually").build());
            }
        }

        if (createdBookings.isEmpty()) {
            throw new BusinessException("No bookings could be created. All dates were unavailable or conflicting.");
        }

        log.info("Recurring group {} created: {} bookings, {} skipped", groupId,
            createdBookings.size(), skipped.size());

        return RecurringBookingResult.builder()
            .recurringGroupId(groupId)
            .requestedOccurrences(request.getOccurrences())
            .successfulOccurrences(createdBookings.size())
            .skippedOccurrences(skipped.size())
            .createdBookings(createdBookings)
            .skipped(skipped)
            .build();
    }

    private LocalDate calculateRecurrenceDate(LocalDate startDate,
                                               RecurringBookingRequest.RecurrencePattern pattern, int index) {
        return switch (pattern) {
            case WEEKLY -> startDate.plusWeeks(index);
            case BIWEEKLY -> startDate.plusWeeks(index * 2L);
            case MONTHLY -> startDate.plusMonths(index);
        };
    }

    // ── Customer: get all bookings in a recurring group ──────
    @Transactional(readOnly = true)
    public List<BookingDto> getRecurringGroupBookings(String groupId, Long customerId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> bookings;
        if (bid != null) {
            bookings = bookingRepository.findByRecurringGroupIdAndBingeId(groupId, bid);
        } else {
            bookings = bookingRepository.findByRecurringGroupId(groupId);
        }
        // Verify ownership: at least one booking in the group belongs to the customer
        boolean owns = bookings.stream().anyMatch(b -> b.getCustomerId().equals(customerId));
        if (!owns) {
            throw new BusinessException("Not authorised to view this recurring group");
        }
        return bookings.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getPendingCustomerReviews(Long customerId, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        Long bid = BingeContext.getBingeId();

        List<Booking> completed = (bid != null
            ? bookingRepository.findCustomerPastBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerPastBookings(customerId, today)).stream()
            .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
            .toList();

        return completed.stream()
            .filter(b -> bookingReviewRepository
                .findByBookingRefAndCustomerIdAndReviewerRole(b.getBookingRef(), customerId, "CUSTOMER")
                .isEmpty())
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public BookingReviewDto getCustomerReview(String bookingRef, Long customerId) {
        Booking booking = findScopedBookingByRef(bookingRef);
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to access this review");
        }

        BookingReview review = bookingReviewRepository
            .findByBookingRefAndCustomerIdAndReviewerRole(bookingRef, customerId, "CUSTOMER")
            .orElseThrow(() -> new ResourceNotFoundException("BookingReview", "bookingRef", bookingRef));
        return toReviewDto(review);
    }

    @Transactional
    public BookingReviewDto submitCustomerReview(String bookingRef, Long customerId, CustomerReviewRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to review this booking");
        }
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Reviews can be submitted only after booking completion");
        }

        boolean skipped = request.getSkipped() != null && request.getSkipped();
        if (!skipped && request.getRating() == null) {
            throw new BusinessException("Please provide a rating or choose skip");
        }

        BookingReview review = bookingReviewRepository
            .findByBookingRefAndCustomerIdAndReviewerRole(bookingRef, customerId, "CUSTOMER")
            .orElseGet(() -> BookingReview.builder()
                .booking(booking)
                .bingeId(booking.getBingeId())
                .bookingRef(bookingRef)
                .customerId(customerId)
                .reviewerRole("CUSTOMER")
                .visibleToCustomer(true)
                .build());

        review.setSkipped(skipped);
        review.setRating(skipped ? null : request.getRating());
        review.setComment(trimToNull(request.getComment()));

        BookingReview saved = bookingReviewRepository.save(review);
        return toReviewDto(saved);
    }

    @Transactional
    public BookingReviewDto submitAdminReview(String bookingRef, Long adminId, String role, AdminReviewRequest request) {
        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("Only admins can submit admin reviews", org.springframework.http.HttpStatus.FORBIDDEN);
        }

        Booking booking = findScopedBookingByRef(bookingRef);
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Admin reviews can be submitted only after booking completion");
        }

        BookingReview review = bookingReviewRepository
            .findByBookingRefAndAdminIdAndReviewerRole(bookingRef, adminId, "ADMIN")
            .orElseGet(() -> BookingReview.builder()
                .booking(booking)
                .bingeId(booking.getBingeId())
                .bookingRef(bookingRef)
                .customerId(booking.getCustomerId())
                .adminId(adminId)
                .reviewerRole("ADMIN")
                .visibleToCustomer(false)
                .build());

        review.setSkipped(false);
        review.setRating(request.getRating());
        review.setComment(trimToNull(request.getComment()));

        BookingReview saved = bookingReviewRepository.save(review);
        return toReviewDto(saved);
    }

    @Transactional(readOnly = true)
    public List<BookingReviewDto> getAdminReviewsForBooking(String bookingRef, String role) {
        if (!"ADMIN".equalsIgnoreCase(role) && !"SUPER_ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessException("Only admins can view booking reviews", org.springframework.http.HttpStatus.FORBIDDEN);
        }

        findScopedBookingByRef(bookingRef);

        return bookingReviewRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef)
            .stream()
            .map(this::toReviewDto)
            .toList();
    }

    // ── Public: binge review summary (overall rating + distribution) ──
    @Transactional(readOnly = true, timeout = 10)
    public BingeReviewSummaryDto getBingeReviewSummary(Long bingeId) {
        long count = bookingReviewRepository.countBingeCustomerReviews(bingeId);
        double avg = count > 0 ? bookingReviewRepository.averageBingeRating(bingeId) : 0;
        List<Object[]> dist = bookingReviewRepository.ratingDistribution(bingeId);
        java.util.Map<Integer, Long> distribution = new java.util.LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) distribution.put(star, 0L);
        for (Object[] row : dist) {
            distribution.put((Integer) row[0], (Long) row[1]);
        }
        return BingeReviewSummaryDto.builder()
            .bingeId(bingeId)
            .averageRating(Math.round(avg * 10.0) / 10.0)
            .totalReviews(count)
            .ratingDistribution(distribution)
            .build();
    }

    // ── Public: paginated customer reviews for a binge ──
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<BookingReviewDto> getBingePublicReviews(Long bingeId, org.springframework.data.domain.Pageable pageable) {
        return bookingReviewRepository
            .findByBingeIdAndReviewerRoleAndSkippedFalseAndVisibleToCustomerTrueAndRatingIsNotNull(bingeId, "CUSTOMER", pageable)
            .map(this::toReviewDto);
    }

    // ── Admin: customer review summary (avg admin rating + count) ──
    @Transactional(readOnly = true, timeout = 10)
    public java.util.Map<String, Object> getCustomerReviewSummary(Long customerId) {
        double avgAdmin = bookingReviewRepository.averageAdminRatingForCustomer(customerId);
        long countAdmin = bookingReviewRepository.countAdminReviewsForCustomer(customerId);
        long countCustomer = bookingReviewRepository.findByCustomerIdAndReviewerRoleAndSkippedFalseAndRatingIsNotNull(
            customerId, "CUSTOMER", org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        return java.util.Map.of(
            "avgAdminRating", Math.round(avgAdmin * 10.0) / 10.0,
            "adminReviewCount", countAdmin,
            "customerReviewCount", countCustomer
        );
    }

    // ── System: cancel booking without request-scoped binge context ──
    @Transactional
    public BookingDto cancelBookingForSystem(String bookingRef, String reason) {
        return cancelBooking(findBookingByRef(bookingRef), "SYSTEM", reason, 100);
    }

    private BookingDto cancelBooking(Booking booking, String actorRole, String description, int refundPercentage) {
        String bookingRef = booking.getBookingRef();

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("Booking is already cancelled");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED || booking.getStatus() == BookingStatus.NO_SHOW) {
            throw new BusinessException("Cannot cancel a " + booking.getStatus() + " booking");
        }

        String prevStatus = booking.getStatus().name();
        booking.setStatus(BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(booking);

        // Reverse collectedAmount for cancellations where money was already collected
        if (saved.getCollectedAmount() != null
                && saved.getCollectedAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            subtractFromCollectedAmount(bookingRef, saved.getCollectedAmount());
            log.info("Reversed collectedAmount {} for cancelled booking {}",
                    saved.getCollectedAmount(), bookingRef);
        }

        // Reverse loyalty points proportionally based on tiered refund percentage
        if (saved.getLoyaltyPointsRedeemed() > 0) {
            long pointsToReverse = refundPercentage >= 100
                ? saved.getLoyaltyPointsRedeemed()
                : Math.round(saved.getLoyaltyPointsRedeemed() * refundPercentage / 100.0);
            if (pointsToReverse > 0) {
                loyaltyService.reverseRedemption(
                    saved.getCustomerId(),
                    bookingRef, pointsToReverse);
                log.info("Reversed {} of {} loyalty points ({}% refund) for cancelled booking {}",
                    pointsToReverse, saved.getLoyaltyPointsRedeemed(), refundPercentage, bookingRef);
            }
        }

        // Reverse earned points (clawback) — only if points were awarded (COMPLETED bookings being cancelled)
        if (saved.getLoyaltyPointsEarned() > 0) {
            loyaltyService.reverseEarnedPoints(
                saved.getCustomerId(), bookingRef);
            log.info("Clawed back {} earned loyalty points for cancelled booking {}",
                saved.getLoyaltyPointsEarned(), bookingRef);
        }

        String eventDescription = StringUtils.hasText(description)
            ? description
            : ("SYSTEM".equals(actorRole) ? "Booking cancelled by system" : "Booking cancelled by admin");
        eventLogService.logEvent(saved, BookingEventType.CANCELLED, prevStatus, null, actorRole,
            eventDescription);
        publishBookingEvent(saved, KafkaTopics.BOOKING_CANCELLED);
        log.info("Booking cancelled: {}", bookingRef);

        return toDto(saved);
    }

    // ── Update payment status (called by payment-service via Kafka) ──
    @Transactional
    public void updatePaymentStatus(String bookingRef, PaymentStatus paymentStatus, String paymentMethod) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
        String prevStatus = booking.getStatus().name();
        booking.setPaymentStatus(paymentStatus);
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            booking.setPaymentMethod(paymentMethod);
        }
        // Only auto-confirm PENDING bookings; don't overwrite CHECKED_IN or COMPLETED
        if (paymentStatus == PaymentStatus.SUCCESS
                && booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.CONFIRMED);
        }
        bookingRepository.save(booking);
        eventLogService.logEvent(booking, BookingEventType.PAYMENT_UPDATED, prevStatus, null, "SYSTEM",
            "Payment status changed to " + paymentStatus.name());
        log.info("Payment status updated for {}: {}", bookingRef, paymentStatus);
    }

    // ── Collected amount tracking (called by payment-service via Kafka) ──
    @Transactional
    public void addToCollectedAmount(String bookingRef, java.math.BigDecimal amount) {
        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) <= 0) return;
        int updated = bookingRepository.addToCollectedAmount(bookingRef, amount);
        if (updated > 0) {
            bookingRepository.findByBookingRef(bookingRef).ifPresent(b -> {
                if (b.getTotalAmount() != null && b.getCollectedAmount() != null
                        && b.getCollectedAmount().compareTo(b.getTotalAmount()) != 0) {
                    log.warn("Payment mismatch for {}: collectedAmount={} vs totalAmount={}",
                        bookingRef, b.getCollectedAmount(), b.getTotalAmount());
                }
            });
        }
    }

    @Transactional
    public void subtractFromCollectedAmount(String bookingRef, java.math.BigDecimal amount) {
        if (amount == null || amount.compareTo(java.math.BigDecimal.ZERO) <= 0) return;
        bookingRepository.subtractFromCollectedAmount(bookingRef, amount);
    }

    // ── Dashboard stats ──────────────────────────────────────
    @Transactional(readOnly = true, timeout = 10)
    public DashboardStatsDto getDashboardStats(LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        if (bid != null) {
            return DashboardStatsDto.builder()
                .totalBookings(bookingRepository.countByBingeIdAndBookingDate(bid, today))
                .pendingBookings(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.PENDING))
                .confirmedBookings(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.CONFIRMED))
                .cancelledBookings(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.CANCELLED))
                .completedBookings(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.COMPLETED))
                .totalRevenue(bookingRepository.actualRevenueByBingeAndDate(bid, today))
                .todayTotal(bookingRepository.countByBingeIdAndBookingDate(bid, today))
                .todayConfirmed(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.CONFIRMED))
                .todayCheckedIn(bookingRepository.countByBingeAndDateAndCheckedIn(bid, today, true))
                .todayPending(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.PENDING))
                .todayCompleted(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.COMPLETED))
                .todayCancelled(bookingRepository.countByBingeIdAndBookingDateAndStatus(bid, today, BookingStatus.CANCELLED))
                .todayRevenue(bookingRepository.actualRevenueByBingeAndDate(bid, today))
                .todayEstimatedRevenue(bookingRepository.estimatedRevenueByBingeAndDate(bid, today))
                .build();
        }
        return DashboardStatsDto.builder()
            .totalBookings(bookingRepository.countByBookingDate(today))
            .pendingBookings(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.PENDING))
            .confirmedBookings(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CONFIRMED))
            .cancelledBookings(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CANCELLED))
            .completedBookings(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.COMPLETED))
            .totalRevenue(bookingRepository.actualRevenueByDate(today))
            // Today
            .todayTotal(bookingRepository.countByBookingDate(today))
            .todayConfirmed(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CONFIRMED))
            .todayCheckedIn(bookingRepository.countByBookingDateAndCheckedIn(today, true))
            .todayPending(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.PENDING))
            .todayCompleted(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.COMPLETED))
            .todayCancelled(bookingRepository.countByBookingDateAndStatus(today, BookingStatus.CANCELLED))
            .todayRevenue(bookingRepository.actualRevenueByDate(today))
            .todayEstimatedRevenue(bookingRepository.estimatedRevenueByDate(today))
            .build();
    }

    // ── Audit: auto-mark past unchecked-in bookings ────────
    @Transactional
    public AuditResultDto runAudit(LocalDate clientToday, LocalDateTime clientNow) {
        // Use client's date/time as reference so UTC-offset servers don't break IST admins
        LocalDate refToday = clientToday != null ? clientToday : LocalDate.now();
        LocalDateTime refNow  = clientNow  != null ? clientNow  : LocalDateTime.now();

        Long bid = BingeContext.getBingeId();
        LocalDate operationalDate = systemSettingsService.getOperationalDate(bid, refToday);

        // Guard: operational date must not be ahead of client's today
        if (operationalDate.isAfter(refToday)) {
            throw new BusinessException(
                "Cannot audit — operational date (" + operationalDate + ") is already ahead of today (" + refToday + ").");
        }

        // Time guard: audit only allowed at or after 23:59 (client local time).
        // If client's date has already moved past the operational date (missed last night),
        // the time constraint is automatically satisfied.
        if (!refToday.isAfter(operationalDate)) {
            LocalDateTime auditAllowedFrom = operationalDate.atTime(LocalTime.of(23, 59));
            if (!refNow.isAfter(auditAllowedFrom.minusSeconds(1))) {
                String hhmm = String.format("%02d:%02d", refNow.getHour(), refNow.getMinute());
                throw new BusinessException(
                    "Audit can only be run after 11:59 PM. Your local time: " + hhmm);
            }
        }

        List<Booking> pastBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, operationalDate)
            : bookingRepository.findActiveBookingsByDate(operationalDate);
        int markedNoShow = 0;
        int markedCompleted = 0;
        List<String> affectedRefs = new ArrayList<>();

        for (Booking b : pastBookings) {
            if (b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING) {
                String prev = b.getStatus().name();
                b.setStatus(BookingStatus.NO_SHOW);
                bookingRepository.save(b);
                eventLogService.logEvent(b, BookingEventType.NO_SHOW, prev, null, "SYSTEM",
                    "Marked no-show by end-of-day audit");
                markedNoShow++;
                affectedRefs.add(b.getBookingRef());
            } else if (b.getStatus() == BookingStatus.CHECKED_IN) {
                b.setStatus(BookingStatus.COMPLETED);
                b.setCheckedIn(false);
                bookingRepository.save(b);
                eventLogService.logEvent(b, BookingEventType.COMPLETED, "CHECKED_IN", null, "SYSTEM",
                    "Auto-completed by end-of-day audit");
                awardLoyaltyPoints(b);
                markedCompleted++;
                affectedRefs.add(b.getBookingRef());
            }
        }

        log.info("Audit for binge {} date {}: {} no-shows, {} completed", bid, operationalDate, markedNoShow, markedCompleted);

        // Advance operational date for this binge (or global fallback)
        LocalDate newOpDate = systemSettingsService.advanceOperationalDate(bid, refToday);

        return AuditResultDto.builder()
            .auditDate(operationalDate)
            .newOperationalDate(newOpDate)
            .totalProcessed(pastBookings.size())
            .markedNoShow(markedNoShow)
            .markedCompleted(markedCompleted)
            .affectedBookingRefs(affectedRefs)
            .build();
    }

    // ── Reports ──────────────────────────────────────────────
    public ReportDto getReport(String period, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        LocalDate from;
        switch (period.toUpperCase()) {
            case "WEEK":
                from = today.minusDays(today.getDayOfWeek().getValue() - 1);
                break;
            case "MONTH":
                from = today.withDayOfMonth(1);
                break;
            case "YEAR":
                from = today.withDayOfYear(1);
                break;
            default: // DAY
                from = today;
                break;
        }
        Long bid = BingeContext.getBingeId();
        return ReportDto.builder()
            .fromDate(from)
            .toDate(today)
            .period(period.toUpperCase())
            .totalBookings(from.equals(today)
                ? (bid != null ? bookingRepository.countNonCancelledByBingeAndDate(bid, today) : bookingRepository.countNonCancelledByDate(today))
                : (bid != null ? bookingRepository.countNonCancelledByBingeAndDateRange(bid, from, today) : bookingRepository.countNonCancelledByDateRange(from, today)))
            .totalRevenue(from.equals(today)
                ? (bid != null ? bookingRepository.actualRevenueByBingeAndDate(bid, today) : bookingRepository.actualRevenueByDate(today))
                : (bid != null ? bookingRepository.actualRevenueByBingeAndDateRange(bid, from, today) : bookingRepository.actualRevenueByDateRange(from, today)))
            .estimatedRevenue(from.equals(today)
                ? (bid != null ? bookingRepository.estimatedRevenueByBingeAndDate(bid, today) : bookingRepository.estimatedRevenueByDate(today))
                : (bid != null ? bookingRepository.estimatedRevenueByBingeAndDateRange(bid, from, today) : bookingRepository.estimatedRevenueByDateRange(from, today)))
            .build();
    }

    // ── Reports: custom date range ───────────────────────────
    public ReportDto getReportByDateRange(LocalDate from, LocalDate to, LocalDate clientToday) {
        LocalDate refToday = clientToday != null ? clientToday : LocalDate.now();
        if (to.isAfter(refToday)) to = refToday;
        if (from.isAfter(to)) from = to;
        LocalDate finalFrom = from;
        LocalDate finalTo = to;
        Long bid = BingeContext.getBingeId();
        return ReportDto.builder()
            .fromDate(finalFrom)
            .toDate(finalTo)
            .period("CUSTOM")
            .totalBookings(finalFrom.equals(finalTo)
                ? (bid != null ? bookingRepository.countNonCancelledByBingeAndDate(bid, finalFrom) : bookingRepository.countNonCancelledByDate(finalFrom))
                : (bid != null ? bookingRepository.countNonCancelledByBingeAndDateRange(bid, finalFrom, finalTo) : bookingRepository.countNonCancelledByDateRange(finalFrom, finalTo)))
            .totalRevenue(finalFrom.equals(finalTo)
                ? (bid != null ? bookingRepository.actualRevenueByBingeAndDate(bid, finalFrom) : bookingRepository.actualRevenueByDate(finalFrom))
                : (bid != null ? bookingRepository.actualRevenueByBingeAndDateRange(bid, finalFrom, finalTo) : bookingRepository.actualRevenueByDateRange(finalFrom, finalTo)))
            .estimatedRevenue(finalFrom.equals(finalTo)
                ? (bid != null ? bookingRepository.estimatedRevenueByBingeAndDate(bid, finalFrom) : bookingRepository.estimatedRevenueByDate(finalFrom))
                : (bid != null ? bookingRepository.estimatedRevenueByBingeAndDateRange(bid, finalFrom, finalTo) : bookingRepository.estimatedRevenueByDateRange(finalFrom, finalTo)))
            .build();
    }

    // ── House accounts: pending payments ──────────────────────
    public Page<BookingDto> getPendingPaymentBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndPaymentStatus(bid, PaymentStatus.PENDING, pageable) : bookingRepository.findByPaymentStatus(PaymentStatus.PENDING, pageable)).map(this::toDto);
    }

    // ── Customer booking count ────────────────────────────
    public long getCustomerBookingCount(Long customerId) {
        Long bid = BingeContext.getBingeId();
        return bid != null ? bookingRepository.countByBingeIdAndCustomerId(bid, customerId) : bookingRepository.countByCustomerId(customerId);
    }

    // ── Booked slots for a date (for double-booking prevention) ──
    @Transactional(readOnly = true)
    public List<BookedSlotDto> getBookedSlotsForDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        List<Booking> active = bid != null
            ? bookingRepository.findActiveBookingsForReadByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsForReadByDate(date);
        return active.stream()
            .map(b -> {
                int startMin = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
                int effMin = getEffectiveDurationMinutes(b);
                return BookedSlotDto.builder()
                    .startHour(b.getStartTime().getHour())
                    .durationHours(effMin / 60)
                    .startMinute(startMin)
                    .durationMinutes(effMin)
                    .bookingRef(b.getBookingRef())
                    .build();
            })
            .filter(slot -> slot.getDurationMinutes() > 0)
            .toList();
    }

    // ── Check for time overlap with existing bookings (minutes-based) ──
    @Transactional(readOnly = true)
    public boolean hasTimeConflict(LocalDate date, int startMinute, int durationMinutes) {
        return hasTimeConflict(date, startMinute, durationMinutes, null);
    }

    @Transactional(readOnly = true)
    public boolean hasTimeConflict(LocalDate date, int startMinute, int durationMinutes, Long excludeBookingId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
        int newEnd = startMinute + durationMinutes;
        for (Booking b : activeBookings) {
            if (excludeBookingId != null && excludeBookingId.equals(b.getId())) continue;
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue; // fully freed early checkout
            int existingStart = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
            int existingEnd = existingStart + effectiveDuration;
            if (startMinute < existingEnd && newEnd > existingStart) {
                return true; // overlap
            }
        }
        return false;
    }

    /** Count active bookings that overlap with a given time range (for capacity enforcement). */
    @Transactional(readOnly = true)
    public int countOverlappingBookings(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
        int newEnd = startMinute + durationMinutes;
        int count = 0;
        for (Booking b : activeBookings) {
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            int existingStart = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
            int existingEnd = existingStart + effectiveDuration;
            if (startMinute < existingEnd && newEnd > existingStart) {
                count++;
            }
        }
        return count;
    }

    /** Count active bookings assigned to a specific venue room for a given time slot. */
    private int countRoomBookings(Long roomId, LocalDate date, int startMinute, int durationMinutes) {
        return countRoomBookings(roomId, date, startMinute, durationMinutes, null);
    }

    /** Count active bookings assigned to a specific venue room, optionally excluding one booking. */
    private int countRoomBookings(Long roomId, LocalDate date, int startMinute, int durationMinutes, Long excludeBookingId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsForReadByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsForReadByDate(date);
        int newEnd = startMinute + durationMinutes;
        int count = 0;
        for (Booking b : activeBookings) {
            if (excludeBookingId != null && excludeBookingId.equals(b.getId())) continue;
            if (!roomId.equals(b.getVenueRoomId())) continue;
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            int existingStart = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
            int existingEnd = existingStart + effectiveDuration;
            if (startMinute < existingEnd && newEnd > existingStart) {
                count++;
            }
        }
        return count;
    }

    /** Award loyalty points when a booking is completed. */
    private void awardLoyaltyPoints(Booking booking) {
        try {
            BigDecimal payableAmount = booking.getCollectedAmount() != null
                ? booking.getCollectedAmount() : booking.getTotalAmount();
            long earned = loyaltyService.earnPoints(
                booking.getCustomerId(),
                booking.getBookingRef(), payableAmount);
            if (earned > 0) {
                booking.setLoyaltyPointsEarned(earned);
                bookingRepository.save(booking);
            }
        } catch (Exception e) {
            log.error("Failed to award loyalty points for booking {}: {}", booking.getBookingRef(), e.getMessage(), e);
        }
    }

    /** Get capacity info: current occupancy vs max for a given slot. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getSlotCapacity(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.requireBingeId();
        return getSlotCapacityForBinge(bid, date, startMinute, durationMinutes);
    }

    /** Get capacity info for a specific binge (no BingeContext required). */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getSlotCapacityForBinge(Long bingeId, LocalDate date, int startMinute, int durationMinutes) {
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        List<Booking> activeBookings = bookingRepository.findActiveBookingsByBingeAndDate(bingeId, date);
        int newEnd = startMinute + durationMinutes;
        int overlapping = 0;
        for (Booking b : activeBookings) {
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            int existingStart = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
            int existingEnd = existingStart + effectiveDuration;
            if (startMinute < existingEnd && newEnd > existingStart) {
                overlapping++;
            }
        }
        Integer max = binge != null ? binge.getMaxConcurrentBookings() : null;
        return java.util.Map.of(
            "currentBookings", overlapping,
            "maxConcurrentBookings", max != null ? max : -1,
            "isFull", max != null && overlapping >= max
        );
    }

    /**
     * For COMPLETED bookings with an early checkout, return the actual used minutes
     * rounded UP to the nearest 30-minute boundary so remaining time becomes available.
     * Returns 0 when the booking was checked out before it even started.
     */
    private int getEffectiveDurationMinutes(Booking b) {
        if (b.getStatus() == BookingStatus.COMPLETED
                && b.getActualUsedMinutes() != null) {
            if (b.getActualUsedMinutes() == 0) return 0;
            // Round up to nearest 30 minutes
            return ((int) Math.ceil(b.getActualUsedMinutes() / 30.0)) * 30;
        }
        return resolveDurationMinutes(b.getDurationMinutes(), b.getDurationHours());
    }

    /** Resolve canonical duration in minutes. durationMinutes takes precedence; falls back to durationHours * 60. */
    private static int resolveDurationMinutes(Integer durationMinutes, int durationHours) {
        return (durationMinutes != null && durationMinutes > 0) ? durationMinutes : durationHours * 60;
    }

    private static final java.util.Map<BookingStatus, java.util.Set<BookingStatus>> VALID_TRANSITIONS = java.util.Map.of(
        BookingStatus.PENDING, java.util.Set.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED),
        BookingStatus.CONFIRMED, java.util.Set.of(BookingStatus.CHECKED_IN, BookingStatus.CANCELLED, BookingStatus.NO_SHOW),
        BookingStatus.CHECKED_IN, java.util.Set.of(BookingStatus.COMPLETED),
        BookingStatus.COMPLETED, java.util.Set.of(),
        BookingStatus.CANCELLED, java.util.Set.of(),
        BookingStatus.NO_SHOW, java.util.Set.of()
    );

    private static boolean isValidTransition(BookingStatus from, BookingStatus to) {
        return VALID_TRANSITIONS.getOrDefault(from, java.util.Set.of()).contains(to);
    }

    // ── Admin: early checkout ────────────────────────────────
    @Transactional
    public BookingDto earlyCheckout(String bookingRef, LocalDateTime clientNow) {
        Booking booking = findScopedBookingByRef(bookingRef);

        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BusinessException(
                "Early checkout requires the booking to be CHECKED_IN. Current status: " + booking.getStatus());
        }

        var ps = booking.getPaymentStatus();
        if (ps == PaymentStatus.PENDING || ps == PaymentStatus.FAILED) {
            throw new BusinessException(
                "Cannot checkout — no valid payment on this booking. Collect payment before checking out.");
        }

        LocalDateTime now = clientNow != null ? clientNow : LocalDateTime.now();
        LocalDateTime scheduledStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        int bookedMinutes = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
        LocalDateTime scheduledEnd = scheduledStart.plusMinutes(bookedMinutes);

        // Only treat as early if current time is before the scheduled end
        if (!now.isBefore(scheduledEnd)) {
            // Not early — do a normal checkout
            booking.setStatus(BookingStatus.COMPLETED);
            booking.setCheckedIn(false);
            booking.setActualCheckoutTime(now);
            Booking completed = bookingRepository.save(booking);
            awardLoyaltyPoints(completed);
            return toDto(completed);
        }

        long usedMinutes = java.time.Duration.between(scheduledStart, now).toMinutes();
        if (usedMinutes < 0) usedMinutes = 0;

        // Round up used minutes to nearest 30-minute boundary
        int roundedUsed = ((int) Math.ceil(usedMinutes / 30.0)) * 30;
        long remainingMinutes = bookedMinutes - roundedUsed;
        if (remainingMinutes < 0) remainingMinutes = 0;

        // Build a human-readable note
        long usedHours = usedMinutes / 60;
        long usedMins = usedMinutes % 60;
        String usedStr = usedHours > 0
            ? String.format("%dh %dm", usedHours, usedMins)
            : String.format("%dm", usedMins);

        long remHours = remainingMinutes / 60;
        long remMins = remainingMinutes % 60;
        String remStr = remHours > 0
            ? String.format("%dh %dm", remHours, remMins)
            : String.format("%dm", remMins);

        // Build booked duration string
        long bookedH = bookedMinutes / 60;
        long bookedM = bookedMinutes % 60;
        String bookedStr = bookedM > 0
            ? String.format("%dh %dm", bookedH, bookedM)
            : String.format("%dh", bookedH);

        String note = String.format(
            "Early checkout at %s. Used %s of %s booked (rounded to %dm). %s remaining time released.",
            now.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")),
            usedStr, bookedStr, roundedUsed, remStr);

        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCheckedIn(false);
        booking.setActualCheckoutTime(now);
        booking.setActualUsedMinutes((int) usedMinutes);
        booking.setEarlyCheckoutNote(note);

        // Append to admin notes as well
        String existing = booking.getAdminNotes() != null ? booking.getAdminNotes() + " | " : "";
        booking.setAdminNotes(existing + note);

        Booking saved = bookingRepository.save(booking);
        eventLogService.logEvent(saved, BookingEventType.CHECKED_OUT, "CHECKED_IN", null, "ADMIN",
            note);
        log.info("Early checkout for {}: {}", bookingRef, note);

        awardLoyaltyPoints(saved);

        return toDto(saved);
    }

    // ── Admin: create booking (walk-in) ──────────────────────
    @Transactional
    public BookingDto adminCreateBooking(AdminCreateBookingRequest request) {
        EventType eventType = findBookableEventType(request.getEventTypeId());
        validateAdminCustomerDetails(request);

        // Resolve duration in minutes
        int durMin = resolveDurationMinutes(request.getDurationMinutes(), request.getDurationHours());
        if (durMin < 30 || durMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (durMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        // Check for double-booking with existing reservations
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        Long adminBingeId = BingeContext.getBingeId();
        if (adminBingeId != null) {
            bookingRepository.acquireSlotLock(slotLockKey(adminBingeId, request.getBookingDate()));
        }
        if (hasTimeConflict(request.getBookingDate(), startMinute, durMin)) {
            throw new BusinessException("Selected time slot conflicts with an existing booking");
        }

        // Calculate pricing using resolved customer pricing (if customer is known)
        Long custId = request.getCustomerId() != null ? request.getCustomerId() : 0L;
        String pricingSource = "DEFAULT";
        String rateCodeName = null;

        // If admin specified a rate code override, resolve all pricing from that rate code
        ResolvedPricingDto rateCodePricingOverride = null;
        if (request.getRateCodeId() != null) {
            rateCodePricingOverride = pricingService.resolveRateCodePricing(request.getRateCodeId());
            pricingSource = "RATE_CODE";
            rateCodeName = rateCodePricingOverride.getRateCodeName();
        }

        PricingService.ResolvedEventPrice eventPrice;
        if (rateCodePricingOverride != null) {
            final String rcName = rateCodeName;
            eventPrice = rateCodePricingOverride.getEventPricings().stream()
                .filter(ep -> ep.getEventTypeId().equals(request.getEventTypeId()))
                .findFirst()
                .map(ep -> new PricingService.ResolvedEventPrice(
                    ep.getBasePrice(), ep.getHourlyRate(), ep.getPricePerGuest(), "RATE_CODE", rcName))
                .orElse(new PricingService.ResolvedEventPrice(
                    eventType.getBasePrice(), eventType.getHourlyRate(), eventType.getPricePerGuest(), "RATE_CODE", rcName));
        } else if (custId > 0) {
            eventPrice = pricingService.resolveEventPrice(custId, request.getEventTypeId());
            pricingSource = eventPrice.source();
            rateCodeName = eventPrice.rateCodeName();
        } else {
            eventPrice = new PricingService.ResolvedEventPrice(
                eventType.getBasePrice(), eventType.getHourlyRate(), eventType.getPricePerGuest(), "DEFAULT", null);
        }

        BigDecimal durationDecimalHours = BigDecimal.valueOf(durMin)
            .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal baseAmount = eventPrice.basePrice()
            .add(eventPrice.hourlyRate().multiply(durationDecimalHours).setScale(2, RoundingMode.HALF_UP));

        List<BookingAddOn> bookingAddOns = new ArrayList<>();
        BigDecimal addOnTotal = BigDecimal.ZERO;

        if (request.getAddOns() != null) {
            for (AddOnSelection sel : request.getAddOns()) {
                AddOn addOn = findBookableAddOn(sel.getAddOnId());
                int qty = Math.max(sel.getQuantity(), 1);
                BigDecimal resolvedPrice;
                if (rateCodePricingOverride != null) {
                    resolvedPrice = rateCodePricingOverride.getAddonPricings().stream()
                        .filter(ap -> ap.getAddOnId().equals(sel.getAddOnId()))
                        .findFirst()
                        .map(ResolvedPricingDto.AddonPricing::getPrice)
                        .orElse(addOn.getPrice());
                } else if (custId > 0) {
                    PricingService.ResolvedAddonPrice ap = pricingService.resolveAddonPrice(custId, sel.getAddOnId());
                    resolvedPrice = ap.price();
                } else {
                    resolvedPrice = addOn.getPrice();
                }
                BigDecimal linePrice = resolvedPrice.multiply(BigDecimal.valueOf(qty));
                addOnTotal = addOnTotal.add(linePrice);
                bookingAddOns.add(BookingAddOn.builder()
                    .addOn(addOn).quantity(qty).price(linePrice).build());
            }
        }

        // Guest charge
        int adminGuests = Math.max(request.getNumberOfGuests(), 1);
        BigDecimal guestAmount = eventPrice.pricePerGuest()
            .multiply(BigDecimal.valueOf(Math.max(adminGuests - 1, 0)));

        // Check for admin price overrides
        if (request.getOverrideBaseAmount() != null) {
            baseAmount = request.getOverrideBaseAmount();
            pricingSource = "ADMIN_OVERRIDE";
        }
        if (request.getOverrideTotalAmount() != null) {
            // Admin explicitly sets total
            BigDecimal totalAmount = request.getOverrideTotalAmount();
            pricingSource = "ADMIN_OVERRIDE";
            validateAdminPaymentTracking(custId, totalAmount);

            String bookingRef = generateBookingRef();
            boolean autoConfirm = totalAmount.compareTo(BigDecimal.ZERO) == 0;
            BookingStatus status = autoConfirm ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
            PaymentStatus payStatus = autoConfirm ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

            Booking booking = Booking.builder()
                .bookingRef(bookingRef)
                .bingeId(BingeContext.getBingeId())
                .customerId(custId)
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .customerPhone(request.getCustomerPhone() != null ? request.getCustomerPhone() : "")
                .eventType(eventType)
                .bookingDate(request.getBookingDate())
                .startTime(request.getStartTime())
                .durationHours(durMin / 60)
                .durationMinutes(durMin)
                .numberOfGuests(adminGuests)
                .specialNotes(request.getSpecialNotes())
                .adminNotes(request.getAdminNotes())
                .baseAmount(baseAmount)
                .addOnAmount(addOnTotal)
                .guestAmount(guestAmount)
                .totalAmount(totalAmount)
                .pricingSource(pricingSource)
                .rateCodeName(rateCodeName)
                .status(status)
                .paymentStatus(payStatus)
                .paymentMethod(request.getPaymentMethod())
                .build();
            bookingAddOns.forEach(ba -> ba.setBooking(booking));
            booking.setAddOns(bookingAddOns);
            Booking saved = bookingRepository.save(booking);
            log.info("Admin booking created (override): {} for customer {}", bookingRef, request.getCustomerName());
            eventLogService.logEvent(saved, BookingEventType.CREATED, null, null, "ADMIN",
                autoConfirm
                    ? "Admin booking created with zero-value override"
                    : "Admin booking created with price override; awaiting payment settlement");
            publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);
            if (autoConfirm) {
                publishBookingEvent(saved, KafkaTopics.BOOKING_CONFIRMED);
            }
            return toDto(saved);
        }

        BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);
        validateAdminPaymentTracking(custId, totalAmount);
        String bookingRef = generateBookingRef();

        boolean autoConfirm = totalAmount.compareTo(BigDecimal.ZERO) == 0;
        BookingStatus status = autoConfirm ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
        PaymentStatus payStatus = autoConfirm ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

        Booking booking = Booking.builder()
            .bookingRef(bookingRef)
            .bingeId(BingeContext.getBingeId())
            .customerId(custId)
            .customerName(request.getCustomerName())
            .customerEmail(request.getCustomerEmail())
            .customerPhone(request.getCustomerPhone() != null ? request.getCustomerPhone() : "")
            .eventType(eventType)
            .bookingDate(request.getBookingDate())
            .startTime(request.getStartTime())
            .durationHours(durMin / 60)
            .durationMinutes(durMin)
            .numberOfGuests(adminGuests)
            .specialNotes(request.getSpecialNotes())
            .adminNotes(request.getAdminNotes())
            .baseAmount(baseAmount)
            .addOnAmount(addOnTotal)
            .guestAmount(guestAmount)
            .totalAmount(totalAmount)
            .pricingSource(pricingSource)
            .rateCodeName(rateCodeName)
            .status(status)
            .paymentStatus(payStatus)
            .paymentMethod(request.getPaymentMethod())
            .build();

        bookingAddOns.forEach(ba -> ba.setBooking(booking));
        booking.setAddOns(bookingAddOns);

        Booking saved = bookingRepository.save(booking);
        log.info("Admin booking created: {} for customer {}", bookingRef, request.getCustomerName());
        eventLogService.logEvent(saved, BookingEventType.CREATED, null, null, "ADMIN",
            autoConfirm
                ? "Admin booking created with zero payable amount"
                : "Admin booking created; awaiting payment settlement");

        publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);
        if (autoConfirm) {
            publishBookingEvent(saved, KafkaTopics.BOOKING_CONFIRMED);
        }

        return toDto(saved);
    }

    private void validateAdminCustomerDetails(AdminCreateBookingRequest request) {
        if (!StringUtils.hasText(request.getCustomerName())) {
            throw new BusinessException("Customer name is required for admin bookings");
        }
        if (!StringUtils.hasText(request.getCustomerEmail())) {
            throw new BusinessException("Customer email is required for admin bookings");
        }
    }

    private void validateAdminPaymentTracking(Long customerId, BigDecimal totalAmount) {
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0 && (customerId == null || customerId <= 0)) {
            throw new BusinessException("Admin bookings with a payable balance require a saved customer account");
        }
    }

    // ── Event types & add-ons (public) ──────────────────────
    public List<EventTypeDto> getActiveEventTypes() {
        Long bid = requireSelectedBinge("viewing event types");
        return eventTypeRepository.findByBingeIdAndActiveTrue(bid)
            .stream().map(this::toEventTypeDto).toList();
    }

    public List<AddOnDto> getActiveAddOns() {
        Long bid = requireSelectedBinge("viewing add-ons");
        return addOnRepository.findByBingeIdAndActiveTrue(bid)
            .stream().map(this::toAddOnDto).toList();
    }

    // ── Admin: Event type CRUD ─────────────────────────────
    @org.springframework.cache.annotation.Cacheable(value = "eventTypes", key = "T(com.skbingegalaxy.common.context.BingeContext).getBingeId()")
    public List<EventTypeDto> getAllEventTypes() {
        Long bid = requireSelectedBinge("managing event types");
        return eventTypeRepository.findByBingeId(bid)
            .stream().map(this::toEventTypeDto).toList();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public EventTypeDto createEventType(EventTypeSaveRequest req) {
        Long bid = requireSelectedBinge("creating an event type");
        EventType et = EventType.builder()
            .name(req.getName())
            .description(req.getDescription())
            .basePrice(req.getBasePrice())
            .hourlyRate(req.getHourlyRate())
            .pricePerGuest(req.getPricePerGuest() != null ? req.getPricePerGuest() : BigDecimal.ZERO)
            .minHours(req.getMinHours())
            .maxHours(req.getMaxHours())
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(bid)
            .build();
        return toEventTypeDto(eventTypeRepository.save(et));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public EventTypeDto updateEventType(Long id, EventTypeSaveRequest req) {
        EventType et = findManagedEventType(id);
        et.setName(req.getName());
        et.setDescription(req.getDescription());
        et.setBasePrice(req.getBasePrice());
        et.setHourlyRate(req.getHourlyRate());
        et.setPricePerGuest(req.getPricePerGuest() != null ? req.getPricePerGuest() : BigDecimal.ZERO);
        et.setMinHours(req.getMinHours());
        et.setMaxHours(req.getMaxHours());
        et.getImageUrls().clear();
        if (req.getImageUrls() != null) et.getImageUrls().addAll(req.getImageUrls());
        return toEventTypeDto(eventTypeRepository.save(et));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public void deactivateEventType(Long id) {
        EventType et = findManagedEventType(id);
        et.setActive(!et.isActive());
        eventTypeRepository.save(et);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public void deleteEventType(Long id) {
        EventType eventType = findManagedEventType(id);
        if (eventType.isActive()) {
            throw new BusinessException("Deactivate the event type before deleting it");
        }
        if (bookingRepository.existsByEventTypeId(id)) {
            throw new BusinessException("Cannot delete this event type because it is already used in bookings");
        }
        if (rateCodeEventPricingRepository.existsByEventTypeId(id)) {
            throw new BusinessException("Cannot delete this event type because rate codes still reference it");
        }
        if (customerEventPricingRepository.existsByEventTypeId(id)) {
            throw new BusinessException("Cannot delete this event type because customer pricing profiles still reference it");
        }

        eventTypeRepository.delete(eventType);
        log.info("Event type deleted: '{}' (ID: {})", eventType.getName(), id);
    }

    // ── Admin: Add-on CRUD ───────────────────────────────────
    @org.springframework.cache.annotation.Cacheable(value = "addOns", key = "T(com.skbingegalaxy.common.context.BingeContext).getBingeId()")
    public List<AddOnDto> getAllAddOns() {
        Long bid = requireSelectedBinge("managing add-ons");
        return addOnRepository.findByBingeId(bid)
            .stream().map(this::toAddOnDto).toList();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public AddOnDto createAddOn(AddOnSaveRequest req) {
        Long bid = requireSelectedBinge("creating an add-on");
        AddOn a = AddOn.builder()
            .name(req.getName())
            .description(req.getDescription())
            .price(req.getPrice())
            .category(req.getCategory().toUpperCase())
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(bid)
            .build();
        return toAddOnDto(addOnRepository.save(a));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public AddOnDto updateAddOn(Long id, AddOnSaveRequest req) {
        AddOn a = findManagedAddOn(id);
        a.setName(req.getName());
        a.setDescription(req.getDescription());
        a.setPrice(req.getPrice());
        a.setCategory(req.getCategory().toUpperCase());
        a.getImageUrls().clear();
        if (req.getImageUrls() != null) a.getImageUrls().addAll(req.getImageUrls());
        return toAddOnDto(addOnRepository.save(a));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public void deactivateAddOn(Long id) {
        AddOn a = findManagedAddOn(id);
        a.setActive(!a.isActive());
        addOnRepository.save(a);
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public void deleteAddOn(Long id) {
        AddOn addOn = findManagedAddOn(id);
        if (addOn.isActive()) {
            throw new BusinessException("Deactivate the add-on before deleting it");
        }
        if (bookingAddOnRepository.existsByAddOnId(id)) {
            throw new BusinessException("Cannot delete this add-on because it is already used in bookings");
        }
        if (rateCodeAddonPricingRepository.existsByAddOnId(id)) {
            throw new BusinessException("Cannot delete this add-on because rate codes still reference it");
        }
        if (customerAddonPricingRepository.existsByAddOnId(id)) {
            throw new BusinessException("Cannot delete this add-on because customer pricing profiles still reference it");
        }

        addOnRepository.delete(addOn);
        log.info("Add-on deleted: '{}' (ID: {})", addOn.getName(), id);
    }

    private Booking findScopedBookingByRef(String bookingRef) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            throw new BusinessException("Select a binge before accessing bookings");
        }

        return bookingRepository.findByBookingRefAndBingeId(bookingRef, bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
    }

    private Booking findBookingByRef(String bookingRef) {
        return bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
    }

    /**
     * Returns all bookings for a binge within a date range, ordered by date.
     * Used for server-side CSV/PDF export (no pagination — export targets).
     */
    @Transactional(readOnly = true)
    public List<Booking> getBookingsForExport(Long bingeId, java.time.LocalDate from, java.time.LocalDate to) {
        return bookingRepository.findByBingeIdAndBookingDateBetweenOrderByBookingDateAscStartTimeAsc(bingeId, from, to);
    }

    private EventType findBookableEventType(Long id) {
        Long bid = requireSelectedBinge("using event types");
        return eventTypeRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
    }

    private AddOn findBookableAddOn(Long id) {
        Long bid = requireSelectedBinge("using add-ons");
        return addOnRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
    }

    private EventType findManagedEventType(Long id) {
        Long bid = requireSelectedBinge("managing event types");
        return eventTypeRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
    }

    // ═══════════════════════════════════════════════════════════
    //  VENUE ROOM MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<VenueRoomDto> getActiveVenueRooms() {
        Long bid = BingeContext.requireBingeId();
        return venueRoomRepository.findByBingeIdAndActiveTrueOrderBySortOrderAsc(bid)
            .stream().map(this::toRoomDto).toList();
    }

    @Transactional(readOnly = true)
    public List<VenueRoomDto> getAvailableRooms(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.requireBingeId();
        List<VenueRoom> rooms = venueRoomRepository.findByBingeIdAndActiveTrueOrderBySortOrderAsc(bid);
        return rooms.stream().map(room -> {
            int occ = countRoomBookings(room.getId(), date, startMinute, durationMinutes);
            VenueRoomDto dto = toRoomDto(room);
            dto.setCurrentOccupancy(occ);
            dto.setAvailable(occ < room.getCapacity());
            return dto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<VenueRoomDto> getAllVenueRooms() {
        Long bid = requireSelectedBinge("managing venue rooms");
        return venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bid)
            .stream().map(this::toRoomDto).toList();
    }

    @Transactional
    public VenueRoomDto createVenueRoom(VenueRoomSaveRequest request) {
        Long bid = requireSelectedBinge("creating venue room");
        VenueRoom room = VenueRoom.builder()
            .bingeId(bid).name(request.getName()).roomType(request.getRoomType())
            .capacity(request.getCapacity()).description(request.getDescription())
            .sortOrder(request.getSortOrder()).active(request.isActive())
            .build();
        room = venueRoomRepository.save(room);
        log.info("Venue room created: {}", room.getName());
        return toRoomDto(room);
    }

    @Transactional
    public VenueRoomDto updateVenueRoom(Long id, VenueRoomSaveRequest request) {
        Long bid = requireSelectedBinge("updating venue room");
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", id));
        room.setName(request.getName());
        room.setRoomType(request.getRoomType());
        room.setCapacity(request.getCapacity());
        room.setDescription(request.getDescription());
        room.setSortOrder(request.getSortOrder());
        room.setActive(request.isActive());
        room = venueRoomRepository.save(room);
        log.info("Venue room updated: {}", room.getName());
        return toRoomDto(room);
    }

    @Transactional
    public void toggleVenueRoom(Long id) {
        Long bid = requireSelectedBinge("toggling venue room");
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", id));
        room.setActive(!room.isActive());
        venueRoomRepository.save(room);
        log.info("Venue room {} toggled to active={}", room.getName(), room.isActive());
    }

    @Transactional
    public void deleteVenueRoom(Long id) {
        Long bid = requireSelectedBinge("deleting venue room");
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", id));
        venueRoomRepository.delete(room);
        log.info("Venue room deleted: {}", room.getName());
    }

    private VenueRoomDto toRoomDto(VenueRoom r) {
        return VenueRoomDto.builder()
            .id(r.getId()).bingeId(r.getBingeId()).name(r.getName())
            .roomType(r.getRoomType()).capacity(r.getCapacity())
            .description(r.getDescription()).sortOrder(r.getSortOrder())
            .active(r.isActive()).createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
            .build();
    }

    private AddOn findManagedAddOn(Long id) {
        Long bid = requireSelectedBinge("managing add-ons");
        return addOnRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
    }

    private EventType findAccessibleEventType(Long id) {
        Long bid = requireSelectedBinge("using event types");
        return eventTypeRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
    }

    private AddOn findAccessibleAddOn(Long id) {
        Long bid = requireSelectedBinge("using add-ons");
        return addOnRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
    }

    private Long requireSelectedBinge(String action) {
        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            throw new BusinessException("Select a binge before " + action);
        }
        return bingeId;
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Deterministic lock key for {@code pg_advisory_xact_lock}.
     * Combines bingeId and bookingDate into a single {@code long} so that
     * all booking-creation attempts for the same venue + day serialise.
     */
    private static long slotLockKey(Long bingeId, LocalDate date) {
        // Upper 32 bits: bingeId, lower 32 bits: date epoch-day hash
        long binge = bingeId != null ? bingeId : 0L;
        return (binge << 32) | (date.toEpochDay() & 0xFFFFFFFFL);
    }

    private String generateBookingRef() {
        String year = String.valueOf(Year.now().getValue()).substring(2);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return refPrefix + year + random;
    }

    private void publishBookingEvent(Booking b, String topic) {
        try {
            BookingEvent event = BookingEvent.builder()
                .bookingRef(b.getBookingRef())
                .bingeId(b.getBingeId())
                .customerId(b.getCustomerId())
                .customerName(b.getCustomerName())
                .customerEmail(b.getCustomerEmail())
                .customerPhone(b.getCustomerPhone())
                .eventTypeName(b.getEventType().getName())
                .bookingDate(b.getBookingDate())
                .startTime(b.getStartTime())
                .durationHours(b.getDurationHours())
                .durationMinutes(resolveDurationMinutes(b.getDurationMinutes(), b.getDurationHours()))
                .totalAmount(b.getTotalAmount())
                .status(b.getStatus().name())
                .specialNotes(b.getSpecialNotes())
                .build();

            // Write to outbox table (same DB transaction) instead of directly to Kafka.
            // The OutboxPublisher scheduler picks these up and actually sends them.
            OutboxEvent outbox = OutboxEvent.builder()
                .topic(topic)
                .aggregateKey(b.getBookingRef())
                .payload(objectMapper.writeValueAsString(event))
                .build();
            outboxEventRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to write outbox event for topic {} and booking {}", topic, b.getBookingRef(), e);
            throw new IllegalStateException(
                "Failed to persist outbox event for topic " + topic + " and booking " + b.getBookingRef(), e);
        }
    }

    private BookingDto toDto(Booking b) {
        CancellationPolicyDecision cancelDecision = evaluateCustomerCancellation(b);
        return BookingDto.builder()
            .id(b.getId())
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            .customerId(b.getCustomerId())
            .customerName(b.getCustomerName())
            .customerEmail(b.getCustomerEmail())
            .customerPhone(b.getCustomerPhone())
            .eventType(toEventTypeDto(b.getEventType()))
            .bookingDate(b.getBookingDate())
            .startTime(b.getStartTime())
            .durationHours(b.getDurationHours())
            .durationMinutes(resolveDurationMinutes(b.getDurationMinutes(), b.getDurationHours()))
            .addOns(b.getAddOns().stream().map(ba -> BookingAddOnDto.builder()
                .addOnId(ba.getAddOn().getId())
                .name(ba.getAddOn().getName())
                .category(ba.getAddOn().getCategory())
                .quantity(ba.getQuantity())
                .price(ba.getPrice())
                .build()).toList())
            .specialNotes(b.getSpecialNotes())
            .adminNotes(b.getAdminNotes())
            .baseAmount(b.getBaseAmount())
            .addOnAmount(b.getAddOnAmount())
            .guestAmount(b.getGuestAmount())
            .numberOfGuests(b.getNumberOfGuests())
            .totalAmount(b.getTotalAmount())
            .collectedAmount(b.getCollectedAmount())
            .balanceDue(b.getTotalAmount().subtract(
                b.getCollectedAmount() != null ? b.getCollectedAmount() : BigDecimal.ZERO))
            .status(b.getStatus())
            .paymentStatus(b.getPaymentStatus())
            .paymentMethod(b.getPaymentMethod())
            .checkedIn(b.isCheckedIn())
            .actualCheckoutTime(b.getActualCheckoutTime())
            .actualUsedMinutes(b.getActualUsedMinutes())
            .earlyCheckoutNote(b.getEarlyCheckoutNote())
            .canCustomerCancel(cancelDecision.allowed())
            .customerCancelMessage(cancelDecision.message())
            .cancellationRefundPercentage(cancelDecision.refundPercentage())
            .pricingSource(b.getPricingSource())
            .rateCodeName(b.getRateCodeName())
            .rescheduleCount(b.getRescheduleCount())
            .originalBookingRef(b.getOriginalBookingRef())
            .transferred(b.isTransferred())
            .originalCustomerName(b.getOriginalCustomerName())
            .recurringGroupId(b.getRecurringGroupId())
            .canCustomerReschedule(canCustomerReschedule(b))
            .canCustomerTransfer(canCustomerTransfer(b))
            .venueRoomId(b.getVenueRoomId())
            .venueRoomName(b.getVenueRoomName())
            .loyaltyPointsEarned(b.getLoyaltyPointsEarned())
            .loyaltyPointsRedeemed(b.getLoyaltyPointsRedeemed())
            .loyaltyDiscountAmount(b.getLoyaltyDiscountAmount())
            .surgeMultiplier(b.getSurgeMultiplier())
            .surgeLabel(b.getSurgeLabel())
            .createdAt(b.getCreatedAt())
            .updatedAt(b.getUpdatedAt())
            .build();
    }

    private boolean canCustomerReschedule(Booking b) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) return false;
        if (b.getRescheduleCount() >= maxReschedulesPerBooking) return false;
        LocalDateTime eventStart = LocalDateTime.of(b.getBookingDate(), b.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), eventStart);
        return hoursUntilStart >= rescheduleCutoffHours;
    }

    private boolean canCustomerTransfer(Booking b) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) return false;
        if (b.isTransferred()) return false;
        LocalDateTime eventStart = LocalDateTime.of(b.getBookingDate(), b.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), eventStart);
        return hoursUntilStart >= transferCutoffHours;
    }

    private CancellationPolicyDecision evaluateCustomerCancellation(Booking booking) {
        Binge binge = null;
        if (booking.getBingeId() != null) {
            binge = bingeRepository.findById(booking.getBingeId()).orElse(null);
        }

        boolean enabled = binge == null || binge.isCustomerCancellationEnabled();
        if (!enabled) {
            return new CancellationPolicyDecision(false, "This venue currently does not allow customer self-cancellation.", 0);
        }

        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        LocalDateTime now = LocalDateTime.now();
        long hoursUntilStart = ChronoUnit.HOURS.between(now, eventStart);

        // Check for tiered cancellation policy
        List<com.skbingegalaxy.booking.entity.CancellationTier> tiers = booking.getBingeId() != null
            ? cancellationTierRepository.findByBingeIdOrderByHoursBeforeStartDesc(booking.getBingeId())
            : List.of();

        if (!tiers.isEmpty()) {
            // Tiered policy: find matching tier (first where hoursUntilStart >= tier.hoursBeforeStart)
            for (var tier : tiers) {
                if (hoursUntilStart >= tier.getHoursBeforeStart()) {
                    String label = tier.getLabel() != null ? tier.getLabel() : (tier.getRefundPercentage() + "% refund");
                    return new CancellationPolicyDecision(true,
                        "Cancellation available with " + tier.getRefundPercentage() + "% refund (" + label + "). "
                            + hoursUntilStart + " hours until start.",
                        tier.getRefundPercentage());
                }
            }
            // No tier matched (too close to start) — check if there's a 0h tier
            var lastTier = tiers.get(tiers.size() - 1);
            if (lastTier.getHoursBeforeStart() == 0) {
                String label = lastTier.getLabel() != null ? lastTier.getLabel() : (lastTier.getRefundPercentage() + "% refund");
                return new CancellationPolicyDecision(true,
                    "Late cancellation: " + lastTier.getRefundPercentage() + "% refund (" + label + ").",
                    lastTier.getRefundPercentage());
            }
            // Beyond all tiers — deny cancellation
            return new CancellationPolicyDecision(false,
                "Cancellation is no longer available. The closest policy tier requires at least "
                    + lastTier.getHoursBeforeStart() + " hours notice.", 0);
        }

        // Legacy binary policy (no tiers configured)
        int cutoffMinutes = (binge != null && binge.getCustomerCancellationCutoffMinutes() >= 0)
            ? binge.getCustomerCancellationCutoffMinutes()
            : 180;

        LocalDateTime lockAt = eventStart.minusMinutes(cutoffMinutes);

        if (now.isAfter(lockAt) || now.isEqual(lockAt)) {
            return new CancellationPolicyDecision(false,
                "Cancellation is locked for this booking. This venue allows cancellation only until "
                    + cutoffMinutes + " minutes before start time.", 0);
        }

        long minutesLeft = Math.max(0, ChronoUnit.MINUTES.between(now, lockAt));
        return new CancellationPolicyDecision(true,
            "Cancellation is open. It will lock " + minutesLeft + " minutes from now.",
            100); // Legacy policy = full refund if within window
    }

    private BookingReviewDto toReviewDto(BookingReview review) {
        Booking booking = review.getBooking();
        return BookingReviewDto.builder()
            .id(review.getId())
            .bookingRef(review.getBookingRef())
            .customerId(review.getCustomerId())
            .customerName(booking != null ? booking.getCustomerName() : null)
            .adminId(review.getAdminId())
            .reviewerRole(review.getReviewerRole())
            .rating(review.getRating())
            .comment(review.getComment())
            .skipped(review.isSkipped())
            .visibleToCustomer(review.isVisibleToCustomer())
            .eventTypeName(booking != null && booking.getEventType() != null ? booking.getEventType().getName() : null)
            .createdAt(review.getCreatedAt())
            .build();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record CancellationPolicyDecision(boolean allowed, String message, int refundPercentage) {}

    private EventTypeDto toEventTypeDto(EventType et) {
        return EventTypeDto.builder()
            .id(et.getId())
            .bingeId(et.getBingeId())
            .name(et.getName())
            .description(et.getDescription())
            .basePrice(et.getBasePrice())
            .hourlyRate(et.getHourlyRate())
            .pricePerGuest(et.getPricePerGuest())
            .minHours(et.getMinHours())
            .maxHours(et.getMaxHours())
            // Copy into a plain ArrayList so no Hibernate PersistentBag reference
            // leaks outside the transaction (would cause LazyInitializationException
            // when Jackson serializes the response after the session is closed).
            .imageUrls(et.getImageUrls() != null ? new ArrayList<>(et.getImageUrls()) : new ArrayList<>())
            .active(et.isActive())
            .build();
    }

    private AddOnDto toAddOnDto(AddOn a) {
        return AddOnDto.builder()
            .id(a.getId())
            .bingeId(a.getBingeId())
            .name(a.getName())
            .description(a.getDescription())
            .price(a.getPrice())
            .category(a.getCategory())
            .imageUrls(a.getImageUrls() != null ? new ArrayList<>(a.getImageUrls()) : new ArrayList<>())
            .active(a.isActive())
            .build();
    }
}
