package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.domain.OccupancyWindow;
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
    private final SlotHoldService slotHoldService;                          // BOOK-001 — hold consumption at booking creation
    private final com.skbingegalaxy.booking.repository.BookingTransferRepository bookingTransferRepository;
    private final BookingEventPublisher bookingEventPublisher;              // Envelope-aware Kafka outbox writer (V46)
    private final BookingRiskEvaluator bookingRiskEvaluator;                // Item 23 — fraud / abuse rule engine
    private final BookingAnalyticsMetrics analyticsMetrics;                 // Item 27 — funnel/lifecycle counters
    private final BookingStateMachine stateMachine;                         // Centralized status-transition engine
    private final TaxService taxService;                                    // Tax computation at booking creation time
    private final VenueClockService venueClock;                             // Venue-aware timezone resolution
    private final TurnoverPolicy turnoverPolicy;                            // V81 — setup/cleanup buffers → occupancy windows
    private final BookingWindowPolicy bookingWindowPolicy;                  // V84 — min notice, max advance, permitted durations
    // Owns the Binge aggregate. Needed only for the "first event created" stamp, which
    // must live with the entity it mutates rather than be re-implemented here — the
    // duplicate that used to sit in createEventType is what let the seeder path diverge.
    // No cycle: BingeService does not depend on BookingService.
    private final BingeService bingeService;

    @Value("${internal.api.secret}")
    private String internalApiSecret;

    @Value("${app.booking.ref-prefix:SKBG}")
    private String refPrefix;

    @Value("${app.booking.max-pending-per-customer:2}")
    private int maxPendingPerCustomer;

    @Value("${app.booking.cooldown-minutes-after-timeout:10}")
    private int cooldownMinutesAfterTimeout;

    /**
     * Payment window for a PENDING booking — must mirror the
     * {@link com.skbingegalaxy.booking.scheduler.PendingBookingTimeoutScheduler} value so
     * the {@code paymentExpiresAt} we expose to customers matches when the saga timeout
     * will actually release the reservation.
     */
    @Value("${app.saga.pending-timeout-minutes:30}")
    private int pendingTimeoutMinutes;

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
        return createBooking(request, customerId, customerName, customerEmail,
            customerPhone, customerPhoneCountryCode,
            com.skbingegalaxy.booking.domain.BookingOrigin.DIRECT, null, null);
    }

    /**
     * Core reservation creation, shared by the customer funnel and by external
     * ingestion (V85).
     *
     * <p>{@code origin} selects which anti-abuse guards apply — see
     * {@link #applyCustomerFunnelGuards}. Every guard that protects the venue's
     * physical reality runs regardless.
     *
     * @param externalSource provider-neutral channel slug; required when origin is
     *        CHANNEL, must be null otherwise (a DB CHECK enforces the pairing)
     * @param externalRef    the channel's own booking reference, same rule
     */
    @Transactional(timeout = 15)
    public BookingDto createBooking(CreateBookingRequest request,
                                    Long customerId, String customerName,
                                    String customerEmail, String customerPhone,
                                    String customerPhoneCountryCode,
                                    com.skbingegalaxy.booking.domain.BookingOrigin origin,
                                    String externalSource, String externalRef) {

        Long bingeId = BingeContext.requireBingeId();

        // Approval / activation guard — reject bookings against any binge that
        // has not been approved by a super-admin or has been deactivated. The
        // customer-visible listing already filters these out, but a leaked or
        // guessed bingeId in the X-Binge-Id header would otherwise slip through.
        assertBingeBookable(bingeId);

        // V85/G3: the customer-funnel anti-abuse guards. They exist to stop a CUSTOMER
        // misusing the self-service funnel, so they only apply to an origin that has
        // one. See BookingOrigin#customerFunnelGuardsApply.
        int unpaidLimit = effectiveUnpaidLimit(bingeId);
        applyCustomerFunnelGuards(origin, customerId, bingeId, unpaidLimit);
        EventType eventType = findBookableEventType(request.getEventTypeId());

        // Reject bookings in the past — use the venue's configured timezone so the
        // business-day boundary is correct for any country the venue operates in.
        ZoneId bizZone = venueClock.zoneOf(bingeId);
        LocalDateTime bizNow = LocalDateTime.now(bizZone);
        if (request.getBookingDate().isBefore(bizNow.toLocalDate())) {
            throw new BusinessException("Booking date cannot be in the past");
        }
        // Same-day defense-in-depth: a date-only check above lets a same-day slot
        // whose start time has already elapsed (venue-local) slip through — the
        // availability-service grid/checkSlotAvailable are the primary guard against
        // this, but both are reachable through a resilience cache (see
        // AvailabilityClientFallback) that can serve a stale "available" result
        // during an outage. Re-derive the same rule here so booking-service never
        // depends solely on a downstream call staying perfectly in sync with "now".
        if (request.getBookingDate().isEqual(bizNow.toLocalDate())
            && !request.getStartTime().isAfter(bizNow.toLocalTime())) {
            throw new BusinessException("This time slot has already passed for today. Please choose a later time.");
        }
        // V84 (G5): per-venue booking window — minimum notice and maximum advance.
        // Supersedes the previous global-only horizon check; a venue that sets neither
        // still gets the platform default, so behaviour is unchanged until configured.
        // Prevents "slot squatting", schedule poisoning and pricing-rule drift on
        // far-future dates, and stops a channel selling at 23:58 for 00:30.
        bookingWindowPolicy.assertWithinBookingWindow(
            bingeRepository.findById(bingeId).orElse(null),
            request.getBookingDate(), request.getStartTime(), maxBookingHorizonDays);

        // Content-based dedupe (defence-in-depth alongside Idempotency-Key + rate limiter):
        // refuse to create a second PENDING booking for the same customer + event + slot.
        // Catches accidental double-submits that arrive milliseconds apart on different
        // gateway instances and any client that doesn't send Idempotency-Key.
        //
        // V85/G3: customer-funnel scoped. Channel reservations share one synthetic
        // customer per channel, so this would misfire on unrelated guests. Their
        // duplicate protection is stronger anyway — the V85 unique index on
        // (external_source, external_ref) makes a redelivered reservation impossible.
        if (origin.customerFunnelGuardsApply()
                && bookingRepository.existsPendingDuplicate(
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
        // V84 (B5): honour the venue's published duration allow-list, when it has one.
        // Placed after the generic bounds so the customer sees the specific, actionable
        // message ("offered in 2 hours, 3 hours") rather than a generic range error.
        bookingWindowPolicy.assertDurationPermitted(eventType, durMin);

        // Operating-hours guard: reject bookings outside this binge's published
        // open/close window. Defence in depth against any client that bypasses
        // the slot grid (mobile app misuse, scripted abuse, leaked API token,
        // or admin walk-in typos). Falls back to global config when the binge
        // has no per-binge override.
        validateWithinOperatingHours(bingeId, request.getBookingDate(), request.getStartTime(), durMin);

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

        // The duplicate/unpaid guards above ran BEFORE the lock, so two
        // concurrent submits (no Idempotency-Key, multi-room venue) could both
        // pass them and be assigned different rooms. Re-check now that this
        // transaction owns the slot lock — a competing creation for the same
        // (binge, date) has either committed (visible here) or not started.
        // V85/G3: same origin scoping as the pre-lock pass. A channel reservation
        // shares a synthetic customer identity with every other reservation from that
        // channel, so an unpaid-limit or pending-duplicate re-check here would start
        // rejecting them once two happened to land on the same slot shape.
        if (origin.customerFunnelGuardsApply()) {
            if (bookingRepository.existsPendingDuplicate(
                    customerId, request.getEventTypeId(),
                    request.getBookingDate(), request.getStartTime())) {
                throw new BusinessException(
                    "You already have a pending booking for this event and time slot. Please check My Bookings.");
            }
            long pendingCountLocked = bookingRepository.countPendingByCustomerIdAndBingeId(customerId, bingeId);
            if (pendingCountLocked >= unpaidLimit) {
                throw new BusinessException(
                    "You already have " + pendingCountLocked + " unpaid booking(s) at this venue. "
                    + "Open My Bookings to complete payment or cancel them (unpaid bookings can "
                    + "always be cancelled free of charge), then try again.");
            }
        }

        // A venue WITH rooms is not a single space: two parties CAN share a time in
        // different rooms. Per-room exclusivity is enforced by resolveRoomAssignment
        // below, and the maxConcurrentBookings ceiling caps total concurrency at the
        // room count — so the binge-wide "any overlap = conflict" rule applies only to
        // a room-LESS venue (a single physical space).
        List<VenueRoom> bookableRooms = venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bingeId).stream()
            .filter(r -> r.isActive()
                && (r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED))
            .toList();
        boolean venueHasBookableRooms = !bookableRooms.isEmpty();

        // V81: resolve this reservation's turnover buffers ONCE, then use the same
        // occupancy window for every guard below and snapshot it onto the booking.
        // Resolving per-guard would risk two guards disagreeing if the event type
        // were edited mid-transaction.
        TurnoverPolicy.Buffers buffers = turnoverPolicy.resolve(bingeId, eventType);
        OccupancyWindow candidateWindow = buffers.windowFor(startMinute, durMin);

        if (!venueHasBookableRooms && hasTimeConflict(request.getBookingDate(), candidateWindow)) {
            throw new BusinessException(buffers.isZero()
                ? "Selected time slot conflicts with an existing booking"
                : "Selected time slot conflicts with an existing booking or its setup/cleanup time");
        }

        // A live hold from ANOTHER customer is a real reservation: it must block a
        // direct booking exactly the way an existing booking blocks a new hold
        // (assertSlotAvailableForHold), otherwise the countdown promise shown to
        // the hold-holder is empty. The booking customer's own holds never block
        // them (their holdToken is consumed below).
        String holdToken = request.getHoldToken() != null && !request.getHoldToken().isBlank()
            ? request.getHoldToken().trim() : null;
        int foreignHoldOverlap = countForeignLiveHoldOverlap(
            bingeId, request.getBookingDate(), candidateWindow, customerId, null);
        if (!venueHasBookableRooms && foreignHoldOverlap > 0) {
            throw new BusinessException(
                "This time slot is temporarily held by another customer completing checkout. "
                + "Please try again in a few minutes or pick a different slot.");
        }

        // Capacity management: the static max-concurrent ceiling applies ONLY to room-less
        // venues (a single space). A venue WITH rooms is bounded by its actual number of
        // bookable rooms — enforced by resolveRoomAssignment below (CAPACITY_FULL when every
        // room is taken) — so the concurrency limit tracks the room count automatically and
        // adding/removing a room changes capacity without touching maxConcurrentBookings.
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (!venueHasBookableRooms && binge != null && binge.getMaxConcurrentBookings() != null) {
            int overlapping = countOverlappingBookings(request.getBookingDate(), candidateWindow);
            if (overlapping + foreignHoldOverlap >= binge.getMaxConcurrentBookings()) {
                throw new BusinessException("CAPACITY_FULL:This time slot has reached maximum capacity ("
                    + binge.getMaxConcurrentBookings() + " bookings). You can join the waitlist to be notified when a spot opens up.");
            }
        }
        if (venueHasBookableRooms) {
            // Foreign live holds (pinned to a room or not) reserve capacity out of
            // the venue's total room-slots; per-room pinning is enforced again in
            // resolveRoomAssignment.
            int totalRoomCapacity = bookableRooms.stream()
                .mapToInt(r -> Math.max(r.getCapacity(), 1)).sum();
            int occupied = countOverlappingBookings(request.getBookingDate(), candidateWindow)
                + foreignHoldOverlap;
            if (occupied >= totalRoomCapacity) {
                throw new BusinessException("CAPACITY_FULL:All rooms are booked or held for this time slot. "
                    + "You can join the waitlist to be notified when a spot opens up.");
            }
        }

        // Calculate pricing using resolved customer pricing
        PricingService.ResolvedEventPrice eventPrice = pricingService.resolveEventPrice(customerId, request.getEventTypeId());
        BigDecimal baseAmount = PricingService.computeBaseAmount(eventPrice, durMin);

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
        BigDecimal guestAmount = PricingService.computeGuestAmount(eventPrice, guests);

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
            // Component amounts (base, addOn, guest) stay pre-surge so admins see
            // the breakdown; the surgeMultiplier field records the factor.
            totalAmount = PricingService.applySurge(totalAmount, surgeMultiplier);
        }

        // ── Venue room assignment ─────────────────────────────────
        VenueRoom assignedRoom = resolveRoomAssignment(
            bingeId, request.getVenueRoomId(), request.getBookingDate(), candidateWindow, customerId);
        Long venueRoomId = assignedRoom != null ? assignedRoom.getId() : null;
        String venueRoomName = assignedRoom != null ? assignedRoom.getName() : null;
        BigDecimal venueRoomPrice = (assignedRoom != null && assignedRoom.getPriceAddition() != null)
            ? assignedRoom.getPriceAddition() : BigDecimal.ZERO;
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
        TaxContext taxCtx = buildBookingTaxContext(bingeId, durMin);
        TaxComputationResult taxResult = taxService.compute(taxCtx, totalAmount, baseAmount, addOnTotal, guestAmount);
        BigDecimal subtotalForTax = totalAmount;
        BigDecimal taxComputed = taxResult.getTotalTax() != null ? taxResult.getTotalTax() : BigDecimal.ZERO;
        if (taxComputed.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = subtotalForTax.add(taxComputed).setScale(2, RoundingMode.HALF_UP);
        }
        String taxBreakdown = taxResult.getBreakdownJson();

        // ── Payment currency ────────────────────────────────────────────────────
        // Native per-binge pricing: the booking is priced and charged in the BINGE's own
        // currency (derived from its country) — the customer never chooses a currency, so
        // there is no server-side FX conversion and no customer-supplied rate lock. A
        // foreign customer's own bank/card converts from their home currency at charge time.
        String bingeCurrency = (binge != null && binge.getCurrency() != null && !binge.getCurrency().isBlank())
            ? binge.getCurrency()
            : com.skbingegalaxy.booking.util.CountryCurrency.BASE;
        LocalDateTime fxLockedUntil = null;
        String paymentCurrencyCode = bingeCurrency;
        BigDecimal lockedFxRate = BigDecimal.ONE;

        // G-B attribution, resolved HERE — after pricing, tax and every eligibility
        // check, immediately before persistence. Placement is the safety property: a
        // value that only comes into existence after every decision is made cannot have
        // influenced one. `of` returns null for absent, malformed or out-of-window input
        // and never throws, because a bad marketing parameter must not fail a booking.
        // Attribution applies to DIRECT bookings; a CHANNEL reservation already records
        // where it came from in external_source and must not be double-counted.
        com.skbingegalaxy.booking.domain.BookingAttribution attribution =
            (origin == com.skbingegalaxy.booking.domain.BookingOrigin.CHANNEL)
                ? null
                : com.skbingegalaxy.booking.domain.BookingAttribution.of(
                    request.getAttributionSource(),
                    request.getAttributionRef(),
                    request.getAttributionCapturedAt(),
                    LocalDateTime.now(ZoneOffset.UTC));

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
            // V81: snapshot the resolved buffers so occupancy stays reproducible
            // even if the event type is re-configured later.
            .origin(origin)
            .externalSource(externalSource)
            .externalRef(externalRef)
            // G-B attribution. Resolved LAST, after every price, tax and eligibility
            // decision above is already final, so it is structurally incapable of
            // influencing any of them. That ordering is the guarantee: attribution
            // arrives as query parameters on a public URL, so if it could reach the
            // pricing path a customer could choose their own discount.
            .attributionSource(attribution == null ? null : attribution.source())
            .attributionRef(attribution == null ? null : attribution.ref())
            .attributionCapturedAt(attribution == null ? null : attribution.capturedAt())
            .setupMinutes(buffers.setupMinutes())
            .cleanupMinutes(buffers.cleanupMinutes())
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

        // Consume the customer's slot hold atomically inside this transaction
        // (BOOK-001): validates ownership + slot match and marks it CONVERTED
        // with this booking's ref. A validation failure rolls the booking back;
        // a rollback after this point restores the hold to ACTIVE.
        if (holdToken != null) {
            slotHoldService.consumeHold(holdToken, customerId, request.getEventTypeId(),
                request.getBookingDate(), request.getStartTime(), durMin, bookingRef);
            log.info("Slot hold {} consumed by booking {}", holdToken, bookingRef);
        }

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

        // In-process AFTER_COMMIT fan-out (waitlist OFFERED→BOOKED conversion).
        eventPublisher.publishEvent(new com.skbingegalaxy.booking.event.BookingCreatedEvent(
            saved.getId(), saved.getBookingRef(), customerId, bingeId,
            saved.getBookingDate(), saved.getStartTime()));

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
        return toDtos(list);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerBookingsByStatus(Long customerId, BookingStatus status) {
        Long bid = BingeContext.getBingeId();
        return toDtos(bid != null
            ? bookingRepository.findByBingeIdAndCustomerIdAndStatus(bid, customerId, status)
            : bookingRepository.findByCustomerIdAndStatus(customerId, status));
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerCurrentBookings(Long customerId, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerCurrentBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerCurrentBookings(customerId, today);
        return toDtos(list);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerPastBookings(Long customerId, LocalDate clientToday) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = clientToday != null ? clientToday : venueClock.today(bid);
        List<Booking> list = bid != null
            ? bookingRepository.findCustomerPastBookingsByBinge(bid, customerId, today)
            : bookingRepository.findCustomerPastBookings(customerId, today);
        return toDtos(list);
    }

    // â”€â”€ Admin: all bookings (paginated) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getAllBookings(Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return toDtoPage(bid != null ? bookingRepository.findByBingeId(bid, pageable) : bookingRepository.findAll(pageable));
    }

    // â”€â”€ Admin: today's bookings (paginated) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getTodayBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return toDtoPage(bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, today, pageable) : bookingRepository.findByBookingDate(today, pageable));
    }

    // â”€â”€ Admin: upcoming bookings (today+future, PENDING or CONFIRMED only) â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getUpcomingBookings(LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return toDtoPage(bid != null ? bookingRepository.findUpcomingBookingsByBinge(bid, today, pageable) : bookingRepository.findUpcomingBookings(today, pageable));
    }

    // â”€â”€ Admin: by date â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDate(LocalDate date, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return toDtoPage(bid != null ? bookingRepository.findByBingeIdAndBookingDate(bid, date, pageable) : bookingRepository.findByBookingDate(date, pageable));
    }

    // â”€â”€ Admin: by status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatus(BookingStatus status, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return toDtoPage(bid != null ? bookingRepository.findByBingeIdAndStatus(bid, status, pageable) : bookingRepository.findByStatus(status, pageable));
    }

    // â”€â”€ Admin: by status scoped to operational day â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByStatusForToday(BookingStatus status, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return toDtoPage(bid != null
            ? bookingRepository.findByBingeIdAndBookingDateAndStatus(bid, today, status, pageable)
            : bookingRepository.findByBookingDateAndStatus(today, status, pageable));
    }

    // â”€â”€ Admin: by date range â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> getBookingsByDateRange(LocalDate from, LocalDate to, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return toDtoPage(bid != null
            ? bookingRepository.findByBingeIdAndBookingDateBetween(bid, from, to, pageable)
            : bookingRepository.findByBookingDateBetween(from, to, pageable));
    }

    // â”€â”€ Admin: search â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookings(String query, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        return toDtoPage(bid != null ? bookingRepository.searchBookingsByBinge(bid, query, pageable) : bookingRepository.searchBookings(query, pageable));
    }

    // â”€â”€ Admin: search scoped to operational day â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional(readOnly = true)
    public Page<BookingDto> searchBookingsForToday(String query, LocalDate clientToday, Pageable pageable) {
        Long bid = BingeContext.getBingeId();
        LocalDate today = systemSettingsService.getOperationalDate(bid, clientToday);
        return toDtoPage(bid != null
            ? bookingRepository.searchBookingsByBingeAndDate(bid, today, query, pageable)
            : bookingRepository.searchBookingsByDate(today, query, pageable));
    }

    // â”€â”€ Admin: update booking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    @Transactional
    public BookingDto updateBooking(String bookingRef, UpdateBookingRequest request) {
        Booking booking = findScopedBookingByRef(bookingRef);
        String previousStatus = booking.getStatus().name();
        // Item 7 (July-2026): every ACTUAL reservation change is collected here so
        // (a) remarks can be demanded only when something really changed, and
        // (b) the event-log row names exactly what changed. Status / check-in
        // transitions are excluded — they carry their own audited reasons.
        java.util.List<String> changeSummary = new java.util.ArrayList<>();

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
                    // Physical-occupancy guard: a room (or a room-less venue) can
                    // only hold its capacity of simultaneously checked-in parties.
                    // Blocks a back-to-back booking from checking in while the prior
                    // guests are still physically present (CHECKED_IN, not yet out).
                    enforceCheckInOccupancy(booking);
                    booking = stateMachine.transition(
                        booking, BookingTransitionEvent.CHECK_IN,
                        adminActorFromContext(), /*reason*/ null);
                }
                booking.setCheckedIn(true);
                ZoneId venueZone = venueClock.zoneOf(booking.getBingeId());
                // Audit instant stored in UTC (platform contract: point-in-time events
                // are UTC; only business-meaningful wall-clock fields such as
                // bookingDate/startTime are venue-local). Clients render it in their own
                // locale. Storing it venue-local previously made the value ambiguous and
                // skewed every downstream timestamp display by the viewer's UTC offset.
                java.time.Instant nowInstant = java.time.Instant.now();
                if (booking.getActualCheckInTime() == null) {
                    booking.setActualCheckInTime(LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC));
                }
                // Late-arrival flag — compare absolute instants so the result is
                // timezone-robust for a multi-region deployment: real "now" vs the
                // scheduled start resolved through the VENUE's zone (not the operator's
                // browser zone, not the JVM default). Both QR/OTP and manual admin
                // check-in funnel through this method, so the flag is set consistently
                // regardless of channel.
                java.time.Instant scheduledStartInstant = LocalDateTime.of(
                        booking.getBookingDate(), booking.getStartTime())
                    .atZone(venueZone).toInstant();
                if (nowInstant.isAfter(scheduledStartInstant) && !booking.isLateArrival()) {
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
            if (!request.getCustomerName().equals(booking.getCustomerName())) changeSummary.add("customer name");
            booking.setCustomerName(request.getCustomerName());
        }
        if (request.getCustomerEmail() != null) {
            if (!request.getCustomerEmail().equals(booking.getCustomerEmail())) changeSummary.add("customer email");
            booking.setCustomerEmail(request.getCustomerEmail());
        }
        if (request.getCustomerPhone() != null) {
            if (!request.getCustomerPhone().equals(booking.getCustomerPhone())) changeSummary.add("customer phone");
            booking.setCustomerPhone(request.getCustomerPhone());
        }
        if (request.getCustomerPhoneCountryCode() != null) {
            booking.setCustomerPhoneCountryCode(request.getCustomerPhoneCountryCode());
        }
        if (request.getSpecialNotes() != null) {
            String oldNotes = booking.getSpecialNotes() == null ? "" : booking.getSpecialNotes();
            if (!request.getSpecialNotes().equals(oldNotes)) changeSummary.add("special notes");
            booking.setSpecialNotes(request.getSpecialNotes());
        }

        // â”€â”€ Pricing-relevant field updates â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        boolean pricingChanged = false;
        /** V81: set when an event-type change re-resolved this booking's turnover buffers. */
        boolean bufferChanged = false;

        // Event type change
        if (request.getEventTypeId() != null
                && !request.getEventTypeId().equals(booking.getEventType().getId())) {
            EventType newEventType = findBookableEventType(request.getEventTypeId());
            booking.setEventType(newEventType);
            // V81: the event type IS what the buffers were resolved from, so changing
            // it must re-resolve them. This is the one edit that legitimately replaces
            // the snapshot — a "Screening" becoming a "Birthday" genuinely needs the
            // birthday's reset time, and the conflict re-check below then runs against
            // the new window. Every other edit keeps the original snapshot.
            TurnoverPolicy.Buffers rebased = turnoverPolicy.resolve(booking.getBingeId(), newEventType);
            bufferChanged = rebased.setupMinutes() != booking.getSetupMinutes()
                || rebased.cleanupMinutes() != booking.getCleanupMinutes();
            booking.setSetupMinutes(rebased.setupMinutes());
            booking.setCleanupMinutes(rebased.cleanupMinutes());
            pricingChanged = true;
            changeSummary.add("event type");
        }

        // Duration change
        if (request.getDurationMinutes() != null) {
            int newDur = request.getDurationMinutes();
            int oldDur = booking.getScheduledDurationMinutes();
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
                changeSummary.add("duration");
            }
        }

        // Date/time change — check availability. Only a REAL change flips the
        // flag; echoing the unchanged date/time back must not trigger conflict
        // re-checks, surge re-resolution, or the remarks requirement.
        boolean dateTimeChanged = false;
        if (request.getBookingDate() != null && !request.getBookingDate().equals(booking.getBookingDate())) {
            booking.setBookingDate(request.getBookingDate());
            dateTimeChanged = true;
            changeSummary.add("date");
        }
        if (request.getStartTime() != null && !request.getStartTime().equals(booking.getStartTime())) {
            booking.setStartTime(request.getStartTime());
            dateTimeChanged = true;
            changeSummary.add("start time");
        }
        // A duration change moves the occupied window's END even when the date
        // and start stay put — it must pass the same conflict / capacity gate.
        //
        // V81: so does an event-type change, because it re-resolves the buffers
        // above. Skipping the re-check there would let a widened window through
        // the application and straight into the database backstop, which fires
        // as a raw exclusion_violation instead of a readable business error.
        if (dateTimeChanged || changeSummary.contains("duration") || bufferChanged) {
            bookingRepository.acquireSlotLock(slotLockKey(booking.getBingeId(), booking.getBookingDate()));
            int startMinute = booking.getStartTime().getHour() * 60 + booking.getStartTime().getMinute();
            int durMin = booking.getScheduledDurationMinutes();
            // The booking's OWN snapshotted buffers — re-resolved only when the
            // event type changed, never silently from a since-edited event type.
            OccupancyWindow editWindow = OccupancyWindow.of(
                startMinute, durMin, booking.getSetupMinutes(), booking.getCleanupMinutes());
            if (hasTimeConflict(booking.getBookingDate(), editWindow, booking.getId())) {
                throw new BusinessException("Selected time slot is no longer available");
            }
            // Validate venue room capacity at the new time slot. Live holds from
            // other customers count as occupancy (same rule as createBooking) —
            // release the hold from Admin → Slot Holds first if the move is intended.
            if (booking.getVenueRoomId() != null) {
                VenueRoom room = venueRoomRepository.findById(booking.getVenueRoomId()).orElse(null);
                if (room == null || !room.isActive()) {
                    booking.setVenueRoomId(null);
                    booking.setVenueRoomName(null);
                } else {
                    int roomOcc = countRoomBookings(room.getId(), booking.getBookingDate(), editWindow, booking.getId())
                        + countForeignLiveHoldOverlap(booking.getBingeId(), booking.getBookingDate(),
                            editWindow, booking.getCustomerId(), room.getId());
                    if (roomOcc >= room.getCapacity()) {
                        throw new BusinessException("Room '" + room.getName() + "' is fully booked or held for the new time slot");
                    }
                }
            } else if (countForeignLiveHoldOverlap(booking.getBingeId(), booking.getBookingDate(),
                    editWindow, booking.getCustomerId(), null) > 0) {
                throw new BusinessException(
                    "The new time slot is temporarily held by another customer completing checkout.");
            }
        }

        // Guest count change
        if (request.getNumberOfGuests() != null
                && request.getNumberOfGuests() != booking.getNumberOfGuests()) {
            enforceEventTypeGuestRange(booking.getEventType(), request.getNumberOfGuests());
            booking.setNumberOfGuests(request.getNumberOfGuests());
            pricingChanged = true;
            changeSummary.add("guest count");
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
            String oldAddOnSig = booking.getAddOns().stream()
                .map(ba -> ba.getAddOn().getId() + ":" + ba.getQuantity())
                .sorted().collect(Collectors.joining(","));
            String newAddOnSig = request.getAddOns().stream()
                .map(sel -> sel.getAddOnId() + ":" + Math.max(sel.getQuantity(), 1))
                .sorted().collect(Collectors.joining(","));
            if (!oldAddOnSig.equals(newAddOnSig)) changeSummary.add("add-ons");
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
            // Amounts are denominated in the BINGE's currency — never a hardcoded ₹.
            String cur = booking.getPaymentCurrencyCode() != null ? booking.getPaymentCurrencyCode() + " " : "";
            String diffNote = String.format("PRICE OVERRIDE: %s%s → %s%s (%s%s%s). Reason: %s",
                cur, oldTotal.toPlainString(), cur, newTotal.toPlainString(),
                diff.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "-",
                cur, diff.abs().toPlainString(), reason);
            // Deliberately NOT added to changeSummary: price overrides carry their
            // own audited priceAdjustmentReason, so the generic remarks gate would
            // just double-charge the operator for the same justification.
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

            int durMin = booking.getScheduledDurationMinutes();
            BigDecimal baseAmount = PricingService.computeBaseAmount(eventPrice, durMin);

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
            BigDecimal guestAmount = PricingService.computeGuestAmount(eventPrice, guests);

            BigDecimal newTotal = baseAmount.add(addOnTotal).add(guestAmount);

            // Build modification note
            BigDecimal oldTotal = booking.getTotalAmount();
            BigDecimal diff = newTotal.subtract(oldTotal);
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                String cur = booking.getPaymentCurrencyCode() != null ? booking.getPaymentCurrencyCode() + " " : "";
                String diffNote = String.format("Price updated: %s%s → %s%s (%s%s%s)",
                    cur, oldTotal.toPlainString(), cur, newTotal.toPlainString(),
                    diff.compareTo(BigDecimal.ZERO) > 0 ? "+" : "-",
                    cur, diff.abs().toPlainString());
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
                    booking.setTotalAmount(PricingService.applySurge(preSurgeTotal, surge.multiplier()));
                } else {
                    booking.setSurgeMultiplier(null);
                    booking.setSurgeLabel(null);
                    booking.setTotalAmount(preSurgeTotal);
                }
            } else if (booking.getSurgeMultiplier() != null) {
                // Pricing fields changed but date/time didn't — reapply existing surge multiplier
                BigDecimal preSurgeTotal = booking.getBaseAmount()
                    .add(booking.getAddOnAmount()).add(booking.getGuestAmount());
                booking.setTotalAmount(PricingService.applySurge(preSurgeTotal, booking.getSurgeMultiplier()));
            }
        }

        // â”€â”€ Sync paymentStatus with actual balance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // -- Recompute taxes after any pricing / schedule / component override --
        // Note: UpdateBookingRequest has no totalAmount field; admins can only
        // override individual components (baseAmount / addOnAmount / guestAmount).
        // Therefore tax must always be reapplied on top of the new line items —
        // we do NOT treat a component override as "admin set the final number".
        if (pricingChanged || dateTimeChanged || directPriceOverride) {
            TaxContext taxCtxUpdate = buildBookingTaxContext(booking.getBingeId(),
                booking.getScheduledDurationMinutes());
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

        // Item 7: a real reservation change demands operator remarks — the
        // whole transaction rolls back here, so nothing above has committed.
        // Notes-only or status-only updates (empty changeSummary) stay exempt.
        boolean hasRemarks = request.getRemarks() != null && !request.getRemarks().isBlank();
        if (!changeSummary.isEmpty() && !hasRemarks) {
            throw new BusinessException("Remarks are required when changing a reservation ("
                + String.join(", ", changeSummary) + " changed)");
        }

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
            String eventDesc;
            if (directPriceOverride) {
                eventDesc = "Price adjusted by admin: " + (request.getPriceAdjustmentReason() != null && !request.getPriceAdjustmentReason().isBlank()
                    ? request.getPriceAdjustmentReason() : "Admin price adjustment");
            } else if (!changeSummary.isEmpty()) {
                eventDesc = "Reservation modified (" + String.join(", ", changeSummary) + ")";
            } else {
                eventDesc = "Booking updated by admin";
            }
            if (hasRemarks) {
                eventDesc += " — Remarks: " + request.getRemarks().trim();
            }
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
        // BOOK-004: paid (CONFIRMED) bookings are cancellable too — the
        // venue's cancellation policy (evaluated below) decides whether it is
        // allowed and what refund percentage of the collected money is owed.
        // The UI has always advertised this; the old PENDING-only gate made
        // the policy evaluation unreachable for exactly the bookings it was
        // written for.
        if (booking.getStatus() != BookingStatus.PENDING
                && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                "Only PENDING or CONFIRMED bookings can be cancelled by the customer. Current status: "
                    + booking.getStatus());
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
        int existingDuration = booking.getScheduledDurationMinutes();
        int newDurMin = request.getNewDurationMinutes() != null ? request.getNewDurationMinutes() : existingDuration;
        if (newDurMin < 30 || newDurMin > 720) {
            throw new BusinessException("Duration must be between 30 minutes and 12 hours");
        }
        if (newDurMin % 30 != 0) {
            throw new BusinessException("Duration must be in 30-minute increments");
        }

        Long bingeId = booking.getBingeId();

        // Operating-hours guard for the *new* slot (same rule as createBooking).
        validateWithinOperatingHours(bingeId, request.getNewBookingDate(), request.getNewStartTime(), newDurMin);

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

        // Conflict check — room-aware. A reschedule keeps the booking's room, so it only
        // conflicts if THAT room is occupied at the new slot (excluding itself). A room-less
        // venue is a single space where any overlap conflicts. Live holds from OTHER
        // customers count as occupancy exactly as in createBooking — a reschedule must not
        // be a side door into a slot someone is holding at checkout.
        Long rescheduleRoomId = booking.getVenueRoomId();
        // V81: a reschedule carries the booking's own snapshotted buffers to the new slot.
        OccupancyWindow rescheduleWindow = OccupancyWindow.of(
            startMinute, newDurMin, booking.getSetupMinutes(), booking.getCleanupMinutes());
        if (rescheduleRoomId != null) {
            VenueRoom rRoom = venueRoomRepository.findById(rescheduleRoomId).orElse(null);
            int rCap = rRoom != null ? Math.max(rRoom.getCapacity(), 1) : 1;
            int rOccupied = countRoomBookings(rescheduleRoomId, request.getNewBookingDate(), rescheduleWindow, booking.getId())
                + countForeignLiveHoldOverlap(bingeId, request.getNewBookingDate(), rescheduleWindow,
                    booking.getCustomerId(), rescheduleRoomId);
            if (rOccupied >= rCap) {
                throw new BusinessException("The new time slot conflicts with an existing booking or an active hold in this room");
            }
        } else {
            if (hasTimeConflict(request.getNewBookingDate(), rescheduleWindow, booking.getId())) {
                throw new BusinessException("The new time slot conflicts with an existing booking");
            }
            if (countForeignLiveHoldOverlap(bingeId, request.getNewBookingDate(), rescheduleWindow,
                    booking.getCustomerId(), null) > 0) {
                throw new BusinessException(
                    "The new time slot is temporarily held by another customer completing checkout. "
                    + "Please try again in a few minutes or pick a different slot.");
            }
        }

        // Capacity check for new slot — static ceiling only for a room-less booking. When the
        // booking occupies a room, the per-room conflict check above already bounds capacity
        // to the number of rooms, so the static maxConcurrentBookings must not double-cap it.
        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        if (rescheduleRoomId == null && binge != null && binge.getMaxConcurrentBookings() != null) {
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
            BigDecimal newBaseAmount = PricingService.computeBaseAmount(eventPrice, newDurMin);
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
            booking.setTotalAmount(PricingService.applySurge(preSurgeTotal, newSurge.multiplier()));
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
            TaxContext taxCtxResched = buildBookingTaxContext(booking.getBingeId(),
                booking.getScheduledDurationMinutes());
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
                int roomOccupancy = countRoomBookings(room.getId(), request.getNewBookingDate(),
                    OccupancyWindow.of(newStartMinute, newDurMin,
                        booking.getSetupMinutes(), booking.getCleanupMinutes()),
                    booking.getId());
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

        // Anti-abuse: same limits as single booking creation — per-binge scope and
        // per-binge admin-configured threshold, matching createBooking exactly.
        long pendingCount = bookingRepository.countPendingByCustomerIdAndBingeId(customerId, bingeId);
        int unpaidLimit = effectiveUnpaidLimit(bingeId);
        if (pendingCount >= unpaidLimit) {
            throw new BusinessException(
                "You already have " + pendingCount + " unpaid booking(s) at this venue. "
                + "Open My Bookings to complete payment or cancel them, then try again.");
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
        // V81: one buffer resolution for the whole series — every occurrence of a
        // recurring booking must occupy an identically shaped window.
        TurnoverPolicy.Buffers recurringBuffers = turnoverPolicy.resolve(bingeId, eventType);
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
                OccupancyWindow recurringWindow = recurringBuffers.windowFor(startMinute, durMin);
                if (hasTimeConflict(date, recurringWindow)) {
                    skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                        .date(date).reason("Time slot conflicts with existing booking").build());
                    continue;
                }

                // Capacity check
                Binge binge = bingeRepository.findById(bingeId).orElse(null);
                if (binge != null && binge.getMaxConcurrentBookings() != null) {
                    int overlapping = countOverlappingBookings(date, recurringWindow);
                    if (overlapping >= binge.getMaxConcurrentBookings()) {
                        skipped.add(RecurringBookingResult.SkippedOccurrence.builder()
                            .date(date).reason("Slot at capacity").build());
                        continue;
                    }
                }

                // Calculate pricing
                PricingService.ResolvedEventPrice eventPrice = pricingService.resolveEventPrice(customerId, request.getEventTypeId());
                BigDecimal baseAmount = PricingService.computeBaseAmount(eventPrice, durMin);

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
                BigDecimal guestAmount = PricingService.computeGuestAmount(eventPrice, guests);
                BigDecimal totalAmount = baseAmount.add(addOnTotal).add(guestAmount);

                // Apply surge pricing per occurrence date/time
                BigDecimal surgeMultiplier = null;
                String surgeLabel = null;
                PricingService.SurgeResult surge = pricingService.resolveSurge(date, request.getStartTime());
                if (surge != null) {
                    surgeMultiplier = surge.multiplier();
                    surgeLabel = surge.label();
                    totalAmount = PricingService.applySurge(totalAmount, surgeMultiplier);
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
                        int roomOccupancy = countRoomBookings(room.getId(), date, recurringWindow);
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
                TaxContext taxCtxRec = buildBookingTaxContext(bingeId, durMin);
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
                    .origin(com.skbingegalaxy.booking.domain.BookingOrigin.DIRECT)
                    .setupMinutes(recurringBuffers.setupMinutes())
                    .cleanupMinutes(recurringBuffers.cleanupMinutes())
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
        return toDtos(bookings);
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

        return toDtos(completed.stream()
            .filter(b -> bookingReviewRepository
                .findByBookingRefAndCustomerIdAndReviewerRole(b.getBookingRef(), customerId, "CUSTOMER")
                .isEmpty())
            .toList());
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

    // ── Public: room review summary + list (derived from bookings in that room) ──
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getRoomReviewSummary(Long roomId) {
        long count = bookingReviewRepository.countRoomCustomerReviews(roomId);
        double avg = count > 0 ? bookingReviewRepository.averageRoomRating(roomId) : 0;
        java.util.Map<Integer, Long> distribution = new java.util.LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) distribution.put(star, 0L);
        for (Object[] row : bookingReviewRepository.roomRatingDistribution(roomId)) {
            distribution.put((Integer) row[0], (Long) row[1]);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("roomId", roomId);
        out.put("averageRating", Math.round(avg * 10.0) / 10.0);
        out.put("totalReviews", count);
        out.put("ratingDistribution", distribution);
        return out;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<BookingReviewDto> getRoomPublicReviews(
            Long roomId, org.springframework.data.domain.Pageable pageable) {
        return bookingReviewRepository.findRoomCustomerReviews(roomId, pageable).map(this::toReviewDto);
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

        // ── BOOK-004: captured money is returned through REAL refunds ───────
        // The refund owed is refundPercentage of the COLLECTED amount (money
        // actually held), not of the list price. We no longer zero the
        // collected amount locally — payment-service receives refundAmount on
        // the booking.cancelled event, moves the money at the gateway, and the
        // resulting payment.refunded events are what reduce collectedAmount
        // here. Until they settle, the booking truthfully shows the money as
        // still collected, with the refund timeline visible to the customer.
        java.math.BigDecimal collected = saved.getCollectedAmount() != null
            ? saved.getCollectedAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal moneyRefund = collected.compareTo(java.math.BigDecimal.ZERO) > 0
            ? collected.multiply(BigDecimal.valueOf(refundPercentage))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : java.math.BigDecimal.ZERO;
        if (collected.compareTo(java.math.BigDecimal.ZERO) > 0) {
            log.info("Cancellation of {}: {}% of collected {} → refund {} requested via payment-service",
                bookingRef, refundPercentage, collected, moneyRefund);
            try {
                eventLogService.logEvent(saved, BookingEventType.REFUND_INITIATED,
                    saved.getStatus().name(), null, actorRole,
                    String.format("Cancellation refund of %s (%d%% of collected %s) requested",
                        moneyRefund, refundPercentage, collected));
            } catch (Exception ex) {
                log.warn("Timeline log for cancellation refund request failed for {}: {}",
                    bookingRef, ex.getMessage());
            }
        }

        // Loyalty reversal (both REVERSE_REDEEM and REVERSE_EARN) is handled
        // proportionally by LoyaltyV2BookingListener.onBookingCancelled, which
        // fires AFTER_COMMIT against the v2 wallet ledger.  Nothing to do here.

        // Cancellation audit row was emitted by BookingStateMachine.transition
        // above (with reason / IP / User-Agent). Here we only publish the
        // outbound Kafka event so notification-service can react and
        // payment-service can issue the policy-decided refund.
        publishBookingEvent(saved, KafkaTopics.BOOKING_CANCELLED, moneyRefund);
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
        return toDtoPage(bid != null ? bookingRepository.findByBingeIdAndPaymentStatus(bid, PaymentStatus.PENDING, pageable) : bookingRepository.findByPaymentStatus(PaymentStatus.PENDING, pageable));
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
                OccupancyWindow occ = TurnoverPolicy.windowOf(b, effMin);
                return BookedSlotDto.builder()
                    .startHour(b.getStartTime().getHour())
                    .durationHours(effMin / 60)
                    .startMinute(startMin)
                    .durationMinutes(effMin)
                    .occupancyStartMinute(occ.startMinute())
                    .occupancyEndMinute(occ.endMinute())
                    .bookingRef(b.getBookingRef())
                    .venueRoomId(b.getVenueRoomId())
                    .build();
            })
            .filter(slot -> slot.getDurationMinutes() > 0)
            .toList();
    }

    // â”€â”€ Check for time overlap with existing bookings (minutes-based) â”€â”€
    @Transactional(readOnly = true)
    public boolean hasTimeConflict(LocalDate date, OccupancyWindow candidate) {
        return hasTimeConflict(date, candidate, null);
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
                                            Long eventTypeId,
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
        validateWithinOperatingHours(bingeId, date, startTime, durationMinutes);

        // V84: a hold must see exactly the same booking-window and duration rules as
        // the booking it becomes. Letting a hold through that the booking would reject
        // strands the customer at the end of a countdown they were never going to win.
        Binge windowBinge = bingeRepository.findById(bingeId).orElse(null);
        bookingWindowPolicy.assertWithinBookingWindow(windowBinge, date, startTime, maxBookingHorizonDays);

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

        // V81: the hold must reserve the same occupancy window the resulting
        // booking will claim, or the customer completes a countdown only to be
        // rejected by a turnover rule the hold never checked.
        EventType holdEventType = eventTypeId != null
            ? eventTypeRepository.findById(eventTypeId).orElse(null)
            : null;
        TurnoverPolicy.Buffers holdBuffers = turnoverPolicy.resolve(bingeId, holdEventType);
        OccupancyWindow holdWindow = holdBuffers.windowFor(startMinute, durationMinutes);

        if (hasTimeConflict(date, holdWindow)) {
            throw new BusinessException(holdBuffers.isZero()
                ? "Selected time slot conflicts with an existing booking"
                : "Selected time slot conflicts with an existing booking or its setup/cleanup time");
        }

        Binge binge = bingeRepository.findById(bingeId).orElse(null);
        Integer maxConcurrent = binge != null ? binge.getMaxConcurrentBookings() : null;
        int existingBookings = countOverlappingBookings(date, holdWindow);

        int liveHoldOverlap = 0;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
            for (com.skbingegalaxy.booking.entity.SlotHold h :
                    slotHoldRepository.findLiveHoldsByBingeAndDate(bingeId, date, now)) {
                if (excludeHoldToken != null && excludeHoldToken.equals(h.getHoldToken())) continue;
                if (holdWindow.overlaps(TurnoverPolicy.windowOf(h))) {
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
    private void validateWithinOperatingHours(Long bingeId, LocalDate bookingDate, LocalTime startTime, int durationMinutes) {
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
                // Per-day override (V66): when the binge publishes a per-day schedule and
                // this booking's weekday is configured, it wins over the single open/close
                // pair. A day marked closed rejects the booking outright.
                if (bookingDate != null && binge.getOpeningHoursJson() != null) {
                    var dayHours = com.skbingegalaxy.booking.util.OpeningHoursCodec
                        .forDay(binge.getOpeningHoursJson(), bookingDate.getDayOfWeek());
                    if (dayHours.isPresent()) {
                        var dh = dayHours.get();
                        String dayName = bookingDate.getDayOfWeek()
                            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
                        if (dh.isClosed()) {
                            throw new BusinessException(
                                "This venue is closed on " + dayName + "s. Please pick another day.");
                        }
                        if (dh.getOpenTime() != null) openTime = dh.getOpenTime();
                        if (dh.getCloseTime() != null) closeTime = dh.getCloseTime();
                    }
                }
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
        // BOOK-003: defence-in-depth against a directly-submitted phantom slot. On a
        // DST spring-forward day the venue's wall clock skips an hour, so a start time
        // in that gap names a LOCAL instant that never occurs; booking it would silently
        // shift the real time by the DST offset. The availability grid already omits such
        // slots, but a direct API call could still carry one. No-op for non-DST venues
        // (getTransition returns null), so IST bookings are byte-for-byte unchanged.
        if (bingeId != null && bookingDate != null) {
            ZoneId venueZone = venueClock.zoneOf(bingeId);
            java.time.zone.ZoneOffsetTransition dstTransition =
                venueZone.getRules().getTransition(java.time.LocalDateTime.of(bookingDate, startTime));
            if (dstTransition != null && dstTransition.isGap()) {
                throw new BusinessException(
                    "Selected start time " + startTime + " does not exist on " + bookingDate
                        + " at this venue due to a daylight-saving change. Please pick another time.");
            }
        }
    }

    @Transactional(readOnly = true)
    /**
     * V81: conflict detection compares {@link OccupancyWindow}s, not billable
     * intervals. {@code candidate} must already include the incoming
     * reservation's own setup/cleanup buffers; each existing booking is widened
     * by its own <em>snapshotted</em> buffers. Widening only one side would let
     * an existing booking's turnover time be sold away.
     */
    public boolean hasTimeConflict(LocalDate date, OccupancyWindow candidate, Long excludeBookingId) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
        for (Booking b : activeBookings) {
            if (excludeBookingId != null && excludeBookingId.equals(b.getId())) continue;
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue; // fully freed early checkout
            if (candidate.overlaps(TurnoverPolicy.windowOf(b, effectiveDuration))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Physical-occupancy guard applied at the moment of check-in. A room can hold
     * its {@code capacity} of simultaneously present parties; a room-less venue is
     * a single physical space (capacity 1). If the resource is already at capacity
     * with currently CHECKED_IN reservations, the new check-in is rejected — you
     * cannot put two parties in the same room.
     *
     * <p>Serialized with the same per-binge/date advisory slot lock used at
     * booking creation so two concurrent check-ins can't both slip past the count.
     */
    private void enforceCheckInOccupancy(Booking booking) {
        Long bid = booking.getBingeId();
        LocalDate date = booking.getBookingDate();
        Long excludeId = booking.getId() != null ? booking.getId() : -1L;
        if (bid != null) {
            bookingRepository.acquireSlotLock(slotLockKey(bid, date));
        }
        Long roomId = booking.getVenueRoomId();
        if (roomId != null) {
            VenueRoom room = venueRoomRepository.findById(roomId).orElse(null);
            int capacity = room != null ? Math.max(room.getCapacity(), 1) : 1;
            long occupied = bookingRepository.countActiveCheckInsInRoom(bid, date, roomId, excludeId);
            if (occupied >= capacity) {
                String name = room != null ? room.getName() : ("room " + roomId);
                throw new BusinessException(capacity == 1
                    ? "Room '" + name + "' is already occupied by a checked-in reservation. "
                        + "Check the current guests out before checking another party in."
                    : "Room '" + name + "' is at capacity (" + capacity
                        + " checked-in reservations). Check a party out before checking another in.");
            }
        } else {
            long occupied = bookingRepository.countActiveCheckInsInVenue(bid, date, excludeId);
            if (occupied >= 1) {
                throw new BusinessException(
                    "This venue is already occupied by a checked-in reservation. "
                    + "Check the current guests out before checking another party in.");
            }
        }
    }

    /**
     * Resolve which venue room a booking should occupy, enforcing exclusivity + capacity.
     *
     * <p>Three cases:
     * <ul>
     *   <li><b>Explicit room</b> — the customer/admin picked one: validate it exists, is
     *       active + approved, and is free for the overlapping window (else reject).</li>
     *   <li><b>"Any room" at a venue that HAS rooms</b> — auto-assign the first available
     *       room (lowest sort order). If every room is occupied for the window, reject with
     *       a capacity message. This is what makes "N rooms → at most N concurrent bookings"
     *       hold: every booking is pinned to a specific exclusive room, so the per-room
     *       check-in guard (not the venue-wide fallback) applies and cross-room check-ins
     *       never block each other.</li>
     *   <li><b>Room-less venue</b> — no rooms configured: return null (booking has no room),
     *       honouring the per-binge {@code roomSelectionRequired} toggle only if a room was
     *       required but none exists to pick.</li>
     * </ul>
     */
    private VenueRoom resolveRoomAssignment(Long bingeId, Long requestedRoomId,
                                            LocalDate date, OccupancyWindow candidate) {
        return resolveRoomAssignment(bingeId, requestedRoomId, date, candidate, null);
    }

    private VenueRoom resolveRoomAssignment(Long bingeId, Long requestedRoomId,
                                            LocalDate date, OccupancyWindow candidate,
                                            Long bookingCustomerId) {
        if (requestedRoomId != null) {
            VenueRoom room = venueRoomRepository.findByIdAndBingeId(requestedRoomId, bingeId)
                .orElseThrow(() -> new BusinessException("Selected room not found"));
            if (!room.isActive()) throw new BusinessException("Selected room is currently unavailable");
            if (room.getStatus() != null && room.getStatus() != com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED) {
                throw new BusinessException("Selected room is not yet approved for bookings");
            }
            int roomOccupancy = countRoomBookings(room.getId(), date, candidate)
                + countForeignLiveHoldOverlap(bingeId, date, candidate, bookingCustomerId, room.getId());
            if (roomOccupancy >= Math.max(room.getCapacity(), 1)) {
                throw new BusinessException("Selected room '" + room.getName() + "' is fully booked or held for this time slot");
            }
            return room;
        }

        // "Any room" — find the bookable rooms for this venue and auto-assign a free one.
        List<VenueRoom> rooms = venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bingeId).stream()
            .filter(VenueRoom::isActive)
            .filter(r -> r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)
            .toList();

        if (rooms.isEmpty()) {
            // Genuinely room-less venue. Only block if the venue insists a room be picked.
            Binge bingeCfg = bingeRepository.findById(bingeId).orElse(null);
            if (bingeCfg != null && bingeCfg.isRoomSelectionRequired()) {
                throw new BusinessException("This binge requires a room to be selected before booking");
            }
            return null;
        }

        for (VenueRoom room : rooms) {
            int occ = countRoomBookings(room.getId(), date, candidate)
                + countForeignLiveHoldOverlap(bingeId, date, candidate, bookingCustomerId, room.getId());
            if (occ < Math.max(room.getCapacity(), 1)) {
                return room; // first free room wins (deterministic by sort order)
            }
        }
        // Every room is occupied for the overlapping window → at capacity.
        throw new BusinessException("CAPACITY_FULL:All " + rooms.size()
            + " room(s) are booked for this time slot. You can join the waitlist to be notified when a spot opens up.");
    }

    /**
     * Overlapping live holds owned by customers OTHER than {@code bookingCustomerId}
     * — reservations a direct booking must not steal (BOOK-001). When {@code roomId}
     * is non-null only holds pinned to that room count; when null, every overlapping
     * foreign live hold counts (pinned or not).
     */
    private int countForeignLiveHoldOverlap(Long bingeId, LocalDate date, OccupancyWindow candidate,
                                            Long bookingCustomerId, Long roomId) {
        int n = 0;
        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
            for (com.skbingegalaxy.booking.entity.SlotHold h :
                    slotHoldRepository.findLiveHoldsByBingeAndDate(bingeId, date, now)) {
                if (bookingCustomerId != null && bookingCustomerId.equals(h.getCustomerId())) continue;
                if (roomId != null && !roomId.equals(h.getVenueRoomId())) continue;
                // V81: a hold reserves its buffered window too, otherwise the countdown
                // promises a slot that a competing booking's cleanup time already owns.
                if (candidate.overlaps(TurnoverPolicy.windowOf(h))) n++;
            }
        } catch (Exception e) {
            // Fail-open matches assertSlotAvailableForHold: a hold-lookup outage
            // must not block bookings; the advisory lock still prevents physical
            // double-booking.
            log.warn("Foreign-hold overlap check failed for binge={} date={}: {}", bingeId, date, e.getMessage());
        }
        return n;
    }

    /** Count active bookings that overlap with a given time range (for capacity enforcement). */
    @Transactional(readOnly = true)
    public int countOverlappingBookings(LocalDate date, OccupancyWindow candidate) {
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsByDate(date);
        int count = 0;
        for (Booking b : activeBookings) {
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            if (candidate.overlaps(TurnoverPolicy.windowOf(b, effectiveDuration))) {
                count++;
            }
        }
        return count;
    }

    /** Count active bookings assigned to a specific venue room for a given time slot. */
    private int countRoomBookings(Long roomId, LocalDate date, OccupancyWindow candidate) {
        return countRoomBookings(roomId, date, candidate, null);
    }

    /**
     * V57: returns true when the occupancy window on {@code date} overlaps any
     * maintenance / hold block on {@code roomId}. Resolved at request time so
     * newly-added blocks immediately gate availability.
     *
     * <p>V81: the window is the buffered one — a room being cleaned into a
     * maintenance block is still a clash, and staff cannot reset a room during
     * maintenance any more than they can host in it.
     *
     * <p>The window may extend outside the calendar day (a booking starting
     * within its own setup buffer of midnight, or ending within its cleanup
     * buffer of it). {@code atTime} cannot represent that, so the timestamps are
     * built from midnight plus an offset, which handles negative and &gt;1440
     * minute values correctly.
     */
    private boolean isRoomBlocked(Long roomId, LocalDate date, OccupancyWindow window) {
        if (roomId == null || date == null) return false;
        java.time.LocalDateTime dayStart = date.atStartOfDay();
        java.time.LocalDateTime windowStart = dayStart.plusMinutes(window.startMinute());
        java.time.LocalDateTime windowEnd = dayStart.plusMinutes(Math.max(window.endMinute(), window.startMinute() + 1));
        return !roomBlockRepository.findOverlapping(roomId, windowStart, windowEnd).isEmpty();
    }

    /**
     * Count active bookings whose occupancy window overlaps {@code candidate} in a
     * specific venue room, optionally excluding one booking.
     *
     * <p>V81: both sides are buffered — {@code candidate} carries the incoming
     * reservation's buffers, each existing row carries its own snapshot.
     */
    private int countRoomBookings(Long roomId, LocalDate date, OccupancyWindow candidate, Long excludeBookingId) {
        // V57: a maintenance / hold block covering this slot makes the room fully unavailable.
        if (isRoomBlocked(roomId, date, candidate)) {
            VenueRoom room = venueRoomRepository.findById(roomId).orElse(null);
            return room != null ? room.getCapacity() : Integer.MAX_VALUE;
        }
        Long bid = BingeContext.getBingeId();
        List<Booking> activeBookings = bid != null
            ? bookingRepository.findActiveBookingsForReadByBingeAndDate(bid, date)
            : bookingRepository.findActiveBookingsForReadByDate(date);
        int count = 0;
        for (Booking b : activeBookings) {
            if (excludeBookingId != null && excludeBookingId.equals(b.getId())) continue;
            if (!roomId.equals(b.getVenueRoomId())) continue;
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            if (candidate.overlaps(TurnoverPolicy.windowOf(b, effectiveDuration))) {
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
     * the persist path consistent with the public tax-preview endpoint.
     */
    private TaxContext buildBookingTaxContext(Long bingeId, Integer durationMinutes) {
        // Single source of truth lives in TaxService so the customer preview,
        // checkout quote and the persisted booking all resolve the SAME context.
        // durationMinutes drives FLAT_PER_HOUR tax rules (per-hour occupancy fees).
        return taxService.venueContext(bingeId)
            .durationMinutes(durationMinutes)
            .build();
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
        // V81: the probe window carries the venue's default buffers. This is a
        // capacity *read* with no event type in scope, so the venue default is
        // the most accurate answer available — an event type with a larger
        // override will be caught by the write-path guards.
        OccupancyWindow probeWindow = OccupancyWindow.of(startMinute, durationMinutes,
            binge != null ? binge.getDefaultSetupMinutes() : 0,
            binge != null ? binge.getDefaultCleanupMinutes() : 0);
        int overlapping = 0;
        for (Booking b : activeBookings) {
            int effectiveDuration = getEffectiveDurationMinutes(b);
            if (effectiveDuration == 0) continue;
            if (probeWindow.overlaps(TurnoverPolicy.windowOf(b, effectiveDuration))) {
                overlapping++;
            }
        }
        // Live holds ARE occupancy: a slot reserved for a customer mid-checkout
        // (or a promoted waitlist entry) must read as taken, or the waitlist
        // promoter would offer the same physical slot twice.
        int liveHolds = countForeignLiveHoldOverlap(bingeId, date, probeWindow, null, null);
        Integer max = binge != null ? binge.getMaxConcurrentBookings() : null;
        return java.util.Map.of(
            "currentBookings", overlapping,
            "liveHolds", liveHolds,
            "maxConcurrentBookings", max != null ? max : -1,
            "isFull", max != null && (overlapping + liveHolds) >= max
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
        return b.getScheduledDurationMinutes();
    }

    /**
     * V85/G2 — ingest a reservation delivered by an external sales channel.
     *
     * <p>Deliberately a thin adapter over {@link #createBooking}: the channel path
     * must not become a second booking truth with its own subtly-different rules.
     * Everything that protects the venue — approval status, the advisory slot lock,
     * occupancy windows and turnover buffers, room capacity, operating hours, the
     * booking window, the database backstop — runs exactly as it does for a customer.
     * Only the customer-funnel anti-abuse guards are skipped, because a channel guest
     * has no funnel (see {@link #applyCustomerFunnelGuards}).
     *
     * <p><b>Guest identity.</b> The guest has no SK account, so the reservation is
     * attributed to {@code customerId = 0} — the same convention
     * {@code adminCreateBooking} already uses for walk-ins — with the channel's guest
     * details snapshotted onto the booking. No user account is created: a channel
     * guest never authenticates, and minting accounts for them would silently build a
     * PII estate nobody asked for.
     *
     * <p><b>Duplicate delivery.</b> Channels retry. The unique index on
     * {@code (external_source, external_ref)} makes a redelivered reservation a
     * conflict rather than a second booking; this method turns that into an explicit
     * idempotent response by returning the existing booking.
     */
    /**
     * Outcome of an ingestion attempt. The distinction is not cosmetic: a channel
     * that receives 201 for a redelivery will record a second booking on its side and
     * the two systems drift apart. {@code CREATED} vs {@code ALREADY_EXISTED} lets the
     * controller answer 201 vs 200 truthfully.
     */
    public record ChannelIngestResult(BookingDto booking, boolean created) {}

    /** @see #ingestChannelReservationDetailed(ChannelReservationRequest) */
    @Transactional(timeout = 15)
    public BookingDto ingestChannelReservation(ChannelReservationRequest request) {
        return ingestChannelReservationDetailed(request).booking();
    }

    @Transactional(timeout = 15)
    public ChannelIngestResult ingestChannelReservationDetailed(ChannelReservationRequest request) {
        // Normally already canonical — the DTO setters do this on deserialization.
        // Repeated here (idempotently) because this method is also reachable from
        // in-process callers that construct the request with a builder, which bypasses
        // setters. One definition of "canonical", used by every path.
        String source = ChannelReservationRequest.canonicalSource(request.getExternalSource());
        String ref = request.getExternalRef() == null ? null : request.getExternalRef().trim();

        // Idempotent by contract: a redelivered reservation returns the original
        // rather than erroring, so a channel's retry logic converges instead of
        // escalating to a support ticket.
        var existing = bookingRepository.findByExternalSourceAndExternalRef(source, ref);
        if (existing.isPresent()) {
            log.info("Channel reservation {}:{} already ingested as {} — returning existing",
                source, ref, existing.get().getBookingRef());
            return new ChannelIngestResult(toDto(existing.get()), false);
        }

        // The channel names the venue explicitly; there is no user session or
        // X-Binge-Id header to derive it from. Setting it here keeps every
        // downstream binge-scoped query (availability, rooms, pricing, tax) working
        // through the same BingeContext the customer path uses.
        Long previousBinge = BingeContext.getBingeId();
        try {
            BingeContext.setBingeId(request.getBingeId());

            CreateBookingRequest inner = CreateBookingRequest.builder()
                .eventTypeId(request.getEventTypeId())
                .bookingDate(request.getBookingDate())
                .startTime(request.getStartTime())
                .durationMinutes(request.getDurationMinutes())
                .durationHours(request.getDurationMinutes() / 60)
                .numberOfGuests(Math.max(request.getNumberOfGuests(), 1))
                .addOns(request.getAddOns())
                .specialNotes(request.getSpecialNotes())
                .venueRoomId(request.getVenueRoomId())
                .build();

            BookingDto created = createBooking(inner,
                CHANNEL_GUEST_CUSTOMER_ID,
                request.getGuestName(),
                request.getGuestEmail() != null ? request.getGuestEmail() : "",
                request.getGuestPhone() != null ? request.getGuestPhone() : "",
                request.getGuestPhoneCountryCode(),
                com.skbingegalaxy.booking.domain.BookingOrigin.CHANNEL,
                source, ref);
            return new ChannelIngestResult(created, true);
        } finally {
            // Restore rather than clear: this runs inside whatever context the caller
            // had, and leaking a binge id across a pooled thread would be a tenancy bug.
            if (previousBinge != null) {
                BingeContext.setBingeId(previousBinge);
            } else {
                BingeContext.clear();
            }
        }
    }

    /**
     * Reservations from a channel carry no SK customer account. Zero is the existing
     * convention for "no known customer" ({@code adminCreateBooking} uses it for
     * walk-ins), so loyalty, per-customer pricing and customer-scoped queries all
     * already treat it as anonymous.
     */
    private static final long CHANNEL_GUEST_CUSTOMER_ID = 0L;

    /**
     * V85/G3 — the anti-abuse guards that only make sense against a self-service
     * customer funnel: concurrent-unpaid limits, the auto-cancel cooldown, and the
     * per-binge customer freeze.
     *
     * <p><b>Why this is origin-scoped rather than always-on.</b> Each of these
     * protects the booking funnel from a customer misusing it. An external channel
     * reservation arrives already paid, from a guest with no SK account, through a
     * synthetic customer identity — there is no funnel to abuse, and applying them
     * would reject real paid business for reasons that cannot apply. A venue would
     * see the reservation simply never arrive.
     *
     * <p><b>This is not a trust bypass.</b> Everything that protects the venue's
     * physical reality still runs for every origin, unconditionally: binge approval
     * ({@code assertBingeBookable}), the advisory slot lock, occupancy windows and
     * turnover buffers, room capacity, operating hours, the booking window, and the
     * V81 database backstop. The only thing skipped is "has this customer been
     * behaving badly in our funnel", which is meaningless without a funnel.
     */
    private void applyCustomerFunnelGuards(com.skbingegalaxy.booking.domain.BookingOrigin origin,
                                           Long customerId, Long bingeId, int unpaidLimit) {
        if (!origin.customerFunnelGuardsApply()) {
            log.debug("Skipping customer-funnel guards for origin={} binge={}", origin, bingeId);
            return;
        }

        // Concurrent PENDING bookings per customer **per binge**. Per-binge scope
        // prevents a customer with pending payments at venue A from being blocked at
        // venue B. The threshold is the venue admin's decision
        // (binge.maxUnpaidBookingsPerCustomer), falling back to the platform default
        // only if the binge row is unavailable.
        long pendingCount = bookingRepository.countPendingByCustomerIdAndBingeId(customerId, bingeId);
        if (pendingCount >= unpaidLimit) {
            throw new BusinessException(
                "You already have " + pendingCount + " unpaid booking(s) at this venue. "
                + "Open My Bookings to complete payment or cancel them (unpaid bookings can "
                + "always be cancelled free of charge), then try again.");
        }

        // Cooldown after auto-cancelled (timed-out) bookings — also per binge.
        LocalDateTime cooldownSince = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(cooldownMinutesAfterTimeout);
        long recentTimeouts = bookingRepository.countRecentTimeoutCancellationsByBinge(customerId, bingeId, cooldownSince);
        if (recentTimeouts >= 2) {
            throw new BusinessException(
                "Too many unpaid bookings were auto-cancelled at this venue recently. Please wait a few minutes before trying again.");
        }

        // Per-binge customer freeze (raises 423 LOCKED if active).
        customerFreezeService.assertNotFrozen(customerId, bingeId);
    }

    /**
     * Resolve a duration from an <b>inbound request DTO</b>, which may legitimately
     * supply either field. For a persisted {@link Booking} use
     * {@link Booking#getScheduledDurationMinutes()} instead — V82 made that the single
     * canonical accessor, and this overload must not become a second one.
     */
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

    /**
     * The venue admin's configured cap on concurrent unpaid PENDING bookings per
     * customer (see {@link Binge#getMaxUnpaidBookingsPerCustomer()}). Falls back to
     * the platform default when the binge row is unavailable; a stored value below 1
     * (legacy rows) also falls back rather than locking everyone out.
     */
    private int effectiveUnpaidLimit(Long bingeId) {
        Binge binge = bingeId != null ? bingeRepository.findById(bingeId).orElse(null) : null;
        if (binge != null && binge.getMaxUnpaidBookingsPerCustomer() >= 1) {
            return binge.getMaxUnpaidBookingsPerCustomer();
        }
        return Math.max(1, maxPendingPerCustomer);
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
    /**
     * @param clientNow retained for API compatibility but IGNORED for time math —
     *                  the operator's browser clock is untrusted and zone-ambiguous;
     *                  the venue clock is the single time authority.
     */
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
            String ccy = booking.getCurrencyCode() != null ? booking.getCurrencyCode() : "INR";
            String direction = balance.signum() > 0
                ? "Outstanding balance of " + ccy + " " + balance.toPlainString() + " must be collected"
                : "Customer overpaid by " + ccy + " " + balance.abs().toPlainString() + "; issue refund";
            throw new BusinessException(
                "Cannot checkout — balance not settled. " + direction
                + " before checkout. Use \"Adjust Prices\" or the Payment tab to reconcile.");
        }

        // ── Venue-zone time authority ─────────────────────────────────────
        // All duration math runs on absolute instants; wall-clock strings shown
        // to humans are rendered in the VENUE's zone. The client-supplied clock
        // (clientNow) is deliberately NOT used for time math — an operator's
        // browser zone/skew must never decide whether a checkout is "early"
        // (mixing it with venue-local schedule times produced artifacts like
        // "Early checkout at 06:33 AM (checked in 11:29 AM). Used 0m").
        java.time.ZoneId venueZone = venueClock.zoneOf(booking.getBingeId());
        java.time.Instant nowInstant = java.time.Instant.now();

        // bookingDate/startTime are venue-local wall times (platform contract);
        // actualCheckInTime is a UTC instant (see the check-in path).
        java.time.Instant scheduledStartInstant = LocalDateTime
            .of(booking.getBookingDate(), booking.getStartTime())
            .atZone(venueZone).toInstant();
        int bookedMinutes = booking.getScheduledDurationMinutes();
        java.time.Instant scheduledEndInstant = scheduledStartInstant.plusSeconds(bookedMinutes * 60L);
        java.time.Instant sessionStartInstant = booking.getActualCheckInTime() != null
            ? booking.getActualCheckInTime().toInstant(ZoneOffset.UTC)
            : scheduledStartInstant;

        // Stored form of "now" — UTC, matching actualCheckInTime's contract.
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC);

        // Only treat as early if the real current instant is before scheduled end
        if (!nowInstant.isBefore(scheduledEndInstant)) {
            // Not early — do a normal checkout, but still record actual session duration
            long fullSessionMinutes = java.time.Duration.between(sessionStartInstant, nowInstant).toMinutes();
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

        long usedMinutes = java.time.Duration.between(sessionStartInstant, nowInstant).toMinutes();
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

        // Human-readable times are rendered in the venue's zone — the note is a
        // wall-clock statement about what happened AT the venue.
        java.time.format.DateTimeFormatter venueTimeFmt =
            java.time.format.DateTimeFormatter.ofPattern("hh:mm a").withZone(venueZone);
        String checkInDisplay = booking.getActualCheckInTime() != null
            ? venueTimeFmt.format(booking.getActualCheckInTime().toInstant(ZoneOffset.UTC))
            : booking.getStartTime().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"));
        String note = String.format(
            "Early checkout at %s (checked in %s). Used %s of %s booked.",
            venueTimeFmt.format(nowInstant), checkInDisplay,
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
            validateWithinOperatingHours(adminBingeIdForHours, request.getBookingDate(), request.getStartTime(), durMin);
        }

        // Check for double-booking with existing reservations
        int startMinute = request.getStartTime().getHour() * 60 + request.getStartTime().getMinute();
        Long adminBingeId = BingeContext.getBingeId();
        if (adminBingeId != null) {
            bookingRepository.acquireSlotLock(slotLockKey(adminBingeId, request.getBookingDate()));
        }
        // Room-aware: only a room-LESS venue is a single space where any overlap conflicts.
        // A multi-room venue allows concurrent bookings in different rooms (per-room
        // exclusivity is enforced by resolveRoomAssignment below).
        boolean adminVenueHasBookableRooms = adminBingeId != null
            && venueRoomRepository.findByBingeIdOrderBySortOrderAsc(adminBingeId).stream()
                .anyMatch(r -> r.isActive()
                    && (r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED));
        // V81: an admin walk-in occupies the same buffered window as any other
        // reservation. Admins are trusted to bend policy deliberately (via the
        // venue's buffer configuration), not to bypass it accidentally.
        TurnoverPolicy.Buffers adminBuffers = turnoverPolicy.resolve(adminBingeId, eventType);
        OccupancyWindow adminWindow = adminBuffers.windowFor(startMinute, durMin);
        if (!adminVenueHasBookableRooms && hasTimeConflict(request.getBookingDate(), adminWindow)) {
            throw new BusinessException(adminBuffers.isZero()
                ? "Selected time slot conflicts with an existing booking"
                : "Selected time slot conflicts with an existing booking or its setup/cleanup time");
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
        if (adminBingeId != null) {
            // Same unified assignment as the customer path: explicit room is validated for
            // availability; "any room" auto-assigns a free exclusive room (so every booking
            // at a multi-room venue has a room and the per-room check-in guard applies).
            VenueRoom adminRoom = resolveRoomAssignment(
                adminBingeId, request.getVenueRoomId(), request.getBookingDate(), adminWindow);
            if (adminRoom != null) {
                adminVenueRoomId = adminRoom.getId();
                adminVenueRoomName = adminRoom.getName();
                adminVenueRoomPrice = adminRoom.getPriceAddition() != null ? adminRoom.getPriceAddition() : BigDecimal.ZERO;
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

        BigDecimal baseAmount = PricingService.computeBaseAmount(eventPrice, durMin);

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
        BigDecimal guestAmount = PricingService.computeGuestAmount(eventPrice, adminGuests);

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
                .origin(com.skbingegalaxy.booking.domain.BookingOrigin.ADMIN)
                .setupMinutes(adminBuffers.setupMinutes())
                .cleanupMinutes(adminBuffers.cleanupMinutes())
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
        TaxContext taxCtxAdmin = buildBookingTaxContext(BingeContext.getBingeId(), durMin);
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
            .origin(com.skbingegalaxy.booking.domain.BookingOrigin.ADMIN)
            .setupMinutes(adminBuffers.setupMinutes())
            .cleanupMinutes(adminBuffers.cleanupMinutes())
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

    /** Widest reporting range a single call may request. */
    public static final int MAX_ATTRIBUTION_RANGE_DAYS = 366;

    /**
     * Conversions per marketing source for the selected venue (distribution G-B).
     *
     * <p>Without this the attribution captured at checkout is write-only, and the Google
     * Things to Do business case stays unprovable — which is the whole reason slice 2
     * came before any connector.
     *
     * <p>Scoped through {@code requireSelectedBinge}, so it inherits the same
     * multi-tenant boundary as every other admin read. A reporting endpoint is exactly
     * where a missing scope check goes unnoticed: it returns plausible numbers either
     * way, and the numbers would silently be someone else's.
     */
    public List<com.skbingegalaxy.booking.dto.AttributionPerformanceDto> getAttributionPerformance(
            LocalDate from, LocalDate to) {
        Long bid = requireSelectedBinge("viewing channel attribution");

        if (from == null || to == null) {
            throw new BusinessException("Both 'from' and 'to' dates are required");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("'to' cannot be earlier than 'from'");
        }
        // Bounded because this is an aggregate over a table that only grows, and an
        // unbounded range is a cheap way for one authenticated admin to make the
        // database do unbounded work.
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_ATTRIBUTION_RANGE_DAYS) {
            throw new BusinessException(
                "Range too wide — request at most " + MAX_ATTRIBUTION_RANGE_DAYS + " days");
        }

        String currency = bingeRepository.findById(bid)
            .map(Binge::getCurrency)
            .filter(c -> c != null && !c.isBlank())
            .orElse(com.skbingegalaxy.booking.util.CountryCurrency.BASE);

        return bookingRepository.aggregateAttributionPerformance(bid, from, to).stream()
            .map(row -> com.skbingegalaxy.booking.dto.AttributionPerformanceDto.builder()
                .source((String) row[0])
                .bookings(((Number) row[1]).longValue())
                .cancelled(((Number) row[2]).longValue())
                .revenue(row[3] == null ? BigDecimal.ZERO : (BigDecimal) row[3])
                .currency(currency)
                .build())
            .toList();
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
            .setupMinutes(req.getSetupMinutes())
            .cleanupMinutes(req.getCleanupMinutes())
            .permittedDurationsCsv(bookingWindowPolicy.normaliseDurationsForSave(
                req.getPermittedDurations(), req.getMinHours(), req.getMaxHours()))
            .minGuests(req.getMinGuests())
            .maxGuests(req.getMaxGuests())
            .categoryId(resolveEventCategoryId(req.getCategoryId(), bid))
            .imageUrls(req.getImageUrls() != null ? req.getImageUrls() : new ArrayList<>())
            .active(true)
            .bingeId(bid)
            .build();
        EventTypeDto saved = toEventTypeDto(eventTypeRepository.save(et));
        // Approval workflow hook: stamp the binge as "operational" the first time an
        // event lands on it, so BingeGracePeriodScheduler leaves it alone.
        //
        // This was an INLINE COPY of BingeService.recordFirstEventIfNeeded, which left
        // that method with zero callers and the seeder implementing neither — venues
        // ended up with a full catalogue and a NULL flag, then got auto-paused. One
        // stamp point, called from every creation path.
        bingeService.recordFirstEventIfNeeded(bid);
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
        // V81: buffers change occupancy for FUTURE reservations only. Existing
        // bookings keep the buffers snapshotted on them, so tightening turnover
        // never retroactively invalidates a reservation the venue already sold.
        et.setSetupMinutes(req.getSetupMinutes());
        et.setCleanupMinutes(req.getCleanupMinutes());
        // V84 (B5): validated against the event type's NEW hour range, so widening the
        // range and adding a duration in one save works as the operator expects.
        et.setPermittedDurationsCsv(bookingWindowPolicy.normaliseDurationsForSave(
            req.getPermittedDurations(), req.getMinHours(), req.getMaxHours()));
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
        // V81: no event type is in scope on this read, so the venue default buffers
        // give the closest honest answer. The write path re-checks with the event
        // type's own override, so a room shown here can still be refused — better
        // that than showing a room the venue physically cannot turn around.
        Binge roomsBinge = bingeRepository.findById(bid).orElse(null);
        OccupancyWindow roomsWindow = OccupancyWindow.of(startMinute, durationMinutes,
            roomsBinge != null ? roomsBinge.getDefaultSetupMinutes() : 0,
            roomsBinge != null ? roomsBinge.getDefaultCleanupMinutes() : 0);
        return rooms.stream()
            // V56: only APPROVED rooms are bookable on the customer wizard.
            .filter(r -> r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)
            .map(room -> {
                int occ = countRoomBookings(room.getId(), date, roomsWindow);
                VenueRoomDto dto = toRoomDto(room);
                dto.setCurrentOccupancy(occ);
                dto.setAvailable(occ < room.getCapacity());
                return dto;
            }).toList();
    }

    /**
     * Rooms an admin may pick for a specific booking at check-in / room-change. Same as
     * {@link #getAvailableRooms} but excludes THIS booking's own occupancy, so the room it
     * already holds reads as available (and can be re-confirmed), and every room free for the
     * booking's window is offered.
     */
    @Transactional(readOnly = true)
    public List<VenueRoomDto> getAvailableRoomsForBooking(String bookingRef) {
        Booking booking = getBookingEntity(bookingRef);
        Long bid = booking.getBingeId();
        int startMinute = booking.getStartTime().getHour() * 60 + booking.getStartTime().getMinute();
        int durMin = getEffectiveDurationMinutes(booking);
        Long currentRoomId = booking.getVenueRoomId();
        return venueRoomRepository.findByBingeIdAndActiveTrueOrderBySortOrderAsc(bid).stream()
            .filter(r -> r.getStatus() == null || r.getStatus() == com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED)
            .map(room -> {
                int occ = countRoomBookings(room.getId(), booking.getBookingDate(),
                    OccupancyWindow.of(startMinute, durMin, booking.getSetupMinutes(), booking.getCleanupMinutes()),
                    booking.getId());
                VenueRoomDto dto = toRoomDto(room);
                dto.setCurrentOccupancy(occ);
                // Bookable if free for this window, OR it is the room this booking already holds.
                dto.setAvailable(occ < Math.max(room.getCapacity(), 1) || room.getId().equals(currentRoomId));
                return dto;
            }).toList();
    }

    /**
     * Assign / change the physical room for a booking (admin action at or before check-in).
     * Validates the room is active, approved, and free for the booking's window (excluding the
     * booking itself). Operational room swap only — it does NOT re-price the booking. The new
     * room is snapshotted onto the booking so the customer's view reflects it immediately.
     */
    @Transactional
    public BookingDto assignRoomForBooking(String bookingRef, Long venueRoomId) {
        return assignRoomForBooking(bookingRef, venueRoomId, null);
    }

    @Transactional
    public BookingDto assignRoomForBooking(String bookingRef, Long venueRoomId, String remarks) {
        Booking booking = getBookingEntity(bookingRef);
        if (venueRoomId == null) {
            throw new BusinessException("Select a room to assign");
        }
        // A room may only be (re)assigned BEFORE check-in, or AFTER an undo-check-in.
        // Once a party is physically checked into a room, the room is locked to preserve
        // an accurate occupancy/audit record — the admin must undo the check-in first.
        // (The check-in flow assigns the room while the booking is still CONFIRMED, i.e.
        // before checkedIn is set, so it is unaffected by this guard.)
        if (booking.isCheckedIn()) {
            throw new BusinessException(
                "This booking is already checked in — undo the check-in before changing its room.");
        }
        VenueRoom room = venueRoomRepository.findByIdAndBingeId(venueRoomId, booking.getBingeId())
            .orElseThrow(() -> new BusinessException("Selected room not found"));
        if (!room.isActive()) throw new BusinessException("Selected room is currently unavailable");
        if (room.getStatus() != null && room.getStatus() != com.skbingegalaxy.booking.entity.RoomApprovalStatus.APPROVED) {
            throw new BusinessException("Selected room is not yet approved for bookings");
        }
        // Serialise room reassignments for this slot with the same advisory lock booking
        // creation uses, so two admins can't put two parties in one room concurrently.
        bookingRepository.acquireSlotLock(slotLockKey(booking.getBingeId(), booking.getBookingDate()));
        Long previousRoomId = booking.getVenueRoomId();
        String previousRoomName = booking.getVenueRoomName();
        boolean roomChanged = !room.getId().equals(previousRoomId);
        // Item 7: CHANGING an already-assigned room requires operator remarks
        // (recorded in the event log). A first-time assignment stays friction-free.
        boolean hasRemarks = remarks != null && !remarks.isBlank();
        if (roomChanged && previousRoomId != null && !hasRemarks) {
            throw new BusinessException("Remarks are required when changing this booking's room");
        }
        if (roomChanged) {
            int startMinute = booking.getStartTime().getHour() * 60 + booking.getStartTime().getMinute();
            int durMin = getEffectiveDurationMinutes(booking);
            int occ = countRoomBookings(room.getId(), booking.getBookingDate(),
                OccupancyWindow.of(startMinute, durMin, booking.getSetupMinutes(), booking.getCleanupMinutes()),
                booking.getId());
            if (occ >= Math.max(room.getCapacity(), 1)) {
                throw new BusinessException("Room '" + room.getName() + "' is already occupied for this time slot");
            }
        }
        booking.setVenueRoomId(room.getId());
        booking.setVenueRoomName(room.getName());
        bookingRepository.save(booking);
        log.info("Room reassigned for booking {} -> {} ({})", bookingRef, room.getName(), room.getId());

        // Audit the change in the booking event log so it shows up in the admin timeline
        // with who did it (actor name/role resolved from the request headers). Only log an
        // actual change (skip a no-op re-assign of the same room).
        if (roomChanged) {
            String actorRole = com.skbingegalaxy.booking.web.RequestContext.currentRole();
            Long actorId = com.skbingegalaxy.booking.web.RequestContext.currentUserId();
            String desc = (previousRoomName != null && !previousRoomName.isBlank())
                ? "Room changed from '" + previousRoomName + "' to '" + room.getName() + "'"
                : "Room assigned: '" + room.getName() + "'";
            if (hasRemarks) {
                desc += " — Remarks: " + remarks.trim();
            }
            eventLogService.logEvent(booking, BookingEventType.ROOM_CHANGED,
                booking.getStatus().name(), actorId,
                actorRole != null ? actorRole : "ADMIN", desc);
        }
        return toDto(booking);
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

    /**
     * Every room block across the selected binge — powers the Blocked Dates
     * calendar, which shows venue-wide closures (availability-service) and
     * per-room maintenance/hold windows (this store) side by side.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.skbingegalaxy.booking.dto.RoomBlockDto> listAllRoomBlocksForBinge() {
        Long bid = BingeContext.requireBingeId();
        List<Long> roomIds = venueRoomRepository.findByBingeIdOrderBySortOrderAsc(bid)
            .stream().map(VenueRoom::getId).toList();
        if (roomIds.isEmpty()) return java.util.List.of();
        return roomBlockRepository.findByRoomIdInOrderByStartAtAsc(roomIds)
            .stream().map(this::toRoomBlockDto).toList();
    }

    @Transactional
    public com.skbingegalaxy.booking.dto.RoomBlockDto createRoomBlock(
            Long roomId,
            com.skbingegalaxy.booking.dto.RoomBlockSaveRequest req,
            Long actorAdminId) {
        VenueRoom room = requireRoomInCurrentBinge(roomId, "creating room block");
        if (req.getStartAt() == null || req.getEndAt() == null) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "startAt and endAt are required", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        if (!req.getEndAt().isAfter(req.getStartAt())) {
            throw new com.skbingegalaxy.common.exception.BusinessException(
                "endAt must be after startAt", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        // Counted BEFORE the block exists — countRoomBookings would short-circuit
        // to "full" once the new block covers the slot and miscount.
        int affected = countBookingsOverlappingWindow(
            room.getBingeId(), roomId, req.getStartAt(), req.getEndAt());
        com.skbingegalaxy.booking.entity.RoomBlock block = com.skbingegalaxy.booking.entity.RoomBlock.builder()
            .roomId(roomId)
            .startAt(req.getStartAt())
            .endAt(req.getEndAt())
            .reason(req.getReason())
            .createdBy(actorAdminId)
            .build();
        block = roomBlockRepository.save(block);
        log.info("Room block created: room={} window=[{} .. {}] reason='{}' by admin {} ({} existing booking(s) overlap)",
            roomId, block.getStartAt(), block.getEndAt(), block.getReason(), actorAdminId, affected);
        publishBlockLifecycle(block, "BLOCKED", com.skbingegalaxy.common.constants.KafkaTopics.ROOM_BLOCKED, actorAdminId);
        com.skbingegalaxy.booking.dto.RoomBlockDto dto = toRoomBlockDto(block);
        dto.setAffectedBookings(affected);
        return dto;
    }

    /**
     * Active bookings on {@code roomId} overlapping [startAt, endAt) — advisory
     * count surfaced when a block is created, because blocks never cancel
     * existing bookings (ops must reschedule those by hand). Deliberately does
     * NOT reuse countRoomBookings: that helper reports "full capacity" whenever
     * any block covers the slot, which would miscount here.
     */
    private int countBookingsOverlappingWindow(Long bingeId, Long roomId,
            LocalDateTime startAt, LocalDateTime endAt) {
        int total = 0;
        LocalDate day = startAt.toLocalDate();
        // endAt is exclusive: a block ending exactly at midnight doesn't touch that day.
        LocalDate last = endAt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
            ? endAt.toLocalDate().minusDays(1) : endAt.toLocalDate();
        for (int guard = 0; !day.isAfter(last) && guard < 62; day = day.plusDays(1), guard++) {
            int winStart = day.equals(startAt.toLocalDate())
                ? startAt.getHour() * 60 + startAt.getMinute() : 0;
            int winEnd = day.equals(endAt.toLocalDate())
                ? endAt.getHour() * 60 + endAt.getMinute() : 1440;
            if (winEnd <= winStart) continue;
            for (Booking b : bookingRepository.findActiveBookingsForReadByBingeAndDate(bingeId, day)) {
                if (!roomId.equals(b.getVenueRoomId()) || b.getStartTime() == null) continue;
                int dur = getEffectiveDurationMinutes(b);
                if (dur == 0) continue;
                int bs = b.getStartTime().getHour() * 60 + b.getStartTime().getMinute();
                if (bs < winEnd && bs + dur > winStart) total++;
            }
        }
        return total;
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
     * Active escalations for the selected binge — the support console's work
     * queue, so operators see what needs attention without hunting for refs.
     */
    @Transactional(readOnly = true)
    public List<BookingDto> listActiveEscalations() {
        Long bid = BingeContext.requireBingeId();
        return toDtos(bookingRepository.findActiveEscalations(bid));
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
        // Raising / holding an escalation needs a reason the next operator can act
        // on; clearing to NONE (resolution) may omit it.
        if (!"NONE".equals(normalized) && (reason == null || reason.isBlank())) {
            throw new BusinessException("A reason is required when escalating (it becomes the work-queue context)");
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
        publishBookingEvent(b, topic, null);
    }

    /**
     * @param refundAmount BOOK-004 — only for {@code booking.cancelled}: the
     *                     collected-money refund the policy owes; payment-service
     *                     executes it as real gateway refunds.
     */
    private void publishBookingEvent(Booking b, String topic, java.math.BigDecimal refundAmount) {
        // Envelope (eventId/version/correlationId/occurredAt) is filled by
        // BookingEventPublisher — see V46 outbox migration. Keeping the
        // builder here means concrete domain fields stay close to the call
        // site for grep-ability.
        BookingEvent event = BookingEvent.builder()
            .refundAmount(refundAmount)
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
            .durationMinutes(b.getScheduledDurationMinutes())
            .totalAmount(b.getTotalAmount())
            .currency(b.getPaymentCurrencyCode() != null ? b.getPaymentCurrencyCode() : "INR")
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

    /**
     * Single-row mapper — resolves its own lookup context. List/page paths
     * MUST go through {@link #toDtos(java.util.List)} instead, which resolves
     * binges, add-on categories and event categories in ONE query each
     * (PERF-001: the per-row version made query count grow linearly with page
     * size).
     */
    private BookingDto toDto(Booking b) {
        Binge binge = b.getBingeId() != null
            ? bingeRepository.findById(b.getBingeId()).orElse(null)
            : null;
        // Per-row add-on category resolution (single IN query for this booking).
        java.util.List<Long> catIds = b.getAddOns().stream()
            .map(ba -> ba.getAddOn() != null ? ba.getAddOn().getCategoryId() : null)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        java.util.Map<Long, String> catNamesById = catIds.isEmpty()
            ? java.util.Map.of()
            : addOnCategoryRepository.findAllById(catIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.skbingegalaxy.booking.entity.AddOnCategory::getId,
                    com.skbingegalaxy.booking.entity.AddOnCategory::getName));
        java.util.Map<Long, String> eventCatNames = null; // resolved per-row inside the builder
        return toDto(b, binge, catNamesById, eventCatNames);
    }

    /** Batch list mapper: bounded query count regardless of list size (PERF-001). */
    private java.util.List<BookingDto> toDtos(java.util.List<Booking> bookings) {
        if (bookings == null || bookings.isEmpty()) return java.util.List.of();

        java.util.List<Long> bingeIds = bookings.stream()
            .map(Booking::getBingeId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, Binge> bingesById = bingeIds.isEmpty()
            ? java.util.Map.of()
            : bingeRepository.findAllById(bingeIds).stream()
                .collect(java.util.stream.Collectors.toMap(Binge::getId, binge -> binge));

        java.util.List<Long> addonCatIds = bookings.stream()
            .flatMap(b -> b.getAddOns().stream())
            .map(ba -> ba.getAddOn() != null ? ba.getAddOn().getCategoryId() : null)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        java.util.Map<Long, String> addonCatNames = addonCatIds.isEmpty()
            ? java.util.Map.of()
            : addOnCategoryRepository.findAllById(addonCatIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.skbingegalaxy.booking.entity.AddOnCategory::getId,
                    com.skbingegalaxy.booking.entity.AddOnCategory::getName));

        java.util.List<Long> eventCatIds = bookings.stream()
            .map(b -> b.getEventType() != null ? b.getEventType().getCategoryId() : null)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        java.util.Map<Long, String> eventCatNames = eventCatIds.isEmpty()
            ? java.util.Map.of()
            : eventCategoryRepository.findAllById(eventCatIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                    com.skbingegalaxy.booking.entity.EventCategory::getId,
                    com.skbingegalaxy.booking.entity.EventCategory::getName));

        return bookings.stream()
            .map(b -> toDto(b, b.getBingeId() != null ? bingesById.get(b.getBingeId()) : null,
                addonCatNames, eventCatNames))
            .toList();
    }

    /** Page adapter over {@link #toDtos}. */
    private org.springframework.data.domain.Page<BookingDto> toDtoPage(
            org.springframework.data.domain.Page<Booking> page) {
        return new org.springframework.data.domain.PageImpl<>(
            toDtos(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    private BookingDto toDto(Booking b, Binge binge,
                             java.util.Map<Long, String> addonCatNamesById,
                             java.util.Map<Long, String> eventCatNamesById) {
        CancellationPolicyDecision cancelDecision = evaluateCustomerCancellation(b, binge);
        final java.util.Map<Long, String> catNamesById =
            addonCatNamesById != null ? addonCatNamesById : java.util.Map.of();
        return BookingDto.builder()
            .id(b.getId())
            .bookingRef(b.getBookingRef())
            .bingeId(b.getBingeId())
            // Venue-local IANA zone so the UI can label bookingDate/startTime unambiguously.
            // venueClock caches per-binge, so this adds no per-booking DB hit.
            .venueTimezone(venueClock.zoneOf(b.getBingeId()).getId())
            .customerId(b.getCustomerId())
            .customerName(b.getCustomerName())
            .customerEmail(b.getCustomerEmail())
            .customerPhone(b.getCustomerPhone())
            .customerPhoneCountryCode(b.getCustomerPhoneCountryCode())
            .eventType(eventCatNamesById != null
                ? toEventTypeDto(b.getEventType(),
                    b.getEventType() != null && b.getEventType().getCategoryId() != null
                        ? eventCatNamesById.get(b.getEventType().getCategoryId()) : null)
                : toEventTypeDto(b.getEventType()))
            .bookingDate(b.getBookingDate())
            .startTime(b.getStartTime())
            .durationHours(b.getDurationHours())
            .durationMinutes(b.getScheduledDurationMinutes())
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
            .paymentExpiresAt(computePaymentExpiresAt(b))
            .pricingSource(b.getPricingSource())
            .rateCodeName(b.getRateCodeName())
            .rescheduleCount(b.getRescheduleCount())
            .originalBookingRef(b.getOriginalBookingRef())
            .transferred(b.isTransferred())
            .originalCustomerName(b.getOriginalCustomerName())
            .recurringGroupId(b.getRecurringGroupId())
            // V85 provenance — lets admins and support distinguish a channel
            // reservation from a direct one, and quote the provider's own reference.
            .origin(b.getOrigin() != null ? b.getOrigin().name() : null)
            .externalSource(b.getExternalSource())
            .externalRef(b.getExternalRef())
            .canCustomerReschedule(canCustomerReschedule(b))
            .canCustomerTransfer(canCustomerTransfer(b))
            .venueRoomId(b.getVenueRoomId())
            .venueRoomName(b.getVenueRoomName())
            .loyaltyPointsEarned(b.getLoyaltyPointsEarned())
            .loyaltyPointsRedeemed(b.getLoyaltyPointsRedeemed())
            .loyaltyDiscountAmount(b.getLoyaltyDiscountAmount())
            .surgeMultiplier(b.getSurgeMultiplier())
            .surgeLabel(b.getSurgeLabel())
            .escalationLevel(b.getEscalationLevel())
            .escalationReason(b.getEscalationReason())
            .goodwillCredit(b.getGoodwillCredit())
            .goodwillReason(b.getGoodwillReason())
            .subtotalAmount(b.getSubtotalAmount())
            .taxAmount(b.getTaxAmount())
            .taxBreakdownJson(b.getTaxBreakdownJson())
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

    /**
     * When the payment-timeout saga will auto-release this booking (UTC), or null when
     * it isn't an unpaid PENDING hold. MUST mirror {@code findStalePendingBookings}
     * exactly (status PENDING + paymentStatus PENDING, createdAt + timeout) — a countdown
     * we show the customer has to be the countdown that actually fires. FAILED-payment
     * bookings are deliberately excluded: the payment-failure saga cancels those directly,
     * so the timeout scheduler never touches them and a timer would be a lie.
     */
    private LocalDateTime computePaymentExpiresAt(Booking b) {
        if (b.getStatus() == BookingStatus.PENDING
            && b.getPaymentStatus() == PaymentStatus.PENDING
            && b.getCreatedAt() != null) {
            return b.getCreatedAt().plusMinutes(pendingTimeoutMinutes);
        }
        return null;
    }

    private CancellationPolicyDecision evaluateCustomerCancellation(Booking booking) {
        Binge binge = booking.getBingeId() != null
            ? bingeRepository.findById(booking.getBingeId()).orElse(null)
            : null;
        return evaluateCustomerCancellation(booking, binge);
    }

    /**
     * Batch-aware overload (PERF-001): list/page mappers prefetch each distinct
     * binge ONCE and pass it in, instead of one binge query per booking row.
     */
    private CancellationPolicyDecision evaluateCustomerCancellation(Booking booking, Binge binge) {
        // Industry rule (Ticketmaster / BookMyShow / Airbnb): an UNPAID hold is not a
        // committed booking. No money has been captured, so the customer may ALWAYS
        // release it — free of charge — regardless of the venue's cancellation policy,
        // tiers or cutoffs. Those apply only once payment is involved (SUCCESS /
        // PARTIALLY_PAID / PARTIALLY_REFUNDED). Without this, a venue that disables
        // self-cancellation strands customers with accidental unpaid bookings they can
        // neither pay for nor remove — forcing manual admin contact.
        if (booking.getStatus() == BookingStatus.PENDING
            && (booking.getPaymentStatus() == PaymentStatus.PENDING
                || booking.getPaymentStatus() == PaymentStatus.FAILED)) {
            return new CancellationPolicyDecision(true,
                "Free cancellation — no payment has been taken for this booking.", 0);
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
        // V81: expose both the raw override (NULL = inherit, what the admin form
        // edits) and the resolved value (what actually governs occupancy), so the
        // UI can show "45 min (from venue default)" without re-deriving the rule.
        TurnoverPolicy.Buffers effective = turnoverPolicy.resolve(et.getBingeId(), et);
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
            .setupMinutes(et.getSetupMinutes())
            .cleanupMinutes(et.getCleanupMinutes())
            .effectiveSetupMinutes(effective.setupMinutes())
            .effectiveCleanupMinutes(effective.cleanupMinutes())
            .permittedDurations(BookingWindowPolicy.parse(et.getPermittedDurationsCsv()))
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



