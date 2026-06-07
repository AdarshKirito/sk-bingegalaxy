package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.booking.tax.provider.TaxContext;
import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.booking.service.statemachine.BookingStateMachine;
import com.skbingegalaxy.booking.service.statemachine.BookingTransitionEvent;
import com.skbingegalaxy.booking.service.statemachine.TransitionActor;
import com.skbingegalaxy.booking.web.RequestContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.BookingEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
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
    private final com.skbingegalaxy.booking.repository.EventCategoryRepository eventCategoryRepository;
    private final com.skbingegalaxy.booking.repository.AddOnCategoryRepository addOnCategoryRepository;
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
    private final com.skbingegalaxy.booking.repository.RoomBlockRepository roomBlockRepository;  // V57: maintenance windows
    private final com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService loyaltyMemberService;
    private final com.skbingegalaxy.booking.loyalty.v2.repository.LoyaltyMembershipRepository loyaltyMembershipRepository;
    private final com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyConfigService loyaltyConfigService;
    private final ApplicationEventPublisher eventPublisher;                 // Loyalty v2 — in-process events
    private final CustomerFreezeService customerFreezeService;              // Anti-abuse freeze policy
    private final com.skbingegalaxy.booking.repository.SlotHoldRepository slotHoldRepository;
    private final com.skbingegalaxy.booking.repository.BookingTransferRepository bookingTransferRepository;
    private final BookingEventPublisher bookingEventPublisher;              // Envelope-aware Kafka outbox writer (V46)
    private final BookingRiskEvaluator bookingRiskEvaluator;                // Item 23 — fraud / abuse rule engine
    private final BookingAnalyticsMetrics analyticsMetrics;                 // Item 27 — funnel/lifecycle counters
    private final BookingStateMachine stateMachine;                         // Centralized status-transition engine
    private final TaxService taxService;                                    // Tax computation at booking creation time
    private final FxLockService fxLockService;                              // FX rate lock validation at booking creation
    private final VenueClockService venueClock;                             // Venue-aware timezone resolution

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

    @Value("${app.booking.max-horizon-days:365}")
    private int maxBookingHorizonDays;

    /**
     * Global fallback for the theater's opening hour (0–23). Only used when a
     * particular {@link Binge} has no per-binge {@code openTime} configured.
     * Mirrors the value availability-service uses to render the slot grid so
     * customer UI and booking-service stay in lock-step.
     */
    @Value("${app.theater.opening-hour:10}")
    private int defaultOpeningHour;

    /**
     * Global fallback for the theater's closing hour (1–24). Only used when a
     * particular {@link Binge} has no per-binge {@code closeTime} configured.
     */
    @Value("${app.theater.closing-hour:23}")
    private int defaultClosingHour;

    // â”€â”€ Create booking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Timeout (15s) bounds the transaction in case the synchronous
    // availability/pricing Feign call stalls - circuit breaker will trip
    // around 4s slow-call, but this caps worst-case DB lock retention
    // during a network partition.

    /** Backward-compat overload (callers without phone country code). */
    public BookingDto createBooking(CreateBookingRequest request,
                                    Long customerId, String customerName,
                                    String customerEmail, String customerPhone) {
        return createBooking(request, customerId, customerName, customerEmail, customerPhone, null);
    }

    @Transactional(timeout = 15)
    public BookingDto createBooking(CreateBookingRequest request,
                                    Long customerId, String customerName,
                                    String customerEmail, String customerPhone,
                                    String customerPhoneCountryCode) {

        Long bingeId = BingeContext.requireBingeId();

        // Approval / activation guard — reject bookings against any binge that
        // has not been approved by a super-admin or has been deactivated. The
        // customer-visible listing already filters these out, but a leaked or
        // guessed bingeId in the X-Binge-Id header would otherwise slip through.
        assertBingeBookable(bingeId);

        // Anti-abuse: limit concurrent PENDING bookings per customer **per binge**.
        // Per-binge scope prevents a customer with pending payments at venue A from
        // being blocked at venue B (each binge runs its own tenancy of pending limits).
        long pendingCount = bookingRepository.countPendingByCustomerIdAndBingeId(customerId, bingeId);
        if (pendingCount >= maxPendingPerCustomer) {
            throw new BusinessException(
                "You already have " + pendingCount + " pending booking(s) at this venue. Please complete or cancel them before creating new ones.");
        }

        // Anti-abuse: cooldown after auto-cancelled (timed-out) bookings — also per binge.
        LocalDateTime cooldownSince = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(cooldownMinutesAfterTimeout);
        long recentTimeouts = bookingRepository.countRecentTimeoutCancellationsByBinge(customerId, bingeId, cooldownSince);
        if (recentTimeouts >= 2) {
            throw new BusinessException(
                "Too many unpaid bookings were auto-cancelled at this venue recently. Please wait a few minutes before trying again.");
        }

        // Anti-abuse: per-binge customer freeze (raises 423 LOCKED if active)
        customerFreezeService.assertNotFrozen(customerId, bingeId);
        EventType eventType = findBookableEventType(request.getEventTypeId());

        // Reject bookings in the past — use the venue's configured timezone so the
        // business-day boundary is correct for any country the venue operates in.
        ZoneId bizZone = venueClock.zoneOf(bingeId);
        if (request.getBookingDate().isBefore(LocalDate.now(bizZone))) {
            throw new BusinessException("Booking date cannot be in the past");
        }
        // Reject bookings absurdly far in the future. Production rule: customers may only
        // book within the published rolling window (default 365 days). Prevents "slot
        // squatting", schedule poisoning and pricing-rule drift on far-future dates.
        LocalDate maxBookingDate = LocalDate.now(bizZone).plusDays(maxBookingHorizonDays);
        if (request.getBookingDate().isAfter(maxBookingDate)) {
            throw new BusinessException(
                "Bookings can only be made up to " + maxBookingHorizonDays + " days in advance.");
        }

        // Content-based dedupe (defence-in-depth alongside Idempotency-Key + rate limiter):
        // refuse to create a second PENDING booking for the same customer + event + slot.
        // Catches accidental double-submits that arrive milliseconds apart on different
        // gateway instances and any client that doesn't send Idempotency-Key.
        if (bookingRepository.existsPendingDuplicate(
                customerId, request.getEventTypeId(),
                request.getBookingDate(), request.getStartTime())) {
            throw new BusinessException(
                "You already have a pending booking for this event and time slot. Please check My Bookings.");
        }

        // Resolve duration in minutes (30-min granularity)
        int durMin = resolveDurationMinutes(request.getDurationMinutes(), request.getDurationHours());
        if (durMin < 30 || durMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (durMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        // Operating-hours guard: reject bookings outside this binge's published
        // open/close window. Defence in depth against any client that bypasses
        // the slot grid (mobile app misuse, scripted abuse, leaked API token,
        // or admin walk-in typos). Falls back to global config when the binge
        // has no per-binge override.
        validateWithinOperatingHours(bingeId, request.getStartTime(), durMin);

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
            java.time.LocalDateTime bookingStartDt =
                java.time.LocalDateTime.of(request.getBookingDate(), request.getStartTime());
            for (AddOnSelection sel : request.getAddOns()) {
                AddOn addOn = findBookableAddOn(sel.getAddOnId());
                int qty = Math.max(sel.getQuantity(), 1);

                // Inventory + advance-notice guards (skipped for null limits).
                enforceAddOnAvailability(addOn, qty, request.getBookingDate(), bookingStartDt, null);

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
        enforceEventTypeGuestRange(eventType, guests);
        BigDecimal guestAmount = eventPrice.pricePerGuest()
            .multiply(BigDecimal.valueOf(Math.max(guests - 1, 0)));

        // Determine pricing source for snapshot
        String pricingSource = eventPrice.source();
        String rateCodeName = eventPrice.rateCodeName();

        BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);

        // â”€â”€ Surge pricing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // ── Venue room assignment ─────────────────────────────────
        Long venueRoomId = null;
        String venueRoomName = null;
        BigDecimal venueRoomPrice = BigDecimal.ZERO;
        if (request.getVenueRoomId() != null) {
            VenueRoom room = venueRoomRepository.findByIdAndBingeId(request.getVenueRoomId(), bingeId)
                .orElseThrow(() -> new BusinessException("Selected room not found"));
            if (!room.isActive()) throw new BusinessException("Selected room is currently unavailable");
            // V56: block bookings against rooms that haven't been approved.
            if (room.getStatus() != null && room.getStatus() != com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED) {
                throw new BusinessException("Selected room is not yet approved for bookings");
            }
            int roomOccupancy = countRoomBookings(room.getId(), request.getBookingDate(), startMinute, durMin);
            if (roomOccupancy >= room.getCapacity()) {
                throw new BusinessException("Selected room '" + room.getName() + "' is fully booked for this time slot");
            }
            venueRoomId = room.getId();
            venueRoomName = room.getName();
            venueRoomPrice = room.getPriceAddition() != null ? room.getPriceAddition() : BigDecimal.ZERO;
        } else {
            // V56: enforce the per-binge "must pick a room" toggle.
            Binge bingeCfg = bingeRepository.findById(bingeId).orElse(null);
            if (bingeCfg != null && bingeCfg.isRoomSelectionRequired()) {
                throw new BusinessException("This binge requires a room to be selected before booking");
            }
        }
        // Add the room surcharge to the booking total. We intentionally apply
        // it after surge multiplication so the room fee is flat and predictable
        // for the customer (a luxury room shouldn't get 1.5x'd on a busy night).
        if (venueRoomPrice.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = totalAmount.add(venueRoomPrice).setScale(2, RoundingMode.HALF_UP);
        }

        // â”€â”€ Loyalty redemption â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        String bookingRef = generateBookingRef();
        long loyaltyPointsRedeemed = 0;
        BigDecimal loyaltyDiscountAmount = BigDecimal.ZERO;
        if (request.getRedeemLoyaltyPoints() != null && request.getRedeemLoyaltyPoints() > 0) {
            try {
                com.skbingegalaxy.booking.loyalty.v2.service.LoyaltyMemberService.RedemptionResult redemption =
                    loyaltyMemberService.redeemForBooking(
                        customerId, bingeId, bookingRef,
                        request.getRedeemLoyaltyPoints(), totalAmount);
                loyaltyPointsRedeemed = redemption.pointsRedeemed();
                loyaltyDiscountAmount = redemption.discountAmount();
                totalAmount = totalAmount.subtract(loyaltyDiscountAmount);
                if (totalAmount.compareTo(BigDecimal.ZERO) < 0) totalAmount = BigDecimal.ZERO;
            } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
                // A concurrent admin adjustment / expiry touched the account
                // between our read and write. Fail-safe: proceed without
                // redemption rather than crashing the entire booking.
                log.warn("Loyalty redemption skipped for booking {} — concurrent modification: {}",
                    bookingRef, ex.getMessage());
            }
        }

        // ── Tax computation (applied at creation, like Stripe/Shopify) ───────────
        TaxContext taxCtx = buildBookingTaxContext(bingeId);
        TaxComputationResult taxResult = taxService.compute(taxCtx, totalAmount, baseAmount, addOnTotal, guestAmount);
        BigDecimal subtotalForTax = totalAmount;
        BigDecimal taxComputed = taxResult.getTotalTax() != null ? taxResult.getTotalTax() : BigDecimal.ZERO;
        if (taxComputed.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = subtotalForTax.add(taxComputed).setScale(2, RoundingMode.HALF_UP);
        }
        String taxBreakdown = taxResult.getBreakdownJson();

        // ── FX rate lock validation ────────────────────────────────────────────
        // If the customer obtained a rate lock at checkout, consume it now (atomic
        // validate + mark CONSUMED). consumeLock() throws BusinessException if the
        // lock has expired or was already consumed, preventing the booking from being
        // committed at a stale rate. Domestic INR customers don't supply a token.
        LocalDateTime fxLockedUntil = null;
        String paymentCurrencyCode = null;
        BigDecimal lockedFxRate = null;
        if (request.getFxLockToken() != null && !request.getFxLockToken().isBlank()) {
            com.skbingegalaxy.booking.entity.FxRateLock fxLock =
                fxLockService.consumeLock(request.getFxLockToken());
            fxLockedUntil = fxLock.getLockedUntil();
            // Record WHICH currency and rate were locked so the payment path can
            // validate a foreign-currency charge against the same rate (the booking
            // total stays in base/INR; fxRate = foreign units per 1 INR).
            paymentCurrencyCode = fxLock.getToCurrency();
            lockedFxRate = fxLock.getFxRate();
            log.info("FX lock consumed for booking {} — rate={} currency={} lockedUntil={}",
                bookingRef, lockedFxRate, paymentCurrencyCode, fxLockedUntil);
        }

        Booking booking = Booking.builder()
            .bookingRef(bookingRef)
            .bingeId(bingeId)
            .customerId(customerId)
            .customerName(customerName)
            .customerEmail(customerEmail)
            .customerPhone(customerPhone)
            .customerPhoneCountryCode(customerPhoneCountryCode)
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
            .subtotalAmount(subtotalForTax)
            .taxAmount(taxComputed)
            .taxBreakdownJson(taxBreakdown)
            .pricingSource(pricingSource)
            .rateCodeName(rateCodeName)
            .venueRoomId(venueRoomId)
            .venueRoomName(venueRoomName)
            .venueRoomPrice(venueRoomPrice)
            .surgeMultiplier(surgeMultiplier)
            .surgeLabel(surgeLabel)
            .loyaltyPointsRedeemed(loyaltyPointsRedeemed)
            .loyaltyDiscountAmount(loyaltyDiscountAmount)
            .fxLockedUntil(fxLockedUntil)
            .paymentCurrencyCode(paymentCurrencyCode)
            .fxRate(lockedFxRate != null ? lockedFxRate : BigDecimal.ONE)
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

        // Item 23 — risk / abuse evaluation. Runs in REQUIRES_NEW so any failure
        // here can never roll back the booking creation; flags are purely
        // informational for the operator queue.
        bookingRiskEvaluator.evaluate(saved);

        return toDto(saved);
    }

    // ── Get booking by ref ─────────────────────────────────
    @Transactional(readOnly = true)
    public BookingDto getByRef(String bookingRef) {
        return toDto(findScopedBookingByRef(bookingRef));
    }

    /**
     * Customer-facing timeline. Returns curated milestones (status changes,
     * payment success, refund completion, notifications) with admin-only
     * fields stripped — IPs, user-agents, internal actor IDs, and the raw
     * snapshot are never exposed to customers.
     *
     * <p>Filtering happens in-memory because the event-log table is small
     * per booking (~10–20 rows) and the alternative — a per-customer JPQL
     * filter passing an {@code IN} clause of enum values — would obscure
     * the privacy-policy decision in the controller.
     */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> getCustomerTimeline(
            String bookingRef,
            java.util.Set<com.skbingegalaxy.booking.entity.BookingEventType> visibleTypes) {
        // Re-scope; getByRef would already 403 mismatched binge.
        findScopedBookingByRef(bookingRef);
        java.util.List<com.skbingegalaxy.booking.entity.BookingEventLog> all =
            eventLogService.getEventHistory(bookingRef);
        return all.stream()
            .filter(e -> visibleTypes.contains(e.getEventType()))
            .map(e -> {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("eventType", e.getEventType().name());
                row.put("status", e.getNewStatus());
                row.put("description", e.getDescription());
                row.put("at", e.getCreatedAt());
                return row;
            })
            .toList();
    }

    // â”€â”€ Customer: my bookings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerCurrentBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerCurrentBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerPastBookings(Long customerId, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerPastBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerPastBookings(customerId, today);
        return list.stream().map(this::toDto).toList();
    }

    // â”€â”€ Admin: all bookings (paginated) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getAllBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeId(bid, pageable) : bookingRepository.findAll(pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: today's bookings (paginated) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getTodayBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, today, pageable) : bookingRepository.findByBookingDate(today, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: upcoming bookings (today+future, PENDING or CONFIRMED only) â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getUpcomingBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null ? bookingRepository.findUpcomingBookingsByBinge(bid, today, pageable) : bookingRepository.findUpcomingBookings(today, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: by date â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDate(LocalDate date, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, date, pageable) : bookingRepository.findByBookingDate(date, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: by status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatus(BookingStatus status, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndStatus(bid, status, pageable) : bookingRepository.findByStatus(status, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: by status scoped to operational day â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatusForToday(BookingStatus status, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null
            ? bookingRepository.findByBingeIdAndBookingDateAndStatus(bid, today, status, pageable)
            : bookingRepository.findByBookingDateAndStatus(today, status, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: by date range â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDateRange(LocalDate from, LocalDate to, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null
            ? bookingRepository.findByBingeIdAndBookingDateBetween(bid, from, to, pageable)
            : bookingRepository.findByBookingDateBetween(from, to, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: search â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookings(String query, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.searchBookingsByBinge(bid, query, pageable) : bookingRepository.searchBookings(query, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: search scoped to operational day â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookingsForToday(String query, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return (bid != null
            ? bookingRepository.searchBookingsByBingeAndDate(bid, today, query, pageable)
            : bookingRepository.searchBookingsByDate(today, query, pageable)).map(this::toDto);
    }

    // â”€â”€ Admin: update booking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public BookingDto updateBooking(String bookingRef, UpdateBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);
        String previousStatus = booking.getStatus().name();

        // ── Status field — routed through the central state machine. ──────
        // Only CONFIRMED / CANCELLED / CHECKED_IN are reachable via the
        // admin PATCH path. Reaching NO_SHOW or COMPLETED requires the
        // appropriate dedicated flow (audit sweeper, checkout) or the
        // super-admin override endpoint.
        if (request.getStatus() != null) {
            BookingStatus newStatus;
            try {
                newStatus = BookingStatus.valueOf(request.getStatus());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Invalid booking status: " + request.getStatus());
            }
            BookingTransitionEvent evt = switch (newStatus) {
                case CONFIRMED  -> BookingTransitionEvent.ADMIN_CONFIRM;
                case CANCELLED  -> BookingTransitionEvent.ADMIN_CANCEL;
                case CHECKED_IN -> BookingTransitionEvent.CHECK_IN;
                default -> throw new BusinessException(
                    "Cannot transition booking to " + newStatus
                        + " via admin update — use the dedicated workflow"
                        + " (checkout/no-show/audit) or super-admin override.");
            };
            TransitionActor actor = adminActorFromContext();
            booking = stateMachine.transition(booking, evt, actor, /*reason*/ null);
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                publishBookingEvent(booking, KafkaTopics.BOOKING_CONFIRMED);
            }
        }
        if (request.getCheckedIn() != null) {
            boolean wasCheckedInBefore = booking.isCheckedIn();
            if (request.getCheckedIn()) {
                if (booking.getStatus() != BookingStatus.CHECKED_IN) {
                    booking = stateMachine.transition(
                        booking, BookingTransitionEvent.CHECK_IN,
                        adminActorFromContext(), /*reason*/ null);
                }
                booking.setCheckedIn(true);
                ZoneId venueZone = venueClock.zoneOf(booking.getBingeId());
                if (booking.getActualCheckInTime() == null) {
                    booking.setActualCheckInTime(LocalDateTime.now(venueZone));
                }
                // Late-arrival flag — set when the operational check-in time is
                // after the scheduled start. Both QR/OTP and manual admin
                // check-in funnel through this method, so the flag is set
                // consistently regardless of channel.
                LocalDateTime scheduledStart = LocalDateTime.of(
                    booking.getBookingDate(), booking.getStartTime());
                if (LocalDateTime.now(venueZone).isAfter(scheduledStart) && !booking.isLateArrival()) {
                    booking.setLateArrival(true);
                }
                // Emit booking.checked-in only on the transition (avoid double
                // publishes if an admin re-saves the same booking). Status was
                // just flipped to CHECKED_IN above, so the event payload
                // reflects the new state.
                if (!wasCheckedInBefore) {
                    publishBookingEvent(booking, KafkaTopics.BOOKING_CHECKED_IN);
                }
            } else {
                // Reverting a check-in (admin "undo") clears the late flag too.
                // Status reversion itself is owned by undoCheckIn(); this path
                // only flips the boolean flag for legacy callers that send
                // checkedIn=false without going through the dedicated endpoint.
                booking.setCheckedIn(false);
                booking.setLateArrival(false);
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
        if (request.getCustomerPhoneCountryCode() != null) {
            booking.setCustomerPhoneCountryCode(request.getCustomerPhoneCountryCode());
        }
        if (request.getSpecialNotes() != null) {
            booking.setSpecialNotes(request.getSpecialNotes());
        }

        // â”€â”€ Pricing-relevant field updates â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
            enforceEventTypeGuestRange(booking.getEventType(), request.getNumberOfGuests());
            booking.setNumberOfGuests(request.getNumberOfGuests());
            pricingChanged = true;
        }

        // Add-on changes
        if (request.getAddOns() != null) {
            java.time.LocalDateTime bookingStartDt =
                java.time.LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
            for (AddOnSelection sel : request.getAddOns()) {
                AddOn addOn = findBookableAddOn(sel.getAddOnId());
                int qty = Math.max(sel.getQuantity(), 1);
                // Re-validate availability — exclude this booking's own existing
                // quantity from the count so editing the same booking doesn't
                // collide with itself.
                enforceAddOnAvailability(addOn, qty, booking.getBookingDate(), bookingStartDt, booking.getId());
            }
            pricingChanged = true;
        }

        // â”€â”€ Direct admin price override (takes priority over recalculation) â”€â”€
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

        // â”€â”€ Recalculate pricing when relevant fields changed â”€â”€
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

        // â”€â”€ Recalculate surge when date/time changed OR pricing changed â”€â”€
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

        // â”€â”€ Sync paymentStatus with actual balance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // -- Recompute taxes after any pricing / schedule / component override --
        // Note: UpdateBookingRequest has no totalAmount field; admins can only
        // override individual components (baseAmount / addOnAmount / guestAmount).
        // Therefore tax must always be reapplied on top of the new line items —
        // we do NOT treat a component override as "admin set the final number".
        if (pricingChanged || dateTimeChanged || directPriceOverride) {
            TaxContext taxCtxUpdate = buildBookingTaxContext(booking.getBingeId());
            BigDecimal preTaxTotal = booking.getTotalAmount();
            TaxComputationResult taxResultUpdate = taxService.compute(taxCtxUpdate, preTaxTotal,
                booking.getBaseAmount() != null ? booking.getBaseAmount() : BigDecimal.ZERO,
                booking.getAddOnAmount() != null ? booking.getAddOnAmount() : BigDecimal.ZERO,
                booking.getGuestAmount() != null ? booking.getGuestAmount() : BigDecimal.ZERO);
            BigDecimal taxAmtUpdate = taxResultUpdate.getTotalTax() != null ? taxResultUpdate.getTotalTax() : BigDecimal.ZERO;
            booking.setSubtotalAmount(preTaxTotal);
            booking.setTaxAmount(taxAmtUpdate);
            booking.setTaxBreakdownJson(taxResultUpdate.getBreakdownJson());
            if (taxAmtUpdate.compareTo(BigDecimal.ZERO) > 0) {
                booking.setTotalAmount(preTaxTotal.add(taxAmtUpdate).setScale(2, RoundingMode.HALF_UP));
            }
        }

        // -- Sync paymentStatus with actual balance -----------------------
        // When the admin changes the price, totalAmount may no longer match what has
        // been collected.  Keep paymentStatus consistent so the customer sees the
        // correct state (PARTIALLY_PAID) and is shown a "pay balance" call-to-action.
        syncPaymentStatusToBalance(booking);

        Booking updated = bookingRepository.save(booking);

        // Award loyalty when admin transitions status to COMPLETED.
        // awardLoyaltyPoints publishes BookingCompletedEvent which the v2
        // listener consumes idempotently (keyed by bookingRef).
        if (!previousStatus.equals("COMPLETED")
                && updated.getStatus() == BookingStatus.COMPLETED
                && updated.getLoyaltyPointsEarned() == 0) {
            awardLoyaltyPoints(updated);
        }

        // Status-change audit rows are already emitted by BookingStateMachine
        // when a transition fires; here we only log a "MODIFIED" row for
        // non-status edits (price overrides, notes, contact details, etc.).
        if (booking.getStatus().name().equals(previousStatus)) {
            String eventDesc = directPriceOverride
                ? "Price adjusted by admin: " + (request.getPriceAdjustmentReason() != null && !request.getPriceAdjustmentReason().isBlank()
                    ? request.getPriceAdjustmentReason() : "Admin price adjustment")
                : "Booking updated by admin";
            eventLogService.logEvent(updated, BookingEventType.MODIFIED, previousStatus, null, "ADMIN", eventDesc);
        }
        return toDto(updated);
    }

    // â”€â”€ Get raw booking entity (for controller-level checks) â”€â”€
    public Booking getBookingEntity(String bookingRef) {
        return findScopedBookingByRef(bookingRef);
    }

    // â”€â”€ Get raw booking entity for background/system flows â”€â”€
    public Booking getBookingEntityForSystem(String bookingRef) {
        return findBookingByRef(bookingRef);
    }

    // ── Admin: cancel booking ─────────────────────────────────────────────
    @Transactional
    public BookingDto cancelBooking(String bookingRef) {
        return cancelBooking(bookingRef, null);
    }

    /**
     * Admin cancellation with optional operator-supplied reason. Item 24 —
     * the reason is persisted on the booking and stitched into the audit log
     * description so the support timeline is self-explanatory.
     */
    @Transactional
    public BookingDto cancelBooking(String bookingRef, String reason) {
        Booking booking = findScopedBookingByRef(bookingRef);
        String description = (reason != null && !reason.isBlank())
            ? "Booking cancelled by admin — " + reason.trim()
            : "Booking cancelled by admin";
        if (reason != null && !reason.isBlank()) {
            booking.setCancellationReason(reason.trim().length() > 500
                ? reason.trim().substring(0, 500) : reason.trim());
        }
        return cancelBooking(booking, "ADMIN", description, 100);
    }

    // â”€â”€ Customer: cancel own PENDING booking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public BookingDto cancelBookingByCustomer(String bookingRef, Long customerId) {
        Booking booking = findScopedBookingByRef(bookingRef);
        if (!booking.getCustomerId().equals(customerId)) {
            // Ownership failure → 403 Forbidden (not 400). Generic message avoids
            // leaking that the booking exists or who owns it.
            throw new BusinessException("Not authorised to cancel this booking", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException(
                "Only PENDING bookings can be cancelled by the customer. Current status: " + booking.getStatus());
        }
        // Production-grade: a PENDING outbound transfer offer locks the booking
        // against owner-side cancellation. The customer must revoke the transfer
        // first — otherwise we'd be racing with the recipient's accept click.
        bookingTransferRepository.findFirstByBookingRefAndStatus(
                bookingRef,
                com.skbingegalaxy.booking.entity.BookingTransfer.Status.PENDING)
            .ifPresent(t -> {
                throw new BusinessException(
                    "A transfer offer is pending for this booking. Revoke the transfer "
                        + "before cancelling.",
                    org.springframework.http.HttpStatus.CONFLICT);
            });
        CancellationPolicyDecision decision = evaluateCustomerCancellation(booking);
        if (!decision.allowed()) {
            throw new BusinessException(decision.message());
        }
        BookingDto result = cancelBooking(booking, "CUSTOMER", "Booking cancelled by customer", decision.refundPercentage());
        // Track customer-initiated pending cancellation toward the freeze policy.
        // Best-effort hook — freeze service swallows its own exceptions.
        if (booking.getBingeId() != null) {
            try { customerFreezeService.recordCustomerCancellation(customerId, booking.getBingeId()); }
            catch (Exception ex) { log.warn("Freeze record (cancellation) failed: {}", ex.getMessage()); }
        }
        return result;
    }

    // â”€â”€ Customer: reschedule own booking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public BookingDto rescheduleBooking(String bookingRef, Long customerId, RescheduleBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);

        // Ownership check — runs FIRST, before any body validation, so that an
        // attacker probing other customers' refs receives 403 regardless of payload.
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to reschedule this booking", org.springframework.http.HttpStatus.FORBIDDEN);
        }

        // Body validation (manual — controller intentionally omits @Valid so we can
        // run ownership first; field-shape errors otherwise leak resource existence).
        if (request == null) {
            throw new BusinessException("Reschedule request body is required");
        }
        if (request.getNewBookingDate() == null) {
            throw new BusinessException("New booking date is required");
        }
        if (request.getNewStartTime() == null) {
            throw new BusinessException("New start time is required");
        }

        // Status check: only PENDING or CONFIRMED can be rescheduled
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Only PENDING or CONFIRMED bookings can be rescheduled. Current status: " + booking.getStatus());
        }

        // Production-grade: a PENDING transfer offer locks the booking against
        // reschedule. The recipient is mid-decision against a specific date/time;
        // changing it underneath them creates a stale offer they'd accept blind.
        bookingTransferRepository.findFirstByBookingRefAndStatus(
                bookingRef,
                com.skbingegalaxy.booking.entity.BookingTransfer.Status.PENDING)
            .ifPresent(t -> {
                throw new BusinessException(
                    "A transfer offer is pending for this booking. Revoke the transfer "
                        + "before rescheduling.",
                    org.springframework.http.HttpStatus.CONFLICT);
            });

        // Anti-abuse: max reschedule limit
        if (booking.getRescheduleCount() >= maxReschedulesPerBooking) {
            throw new BusinessException(
                "This booking has already been rescheduled " + maxReschedulesPerBooking
                    + " times. Please cancel and create a new booking instead.");
        }

        // Cutoff check: must be at least N hours before existing booking start.
        // bookingDate/startTime are venue-local values — compare against venue-local "now"
        // so the window is correct regardless of server or JVM timezone.
        ZoneId rescheduleVenueZone = venueClock.zoneOf(booking.getBingeId());
        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(rescheduleVenueZone), eventStart);
        if (hoursUntilStart < rescheduleCutoffHours) {
            throw new BusinessException(
                "Rescheduling requires at least " + rescheduleCutoffHours
                    + " hours notice before the booking start time.");
        }

        // New date must be in the future
        if (request.getNewBookingDate().isBefore(venueClock.today(booking.getBingeId()))) {
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

        // Operating-hours guard for the *new* slot (same rule as createBooking).
        validateWithinOperatingHours(bingeId, request.getNewStartTime(), newDurMin);

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

        // -- Recompute taxes after schedule / surge change ----------------
        // totalAmount above is the new pre-tax subtotal (post-surge). Tax must
        // be reapplied so the rescheduled booking honours the binge's tax rules
        // exactly like a fresh booking would.
        {
            TaxContext taxCtxResched = buildBookingTaxContext(booking.getBingeId());
            BigDecimal preTaxTotalResched = booking.getTotalAmount();
            TaxComputationResult taxResultResched = taxService.compute(taxCtxResched, preTaxTotalResched,
                booking.getBaseAmount() != null ? booking.getBaseAmount() : BigDecimal.ZERO,
                booking.getAddOnAmount() != null ? booking.getAddOnAmount() : BigDecimal.ZERO,
                booking.getGuestAmount() != null ? booking.getGuestAmount() : BigDecimal.ZERO);
            BigDecimal taxAmtResched = taxResultResched.getTotalTax() != null ? taxResultResched.getTotalTax() : BigDecimal.ZERO;
            booking.setSubtotalAmount(preTaxTotalResched);
            booking.setTaxAmount(taxAmtResched);
            booking.setTaxBreakdownJson(taxResultResched.getBreakdownJson());
            if (taxAmtResched.compareTo(BigDecimal.ZERO) > 0) {
                booking.setTotalAmount(preTaxTotalResched.add(taxAmtResched).setScale(2, RoundingMode.HALF_UP));
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
        String reschedDesc = "Rescheduled (attempt #" + saved.getRescheduleCount() + "): "
            + oldDetails + " -> " + newDetails;
        eventLogService.logEventFull(saved, BookingEventType.RESCHEDULED, oldDetails, customerId,
            "CUSTOMER", null, reschedDesc, request.getReason(),
            com.skbingegalaxy.booking.web.RequestContext.currentIp(),
            com.skbingegalaxy.booking.web.RequestContext.currentUserAgent());
        publishBookingEvent(saved, KafkaTopics.BOOKING_RESCHEDULED);
        log.info("Booking rescheduled: {} (attempt #{})", bookingRef, saved.getRescheduleCount());

        return toDto(saved);
    }

    // â”€â”€ Customer: transfer booking to another person â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public BookingDto transferBooking(String bookingRef, Long customerId, TransferBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);

        // Ownership check
        if (!booking.getCustomerId().equals(customerId)) {
            throw new BusinessException("Not authorised to transfer this booking", org.springframework.http.HttpStatus.FORBIDDEN);
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

        // Cutoff check: must be at least N hours before start.
        // bookingDate/startTime are venue-local values — compare against venue-local "now".
        ZoneId transferVenueZone = venueClock.zoneOf(booking.getBingeId());
        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(transferVenueZone), eventStart);
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
        if (request.getRecipientPhoneCountryCode() != null && !request.getRecipientPhoneCountryCode().isBlank()) {
            booking.setCustomerPhoneCountryCode(request.getRecipientPhoneCountryCode());
        }
        booking.setTransferred(true);

        Booking saved = bookingRepository.save(booking);

        String newCustomerDetails = String.format("Customer: %s (%s)",
            saved.getCustomerName(), saved.getCustomerEmail());
        eventLogService.logEvent(saved, BookingEventType.TRANSFERRED, oldCustomerDetails, customerId,
            "CUSTOMER", "Transferred: " + oldCustomerDetails + " ? " + newCustomerDetails);
        publishBookingEvent(saved, KafkaTopics.BOOKING_TRANSFERRED);
        log.info("Booking transferred: {} from {} to {}", bookingRef,
            booking.getOriginalCustomerName(), request.getRecipientName());

        return toDto(saved);
    }

    // â”€â”€ Customer: create recurring bookings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public RecurringBookingResult createRecurringBookings(RecurringBookingRequest request,
                                                          Long customerId, String customerName,
                                                          String customerEmail, String customerPhone,
                                                          String customerPhoneCountryCode) {
        Long bingeId = BingeContext.requireBingeId();
        assertBingeBookable(bingeId);

        // Anti-abuse: same limits as single booking creation
        long pendingCount = bookingRepository.countPendingByCustomerId(customerId);
        if (pendingCount >= maxPendingPerCustomer) {
            throw new BusinessException(
                "You already have " + pendingCount + " pending booking(s). Please complete or cancel them before creating new ones.");
        }
        LocalDateTime cooldownSince = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(cooldownMinutesAfterTimeout);
        long recentTimeouts = bookingRepository.countRecentTimeoutCancellations(customerId, cooldownSince);
        if (recentTimeouts >= 2) {
            throw new BusinessException(
                "Too many unpaid bookings were auto-cancelled recently. Please wait a few minutes before trying again.");
        }
        // Anti-abuse: per-binge customer freeze (raises 423 LOCKED if active)
        customerFreezeService.assertNotFrozen(customerId, bingeId);

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
            if (date.isBefore(venueClock.today(bingeId))) {
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

                // Validate venue room if requested (V56: must be APPROVED). Done
                // before tax so the room surcharge is part of the tax base.
                Long venueRoomId = null;
                String venueRoomName = null;
                BigDecimal venueRoomPrice = BigDecimal.ZERO;
                if (request.getVenueRoomId() != null) {
                    VenueRoom room = venueRoomRepository.findByIdAndBingeId(request.getVenueRoomId(), bingeId).orElse(null);
                    if (room != null && room.isActive()
                            && (room.getStatus() == null || room.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)) {
                        int roomOccupancy = countRoomBookings(room.getId(), date, startMinute, durMin);
                        if (roomOccupancy < room.getCapacity()) {
                            venueRoomId = room.getId();
                            venueRoomName = room.getName();
                            venueRoomPrice = room.getPriceAddition() != null ? room.getPriceAddition() : BigDecimal.ZERO;
                        }
                        // silently skip room assignment if at capacity (don't fail the whole occurrence)
                    }
                }
                if (venueRoomPrice.compareTo(BigDecimal.ZERO) > 0) {
                    totalAmount = totalAmount.add(venueRoomPrice).setScale(2, RoundingMode.HALF_UP);
                }

                // Tax computation (per-occurrence, since surge varies per date)
                TaxContext taxCtxRec = buildBookingTaxContext(bingeId);
                TaxComputationResult taxResultRec = taxService.compute(taxCtxRec, totalAmount, baseAmount, addOnTotal, guestAmount);
                BigDecimal subtotalRec = totalAmount;
                BigDecimal taxComputedRec = taxResultRec.getTotalTax() != null ? taxResultRec.getTotalTax() : BigDecimal.ZERO;
                if (taxComputedRec.compareTo(BigDecimal.ZERO) > 0) {
                    totalAmount = subtotalRec.add(taxComputedRec).setScale(2, RoundingMode.HALF_UP);
                }
                String taxBreakdownRec = taxResultRec.getBreakdownJson();

                Booking booking = Booking.builder()
                    .bookingRef(generateBookingRef())
                    .bingeId(bingeId)
                    .customerId(customerId)
                    .customerName(customerName)
                    .customerEmail(customerEmail)
                    .customerPhone(customerPhone)
                    .customerPhoneCountryCode(customerPhoneCountryCode)
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
                    .subtotalAmount(subtotalRec)
                    .taxAmount(taxComputedRec)
                    .taxBreakdownJson(taxBreakdownRec)
                    .surgeMultiplier(surgeMultiplier)
                    .surgeLabel(surgeLabel)
                    .pricingSource(eventPrice.source())
                    .rateCodeName(eventPrice.rateCodeName())
                    .venueRoomId(venueRoomId)
                    .venueRoomName(venueRoomName)
                    .venueRoomPrice(venueRoomPrice)
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
                bookingRiskEvaluator.evaluate(saved);
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

    // â”€â”€ Customer: get all bookings in a recurring group â”€â”€â”€â”€â”€â”€
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
            throw new BusinessException("Not authorised to view this recurring group", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return bookings.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getPendingCustomerReviews(Long customerId, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);

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
            throw new BusinessException("Not authorised to access this review", org.springframework.http.HttpStatus.FORBIDDEN);
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
            throw new BusinessException("Not authorised to review this booking", org.springframework.http.HttpStatus.FORBIDDEN);
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

    // â”€â”€ Public: binge review summary (overall rating + distribution) â”€â”€
    //
    //  Produces two averages:
    //   • averageRating ? classic arithmetic mean (kept for analytics
    //     dashboards and old API consumers).
    //   • weightedAverageRating ? each review's contribution is scaled
    //     by the reviewer's influence weight.  The weight combines:
    //       1. loyalty tier (Bronze 1.00 … Platinum 1.15) so highly
    //          engaged repeat customers carry marginally more signal.
    //       2. admin-community trust in the reviewer — admins' private
    //          star ratings on that customer drive a trust multiplier.
    //          A customer habitually rated poorly by admins has their
    //          public-review influence reduced (min 0.60?).  A brand-
    //          new customer with no admin reviews sits slightly below
    //          neutral (0.90?) so spike reviews from fresh accounts
    //          don't dominate the rating.
    //   Weights are bounded to [0.5, 1.25] — they smooth outliers but
    //   never erase a legitimate reviewer.
    @Transactional(readOnly = true, timeout = 10)
    public BingeReviewSummaryDto getBingeReviewSummary(Long bingeId) {
        long count = bookingReviewRepository.countBingeCustomerReviews(bingeId);
        double avg = count > 0 ? bookingReviewRepository.averageBingeRating(bingeId) : 0;

        // Weighted calc — skipped when we have zero reviews.
        double weightedAvg = avg;
        if (count > 0) {
            java.util.List<Object[]> ratings = bookingReviewRepository.ratingAndCustomerIdForBinge(bingeId);
            java.util.Set<Long> customerIds = new java.util.HashSet<>();
            for (Object[] row : ratings) customerIds.add((Long) row[1]);

            // Per-customer loyalty tiers + admin-rating stats (both
            // streamed in bulk to keep this O(1) on review volume).
            java.util.Map<Long, String> tierByCustomer = new java.util.HashMap<>();
            if (!customerIds.isEmpty()) {
                Long programId = loyaltyConfigService.requireDefaultProgram().getId();
                loyaltyMembershipRepository
                    .findByProgramIdAndCustomerIdIn(programId, new java.util.ArrayList<>(customerIds))
                    .forEach(m -> tierByCustomer.put(m.getCustomerId(), m.getCurrentTierCode()));
            }
            java.util.Map<Long, double[]> adminStatsByCustomer = new java.util.HashMap<>();
            if (!customerIds.isEmpty()) {
                for (Object[] row : bookingReviewRepository.adminRatingStatsForCustomers(customerIds)) {
                    Long cid = (Long) row[0];
                    double a = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                    long c = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    adminStatsByCustomer.put(cid, new double[] { a, c });
                }
            }

            double weightedSum = 0;
            double totalWeight = 0;
            for (Object[] row : ratings) {
                int rating = ((Number) row[0]).intValue();
                Long cid = (Long) row[1];
                String tier = tierByCustomer.getOrDefault(cid, "BRONZE");
                double[] adminStats = adminStatsByCustomer.getOrDefault(cid, new double[] { 0, 0 });
                double w = weightForReviewer(tier, adminStats[0], (long) adminStats[1]);
                weightedSum += rating * w;
                totalWeight += w;
            }
            if (totalWeight > 0) weightedAvg = weightedSum / totalWeight;
        }

        List<Object[]> dist = bookingReviewRepository.ratingDistribution(bingeId);
        java.util.Map<Integer, Long> distribution = new java.util.LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) distribution.put(star, 0L);
        for (Object[] row : dist) {
            distribution.put((Integer) row[0], (Long) row[1]);
        }
        return BingeReviewSummaryDto.builder()
            .bingeId(bingeId)
            .averageRating(Math.round(avg * 10.0) / 10.0)
            .weightedAverageRating(Math.round(weightedAvg * 10.0) / 10.0)
            .totalReviews(count)
            .ratingDistribution(distribution)
            .build();
    }

    /** See {@link #getBingeReviewSummary(Long)} for the weighting rationale. */
    private static double weightForReviewer(String tier, double avgAdminRating, long adminReviewCount) {
        double tierMultiplier = switch (tier == null ? "BRONZE" : tier.toUpperCase()) {
            case "PLATINUM" -> 1.15;
            case "GOLD"     -> 1.10;
            case "SILVER"   -> 1.05;
            default         -> 1.00;
        };
        double trust;
        if (adminReviewCount <= 0)      trust = 0.90;
        else if (avgAdminRating >= 4.5) trust = 1.00;
        else if (avgAdminRating >= 3.5) trust = 0.95;
        else if (avgAdminRating >= 2.5) trust = 0.85;
        else                            trust = 0.60;
        return Math.max(0.5, Math.min(1.25, tierMultiplier * trust));
    }

    // â”€â”€ Public: paginated customer reviews for a binge â”€â”€
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<BookingReviewDto> getBingePublicReviews(Long bingeId, org.springframework.data.domain.Pageable pageable) {
        return bookingReviewRepository
            .findByBingeIdAndReviewerRoleAndSkippedFalseAndVisibleToCustomerTrueAndRatingIsNotNull(bingeId, "CUSTOMER", pageable)
            .map(this::toReviewDto);
    }

    // â”€â”€ Admin: customer review summary (avg admin rating + count) â”€â”€
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

    // â”€â”€ Admin: paginated admin reviews for a customer â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<BookingReviewDto> getAdminReviewsForCustomer(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return bookingReviewRepository
            .findByCustomerIdAndReviewerRoleAndSkippedFalseAndRatingIsNotNull(customerId, "ADMIN", pageable)
            .map(this::toReviewDto);
    }

    // â”€â”€ System: cancel booking without request-scoped binge context â”€â”€
    @Transactional
    public BookingDto cancelBookingForSystem(String bookingRef, String reason) {
        Booking booking = findBookingByRef(bookingRef);
        Long customerId = booking.getCustomerId();
        Long bingeId = booking.getBingeId();
        BookingDto result = cancelBooking(booking, "SYSTEM", reason, 100);
        // Detect payment-timeout origin so we can feed the freeze policy.
        if (customerId != null && bingeId != null && reason != null
            && reason.toLowerCase().contains("payment timeout")) {
            try { customerFreezeService.recordPendingPaymentTimeout(customerId, bingeId); }
            catch (Exception ex) { log.warn("Freeze record (payment-timeout) failed: {}", ex.getMessage()); }
        }
        return result;
    }

    private BookingDto cancelBooking(Booking booking, String actorRole, String description, int refundPercentage) {
        String bookingRef = booking.getBookingRef();

        // The state machine enforces "already CANCELLED" / "terminal" rules,
        // but we keep an explicit early-return for the idempotent path so
        // saga retries don't roll back side-effects below (loyalty reversal,
        // collected-amount adjustment). The other terminal states get a
        // friendlier API-facing message than the SM's generic 409 detail.
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("Booking is already cancelled");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException("Cannot cancel a COMPLETED booking");
        }
        if (booking.getStatus() == BookingStatus.NO_SHOW) {
            throw new BusinessException("Cannot cancel a NO_SHOW booking");
        }

        BookingTransitionEvent evt = switch (actorRole == null ? "" : actorRole.toUpperCase()) {
            case "CUSTOMER"     -> BookingTransitionEvent.CUSTOMER_CANCEL;
            case "ADMIN",
                 "SUPER_ADMIN"  -> BookingTransitionEvent.ADMIN_CANCEL;
            default              -> BookingTransitionEvent.SYSTEM_AUTO_CANCEL;
        };
        TransitionActor actor = switch (actorRole == null ? "" : actorRole.toUpperCase()) {
            case "CUSTOMER"    -> TransitionActor.customer(
                                    booking.getCustomerId(), booking.getCustomerName());
            case "ADMIN"       -> TransitionActor.admin(
                                    RequestContext.currentUserId(), RequestContext.currentUserName());
            case "SUPER_ADMIN" -> TransitionActor.superAdmin(
                                    RequestContext.currentUserId(), RequestContext.currentUserName());
            default            -> TransitionActor.system();
        };

        booking.setCancellationActor(actorRole);
        Booking saved = stateMachine.transition(booking, evt, actor, description);

        // Loyalty v2 (M6) — shadow event for the v2 cancellation listener.
        // Refund amount is approximated as (totalAmount ? refundPercentage / 100);
        // the v2 listener uses this ratio to reverse earned points
        // proportionally.  Zero-risk: listener is @Async + AFTER_COMMIT.
        BigDecimal refundAmt = saved.getTotalAmount() == null
                ? BigDecimal.ZERO
                : saved.getTotalAmount()
                        .multiply(BigDecimal.valueOf(refundPercentage))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.FLOOR);
        eventPublisher.publishEvent(new com.skbingegalaxy.booking.event.BookingCancelledEvent(
                saved.getId(),
                saved.getBookingRef(),
                saved.getCustomerId(),
                saved.getBingeId(),
                null,                                                       // tenantId — multi-tenant is future
                saved.getTotalAmount(),
                refundAmt,
                description,
                LocalDateTime.now(ZoneOffset.UTC)
        ));

        // Reverse collectedAmount for cancellations where money was already collected
        if (saved.getCollectedAmount() != null
                && saved.getCollectedAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            subtractFromCollectedAmount(bookingRef, saved.getCollectedAmount());
            log.info("Reversed collectedAmount {} for cancelled booking {}",
                    saved.getCollectedAmount(), bookingRef);
        }

        // Loyalty reversal (both REVERSE_REDEEM and REVERSE_EARN) is handled
        // proportionally by LoyaltyV2BookingListener.onBookingCancelled, which
        // fires AFTER_COMMIT against the v2 wallet ledger.  Nothing to do here.

        // Cancellation audit row was emitted by BookingStateMachine.transition
        // above (with reason / IP / User-Agent). Here we only publish the
        // outbound Kafka event so notification-service can react.
        publishBookingEvent(saved, KafkaTopics.BOOKING_CANCELLED);
        log.info("Booking cancelled: {}", bookingRef);

        return toDto(saved);
    }

    // â”€â”€ Update payment status (called by payment-service via Kafka) â”€â”€
    @Transactional
    public void updatePaymentStatus(String bookingRef, PaymentStatus paymentStatus, String paymentMethod) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", "ref", bookingRef));
        String prevStatus = booking.getStatus().name();
        booking.setPaymentStatus(paymentStatus);
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            booking.setPaymentMethod(paymentMethod);
        }

        // PENDING → CONFIRMED is owned by the central state machine and only
        // fires on full SUCCESS so partial / failed payments don't auto-confirm.
        // The transition saves the entity (capturing paymentStatus + method
        // changes above in the same write). Otherwise we save here directly
        // so the new payment fields are persisted regardless of state.
        if (paymentStatus == PaymentStatus.SUCCESS
                && booking.getStatus() == BookingStatus.PENDING) {
            booking = stateMachine.transition(
                booking, BookingTransitionEvent.PAYMENT_SUCCEEDED,
                TransitionActor.system(),
                "Payment captured — auto-confirmed");
        } else {
            booking = bookingRepository.save(booking);
        }
        eventLogService.logEvent(booking, BookingEventType.PAYMENT_UPDATED, prevStatus, null, "SYSTEM",
            "Payment status changed to " + paymentStatus.name());
        log.info("Payment status updated for {}: {}", bookingRef, paymentStatus);
    }

    // â”€â”€ Collected amount tracking (called by payment-service via Kafka) â”€â”€
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

    // â”€â”€ Dashboard stats â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

    // â”€â”€ Audit: auto-mark past unchecked-in bookings â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public AuditResultDto runAudit(LocalDate clientToday, LocalDateTime clientNow) {
        // Use client's date/time as reference so UTC-offset servers don't break IST admins
        Long bid = BingeContext.getBingeId();
        LocalDate refToday = clientToday != null ? clientToday : venueClock.today(bid);
        LocalDateTime refNow  = clientNow  != null ? clientNow  : LocalDateTime.now(ZoneOffset.UTC);
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
                Booking marked = stateMachine.transition(
                    b, BookingTransitionEvent.MARK_NO_SHOW,
                    TransitionActor.system(),
                    "Marked no-show by end-of-day audit");
                // Publish a BOOKING_CANCELLED-topic event so notification-service
                // cancels any pending reminders (POST_VISIT_REVIEW, etc) for the
                // no-show booking. The event's status field is "NO_SHOW", which
                // the listener uses to suppress the user-facing "your booking was
                // cancelled" email — only the reminder-cancellation side-effect
                // runs.
                publishBookingEvent(marked, KafkaTopics.BOOKING_CANCELLED);
                // Anti-abuse: count NO_SHOW toward the per-binge freeze threshold.
                // REQUIRES_NEW inside the service ensures this counter update
                // doesn't roll back if a later booking in the loop fails.
                customerFreezeService.recordNoShow(marked.getCustomerId(), marked.getBingeId());
                markedNoShow++;
                affectedRefs.add(marked.getBookingRef());
            } else if (b.getStatus() == BookingStatus.CHECKED_IN) {
                b.setCheckedIn(false);
                Booking completed = stateMachine.transition(
                    b, BookingTransitionEvent.CHECK_OUT,
                    TransitionActor.system(),
                    "Auto-completed by end-of-day audit");
                // Emit booking.completed so downstream services (analytics,
                // post-visit reviews, loyalty external integrations) can react.
                publishBookingEvent(completed, KafkaTopics.BOOKING_COMPLETED);
                awardLoyaltyPoints(completed);
                markedCompleted++;
                affectedRefs.add(completed.getBookingRef());
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

    // â”€â”€ Reports â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public ReportDto getReport(String period, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);
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

    // â”€â”€ Reports: custom date range â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public ReportDto getReportByDateRange(LocalDate from, LocalDate to, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate refToday = clientToday != null ? clientToday : venueClock.today(bid);
        if (to.isAfter(refToday)) to = refToday;
        if (from.isAfter(to)) from = to;
        LocalDate finalFrom = from;
        LocalDate finalTo = to;
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

    // â”€â”€ House accounts: pending payments â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public Page<BookingDto> getPendingPaymentBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return (bid != null ? bookingRepository.findByBingeIdAndPaymentStatus(bid, PaymentStatus.PENDING, pageable) : bookingRepository.findByPaymentStatus(PaymentStatus.PENDING, pageable)).map(this::toDto);
    }

    // â”€â”€ Customer booking count â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public long getCustomerBookingCount(Long customerId) {
        Long bid = BingeContext.getBingeId();
        return bid != null ? bookingRepository.countByBingeIdAndCustomerId(bid, customerId) : bookingRepository.countByCustomerId(customerId);
    }

    // â”€â”€ Booked slots for a date (for double-booking prevention) â”€â”€
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

    // â”€â”€ Check for time overlap with existing bookings (minutes-based) â”€â”€
    @Transactional(readOnly = true)
    public boolean hasTimeConflict(LocalDate date, int startMinute, int durationMinutes) {
        return hasTimeConflict(date, startMinute, durationMinutes, null);
    }

    /**
     * Slot-availability rules for {@link com.skbingegalaxy.booking.service.SlotHoldService}
     * pre-payment holds. Mirrors the booking-creation flow so a hold sees the
     * exact same world a confirmed booking would: operating hours, remote
     * availability check, time conflicts vs. existing active bookings,
     * capacity ceiling, and conflicts vs. other live holds.
     *
     * <p>Throws {@link BusinessException} with a customer-readable message
     * when the slot is unavailable.</p>
     *
     * @param bingeId            current binge
     * @param date               requested date
     * @param startMinute        minutes-since-midnight start
     * @param durationMinutes    requested duration
     * @param venueRoomId        optional room (currently informational; remote
     *                           availability check enforces room-level rules)
     * @param excludeHoldToken   when re-checking an existing hold, exclude
     *                           this hold from the conflict count; {@code null}
     *                           for new holds
     */
    @Transactional
    public void assertSlotAvailableForHold(Long bingeId,
                                            LocalDate date,
                                            int startMinute,
                                            int durationMinutes,
                                            Long venueRoomId,
                                            String excludeHoldToken) {
        if (bingeId == null) {
            throw new BusinessException("No binge selected for slot hold");
        }
        if (date == null) {
            throw new BusinessException("Slot hold date is required");
        }
        if (durationMinutes <= 0 || durationMinutes % 30 != 0) {
            throw new BusinessException("Duration must be a positive 30-minute multiple");
        }
        if (startMinute < 0 || startMinute >= 24 * 60) {
            throw new BusinessException("Start time is out of range");
        }

        LocalTime startTime = LocalTime.of(startMinute / 60, startMinute % 60);
        validateWithinOperatingHours(bingeId, startTime, durationMinutes);

        Boolean available = availabilityClient.checkSlotAvailable(
            internalApiSecret, date, bingeId, startMinute, durationMinutes);
        if (available != null) {
            availabilityFallback.cacheResult(date, startMinute, durationMinutes, available);
        }
        if (available == null) {
            throw new BusinessException("Availability service is temporarily unavailable. Please try again.");
        }
        if (Boolean.FALSE.equals(available)) {
            throw new BusinessException("Selected date/time slot is not available");
        }

        if (hasTimeConflict(date, startMinute, durationMinutes)) {
            throw new BusinessException("Selected time slot conflicts with an existing booking");
        }

        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        Integer maxConcurrent = binge != null ? binge.getMaxConcurrentBookings() : null;
        int existingBookings = countOverlappingBookings(date, startMinute, durationMinutes);

        int liveHoldOverlap = 0;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
            int newEnd = startMinute + durationMinutes;
            for (com.skbingegalaxy.booking.entity.SlotHold h :
                    slotHoldRepository.findLiveHoldsByBingeAndDate(bingeId, date, now)) {
                if (excludeHoldToken != null && excludeHoldToken.equals(h.getHoldToken())) continue;
                int hStart = h.getStartTime().getHour() * 60 + h.getStartTime().getMinute();
                int hEnd = hStart + Math.max(h.getDurationMinutes(), 0);
                if (startMinute < hEnd && newEnd > hStart) {
                    liveHoldOverlap++;
                }
            }
        } catch (Exception e) {
            log.warn("Slot-hold overlap check failed for binge={} date={}: {}", bingeId, date, e.getMessage());
        }

        if (maxConcurrent != null && (existingBookings + liveHoldOverlap) >= maxConcurrent) {
            throw new BusinessException(
                "CAPACITY_FULL:This time slot has reached maximum capacity ("
                    + maxConcurrent + " bookings). Try a different time or join the waitlist.");
        }
    }

    /**
     * Reject a booking whose start time / end time fall outside the binge's
     * published operating window. Resolution order:
     * <ol>
     *   <li>Per-binge {@code openTime} / {@code closeTime} on the {@link Binge}
     *       row, if both are set;</li>
     *   <li>Otherwise the global {@code app.theater.opening-hour} /
     *       {@code app.theater.closing-hour} fallback (defaults 10:00 / 23:00).</li>
     * </ol>
     *
     * <p>Validates that {@code startMinute >= openMinute} AND
     * {@code startMinute + durationMinutes <= closeMinute}, so a booking that
     * <em>spans past</em> closing time is rejected too. Cross-midnight
     * windows are not currently supported (admin must set close > open).</p>
     *
     * @param bingeId the binge to validate against; if null, only global
     *                fallback is used
     * @param startTime customer's chosen start time
     * @param durationMinutes resolved duration in minutes
     * @throws BusinessException with a friendly message if outside the window
     */
    private void validateWithinOperatingHours(Long bingeId, LocalTime startTime, int durationMinutes) {
        if (startTime == null) {
            return; // upstream validators have already rejected null start times
        }
        LocalTime openTime = null;
        LocalTime closeTime = null;
        if (bingeId != null) {
            Binge binge = bingeRepository.findById(bingeId).orElse(null);
            if (binge != null) {
                openTime = binge.getOpenTime();
                closeTime = binge.getCloseTime();
            }
        }
        if (openTime == null) {
            openTime = LocalTime.of(Math.min(Math.max(defaultOpeningHour, 0), 23), 0);
        }
        if (closeTime == null) {
            // closingHour 24 == end of day → use 23:59 to keep within LocalTime range.
            int ch = Math.min(Math.max(defaultClosingHour, 1), 24);
            closeTime = ch >= 24 ? LocalTime.of(23, 59) : LocalTime.of(ch, 0);
        }
        if (!closeTime.isAfter(openTime)) {
            // Mis-configured binge — fall back to global defaults so we don't
            // brick bookings entirely while ops fixes the row.
            log.warn("Binge {} has invalid operating hours open={} close={}; using global fallback",
                bingeId, openTime, closeTime);
            openTime = LocalTime.of(Math.min(Math.max(defaultOpeningHour, 0), 23), 0);
            int ch = Math.min(Math.max(defaultClosingHour, 1), 24);
            closeTime = ch >= 24 ? LocalTime.of(23, 59) : LocalTime.of(ch, 0);
        }
        int openMinute = openTime.getHour() * 60 + openTime.getMinute();
        int closeMinute = closeTime.getHour() * 60 + closeTime.getMinute();
        int startMinute = startTime.getHour() * 60 + startTime.getMinute();
        int endMinute = startMinute + durationMinutes;
        if (startMinute < openMinute) {
            throw new BusinessException(
                "Booking start time " + startTime + " is before this binge's opening time ("
                    + openTime + "). Please pick a later slot.");
        }
        if (endMinute > closeMinute) {
            throw new BusinessException(
                "Booking would end after this binge's closing time (" + closeTime
                    + "). Either pick an earlier start time or reduce the duration.");
        }
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

    /**
     * V57: returns true when the slot [startMinute, startMinute+durationMinutes)
     * on {@code date} overlaps any maintenance / hold block on {@code roomId}.
     * Resolved at request time so newly-added blocks immediately gate availability.
     */
    private boolean isRoomBlocked(Long roomId, LocalDate date, int startMinute, int durationMinutes) {
        if (roomId == null || date == null) return false;
        java.time.LocalDateTime windowStart = date.atTime(startMinute / 60, startMinute % 60);
        java.time.LocalDateTime windowEnd = windowStart.plusMinutes(Math.max(durationMinutes, 1));
        return !roomBlockRepository.findOverlapping(roomId, windowStart, windowEnd).isEmpty();
    }

    /** Count active bookings assigned to a specific venue room, optionally excluding one booking. */
    private int countRoomBookings(Long roomId, LocalDate date, int startMinute, int durationMinutes, Long excludeBookingId) {
        // V57: a maintenance / hold block covering this slot makes the room fully unavailable.
        if (isRoomBlocked(roomId, date, startMinute, durationMinutes)) {
            VenueRoom room = venueRoomRepository.findById(roomId).orElse(null);
            return room != null ? room.getCapacity() : Integer.MAX_VALUE;
        }
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

    /**
     * Keeps the booking's paymentStatus consistent with its actual financial balance.
     * Called after any admin price mutation so the customer-facing state stays accurate:
     * <ul>
     *   <li>SUCCESS ? PARTIALLY_PAID  when the new total exceeds what has been collected</li>
     *   <li>PARTIALLY_PAID ? SUCCESS  when a top-up closes the gap (defensive: normally
     *       Kafka does this, but the guard is cheap)</li>
     * </ul>
     * Refunded / FAILED / PENDING statuses are intentionally left untouched — those have
     * their own lifecycle managed by the PaymentEventListener saga.
     */

    /**
     * Build a {@link TaxContext} for a server-side booking persist operation.
     *
     * <p>Critical for jurisdiction matching: {@link JurisdictionResolver} hard-filters
     * any TaxRule whose {@code countryCode} / {@code stateCode} / {@code city} /
     * {@code postalCode} does not match the context. If we pass an empty context
     * (only bingeId / customerType / productType), country-scoped rules such as
     * India GST are silently dropped — leading to the customer being shown tax in
     * the checkout preview but billed without tax on the persisted booking.
     *
     * <p>We therefore enrich the context with the binge's venue address (used by
     * {@code TaxContext#resolved*()} when billing address is unknown). This keeps
     * the persist path consistent with {@code CheckoutQuoteService.preview()}.
     */
    private TaxContext buildBookingTaxContext(Long bingeId) {
        TaxContext.TaxContextBuilder b = TaxContext.builder()
            .bingeId(bingeId)
            .customerType("B2C")
            .productType("BOOKING")
            .venueZone(venueClock.zoneOf(bingeId));
        if (bingeId != null) {
            bingeRepository.findById(bingeId).ifPresent(binge -> {
                b.venueCountryCode(binge.getCountry())
                 .venueStateCode(binge.getState())
                 .venueCity(binge.getCity())
                 .venuePostalCode(binge.getPostalCode());
            });
        }
        return b.build();
    }

    private void syncPaymentStatusToBalance(Booking booking) {
        PaymentStatus current = booking.getPaymentStatus();
        if (current == null) return;
        // Only act on statuses that represent a "paid" state
        if (current != PaymentStatus.SUCCESS && current != PaymentStatus.PARTIALLY_PAID) return;

        BigDecimal collected = booking.getCollectedAmount() != null
            ? booking.getCollectedAmount() : java.math.BigDecimal.ZERO;
        BigDecimal total = booking.getTotalAmount() != null
            ? booking.getTotalAmount() : java.math.BigDecimal.ZERO;
        if (total.compareTo(java.math.BigDecimal.ZERO) <= 0) return;

        if (current == PaymentStatus.SUCCESS
                && collected.compareTo(total) < 0) {
            booking.setPaymentStatus(PaymentStatus.PARTIALLY_PAID);
            log.info("Booking {} paymentStatus adjusted SUCCESS?PARTIALLY_PAID after price change "
                + "(collected={}, newTotal={})", booking.getBookingRef(), collected, total);
        } else if (current == PaymentStatus.PARTIALLY_PAID
                && collected.compareTo(total) >= 0) {
            booking.setPaymentStatus(PaymentStatus.SUCCESS);
            log.info("Booking {} paymentStatus adjusted PARTIALLY_PAID?SUCCESS after price change "
                + "(collected={}, newTotal={})", booking.getBookingRef(), collected, total);
        }
    }

    /**
     * Award loyalty points when a booking is completed.  Publishes an
     * in-process {@code BookingCompletedEvent}; the v2 listener
     * {@code LoyaltyV2BookingListener.onBookingCompleted} handles
     * earning idempotently against the v2 wallet ledger.  Safe to call
     * multiple times — the listener keys off bookingRef.
     */
    private void awardLoyaltyPoints(Booking booking) {
        try {
            BigDecimal payableAmount = booking.getCollectedAmount() != null
                ? booking.getCollectedAmount() : booking.getTotalAmount();
            eventPublisher.publishEvent(new com.skbingegalaxy.booking.event.BookingCompletedEvent(
                booking.getId(),
                booking.getBookingRef(),
                booking.getCustomerId(),
                booking.getBingeId(),
                null,                                                       // tenantId — multi-tenant is future
                payableAmount,
                LocalDateTime.now(ZoneOffset.UTC)
            ));
        } catch (Exception e) {
            log.error("Failed to publish loyalty earn event for booking {}: {}",
                booking.getBookingRef(), e.getMessage(), e);
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

    /**
     * Defence-in-depth: refuse to create or modify reservations against a binge
     * that hasn't been approved by a super-admin, has been rejected, or has been
     * deactivated. Customer-visible listings already filter these out
     * ({@link com.skbingegalaxy.booking.repository.BingeRepository#findCustomerVisibleBinges()}),
     * but the booking-write APIs trust an {@code X-Binge-Id} header that an
     * authenticated client can spoof, so we re-check at the write boundary.
     */
    private void assertBingeBookable(Long bingeId) {
        Binge binge = bingeRepository.findById(bingeId)
            .orElseThrow(() -> new ResourceNotFoundException("Binge", "id", bingeId));
        if (binge.getStatus() != null && binge.getStatus() != BingeApprovalStatus.APPROVED) {
            throw new BusinessException(
                "This venue is not currently accepting bookings (awaiting super-admin approval).");
        }
        if (!binge.isActive()) {
            throw new BusinessException("This venue is currently inactive and not accepting bookings.");
        }
    }

    private static final java.util.Map<BookingStatus, java.util.Set<BookingStatus>> VALID_TRANSITIONS = java.util.Map.of(
        BookingStatus.PENDING, java.util.Set.of(BookingStatus.CONFIRMED, BookingStatus.CANCELLED),
        BookingStatus.CONFIRMED, java.util.Set.of(BookingStatus.CHECKED_IN, BookingStatus.CANCELLED, BookingStatus.NO_SHOW),
        BookingStatus.CHECKED_IN, java.util.Set.of(BookingStatus.COMPLETED),
        BookingStatus.COMPLETED, java.util.Set.of(),
        BookingStatus.CANCELLED, java.util.Set.of(),
        BookingStatus.NO_SHOW, java.util.Set.of()
    );

    @SuppressWarnings("unused") // retained as documentation of legacy table; canonical rules live in BookingStateMachine
    private static boolean isValidTransition(BookingStatus from, BookingStatus to) {
        return VALID_TRANSITIONS.getOrDefault(from, java.util.Set.of()).contains(to);
    }

    /**
     * Build a {@link TransitionActor} for the current admin/super-admin
     * request, falling back to {@code SYSTEM} when called outside a
     * request scope. Used by the {@link #updateBooking} PATCH path.
     */
    /**
     * Build a {@link TransitionActor} from the current request headers. ALL
     * callers of this helper are admin-only entry points (updateBooking,
     * earlyCheckout, undoCheckIn) so when the gateway headers are absent —
     * e.g. unit tests, internal cron jobs that re-enter via the admin
     * surface — we default to {@code ADMIN} role rather than SYSTEM. The
     * SM's role allow-list rejects SYSTEM for ADMIN_CONFIRM / CHECK_IN /
     * UNDO_CHECK_IN, so a SYSTEM fallback would 409 every test and any
     * legitimate admin call that lost its X-User-Role header on the way in.
     */
    private static TransitionActor adminActorFromContext() {
        String role = RequestContext.currentRole();
        if (role == null || role.isBlank()) {
            return TransitionActor.admin(
                RequestContext.currentUserId(), RequestContext.currentUserName());
        }
        return TransitionActor.from(role,
            RequestContext.currentUserId(), RequestContext.currentUserName());
    }

    // â”€â”€ Admin: early checkout â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // ── Balance must be settled before checkout (production-grade guard) ──
        BigDecimal total = booking.getTotalAmount() != null ? booking.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal collected = booking.getCollectedAmount() != null ? booking.getCollectedAmount() : BigDecimal.ZERO;
        BigDecimal balance = total.subtract(collected);
        if (balance.abs().compareTo(new BigDecimal("0.01")) > 0) {
            String direction = balance.signum() > 0
                ? "Outstanding balance of ₹" + balance.toPlainString() + " must be collected"
                : "Customer overpaid by ₹" + balance.abs().toPlainString() + "; issue refund";
            throw new BusinessException(
                "Cannot checkout — balance not settled. " + direction
                + " before checkout. Use \"Adjust Prices\" or the Payment tab to reconcile.");
        }

        LocalDateTime now = clientNow != null ? clientNow : LocalDateTime.now(ZoneOffset.UTC);
        // Use actual check-in time when available — otherwise fall back to scheduled start.
        LocalDateTime sessionStart = booking.getActualCheckInTime() != null
            ? booking.getActualCheckInTime()
            : LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        LocalDateTime scheduledStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        int bookedMinutes = resolveDurationMinutes(booking.getDurationMinutes(), booking.getDurationHours());
        LocalDateTime scheduledEnd = scheduledStart.plusMinutes(bookedMinutes);

        // Only treat as early if current time is before the scheduled end
        if (!now.isBefore(scheduledEnd)) {
            // Not early — do a normal checkout, but still record actual session duration
            long fullSessionMinutes = java.time.Duration.between(sessionStart, now).toMinutes();
            if (fullSessionMinutes < 0) fullSessionMinutes = 0;
            booking.setCheckedIn(false);
            booking.setActualCheckoutTime(now);
            booking.setActualUsedMinutes((int) fullSessionMinutes);
            Booking completed = stateMachine.transition(
                booking, BookingTransitionEvent.CHECK_OUT,
                adminActorFromContext(),
                "Scheduled checkout");
            awardLoyaltyPoints(completed);
            return toDto(completed);
        }

        long usedMinutes = java.time.Duration.between(sessionStart, now).toMinutes();
        if (usedMinutes < 0) usedMinutes = 0;

        // For slot/availability release: round up to nearest 30-min boundary
        int roundedUsed = ((int) Math.ceil(usedMinutes / 30.0)) * 30;
        long remainingMinutes = bookedMinutes - roundedUsed;
        if (remainingMinutes < 0) remainingMinutes = 0;

        // Build human-readable duration strings using ACTUAL minutes (no rounding in display)
        long usedHours = usedMinutes / 60;
        long usedMins  = usedMinutes % 60;
        String usedStr = usedHours > 0 && usedMins > 0
            ? String.format("%dh %dm", usedHours, usedMins)
            : usedHours > 0
                ? String.format("%dh", usedHours)
                : String.format("%dm", usedMins);

        // Build booked duration string
        long bookedH = bookedMinutes / 60;
        long bookedM = bookedMinutes % 60;
        String bookedStr = bookedH > 0 && bookedM > 0
            ? String.format("%dh %dm", bookedH, bookedM)
            : bookedH > 0
                ? String.format("%dh", bookedH)
                : String.format("%dm", bookedM);

        java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("hh:mm a");
        String checkInDisplay = booking.getActualCheckInTime() != null
            ? booking.getActualCheckInTime().toLocalTime().format(timeFmt)
            : booking.getStartTime().format(timeFmt);
        String note = String.format(
            "Early checkout at %s (checked in %s). Used %s of %s booked.",
            now.toLocalTime().format(timeFmt), checkInDisplay,
            usedStr, bookedStr);

        booking.setCheckedIn(false);
        booking.setActualCheckoutTime(now);
        booking.setActualUsedMinutes((int) usedMinutes);
        booking.setEarlyCheckoutNote(note);

        // Append to admin notes as well
        String existing = booking.getAdminNotes() != null ? booking.getAdminNotes() + " | " : "";
        booking.setAdminNotes(existing + note);

        // CHECKED_IN → COMPLETED is owned by the central state machine; the
        // emitted audit row carries the early-checkout note as the reason.
        Booking saved = stateMachine.transition(
            booking, BookingTransitionEvent.CHECK_OUT,
            adminActorFromContext(),
            note);
        log.info("Early checkout for {}: {}", bookingRef, note);

        awardLoyaltyPoints(saved);

        return toDto(saved);
    }

    // ── Admin: undo check-in ──────────────────────────────────────────────
    /**
     * Reverts an in-progress check-in back to CONFIRMED. This is a deliberate,
     * audited reverse-transition that bypasses the forward-only state machine
     * (CHECKED_IN → CONFIRMED is not in {@code VALID_TRANSITIONS} by design,
     * so admin "undo" must not flow through {@link #updateBooking}).
     *
     * <p>Production-grade behaviour:
     * <ul>
     *   <li>Idempotent: re-invoking on an already-CONFIRMED booking is a no-op
     *       (returns the current state) — paired with the controller's
     *       Idempotency-Key handling, double-clicks never error.</li>
     *   <li>Refuses to undo once the session has been checked out — that
     *       transition is COMPLETED and would corrupt revenue/used-minutes.</li>
     *   <li>Clears side-effects of check-in: {@code actualCheckInTime} and the
     *       {@code lateArrival} flag, so a follow-up legitimate check-in is
     *       scored fresh.</li>
     *   <li>Emits a {@code CHECK_IN_REVERTED} audit event with previous status,
     *       actor, reason, and request-context (IP / User-Agent) for forensic
     *       review.</li>
     * </ul>
     */
    @Transactional
    public BookingDto undoCheckIn(String bookingRef, Long adminId, String reason) {
        Booking booking = findScopedBookingByRef(bookingRef);

        // Idempotent no-op — already reverted (e.g. retried request).
        if (booking.getStatus() == BookingStatus.CONFIRMED && !booking.isCheckedIn()) {
            return toDto(booking);
        }

        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BusinessException(
                "Undo check-in requires the booking to be CHECKED_IN. Current status: "
                    + booking.getStatus(),
                org.springframework.http.HttpStatus.CONFLICT);
        }

        // Defence-in-depth: a checked-out session cannot be unwound here.
        // (Status guard above already covers this, but the explicit check
        // documents intent and survives future state-machine edits.)
        if (booking.getActualCheckoutTime() != null) {
            throw new BusinessException(
                "Cannot undo check-in — session has already been checked out.",
                org.springframework.http.HttpStatus.CONFLICT);
        }

        // Pre-clear check-in side-effects so the audit row reflects a clean
        // CONFIRMED state. The state-machine save below persists everything
        // atomically and emits the CHECK_IN_REVERTED audit event.
        String previousStatus = booking.getStatus().name();
        booking.setCheckedIn(false);
        booking.setActualCheckInTime(null);
        booking.setLateArrival(false);

        TransitionActor actor = adminActorFromContext();
        Booking saved = stateMachine.transition(
            booking, BookingTransitionEvent.UNDO_CHECK_IN, actor,
            (reason != null && !reason.isBlank()) ? reason.trim() : "Check-in reverted by admin");

        log.info("Check-in reverted for {} by admin {} (was {})",
            bookingRef, adminId, previousStatus);

        return toDto(saved);
    }

    // ── SUPER_ADMIN: state-machine override ──────────────────────────────
    /**
     * Force a booking into {@code targetStatus}, bypassing the normal
     * transition table. Reserved for operational recovery scenarios such as:
     *
     * <ul>
     *   <li>Reinstating a wrongfully-cancelled booking (CANCELLED → CONFIRMED)
     *       — e.g. after a payment-gateway false-negative was reconciled.</li>
     *   <li>Undoing a misapplied no-show (NO_SHOW → CHECKED_IN) — e.g. the
     *       customer arrived but the front-desk forgot to scan their QR.</li>
     *   <li>Reverting a premature COMPLETED → CHECKED_IN when checkout fired
     *       in error.</li>
     * </ul>
     *
     * <p>Caller must hold the SUPER_ADMIN role; the audit row is tagged
     * {@link BookingEventType#MANUAL_REVIEW_FLAGGED} so the timeline clearly
     * shows the override. Reason is mandatory and recorded in full.
     *
     * @throws com.skbingegalaxy.booking.service.statemachine.InvalidTransitionException
     *         when the actor is not super-admin, the reason is blank, or the
     *         (current → target) pair is not in the override allow-list.
     */
    @Transactional
    public BookingDto adminOverrideStatus(String bookingRef, BookingStatus targetStatus,
                                          Long superAdminId, String reason) {
        Booking booking = findScopedBookingByRef(bookingRef);
        // Capture BEFORE the SM mutates the entity in place — otherwise the
        // info log below would read "target → target" because override()
        // updates booking.status before returning.
        BookingStatus previousStatus = booking.getStatus();
        TransitionActor actor = TransitionActor.superAdmin(
            superAdminId, RequestContext.currentUserName());
        Booking saved = stateMachine.override(booking, targetStatus, actor, reason);
        log.warn("ADMIN_OVERRIDE applied to {}: {} → {} by SUPER_ADMIN id={} reason='{}'",
            bookingRef, previousStatus, targetStatus, superAdminId, reason);
        return toDto(saved);
    }

    // â”€â”€ Admin: create booking (walk-in) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // Operating-hours guard for admin walk-ins. Same rule as customer
        // createBooking — admins cannot accidentally create a 03:00 booking
        // either, even though they may have legitimate reason to (post-hours
        // private events should be modeled by widening the binge's window
        // explicitly via the BingeService update endpoint).
        Long adminBingeIdForHours = BingeContext.getBingeId();
        if (adminBingeIdForHours != null) {
            validateWithinOperatingHours(adminBingeIdForHours, request.getStartTime(), durMin);
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

        // ── Venue room assignment (admin walk-in) ─────────────
        // Mirrors the customer createBooking room block: resolve, validate
        // active+APPROVED, enforce per-room concurrent-capacity, and enforce
        // the per-binge "room selection required" toggle. Snapshot the room
        // id/name/price onto the Booking so reporting stays accurate even if
        // the room is later renamed, re-priced, or deactivated.
        Long adminVenueRoomId = null;
        String adminVenueRoomName = null;
        BigDecimal adminVenueRoomPrice = BigDecimal.ZERO;
        if (request.getVenueRoomId() != null && adminBingeId != null) {
            VenueRoom room = venueRoomRepository.findByIdAndBingeId(request.getVenueRoomId(), adminBingeId)
                .orElseThrow(() -> new BusinessException("Selected room not found"));
            if (!room.isActive()) throw new BusinessException("Selected room is currently unavailable");
            if (room.getStatus() != null && room.getStatus() != com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED) {
                throw new BusinessException("Selected room is not yet approved for bookings");
            }
            int roomOccupancy = countRoomBookings(room.getId(), request.getBookingDate(), startMinute, durMin);
            if (roomOccupancy >= room.getCapacity()) {
                throw new BusinessException("Selected room '" + room.getName() + "' is fully booked for this time slot");
            }
            adminVenueRoomId = room.getId();
            adminVenueRoomName = room.getName();
            adminVenueRoomPrice = room.getPriceAddition() != null ? room.getPriceAddition() : BigDecimal.ZERO;
        } else if (adminBingeId != null) {
            Binge bingeCfg = bingeRepository.findById(adminBingeId).orElse(null);
            if (bingeCfg != null && bingeCfg.isRoomSelectionRequired()) {
                throw new BusinessException("This binge requires a room to be selected before booking");
            }
        }

        // Calculate pricing using resolved customer pricing (if customer is known)
        Long custId = request.getCustomerId() != null ? request.getCustomerId() : 0L;
        String pricingSource;
        String rateCodeName;

        // Precedence (per-event / per-addon):
        //   customer-specific custom  >  admin override rate code  >  profile rate code  >  default
        // If the admin picks a rate code at booking time but the customer has their own
        // custom price for this event or addon, the customer's personal deal wins. This
        // matches PricingService.resolveEventPrice(customerId, eventTypeId, overrideRateCodeId).
        Long overrideRateCodeId = request.getRateCodeId();

        PricingService.ResolvedEventPrice eventPrice;
        if (custId > 0) {
            eventPrice = pricingService.resolveEventPrice(custId, request.getEventTypeId(), overrideRateCodeId);
        } else if (overrideRateCodeId != null) {
            // Walk-in booking with no known customer &mdash; admin's rate code drives pricing.
            eventPrice = pricingService.resolveEventPrice(0L, request.getEventTypeId(), overrideRateCodeId);
        } else {
            eventPrice = new PricingService.ResolvedEventPrice(
                eventType.getBasePrice(), eventType.getHourlyRate(), eventType.getPricePerGuest(), "DEFAULT", null);
        }
        pricingSource = eventPrice.source();
        rateCodeName = eventPrice.rateCodeName();

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
                PricingService.ResolvedAddonPrice ap = pricingService.resolveAddonPrice(
                    custId > 0 ? custId : 0L, sel.getAddOnId(), overrideRateCodeId);
                BigDecimal linePrice = ap.price().multiply(BigDecimal.valueOf(qty));
                addOnTotal = addOnTotal.add(linePrice);
                // If ANY addon resolves to CUSTOMER, promote overall source so admins see the strongest tag.
                if ("CUSTOMER".equals(ap.source())) {
                    pricingSource = "CUSTOMER";
                } else if ("RATE_CODE".equals(ap.source()) && "DEFAULT".equals(pricingSource)) {
                    pricingSource = "RATE_CODE";
                    if (rateCodeName == null) rateCodeName = ap.rateCodeName();
                }
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
                .customerPhoneCountryCode(request.getCustomerPhoneCountryCode())
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
                // Admin explicitly set the final total — respect it, no tax added on top.
                .subtotalAmount(totalAmount)
                .taxAmount(BigDecimal.ZERO)
                .pricingSource(pricingSource)
                .rateCodeName(rateCodeName)
                .venueRoomId(adminVenueRoomId)
                .venueRoomName(adminVenueRoomName)
                .venueRoomPrice(adminVenueRoomPrice)
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
            bookingRiskEvaluator.evaluate(saved);
            return toDto(saved);
        }

        BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);
        // V56: room surcharge added flat (pre-tax) — mirrors customer createBooking.
        if (adminVenueRoomPrice.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = totalAmount.add(adminVenueRoomPrice).setScale(2, RoundingMode.HALF_UP);
        }
        validateAdminPaymentTracking(custId, totalAmount);
        String bookingRef = generateBookingRef();

        boolean autoConfirm = totalAmount.compareTo(BigDecimal.ZERO) == 0;
        BookingStatus status = autoConfirm ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
        PaymentStatus payStatus = autoConfirm ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;

        // ── Tax computation ────────────────────────────────────
        TaxContext taxCtxAdmin = buildBookingTaxContext(BingeContext.getBingeId());
        TaxComputationResult taxResultAdmin = taxService.compute(taxCtxAdmin, totalAmount, baseAmount, addOnTotal, guestAmount);
        BigDecimal subtotalAdmin = totalAmount;
        BigDecimal taxComputedAdmin = taxResultAdmin.getTotalTax() != null ? taxResultAdmin.getTotalTax() : BigDecimal.ZERO;
        if (taxComputedAdmin.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = subtotalAdmin.add(taxComputedAdmin).setScale(2, RoundingMode.HALF_UP);
        }
        String taxBreakdownAdmin = taxResultAdmin.getBreakdownJson();

        Booking booking = Booking.builder()
            .bookingRef(bookingRef)
            .bingeId(BingeContext.getBingeId())
            .customerId(custId)
            .customerName(request.getCustomerName())
            .customerEmail(request.getCustomerEmail())
            .customerPhone(request.getCustomerPhone() != null ? request.getCustomerPhone() : "")
            .customerPhoneCountryCode(request.getCustomerPhoneCountryCode())
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
            .subtotalAmount(subtotalAdmin)
            .taxAmount(taxComputedAdmin)
            .taxBreakdownJson(taxBreakdownAdmin)
            .pricingSource(pricingSource)
            .rateCodeName(rateCodeName)
            .venueRoomId(adminVenueRoomId)
            .venueRoomName(adminVenueRoomName)
            .venueRoomPrice(adminVenueRoomPrice)
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
        bookingRiskEvaluator.evaluate(saved);

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

    // â”€â”€ Event types & add-ons (public) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public List<EventTypeDto> getActiveEventTypes() {
        Long bid = requireSelectedBinge("viewing event types");
        return toEventTypeDtoList(eventTypeRepository.findByBingeIdAndActiveTrue(bid));
    }

    public List<AddOnDto> getActiveAddOns() {
        Long bid = requireSelectedBinge("viewing add-ons");
        return toAddOnDtoList(addOnRepository.findByBingeIdAndActiveTrue(bid));
    }

    // â”€â”€ Admin: Event type CRUD â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @org.springframework.cache.annotation.Cacheable(value = "eventTypes", key = "T(com.skbingegalaxy.common.context.BingeContext).getBingeId()")
    public List<EventTypeDto> getAllEventTypes() {
        Long bid = requireSelectedBinge("managing event types");
        return toEventTypeDtoList(eventTypeRepository.findByBingeId(bid));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public EventTypeDto createEventType(EventTypeSaveRequest req) {
        Long bid = requireSelectedBinge("creating an event type");
        validateGuestRange(req.getMinGuests(), req.getMaxGuests());
        EventType et = EventType.builder()
            .name(req.getName())
            .description(req.getDescription())
            .basePrice(req.getBasePrice())
            .hourlyRate(req.getHourlyRate())
            .pricePerGuest(req.getPricePerGuest() != null ? req.getPricePerGuest() : BigDecimal.ZERO)
            .minHours(req.getMinHours())
            .maxHours(req.getMaxHours())
            .minGuests(req.getMinGuests())
            .maxGuests(req.getMaxGuests())
            .categoryId(resolveEventCategoryId(req.getCategoryId(), bid))
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(bid)
            .build();
        EventTypeDto saved = toEventTypeDto(eventTypeRepository.save(et));
        // Approval workflow hook: stamp the binge as "operational" the first
        // time an active event lands on it. Idempotent — repeated saves never
        // overwrite the original timestamp. Skips the grace-period auto-pause
        // sweep on subsequent ticks of BingeGracePeriodScheduler.
        bingeRepository.findById(bid).ifPresent(b -> {
            if (b.getFirstEventCreatedAt() == null) {
                b.setFirstEventCreatedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
                bingeRepository.save(b);
                log.info("Binge {} marked operational (first event '{}' created)", bid, et.getName());
            }
        });
        return saved;
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public EventTypeDto updateEventType(Long id, EventTypeSaveRequest req) {
        EventType et = findManagedEventType(id);
        validateGuestRange(req.getMinGuests(), req.getMaxGuests());
        et.setName(req.getName());
        et.setDescription(req.getDescription());
        et.setBasePrice(req.getBasePrice());
        et.setHourlyRate(req.getHourlyRate());
        et.setPricePerGuest(req.getPricePerGuest() != null ? req.getPricePerGuest() : BigDecimal.ZERO);
        et.setMinHours(req.getMinHours());
        et.setMaxHours(req.getMaxHours());
        et.setMinGuests(req.getMinGuests());
        et.setMaxGuests(req.getMaxGuests());
        et.setCategoryId(resolveEventCategoryId(req.getCategoryId(), et.getBingeId()));
        et.getImageUrls().clear();
        if (req.getImageUrls() != null) et.getImageUrls().addAll(req.getImageUrls());
        return toEventTypeDto(eventTypeRepository.save(et));
    }

    private void validateGuestRange(Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw new BusinessException("Minimum guests (" + min + ") cannot exceed maximum guests (" + max + ")");
        }
    }

    /**
     * Enforces per-event-type guest range. NULL bounds are treated as
     * "no constraint" so existing event types continue to behave unchanged.
     */
    private void enforceEventTypeGuestRange(EventType eventType, int guests) {
        if (eventType == null) return;
        Integer min = eventType.getMinGuests();
        Integer max = eventType.getMaxGuests();
        if (min != null && guests < min) {
            throw new BusinessException(
                "This event type requires at least " + min + " guests (you selected " + guests + ")");
        }
        if (max != null && guests > max) {
            throw new BusinessException(
                "This event type allows at most " + max + " guests (you selected " + guests + ")");
        }
    }

    /**
     * Enforces add-on inventory ({@code stockPerDay}) and advance-notice
     * ({@code advanceNoticeMinutes}) constraints. Both null fields skip the
     * corresponding check so existing add-ons keep working.
     *
     * @param excludeBookingId set when re-validating an existing booking
     *                         during update so that booking's own quantity is
     *                         not double-counted; null on creation.
     */
    private void enforceAddOnAvailability(AddOn addOn,
                                          int requestedQty,
                                          java.time.LocalDate bookingDate,
                                          java.time.LocalDateTime bookingStart,
                                          Long excludeBookingId) {
        if (addOn == null) return;
        // Advance-notice check. bookingStart is venue-local, so compare against
        // venue-local "now" (not UTC) or the notice window is off by the venue offset.
        Integer notice = addOn.getAdvanceNoticeMinutes();
        if (notice != null && notice > 0 && bookingStart != null) {
            java.time.ZoneId addOnVenueZone = venueClock.zoneOf(BingeContext.getBingeId());
            long minutesUntilStart = java.time.Duration.between(
                java.time.LocalDateTime.now(addOnVenueZone), bookingStart).toMinutes();
            if (minutesUntilStart < notice) {
                throw new BusinessException("Add-on '" + addOn.getName() + "' requires at least "
                    + notice + " minutes advance notice before the booking start time");
            }
        }
        // Inventory check
        Integer stock = addOn.getStockPerDay();
        if (stock != null && stock >= 0 && bookingDate != null) {
            long alreadyBooked = bookingAddOnRepository.sumQuantityForAddOnOnDate(
                addOn.getId(), bookingDate,
                java.util.List.of(
                    com.skbingegalaxy.common.enums.BookingStatus.PENDING,
                    com.skbingegalaxy.common.enums.BookingStatus.CONFIRMED,
                    com.skbingegalaxy.common.enums.BookingStatus.CHECKED_IN,
                    com.skbingegalaxy.common.enums.BookingStatus.COMPLETED),
                excludeBookingId);
            long remaining = stock - alreadyBooked;
            if (requestedQty > remaining) {
                throw new BusinessException("Add-on '" + addOn.getName()
                    + "' is sold out for " + bookingDate + " (only "
                    + Math.max(remaining, 0) + " of " + stock + " remaining)");
            }
        }
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

    // â”€â”€ Admin: Add-on CRUD â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @org.springframework.cache.annotation.Cacheable(value = "addOns", key = "T(com.skbingegalaxy.common.context.BingeContext).getBingeId()")
    public List<AddOnDto> getAllAddOns() {
        Long bid = requireSelectedBinge("managing add-ons");
        return toAddOnDtoList(addOnRepository.findByBingeId(bid));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public AddOnDto createAddOn(AddOnSaveRequest req) {
        Long bid = requireSelectedBinge("creating an add-on");
        AddOn a = AddOn.builder()
            .name(req.getName())
            .description(req.getDescription())
            .price(req.getPrice())
            .categoryId(resolveAddOnCategoryId(req.getCategoryId(), bid))
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(bid)
            .stockPerDay(req.getStockPerDay())
            .advanceNoticeMinutes(req.getAdvanceNoticeMinutes())
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
        a.setCategoryId(resolveAddOnCategoryId(req.getCategoryId(), a.getBingeId()));
        a.setStockPerDay(req.getStockPerDay());
        a.setAdvanceNoticeMinutes(req.getAdvanceNoticeMinutes());
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

    // ── Catalog categories (V55) ──────────────────────────────────────────
    //
    // Categories are an optional taxonomy layered over EventTypes/AddOns.
    // Visibility model mirrors EventType/AddOn exactly:
    //   * binge_id IS NULL  → global (super-admin owned)
    //   * binge_id NOT NULL → per-binge (binge admin owned)
    //
    // The caller (controller) is responsible for enforcing the SUPER_ADMIN
    // role on global-mutating endpoints. Service layer enforces ownership:
    // a binge admin cannot mutate a category owned by a different binge or
    // a global one.

    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listVisibleEventCategories() {
        Long bid = requireSelectedBinge("viewing event categories");
        return eventCategoryRepository.findVisibleForBinge(bid).stream()
            .map(this::toEventCategoryDto).toList();
    }

    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listManagedEventCategories() {
        Long bid = requireSelectedBinge("managing event categories");
        return eventCategoryRepository.findByBingeId(bid).stream()
            .map(this::toEventCategoryDto).toList();
    }

    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listGlobalEventCategories() {
        return eventCategoryRepository.findByBingeIdIsNull().stream()
            .map(this::toEventCategoryDto).toList();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto createEventCategory(
            com.skbingegalaxy.booking.dto.CategorySaveRequest req, boolean global) {
        Long ownerBinge = global ? null : requireSelectedBinge("creating an event category");
        String name = req.getName().trim();
        boolean dup = global
            ? eventCategoryRepository.existsByBingeIdIsNullAndNameIgnoreCase(name)
            : eventCategoryRepository.existsByBingeIdAndNameIgnoreCase(ownerBinge, name);
        if (dup) {
            throw new BusinessException("An event category named '" + name + "' already exists in this scope");
        }
        com.skbingegalaxy.booking.entity.EventCategory c =
            com.skbingegalaxy.booking.entity.EventCategory.builder()
                .bingeId(ownerBinge)
                .name(name)
                .description(trimToNull(req.getDescription()))
                .imageUrl(trimToNull(req.getImageUrl()))
                .sortOrder(req.getSortOrder())
                .active(true)
                .build();
        return toEventCategoryDto(eventCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto updateEventCategory(
            Long id, com.skbingegalaxy.booking.dto.CategorySaveRequest req, boolean global) {
        com.skbingegalaxy.booking.entity.EventCategory c = loadOwnedEventCategory(id, global);
        String name = req.getName().trim();
        if (!c.getName().equalsIgnoreCase(name)) {
            boolean dup = c.getBingeId() == null
                ? eventCategoryRepository.existsByBingeIdIsNullAndNameIgnoreCase(name)
                : eventCategoryRepository.existsByBingeIdAndNameIgnoreCase(c.getBingeId(), name);
            if (dup) throw new BusinessException("Another event category already uses the name '" + name + "'");
        }
        c.setName(name);
        c.setDescription(trimToNull(req.getDescription()));
        c.setImageUrl(trimToNull(req.getImageUrl()));
        c.setSortOrder(req.getSortOrder());
        return toEventCategoryDto(eventCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto toggleEventCategory(Long id, boolean global) {
        com.skbingegalaxy.booking.entity.EventCategory c = loadOwnedEventCategory(id, global);
        c.setActive(!c.isActive());
        return toEventCategoryDto(eventCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "eventTypes", allEntries = true)
    public void deleteEventCategory(Long id, boolean global) {
        com.skbingegalaxy.booking.entity.EventCategory c = loadOwnedEventCategory(id, global);
        if (c.isActive()) throw new BusinessException("Deactivate the category before deleting it");
        // ON DELETE SET NULL on event_types.category_id keeps existing event
        // types intact — they simply become uncategorized after deletion.
        eventCategoryRepository.delete(c);
        log.info("Event category deleted: '{}' (id={}, binge={})", c.getName(), id, c.getBingeId());
    }

    private com.skbingegalaxy.booking.entity.EventCategory loadOwnedEventCategory(Long id, boolean global) {
        if (global) {
            return eventCategoryRepository.findByIdAndBingeIdIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("EventCategory", "id", id));
        }
        Long bid = requireSelectedBinge("managing event categories");
        return eventCategoryRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("EventCategory", "id", id));
    }

    private com.skbingegalaxy.booking.dto.CategoryDto toEventCategoryDto(
            com.skbingegalaxy.booking.entity.EventCategory c) {
        return com.skbingegalaxy.booking.dto.CategoryDto.builder()
            .id(c.getId())
            .bingeId(c.getBingeId())
            .name(c.getName())
            .description(c.getDescription())
            .imageUrl(c.getImageUrl())
            .sortOrder(c.getSortOrder())
            .active(c.isActive())
            .global(c.getBingeId() == null)
            .build();
    }

    // ── Add-on categories ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listVisibleAddOnCategories() {
        Long bid = requireSelectedBinge("viewing add-on categories");
        return addOnCategoryRepository.findVisibleForBinge(bid).stream()
            .map(this::toAddOnCategoryDto).toList();
    }

    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listManagedAddOnCategories() {
        Long bid = requireSelectedBinge("managing add-on categories");
        return addOnCategoryRepository.findByBingeId(bid).stream()
            .map(this::toAddOnCategoryDto).toList();
    }

    @Transactional(readOnly = true)
    public List<com.skbingegalaxy.booking.dto.CategoryDto> listGlobalAddOnCategories() {
        return addOnCategoryRepository.findByBingeIdIsNull().stream()
            .map(this::toAddOnCategoryDto).toList();
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto createAddOnCategory(
            com.skbingegalaxy.booking.dto.CategorySaveRequest req, boolean global) {
        Long ownerBinge = global ? null : requireSelectedBinge("creating an add-on category");
        String name = req.getName().trim();
        boolean dup = global
            ? addOnCategoryRepository.existsByBingeIdIsNullAndNameIgnoreCase(name)
            : addOnCategoryRepository.existsByBingeIdAndNameIgnoreCase(ownerBinge, name);
        if (dup) throw new BusinessException("An add-on category named '" + name + "' already exists in this scope");
        com.skbingegalaxy.booking.entity.AddOnCategory c =
            com.skbingegalaxy.booking.entity.AddOnCategory.builder()
                .bingeId(ownerBinge)
                .name(name)
                .description(trimToNull(req.getDescription()))
                .imageUrl(trimToNull(req.getImageUrl()))
                .sortOrder(req.getSortOrder())
                .active(true)
                .build();
        return toAddOnCategoryDto(addOnCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto updateAddOnCategory(
            Long id, com.skbingegalaxy.booking.dto.CategorySaveRequest req, boolean global) {
        com.skbingegalaxy.booking.entity.AddOnCategory c = loadOwnedAddOnCategory(id, global);
        String name = req.getName().trim();
        if (!c.getName().equalsIgnoreCase(name)) {
            boolean dup = c.getBingeId() == null
                ? addOnCategoryRepository.existsByBingeIdIsNullAndNameIgnoreCase(name)
                : addOnCategoryRepository.existsByBingeIdAndNameIgnoreCase(c.getBingeId(), name);
            if (dup) throw new BusinessException("Another add-on category already uses the name '" + name + "'");
        }
        c.setName(name);
        c.setDescription(trimToNull(req.getDescription()));
        c.setImageUrl(trimToNull(req.getImageUrl()));
        c.setSortOrder(req.getSortOrder());
        return toAddOnCategoryDto(addOnCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public com.skbingegalaxy.booking.dto.CategoryDto toggleAddOnCategory(Long id, boolean global) {
        com.skbingegalaxy.booking.entity.AddOnCategory c = loadOwnedAddOnCategory(id, global);
        c.setActive(!c.isActive());
        return toAddOnCategoryDto(addOnCategoryRepository.save(c));
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = "addOns", allEntries = true)
    public void deleteAddOnCategory(Long id, boolean global) {
        com.skbingegalaxy.booking.entity.AddOnCategory c = loadOwnedAddOnCategory(id, global);
        if (c.isActive()) throw new BusinessException("Deactivate the category before deleting it");
        // Pre-check at the application layer: add_ons.category_id is NOT NULL
        // (V59) with FK ON DELETE RESTRICT (V60), so deleting a referenced
        // category would otherwise fail at the DB layer with a raw FK
        // violation. Surface a clear, actionable message instead.
        long inUse = addOnRepository.countByCategoryId(id);
        if (inUse > 0) {
            throw new BusinessException(
                "Cannot delete this category because " + inUse + " add-on(s) still reference it. " +
                "Reassign or delete those add-ons first.");
        }
        addOnCategoryRepository.delete(c);
        log.info("Add-on category deleted: '{}' (id={}, binge={})", c.getName(), id, c.getBingeId());
    }

    private com.skbingegalaxy.booking.entity.AddOnCategory loadOwnedAddOnCategory(Long id, boolean global) {
        if (global) {
            return addOnCategoryRepository.findByIdAndBingeIdIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("AddOnCategory", "id", id));
        }
        Long bid = requireSelectedBinge("managing add-on categories");
        return addOnCategoryRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("AddOnCategory", "id", id));
    }

    private com.skbingegalaxy.booking.dto.CategoryDto toAddOnCategoryDto(
            com.skbingegalaxy.booking.entity.AddOnCategory c) {
        return com.skbingegalaxy.booking.dto.CategoryDto.builder()
            .id(c.getId())
            .bingeId(c.getBingeId())
            .name(c.getName())
            .description(c.getDescription())
            .imageUrl(c.getImageUrl())
            .sortOrder(c.getSortOrder())
            .active(c.isActive())
            .global(c.getBingeId() == null)
            .build();
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

    // ???????????????????????????????????????????????????????????
    //  VENUE ROOM MANAGEMENT
    // ???????????????????????????????????????????????????????????

    @Transactional(readOnly = true)
    public List<VenueRoomDto> getActiveVenueRooms() {
        Long bid = BingeContext.requireBingeId();
        // V56: customer-facing endpoint must only surface APPROVED rooms so
        // pending/rejected rooms never appear in the picker.
        return venueRoomRepository.findByBingeIdAndActiveTrueOrderBySortOrderAsc(bid)
            .stream()
            .filter(r -> r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)
            .map(this::toRoomDto).toList();
    }

    @Transactional(readOnly = true)
    public List<VenueRoomDto> getAvailableRooms(LocalDate date, int startMinute, int durationMinutes) {
        Long bid = BingeContext.requireBingeId();
        List<VenueRoom> rooms = venueRoomRepository.findByBingeIdAndActiveTrueOrderBySortOrderAsc(bid);
        return rooms.stream()
            // V56: only APPROVED rooms are bookable on the customer wizard.
            .filter(r -> r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)
            .map(room -> {
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
        return createVenueRoom(request, false, null);
    }

    /**
     * V56: create a venue room. {@code autoApprove} is true when the caller
     * is a SUPER_ADMIN — those rooms become bookable immediately. Regular
     * admins create rooms in PENDING_APPROVAL state.
     */
    @Transactional
    public VenueRoomDto createVenueRoom(VenueRoomSaveRequest request, boolean autoApprove, Long actorAdminId) {
        Long bid = requireSelectedBinge("creating venue room");
        VenueRoom room = VenueRoom.builder()
            .bingeId(bid).name(request.getName()).roomType(request.getRoomType())
            .capacity(request.getCapacity()).description(request.getDescription())
            .sortOrder(request.getSortOrder()).active(request.isActive())
            .priceAddition(request.getPriceAddition() != null ? request.getPriceAddition() : java.math.BigDecimal.ZERO)
            .imageUrls(request.getImageUrls() != null ? new java.util.ArrayList<>(request.getImageUrls()) : new java.util.ArrayList<>())
            .status(autoApprove ? com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED
                                : com.skbingegalaxy.booking.entity.RoomApprovalStatus.PENDING_APPROVAL)
            .approvalDecidedBy(autoApprove ? actorAdminId : null)
            .approvalDecidedAt(autoApprove ? java.time.LocalDateTime.now(ZoneOffset.UTC) : null)
            .build();
        room = venueRoomRepository.save(room);
        log.info("Venue room created: {} (status={})", room.getName(), room.getStatus());
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
        if (request.getPriceAddition() != null) {
            room.setPriceAddition(request.getPriceAddition());
        }
        if (request.getImageUrls() != null) {
            room.getImageUrls().clear();
            room.getImageUrls().addAll(request.getImageUrls());
        }
        room = venueRoomRepository.save(room);
        log.info("Venue room updated: {}", room.getName());
        return toRoomDto(room);
    }

    /** V56: SUPER_ADMIN approves a room created by a regular admin. */
    @Transactional
    public VenueRoomDto approveVenueRoom(Long id, Long actorAdminId) {
        Long bid = requireSelectedBinge("approving venue room");
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", id));
        room.setStatus(com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED);
        room.setApprovalDecidedBy(actorAdminId);
        room.setApprovalDecidedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        room.setApprovalRejectionReason(null);
        room = venueRoomRepository.save(room);
        log.info("Venue room approved: {} by admin {}", room.getName(), actorAdminId);
        publishRoomLifecycle(room, "APPROVED", com.skbingegalaxy.common.constants.KafkaTopics.ROOM_APPROVED, actorAdminId, null);
        return toRoomDto(room);
    }

    /** V56: SUPER_ADMIN rejects a pending room with a reason. */
    @Transactional
    public VenueRoomDto rejectVenueRoom(Long id, Long actorAdminId, String reason) {
        Long bid = requireSelectedBinge("rejecting venue room");
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(id, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", id));
        room.setStatus(com.skbingegalaxy.booking.entity.RoomApprovalStatus.REJECTED);
        room.setApprovalDecidedBy(actorAdminId);
        room.setApprovalDecidedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        room.setApprovalRejectionReason(reason);
        room = venueRoomRepository.save(room);
        log.info("Venue room rejected: {} by admin {} ({})", room.getName(), actorAdminId, reason);
        publishRoomLifecycle(room, "REJECTED", com.skbingegalaxy.common.constants.KafkaTopics.ROOM_REJECTED, actorAdminId, reason);
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

    // ───────────────────────────────────────────────────────────
    //  V57: ROOM MAINTENANCE BLOCKS
    // ───────────────────────────────────────────────────────────

    /** Tenant-scoped lookup: room must belong to the current binge. */
    private com.skbingegalaxy.booking.entity.VenueRoom requireRoomInCurrentBinge(Long roomId, String action) {
        Long bid = requireSelectedBinge(action);
        return venueRoomRepository.findByIdAndBingeId(roomId, bid)
            .orElseThrow(() -> new ResourceNotFoundException("VenueRoom", "id", roomId));
    }

    @Transactional(readOnly = true)
    public java.util.List<com.skbingegalaxy.booking.dto.RoomBlockDto> listRoomBlocks(Long roomId) {
        requireRoomInCurrentBinge(roomId, "listing room blocks");
        return roomBlockRepository.findByRoomIdOrderByStartAtAsc(roomId)
            .stream().map(this::toRoomBlockDto).toList();
    }

    @Transactional
    public com.skbingegalaxy.booking.dto.RoomBlockDto createRoomBlock(
            Long roomId,
            com.skbingegalaxy.booking.dto.RoomBlockSaveRequest req,
            Long actorAdminId) {
        requireRoomInCurrentBinge(roomId, "creating room block");
        if (req.getStartAt() == null || req.getEndAt() == null) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "startAt and endAt are required", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        if (!req.getEndAt().isAfter(req.getStartAt())) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "endAt must be after startAt", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        com.skbingegalaxy.booking.entity.RoomBlock block = com.skbingegalaxy.booking.entity.RoomBlock.builder()
            .roomId(roomId)
            .startAt(req.getStartAt())
            .endAt(req.getEndAt())
            .reason(req.getReason())
            .createdBy(actorAdminId)
            .build();
        block = roomBlockRepository.save(block);
        log.info("Room block created: room={} window=[{} .. {}] reason='{}' by admin {}",
            roomId, block.getStartAt(), block.getEndAt(), block.getReason(), actorAdminId);
        publishBlockLifecycle(block, "BLOCKED", com.skbingegalaxy.common.constants.KafkaTopics.ROOM_BLOCKED, actorAdminId);
        return toRoomBlockDto(block);
    }

    @Transactional
    public void deleteRoomBlock(Long blockId) {
        com.skbingegalaxy.booking.entity.RoomBlock block = roomBlockRepository.findById(blockId)
            .orElseThrow(() -> new ResourceNotFoundException("RoomBlock", "id", blockId));
        // Tenant-scope: ensure the block's room belongs to the caller's binge.
        requireRoomInCurrentBinge(block.getRoomId(), "deleting room block");
        roomBlockRepository.delete(block);
        log.info("Room block deleted: id={} room={}", blockId, block.getRoomId());
        publishBlockLifecycle(block, "UNBLOCKED", com.skbingegalaxy.common.constants.KafkaTopics.ROOM_UNBLOCKED, null);
    }

    /**
     * V56: emit an outbox event for a room lifecycle action.
     * Runs inside the caller's {@code @Transactional} boundary (publisher
     * uses {@code Propagation.MANDATORY}). If the outbox write fails we
     * deliberately re-throw so the entire admin action rolls back —
     * that is the atomicity guarantee of the transactional outbox pattern.
     */
    private void publishRoomLifecycle(VenueRoom room, String action, String topic, Long actorAdminId, String reason) {
        try {
            com.skbingegalaxy.common.event.AdminLifecycleEvent ev =
                com.skbingegalaxy.common.event.AdminLifecycleEvent.builder()
                    .entityType("ROOM")
                    .action(action)
                    .entityId(room.getId())
                    .bingeId(room.getBingeId())
                    .actorAdminId(actorAdminId)
                    .name(room.getName())
                    .reason(reason)
                    .build();
            bookingEventPublisher.publish(topic, String.valueOf(room.getId()), ev);
        } catch (Exception ex) {
            log.error("Failed to publish room lifecycle event topic={} roomId={} — rolling back admin action",
                topic, room.getId(), ex);
            throw ex;
        }
    }

    /**
     * V57: emit an outbox event for a room-block lifecycle action.
     * Same transactional-outbox semantics as {@link #publishRoomLifecycle}
     * — a publish failure rolls back the room-block change.
     */
    private void publishBlockLifecycle(com.skbingegalaxy.booking.entity.RoomBlock block, String action, String topic, Long actorAdminId) {
        try {
            com.skbingegalaxy.common.event.AdminLifecycleEvent ev =
                com.skbingegalaxy.common.event.AdminLifecycleEvent.builder()
                    .entityType("ROOM_BLOCK")
                    .action(action)
                    .entityId(block.getId())
                    .actorAdminId(actorAdminId != null ? actorAdminId : block.getCreatedBy())
                    .reason(block.getReason())
                    .startAt(block.getStartAt())
                    .endAt(block.getEndAt())
                    .build();
            bookingEventPublisher.publish(topic, String.valueOf(block.getRoomId()), ev);
        } catch (Exception ex) {
            log.error("Failed to publish room-block lifecycle event topic={} blockId={} — rolling back admin action",
                topic, block.getId(), ex);
            throw ex;
        }
    }

    private com.skbingegalaxy.booking.dto.RoomBlockDto toRoomBlockDto(com.skbingegalaxy.booking.entity.RoomBlock b) {
        return com.skbingegalaxy.booking.dto.RoomBlockDto.builder()
            .id(b.getId())
            .roomId(b.getRoomId())
            .startAt(b.getStartAt())
            .endAt(b.getEndAt())
            .reason(b.getReason())
            .createdBy(b.getCreatedBy())
            .createdAt(b.getCreatedAt())
            .build();
    }

    private VenueRoomDto toRoomDto(VenueRoom r) {
        return VenueRoomDto.builder()
            .id(r.getId()).bingeId(r.getBingeId()).name(r.getName())
            .roomType(r.getRoomType()).capacity(r.getCapacity())
            .description(r.getDescription()).sortOrder(r.getSortOrder())
            .active(r.isActive())
            // V56 fields
            .priceAddition(r.getPriceAddition())
            .status(r.getStatus() != null ? r.getStatus().name() : null)
            .approvalDecidedBy(r.getApprovalDecidedBy())
            .approvalDecidedAt(r.getApprovalDecidedAt())
            .approvalRejectionReason(r.getApprovalRejectionReason())
            .imageUrls(r.getImageUrls() != null ? new java.util.ArrayList<>(r.getImageUrls()) : new java.util.ArrayList<>())
            .createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
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

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    /**
     * Package-scoped accessor so the waitlist promoter can serialise against
     * concurrent booking creation on the same venue + day. Avoids the
     * two-cancellations-promote-position-1 race.
     */
    static long slotLockKeyFor(Long bingeId, LocalDate date) {
        return slotLockKey(bingeId, date);
    }

    private String generateBookingRef() {
        String year = String.valueOf(Year.now().getValue()).substring(2);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return refPrefix + year + random;
    }

    // ── Item 24 — support console actions ───────────────────────────────────

    /**
     * Re-emit the BOOKING_CONFIRMED event with a fresh envelope (new eventId)
     * so notification-service treats it as a new dispatch. Used when a
     * customer reports "I never got the confirmation email" — the operator
     * triggers a re-send from the support console.
     *
     * <p>The booking must currently be CONFIRMED. Sending a confirmation for
     * a cancelled / pending booking would be misleading.
     */
    @Transactional
    public BookingDto resendConfirmation(String bookingRef, Long adminId) {
        Booking b = findScopedBookingByRef(bookingRef);
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Resend confirmation requires status=CONFIRMED. Current: " + b.getStatus());
        }
        publishBookingEvent(b, KafkaTopics.BOOKING_CONFIRMED);
        eventLogService.logEvent(b, BookingEventType.CONFIRMATION_RESENT,
            b.getStatus().name(), adminId, "ADMIN",
            "Confirmation re-sent by admin " + adminId);
        log.info("support-console resend-confirmation bookingRef={} adminId={}", bookingRef, adminId);
        return toDto(b);
    }

    /**
     * Set or clear an escalation level on a booking. Pure metadata — no Kafka
     * side-effects beyond the event-log entry — so the support team can
     * filter "L2+ active escalations" in the console.
     */
    @Transactional
    public BookingDto setEscalation(String bookingRef, String level, String reason, Long adminId) {
        Booking b = findScopedBookingByRef(bookingRef);
        // Defensive: clamp to known levels so a typo doesn't poison the column.
        String normalized = level == null ? "NONE" : level.trim().toUpperCase();
        if (!java.util.Set.of("NONE", "L1", "L2", "L3").contains(normalized)) {
            throw new BusinessException("Invalid escalation level. Use NONE, L1, L2, or L3.");
        }
        b.setEscalationLevel(normalized);
        b.setEscalationReason(reason != null && reason.length() > 500
            ? reason.substring(0, 500) : reason);
        Booking saved = bookingRepository.save(b);
        BookingEventType evt = "NONE".equals(normalized) ? BookingEventType.DE_ESCALATED : BookingEventType.ESCALATED;
        eventLogService.logEvent(saved, evt, saved.getStatus().name(), adminId, "ADMIN",
            "Escalation set to " + normalized
                + (reason != null && !reason.isBlank() ? " — " + reason : ""));
        return toDto(saved);
    }

    /**
     * Issue goodwill credit to a booking. Stored on the booking row plus a
     * pinned customer-visible note pointing to it. Loyalty points are NOT
     * adjusted here — currency/points conversion is policy-dependent and
     * better handled via a separate compensating action.
     */
    @Transactional
    public BookingDto issueGoodwill(String bookingRef, java.math.BigDecimal amount,
                                    String reason, Long adminId) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Goodwill amount must be positive");
        }
        // Cap to a sensible operational ceiling so a misclick can't comp lakhs.
        if (amount.compareTo(new java.math.BigDecimal("10000")) > 0) {
            throw new BusinessException("Goodwill exceeds operational ceiling (₹10,000). Escalate to super-admin.");
        }
        Booking b = findScopedBookingByRef(bookingRef);
        java.math.BigDecimal existing = b.getGoodwillCredit() == null
            ? java.math.BigDecimal.ZERO : b.getGoodwillCredit();
        b.setGoodwillCredit(existing.add(amount));
        b.setGoodwillReason(reason != null && reason.length() > 500
            ? reason.substring(0, 500) : reason);
        b.setGoodwillIssuedByAdminId(adminId);
        b.setGoodwillIssuedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        Booking saved = bookingRepository.save(b);
        eventLogService.logEvent(saved, BookingEventType.GOODWILL_ISSUED,
            saved.getStatus().name(), adminId, "ADMIN",
            "Goodwill credit ₹" + amount + " issued by admin " + adminId
                + (reason != null && !reason.isBlank() ? " — " + reason : ""));
        log.info("support-console goodwill bookingRef={} amount={} adminId={}", bookingRef, amount, adminId);
        return toDto(saved);
    }

    private void publishBookingEvent(Booking b, String topic) {
        // Envelope (eventId/version/correlationId/occurredAt) is filled by
        // BookingEventPublisher — see V46 outbox migration. Keeping the
        // builder here means concrete domain fields stay close to the call
        // site for grep-ability.
        BookingEvent event = BookingEvent.builder()
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            .customerId(b.getCustomerId())
            .customerName(b.getCustomerName())
            .customerEmail(b.getCustomerEmail())
            .customerPhone(b.getCustomerPhone())
            .customerPhoneCountryCode(b.getCustomerPhoneCountryCode())
            .eventTypeName(b.getEventType() != null ? b.getEventType().getName() : null)
            .bookingDate(b.getBookingDate())
            .startTime(b.getStartTime())
            .durationHours(b.getDurationHours())
            .durationMinutes(resolveDurationMinutes(b.getDurationMinutes(), b.getDurationHours()))
            .totalAmount(b.getTotalAmount())
            .status(b.getStatus().name())
            .specialNotes(b.getSpecialNotes())
            .customerCancellationCutoffMinutes(
                bingeRepository.findById(b.getBingeId())
                    .map(com.skbingegalaxy.booking.entity.Binge::getCustomerCancellationCutoffMinutes)
                    .orElse(null))
            .build();

        bookingEventPublisher.publish(topic, b.getBookingRef(), event);

        // Item 27 — funnel / lifecycle counters. Counted from the publish site
        // because every successful state transition publishes through here, so
        // the metric stays consistent with what consumers actually see.
        switch (topic) {
            case KafkaTopics.BOOKING_CREATED     -> analyticsMetrics.funnelCreated();
            case KafkaTopics.BOOKING_CONFIRMED   -> analyticsMetrics.lifecycleConfirmed();
            case KafkaTopics.BOOKING_CANCELLED   -> analyticsMetrics.lifecycleCancelled();
            case KafkaTopics.BOOKING_RESCHEDULED -> analyticsMetrics.lifecycleRescheduled();
            case KafkaTopics.BOOKING_COMPLETED   -> analyticsMetrics.lifecycleCompleted();
            default -> { /* check-in / waitlist-promoted not part of the spec funnel */ }
        }
    }

    private BookingDto toDto(Booking b) {
        CancellationPolicyDecision cancelDecision = evaluateCustomerCancellation(b);
        // Batch-resolve add-on category names in a single IN query to avoid an
        // N+1 against addon_categories for bookings with many add-ons.
        java.util.List<Long> catIds = b.getAddOns().stream()
            .map(ba -> ba.getAddOn() != null ? ba.getAddOn().getCategoryId() : null)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        final java.util.Map<Long, String> catNamesById = catIds.isEmpty()
            ? java.util.Map.of()
            : addOnCategoryRepository.findAllById(catIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.skbingegalaxy.booking.entity.AddOnCategory::getId,
                    com.skbingegalaxy.booking.entity.AddOnCategory::getName));
        return BookingDto.builder()
            .id(b.getId())
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            .customerId(b.getCustomerId())
            .customerName(b.getCustomerName())
            .customerEmail(b.getCustomerEmail())
            .customerPhone(b.getCustomerPhone())
            .customerPhoneCountryCode(b.getCustomerPhoneCountryCode())
            .eventType(toEventTypeDto(b.getEventType()))
            .bookingDate(b.getBookingDate())
            .startTime(b.getStartTime())
            .durationHours(b.getDurationHours())
            .durationMinutes(resolveDurationMinutes(b.getDurationMinutes(), b.getDurationHours()))
            .addOns(b.getAddOns().stream().map(ba -> BookingAddOnDto.builder()
                .addOnId(ba.getAddOn().getId())
                .name(ba.getAddOn().getName())
                .category(ba.getAddOn() != null && ba.getAddOn().getCategoryId() != null
                    ? catNamesById.get(ba.getAddOn().getCategoryId())
                    : null)
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
            .lateArrival(b.isLateArrival())
            .actualCheckInTime(b.getActualCheckInTime())
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
            .subtotalAmount(b.getSubtotalAmount())
            .taxAmount(b.getTaxAmount())
            .paymentCurrencyCode(b.getPaymentCurrencyCode())
            .fxRate(b.getFxRate())
            .createdAt(b.getCreatedAt())
            .updatedAt(b.getUpdatedAt())
            .build();
    }

    private boolean canCustomerReschedule(Booking b) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) return false;
        if (b.getRescheduleCount() >= maxReschedulesPerBooking) return false;
        ZoneId zone = venueClock.zoneOf(b.getBingeId());
        LocalDateTime eventStart = LocalDateTime.of(b.getBookingDate(), b.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(zone), eventStart);
        return hoursUntilStart >= rescheduleCutoffHours;
    }

    private boolean canCustomerTransfer(Booking b) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CONFIRMED) return false;
        if (b.isTransferred()) return false;
        ZoneId zone = venueClock.zoneOf(b.getBingeId());
        LocalDateTime eventStart = LocalDateTime.of(b.getBookingDate(), b.getStartTime());
        long hoursUntilStart = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(zone), eventStart);
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

        // Refund-applicability gate based on the booking's current payment state.
        // When a venue has set the refund flag for this payment state to false,
        // the customer can still cancel but no refund (0%) is owed regardless
        // of any tiered refund schedule that would otherwise apply.
        boolean refundsAllowed = isRefundAllowedForPaymentStatus(binge, booking.getPaymentStatus());

        // bookingDate/startTime are venue-local values, so "now" must be venue-local too —
        // matching the reschedule/transfer cutoff checks. Using UTC here overstated
        // hoursUntilStart by the venue's offset (e.g. +5.5h for IST), handing customers a more
        // generous refund tier than the policy allows and permitting cancellations inside the
        // cutoff window.
        ZoneId cancelVenueZone = venueClock.zoneOf(booking.getBingeId());
        LocalDateTime eventStart = LocalDateTime.of(booking.getBookingDate(), booking.getStartTime());
        LocalDateTime now = LocalDateTime.now(cancelVenueZone);
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
                    int refund = refundsAllowed ? tier.getRefundPercentage() : 0;
                    String suffix = refundsAllowed ? "" : " (no refund — refund-on-this-payment-state is disabled by the venue)";
                    return new CancellationPolicyDecision(true,
                        "Cancellation available with " + refund + "% refund (" + label + ")." + suffix + " "
                            + hoursUntilStart + " hours until start.",
                        refund);
                }
            }
            // No tier matched (too close to start) — check if there's a 0h tier
            var lastTier = tiers.get(tiers.size() - 1);
            if (lastTier.getHoursBeforeStart() == 0) {
                String label = lastTier.getLabel() != null ? lastTier.getLabel() : (lastTier.getRefundPercentage() + "% refund");
                int refund = refundsAllowed ? lastTier.getRefundPercentage() : 0;
                return new CancellationPolicyDecision(true,
                    "Late cancellation: " + refund + "% refund (" + label + ").",
                    refund);
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
            refundsAllowed ? 100 : 0); // Legacy policy = full refund if within window (gated by venue flag)
    }

    /**
     * Refund applicability gate — venue may opt-out of refunds based on the
     * booking's current payment state (typically refundOnPendingPaymentCancel
     * defaults to false to discourage repeated abandon-then-cancel abuse).
     */
    private boolean isRefundAllowedForPaymentStatus(Binge binge, com.skbingegalaxy.common.enums.PaymentStatus paymentStatus) {
        if (binge == null || paymentStatus == null) return true;
        switch (paymentStatus) {
            case SUCCESS:
            case PARTIALLY_PAID:
            case PARTIALLY_REFUNDED:
            case REFUNDED:
                return binge.isRefundOnSuccessfulPaymentCancel();
            case PENDING:
            case INITIATED:
            case FAILED:
                return binge.isRefundOnPendingPaymentCancel();
            default:
                return true;
        }
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
        String categoryName = null;
        if (et.getCategoryId() != null) {
            categoryName = eventCategoryRepository.findById(et.getCategoryId())
                .map(com.skbingegalaxy.booking.entity.EventCategory::getName)
                .orElse(null);
        }
        return toEventTypeDto(et, categoryName);
    }

    /**
     * Batch-aware overload used by list endpoints to avoid an N+1 against
     * event_categories. Callers prefetch the catId→name map with a single
     * {@code findAllById} and pass the resolved name in.
     */
    private EventTypeDto toEventTypeDto(EventType et, String categoryName) {
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
            .minGuests(et.getMinGuests())
            .maxGuests(et.getMaxGuests())
            .categoryId(et.getCategoryId())
            .categoryName(categoryName)
            // Copy into a plain ArrayList so no Hibernate PersistentBag reference
            // leaks outside the transaction (would cause LazyInitializationException
            // when Jackson serializes the response after the session is closed).
            .imageUrls(et.getImageUrls() != null ? new ArrayList<>(et.getImageUrls()) : new ArrayList<>())
            .active(et.isActive())
            .build();
    }

    private List<EventTypeDto> toEventTypeDtoList(List<EventType> ets) {
        if (ets == null || ets.isEmpty()) return java.util.List.of();
        List<Long> catIds = ets.stream()
            .map(EventType::getCategoryId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> catNames = catIds.isEmpty()
            ? Map.of()
            : eventCategoryRepository.findAllById(catIds).stream()
                .collect(Collectors.toMap(
                    com.skbingegalaxy.booking.entity.EventCategory::getId,
                    com.skbingegalaxy.booking.entity.EventCategory::getName));
        return ets.stream()
            .map(et -> toEventTypeDto(
                et,
                et.getCategoryId() != null ? catNames.get(et.getCategoryId()) : null))
            .toList();
    }

    private AddOnDto toAddOnDto(AddOn a) {
        String categoryName = null;
        if (a.getCategoryId() != null) {
            categoryName = addOnCategoryRepository.findById(a.getCategoryId())
                .map(com.skbingegalaxy.booking.entity.AddOnCategory::getName)
                .orElse(null);
        }
        return toAddOnDto(a, categoryName);
    }

    /**
     * Batch-aware overload used by list endpoints to avoid an N+1 against
     * addon_categories.
     */
    private AddOnDto toAddOnDto(AddOn a, String categoryName) {
        return AddOnDto.builder()
            .id(a.getId())
            .bingeId(a.getBingeId())
            .name(a.getName())
            .description(a.getDescription())
            .price(a.getPrice())
            .categoryId(a.getCategoryId())
            .categoryName(categoryName)
            .imageUrls(a.getImageUrls() != null ? new ArrayList<>(a.getImageUrls()) : new ArrayList<>())
            .active(a.isActive())
            .stockPerDay(a.getStockPerDay())
            .advanceNoticeMinutes(a.getAdvanceNoticeMinutes())
            .build();
    }

    private List<AddOnDto> toAddOnDtoList(List<AddOn> addOns) {
        if (addOns == null || addOns.isEmpty()) return java.util.List.of();
        List<Long> catIds = addOns.stream()
            .map(AddOn::getCategoryId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> catNames = catIds.isEmpty()
            ? Map.of()
            : addOnCategoryRepository.findAllById(catIds).stream()
                .collect(Collectors.toMap(
                    com.skbingegalaxy.booking.entity.AddOnCategory::getId,
                    com.skbingegalaxy.booking.entity.AddOnCategory::getName));
        return addOns.stream()
            .map(a -> toAddOnDto(
                a,
                a.getCategoryId() != null ? catNames.get(a.getCategoryId()) : null))
            .toList();
    }

    /**
     * Resolves and validates an event category for the current binge. Accepts
     * either a binge-scoped or a global ({@code bingeId IS NULL}) category;
     * rejects categories owned by a different binge.
     *
     * @return resolved {@code categoryId} or {@code null} when the caller did
     *         not supply one.
     */
    private Long resolveEventCategoryId(Long requested, Long bingeId) {
        if (requested == null) return null;
        com.skbingegalaxy.booking.entity.EventCategory cat = eventCategoryRepository.findById(requested)
            .orElseThrow(() -> new BusinessException("Event category " + requested + " not found"));
        if (cat.getBingeId() != null && !cat.getBingeId().equals(bingeId)) {
            throw new BusinessException("Event category " + requested + " is not available on this binge");
        }
        if (!cat.isActive()) {
            throw new BusinessException("Event category '" + cat.getName() + "' is currently inactive");
        }
        return cat.getId();
    }

    /** Same contract as {@link #resolveEventCategoryId} but for add-ons. */
    private Long resolveAddOnCategoryId(Long requested, Long bingeId) {
        if (requested == null) return null;
        com.skbingegalaxy.booking.entity.AddOnCategory cat = addOnCategoryRepository.findById(requested)
            .orElseThrow(() -> new BusinessException("Add-on category " + requested + " not found"));
        if (cat.getBingeId() != null && !cat.getBingeId().equals(bingeId)) {
            throw new BusinessException("Add-on category " + requested + " is not available on this binge");
        }
        if (!cat.isActive()) {
            throw new BusinessException("Add-on category '" + cat.getName() + "' is currently inactive");
        }
        return cat.getId();
    }
}



