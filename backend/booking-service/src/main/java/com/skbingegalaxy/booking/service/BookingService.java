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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final AvailabilityClient availabilityClient;
    private final AvailabilityClientFallback availabilityFallback;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SystemSettingsService systemSettingsService;
    private final PricingService pricingService;
    private final BookingEventLogService eventLogService;
    private final SagaOrchestrator sagaOrchestrator;

    @Value("${app.booking.ref-prefix:SKBG}")
    private String refPrefix;

    @Value("${app.booking.max-pending-per-customer:2}")
    private int maxPendingPerCustomer;

    @Value("${app.booking.cooldown-minutes-after-timeout:10}")
    private int cooldownMinutesAfterTimeout;

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

        EventType eventType = eventTypeRepository.findById(request.getEventTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", request.getEventTypeId()));

        // Resolve duration in minutes (30-min granularity)
        int durMin = resolveDurationMinutes(request.getDurationMinutes(), request.getDurationHours());
        if (durMin < 30 || durMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (durMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        // Check availability via Feign (with circuit breaker fallback)
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        Boolean available = availabilityClient.checkSlotAvailable(
            request.getBookingDate(), startMinute, durMin);
        if (available != null) {
            availabilityFallback.cacheResult(request.getBookingDate(), startMinute, durMin, available);
        }
        if (available == null) {
            throw new BusinessException("Availability service is temporarily unavailable. Please try again.");
        }
        if (Boolean.FALSE.equals(available)) {
            throw new BusinessException("Selected date/time slot is not available");
        }

        // Check for double-booking with existing reservations
        if (hasTimeConflict(request.getBookingDate(), startMinute, durMin)) {
            throw new BusinessException("Selected time slot conflicts with an existing booking");
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
                AddOn addOn = addOnRepository.findById(sel.getAddOnId())
                    .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", sel.getAddOnId()));
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
        String bookingRef = generateBookingRef();

        Booking booking = Booking.builder()
            .bookingRef(bookingRef)
            .bingeId(BingeContext.getBingeId())
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
    public BookingDto getByRef(String bookingRef) {
        return toDto(bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef)));
    }

    // ── Customer: my bookings ────────────────────────────────
    public List<BookingDto> getCustomerBookings(Long customerId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bid, customerId)
            : bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        return list.stream().map(this::toDto).toList();
    }

    public List<BookingDto> getCustomerBookingsByStatus(Long customerId, BookingStatus status) {
        return bookingRepository.findByCustomerIdAndStatus(customerId, status)
            .stream().map(this::toDto).toList();
    }

    public List<BookingDto> getCustomerCurrentBookings(Long customerId, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerCurrentBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerCurrentBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    public List<BookingDto> getCustomerPastBookings(Long customerId, LocalDate clientToday) {
        LocalDate today = clientToday != null ? clientToday : LocalDate.now();
        Long bid = BingeContext.getBingeId();
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerPastBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerPastBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    // ── Admin: all bookings (paginated) ──────────────────────
    public Page<BookingDto> getAllBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeId(bid, pageable) : bookingRepository.findAll(pageable)).map(this::toDto);
    }

    // ── Admin: today's bookings (paginated) ──────────────────
    public Page<BookingDto> getTodayBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, today, pageable) : bookingRepository.findByBookingDate(today, pageable)).map(this::toDto);
    }

    // ── Admin: upcoming bookings (today+future, PENDING or CONFIRMED only) ─
    public Page<BookingDto> getUpcomingBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findUpcomingBookingsByBinge(bid, today, pageable) : bookingRepository.findUpcomingBookings(today, pageable)).map(this::toDto);
    }

    // ── Admin: by date ─────────────────────────────────────────
    public Page<BookingDto> getBookingsByDate(LocalDate date, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, date, pageable) : bookingRepository.findByBookingDate(date, pageable)).map(this::toDto);
    }

    // ── Admin: by status ───────────────────────────────────────
    public Page<BookingDto> getBookingsByStatus(BookingStatus status, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndStatus(bid, status, pageable) : bookingRepository.findByStatus(status, pageable)).map(this::toDto);
    }

    // ── Admin: by status scoped to operational day ────────────
    public Page<BookingDto> getBookingsByStatusForToday(BookingStatus status, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null
            ? bookingRepository.findByBingeIdAndBookingDateAndStatus(bid, today, status, pageable)
            : bookingRepository.findByBookingDateAndStatus(today, status, pageable)).map(this::toDto);
    }

    // ── Admin: by date range ──────────────────────────────────
    public Page<BookingDto> getBookingsByDateRange(LocalDate from, LocalDate to, Pageable pageable) {
        return bookingRepository.findByBookingDateBetween(from, to, pageable).map(this::toDto);
    }

    // ── Admin: search ────────────────────────────────────────
    public Page<BookingDto> searchBookings(String query, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.searchBookingsByBinge(bid, query, pageable) : bookingRepository.searchBookings(query, pageable)).map(this::toDto);
    }

    // ── Admin: search scoped to operational day ───────────────
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
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
        String previousStatus = booking.getStatus().name();

        if (request.getStatus() != null) {
            BookingStatus newStatus = BookingStatus.valueOf(request.getStatus());
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
            EventType newEventType = eventTypeRepository.findById(request.getEventTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", request.getEventTypeId()));
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
        if (request.getBookingDate() != null) {
            booking.setBookingDate(request.getBookingDate());
        }
        if (request.getStartTime() != null) {
            booking.setStartTime(request.getStartTime());
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
                    AddOn addOn = addOnRepository.findById(sel.getAddOnId())
                        .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", sel.getAddOnId()));
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

        Booking updated = bookingRepository.save(booking);
        BookingEventType eventType = booking.getStatus().name().equals(previousStatus)
            ? BookingEventType.MODIFIED
            : BookingEventType.valueOf(booking.getStatus().name());
        eventLogService.logEvent(updated, eventType, previousStatus, null, "ADMIN",
            "Booking updated by admin");
        return toDto(updated);
    }

    // ── Get raw booking entity (for controller-level checks) ──
    public Booking getBookingEntity(String bookingRef) {
        return bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
    }

    // ── Admin: cancel booking ────────────────────────────────
    @Transactional
    public BookingDto cancelBooking(String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("Booking is already cancelled");
        }

        String prevStatus = booking.getStatus().name();
        booking.setStatus(BookingStatus.CANCELLED);
        Booking saved = bookingRepository.save(booking);
        eventLogService.logEvent(saved, BookingEventType.CANCELLED, prevStatus, null, "ADMIN",
            "Booking cancelled by admin");
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
        if (amount == null) return;
        bookingRepository.findByBookingRef(bookingRef).ifPresent(b -> {
            java.math.BigDecimal current = b.getCollectedAmount() != null ? b.getCollectedAmount() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal updated = current.add(amount);
            if (b.getTotalAmount() != null && updated.compareTo(b.getTotalAmount()) != 0) {
                log.warn("Payment mismatch for {}: collectedAmount={} vs totalAmount={}",
                    bookingRef, updated, b.getTotalAmount());
            }
            b.setCollectedAmount(updated);
            bookingRepository.save(b);
        });
    }

    @Transactional
    public void subtractFromCollectedAmount(String bookingRef, java.math.BigDecimal amount) {
        if (amount == null) return;
        bookingRepository.findByBookingRef(bookingRef).ifPresent(b -> {
            java.math.BigDecimal current = b.getCollectedAmount() != null ? b.getCollectedAmount() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal updated = current.subtract(amount);
            b.setCollectedAmount(updated.compareTo(java.math.BigDecimal.ZERO) < 0 ? java.math.BigDecimal.ZERO : updated);
            bookingRepository.save(b);
        });
    }

    // ── Dashboard stats ──────────────────────────────────────
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
    public List<BookedSlotDto> getBookedSlotsForDate(LocalDate date) {
        Long bid = BingeContext.getBingeId();
        List<Booking> active = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
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
    public boolean hasTimeConflict(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
        int newEnd = startMinute + durationMinutes;
        for (Booking b : activeBookings) {
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

    // ── Admin: early checkout ────────────────────────────────
    @Transactional
    public BookingDto earlyCheckout(String bookingRef, LocalDateTime clientNow) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));

        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BusinessException(
                "Early checkout requires the booking to be CHECKED_IN. Current status: " + booking.getStatus());
        }

        var ps = booking.getPaymentStatus();
        if (ps == PaymentStatus.PENDING || ps == PaymentStatus.FAILED || ps == PaymentStatus.REFUNDED) {
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
            return toDto(bookingRepository.save(booking));
        }

        long usedMinutes = java.time.Duration.between(scheduledStart, now).toMinutes();
        if (usedMinutes < 0) usedMinutes = 0;

        // Round up used minutes to nearest 30-minute boundary
        int roundedUsed = ((int) Math.ceil(usedMinutes / 30.0)) * 30;
        long remainingMinutes = bookedMinutes - roundedUsed;

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

        return toDto(saved);
    }

    // ── Admin: create booking (walk-in) ──────────────────────
    @Transactional
    public BookingDto adminCreateBooking(AdminCreateBookingRequest request) {
        EventType eventType = eventTypeRepository.findById(request.getEventTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", request.getEventTypeId()));

        // Resolve duration in minutes
        int durMin = resolveDurationMinutes(request.getDurationMinutes(), request.getDurationHours());

        // Check for double-booking with existing reservations
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        if (hasTimeConflict(request.getBookingDate(), startMinute, durMin)) {
            throw new BusinessException("Selected time slot conflicts with an existing booking");
        }

        // Calculate pricing using resolved customer pricing (if customer is known)
        Long custId = request.getCustomerId() != null ? request.getCustomerId() : 0L;
        String pricingSource = "DEFAULT";
        String rateCodeName = null;

        PricingService.ResolvedEventPrice eventPrice;
        if (custId > 0) {
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
                AddOn addOn = addOnRepository.findById(sel.getAddOnId())
                    .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", sel.getAddOnId()));
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

            String bookingRef = generateBookingRef();
            boolean isCash = "CASH".equalsIgnoreCase(request.getPaymentMethod());
            BookingStatus status = isCash ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
            PaymentStatus payStatus = isCash ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

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
                "Admin booking created with price override");
            publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);
            if (isCash) publishBookingEvent(saved, KafkaTopics.BOOKING_CONFIRMED);
            return toDto(saved);
        }

        BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);
        String bookingRef = generateBookingRef();

        // For CASH payments, booking is immediately CONFIRMED
        boolean isCash = "CASH".equalsIgnoreCase(request.getPaymentMethod());
        BookingStatus status = isCash ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
        PaymentStatus payStatus = isCash ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

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
            "Admin booking created (walk-in)");

        publishBookingEvent(saved, KafkaTopics.BOOKING_CREATED);
        if (isCash) {
            publishBookingEvent(saved, KafkaTopics.BOOKING_CONFIRMED);
        }

        return toDto(saved);
    }

    // ── Event types & add-ons (public) ──────────────────────
    public List<EventTypeDto> getActiveEventTypes() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? eventTypeRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : eventTypeRepository.findByActiveTrue())
            .stream().map(this::toEventTypeDto).toList();
    }

    public List<AddOnDto> getActiveAddOns() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? addOnRepository.findByBingeIdOrGlobalAndActiveTrue(bid) : addOnRepository.findByActiveTrue())
            .stream().map(this::toAddOnDto).toList();
    }

    // ── Admin: Event type CRUD ─────────────────────────────
    public List<EventTypeDto> getAllEventTypes() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? eventTypeRepository.findByBingeIdOrGlobal(bid) : eventTypeRepository.findAll())
            .stream().map(this::toEventTypeDto).toList();
    }

    @Transactional
    public EventTypeDto createEventType(EventTypeSaveRequest req) {
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
            .bingeId(BingeContext.getBingeId())
            .build();
        return toEventTypeDto(eventTypeRepository.save(et));
    }

    @Transactional
    public EventTypeDto updateEventType(Long id, EventTypeSaveRequest req) {
        EventType et = eventTypeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
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
    public void deactivateEventType(Long id) {
        EventType et = eventTypeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("EventType", "id", id));
        et.setActive(!et.isActive());
        eventTypeRepository.save(et);
    }

    // ── Admin: Add-on CRUD ───────────────────────────────────
    public List<AddOnDto> getAllAddOns() {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? addOnRepository.findByBingeIdOrGlobal(bid) : addOnRepository.findAll())
            .stream().map(this::toAddOnDto).toList();
    }

    @Transactional
    public AddOnDto createAddOn(AddOnSaveRequest req) {
        AddOn a = AddOn.builder()
            .name(req.getName())
            .description(req.getDescription())
            .price(req.getPrice())
            .category(req.getCategory().toUpperCase())
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(BingeContext.getBingeId())
            .build();
        return toAddOnDto(addOnRepository.save(a));
    }

    @Transactional
    public AddOnDto updateAddOn(Long id, AddOnSaveRequest req) {
        AddOn a = addOnRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
        a.setName(req.getName());
        a.setDescription(req.getDescription());
        a.setPrice(req.getPrice());
        a.setCategory(req.getCategory().toUpperCase());
        a.getImageUrls().clear();
        if (req.getImageUrls() != null) a.getImageUrls().addAll(req.getImageUrls());
        return toAddOnDto(addOnRepository.save(a));
    }

    @Transactional
    public void deactivateAddOn(Long id) {
        AddOn a = addOnRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("AddOn", "id", id));
        a.setActive(!a.isActive());
        addOnRepository.save(a);
    }

    // ── Helpers ──────────────────────────────────────────────
    private String generateBookingRef() {
        String year = String.valueOf(Year.now().getValue()).substring(2);
        int random = ThreadLocalRandom.current().nextInt(100000, 999999);
        return refPrefix + year + random;
    }

    private void publishBookingEvent(Booking b, String topic) {
        try {
            BookingEvent event = BookingEvent.builder()
                .bookingRef(b.getBookingRef())
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
            log.warn("Failed to write outbox event for topic {}: {}", topic, e.getMessage());
        }
    }

    private BookingDto toDto(Booking b) {
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
            .pricingSource(b.getPricingSource())
            .rateCodeName(b.getRateCodeName())
            .createdAt(b.getCreatedAt())
            .updatedAt(b.getUpdatedAt())
            .build();
    }

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
            .imageUrls(et.getImageUrls() != null ? et.getImageUrls() : List.of())
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
            .imageUrls(a.getImageUrls() != null ? a.getImageUrls() : List.of())
            .active(a.isActive())
            .build();
    }
}
