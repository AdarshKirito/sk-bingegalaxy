package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.constants.KafkaTopics;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.PaymentMethod;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.event.PaymentEvent;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.dto.*;
import com.skbingegalaxy.payment.entity.Payment;
import com.skbingegalaxy.payment.entity.Refund;
import com.skbingegalaxy.payment.event.PaymentKafkaEvent;
import com.skbingegalaxy.payment.client.RazorpayGatewayClient;
import com.skbingegalaxy.payment.repository.PaymentRepository;
import com.skbingegalaxy.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** Statuses that count as a completed refund for amount-calculation purposes. */
    private static final List<PaymentStatus> REFUNDED_STATUSES =
            List.of(PaymentStatus.REFUNDED);

    /**
     * Refund attempts that hold — or may still claim — part of the payment's
     * refundable amount, including in-flight gateway refunds. Over-refund
     * guards MUST use this set: two PROCESSING refunds that each passed a
     * settled-only check could jointly exceed the payment.
     */
    private static final List<com.skbingegalaxy.payment.entity.RefundStatus> ACTIVE_REFUND_STATUSES =
            List.of(com.skbingegalaxy.payment.entity.RefundStatus.INITIATED,
                    com.skbingegalaxy.payment.entity.RefundStatus.PROCESSING,
                    com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RazorpayGatewayClient razorpayGatewayClient;
    private final com.skbingegalaxy.payment.client.BookingAmountClient bookingAmountClient;
    private final com.skbingegalaxy.payment.repository.PaymentStatusHistoryRepository statusHistoryRepository;
    private final Environment environment;
    private final WebhookDedupService webhookDedupService;
    private final AuditLogService auditLogService;
    private final PaymentMetrics metrics;
    private final AdminApprovalService approvalService;
    private final com.skbingegalaxy.payment.provider.PaymentProviderRegistry providerRegistry;
    private final com.skbingegalaxy.payment.method.PaymentMethodResolver paymentMethodResolver;
    private final ConnectedAccountService connectedAccountService;
    private final com.skbingegalaxy.payment.client.StripeGatewayClient stripeGatewayClient;

    /** Self-reference for calling @Transactional methods from within the same bean. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService self;

    @Value("${app.razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${app.razorpay.key-id:}")
    private String razorpayKeyId;

    /**
     * Stripe PUBLISHABLE key (pk_…) — safe to hand to the browser, which needs it
     * to initialise Stripe.js. Distinct from the secret key, which never leaves
     * the server.
     */
    @Value("${app.stripe.publishable-key:}")
    private String stripePublishableKey;

    @Value("${app.payment.simulation-enabled:false}")
    private boolean paymentSimulationEnabled;

    /**
     * Above this amount (in payment currency, treated as a flat number),
     * a refund retry must go through the maker-checker workflow before it
     * actually moves money. Default 5,000 INR — overridable per environment.
     */
    @Value("${app.refund.retry-approval-threshold:5000}")
    private java.math.BigDecimal refundRetryApprovalThreshold;

    @Value("${app.payment.dedup-window-seconds:30}")
    private int dedupWindowSeconds;

    @jakarta.annotation.PostConstruct
    void validateConfig() {
        boolean isProduction = java.util.Arrays.asList(environment.getActiveProfiles()).contains("production");

        // CRITICAL: simulation MUST be off in production — a stray env var confirms fake payments
        if (isProduction && paymentSimulationEnabled) {
            throw new IllegalStateException(
                "FATAL: PAYMENT_SIMULATION_ENABLED=true is set while the 'production' Spring profile is active. "
                + "This would accept fake payments and confirm real bookings without capturing money. "
                + "Set PAYMENT_SIMULATION_ENABLED=false in your production environment.");
        }

        if (!paymentSimulationEnabled && (razorpayKeySecret == null || razorpayKeySecret.isBlank())) {
            throw new IllegalStateException(
                "RAZORPAY_KEY_SECRET must be set when payment simulation is disabled");
        }

        // Live Razorpay key must start with rzp_live_ in production
        if (isProduction && razorpayKeyId != null && !razorpayKeyId.isBlank()
                && !razorpayKeyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                "FATAL: Production profile is active but Razorpay key_id does not start with 'rzp_live_'. "
                + "key_id=" + razorpayKeyId.substring(0, Math.min(12, razorpayKeyId.length())) + "... — "
                + "this looks like a test key. Set the correct live Razorpay credentials.");
        }

        if (paymentSimulationEnabled && razorpayKeyId != null && !razorpayKeyId.isBlank()
                && !razorpayKeyId.startsWith("rzp_test_")) {
            // Live Razorpay keys alongside simulation = dangerous misconfiguration
            throw new IllegalStateException(
                "FATAL: Payment simulation is ENABLED alongside live Razorpay keys (key_id="
                + razorpayKeyId.substring(0, Math.min(12, razorpayKeyId.length())) + "...). "
                + "Set PAYMENT_SIMULATION_ENABLED=false for production or remove the Razorpay keys.");
        }

        log.info("PaymentService config validated: simulation={} production={}", paymentSimulationEnabled, isProduction);
    }

    /** Backward-compat overload (callers without phone). */
    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String customerEmail, String customerName) {
        return initiatePayment(request, customerId, "CUSTOMER", customerEmail, customerName, null, null);
    }

    /** Backward-compat overload (callers without role). */
    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String customerEmail, String customerName,
                                      String customerPhone, String customerPhoneCountryCode) {
        return initiatePayment(request, customerId, "CUSTOMER", customerEmail, customerName,
            customerPhone, customerPhoneCountryCode);
    }

    /**
     * Non-transactional entry point. Three phases (PAY-005 — durable intent
     * BEFORE the provider order):
     * <ol>
     *   <li>TX1 {@link #reserveInitiatedPayment}: all guards + authoritative
     *       booking binding run under the booking lock and a durable INITIATED
     *       payment (no gateway order yet) commits. Concurrent initiations
     *       converge on this single row.</li>
     *   <li>Provider order creation OUTSIDE any transaction — no DB locks held
     *       during the slow HTTP call; receipt = bookingRef so a timed-out
     *       create is found and reused instead of duplicated.</li>
     *   <li>TX2 {@link #attachGatewayOrder}: the order id is attached to the
     *       intent row (first writer wins; a losing order is logged orphan).</li>
     * </ol>
     * A crash between phases leaves an INITIATED row without an order id —
     * harmless (no callback can ever reference it) and completed or expired by
     * the next attempt / reconciliation.
     */
    public PaymentDto initiatePayment(InitiatePaymentRequest request, Long customerId, String callerRole,
                                      String customerEmail, String customerName,
                                      String customerPhone, String customerPhoneCountryCode) {
        log.info("Initiating payment for booking: {}, amount: {}", request.getBookingRef(), request.getAmount());

        PaymentDto reserved = self.reserveInitiatedPayment(request, customerId, callerRole,
            customerEmail, customerName, customerPhone, customerPhoneCountryCode);
        if (reserved.getGatewayOrderId() != null && !reserved.getGatewayOrderId().isBlank()) {
            return reserved; // existing intent already has a payable order attached
        }

        String gatewayOrderId;
        String usedProvider = null;
        java.util.Map<String, String> checkoutFields = null;
        // Gate on ANY live gateway, not Razorpay specifically. The old check was
        // `razorpayKeyId != null`, so a Stripe-only deployment silently skipped the
        // real gateway call and handed back a fake local order id — i.e. bookings
        // would look initiated while no money was ever requested.
        boolean liveGatewayConfigured =
            (razorpayKeyId != null && !razorpayKeyId.isBlank()) || stripeGatewayClient.isConfigured();
        if (!paymentSimulationEnabled && liveGatewayConfigured) {
            // Route through the provider registry rather than a hardcoded gateway.
            // For a Razorpay/INR venue this is byte-for-byte the previous call
            // (RazorpayPaymentProvider delegates to the same client with
            // receipt = bookingRef, preserving the timed-out-create reuse in the
            // javadoc above). The indirection is what lets a venue whose currency
            // Razorpay cannot settle route to a second gateway later without
            // touching this method.
            String currency = request.getCurrency() != null ? request.getCurrency() : "INR";

            // Venue routing. For a marketplace provider (Stripe Connect) the charge
            // must be created ON the venue's connected account, in the venue's
            // country — that is what makes the venue's local rails available to a
            // customer paying from anywhere. Single-account gateways ignore these.
            var routing = bookingAmountClient.fetchSnapshot(request.getBookingRef());
            String venueCountry = routing != null ? routing.bingeCountry() : null;
            Long routingBingeId = routing != null ? routing.bingeId() : null;
            String connectedAccountId = connectedAccountService
                .chargeableAccountId(routingBingeId).orElse(null);

            // A venue with a chargeable Connect account is paid through it; see
            // preferredProviderFor. Provider selection and method resolution take
            // the SAME preference so the customer cannot be shown one gateway's
            // rails and charged on another's.
            String preferred = connectedAccountId != null ? "stripe" : null;
            var provider = providerRegistry.resolveForCurrency(preferred, currency);
            var methods = paymentMethodResolver.resolve(venueCountry, currency, preferred).methods();

            var orderRequest = new com.skbingegalaxy.payment.provider.PaymentProvider.CreateOrderRequest(
                request.getBookingRef(),
                request.getAmount(),
                currency,
                customerEmail,
                customerName,
                null,
                // transactionId is unique per payment ATTEMPT and is what the
                // provider keys gateway idempotency on — see StripePaymentProvider.
                reserved.getTransactionId() != null
                    ? java.util.Map.of("transactionId", reserved.getTransactionId())
                    : java.util.Map.<String, String>of(),
                venueCountry,
                connectedAccountId,
                methods);
            var created = provider.createOrder(orderRequest);
            gatewayOrderId = created.gatewayOrderId();
            usedProvider = provider.name();
            checkoutFields = created.publicCheckoutFields();
        } else {
            gatewayOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        }

        PaymentDto dto = self.attachGatewayOrder(
            reserved.getId(), request.getBookingRef(), gatewayOrderId, usedProvider);
        // Checkout fields are ephemeral: they exist only for this initiation
        // response, so they are set on the DTO here rather than persisted.
        dto.setProviderName(usedProvider);
        if (checkoutFields != null && !checkoutFields.isEmpty()) {
            java.util.Map<String, String> fields = new java.util.HashMap<>(checkoutFields);
            if (stripeGatewayClient.isConfigured() && "stripe".equals(usedProvider)) {
                fields.put("stripePublishableKey", stripePublishableKey);
            }
            dto.setCheckoutFields(fields);
        }
        return dto;
    }

    /**
     * The payment rails offered for a booking, resolved from the VENUE's country.
     *
     * <p>Read-only and safe to call before initiation — the checkout page uses it
     * to render its method picker, and {@link #reserveInitiatedPayment} enforces
     * the same resolution server-side so the UI and the guard can never disagree.
     */
    /**
     * The gateway to prefer for a venue: {@code "stripe"} once it has a chargeable
     * Connect account, otherwise null (platform default).
     *
     * <p>Completing Connect onboarding is the venue opting in to being paid into
     * its OWN account. Without this preference the default gateway would keep
     * winning for every currency it can settle, the connected account would never
     * be used, and the venue's money would land in the platform's account instead
     * of its bank — onboarding would be decorative.
     *
     * <p>Must be applied identically at display, enforcement and charge time so the
     * rails a customer sees come from the gateway that will actually charge them.
     */
    private String preferredProviderFor(Long bingeId) {
        return connectedAccountService.chargeableAccountId(bingeId).isPresent() ? "stripe" : null;
    }

    public com.skbingegalaxy.payment.dto.PaymentMethodOptionsDto getPaymentMethodOptions(
            String bookingRef, Long callerId, String callerRole) {
        var snapshot = bookingAmountClient.fetchSnapshot(bookingRef);
        if (snapshot == null) {
            throw new BusinessException(
                "Unable to load payment options — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Same SEC-011 ownership rule as initiation. Without it this endpoint is an
        // IDOR: any authenticated user could probe arbitrary booking references,
        // confirming which ones exist and reading the venue's country and currency
        // for bookings that are not theirs.
        Long bookingOwnerId = snapshot.customerId();
        if ("CUSTOMER".equalsIgnoreCase(callerRole)
                && bookingOwnerId != null && !bookingOwnerId.equals(callerId)) {
            log.warn("Rejected payment-method lookup: user {} is not the owner ({}) of booking {}",
                callerId, bookingOwnerId, bookingRef);
            throw new BusinessException("You can only view payment options for your own bookings.",
                HttpStatus.FORBIDDEN);
        }
        String currency = snapshot.paymentCurrencyCode() != null
            ? snapshot.paymentCurrencyCode().trim().toUpperCase() : "INR";
        String venueCountry = snapshot.bingeCountry();

        var resolution = paymentMethodResolver.resolve(
            venueCountry, currency, preferredProviderFor(snapshot.bingeId()));
        if (resolution.methods().isEmpty()) {
            // No gateway can settle this venue's currency — a configuration gap,
            // not something the customer can fix by retrying.
            throw new BusinessException(
                "Online payment is not available for this venue's currency (" + currency
                    + "). Please contact support.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Label against the resolution's EFFECTIVE country (which may have been
        // inferred from currency for a legacy venue) so the labels match the rails.
        String labelCountry = resolution.venueCountry();
        var options = resolution.methods().stream()
            .map(m -> com.skbingegalaxy.payment.dto.PaymentMethodOptionsDto.Option.builder()
                .method(m)
                .label(com.skbingegalaxy.payment.method.PaymentMethodCatalog.labelFor(m, labelCountry))
                .build())
            .toList();

        return com.skbingegalaxy.payment.dto.PaymentMethodOptionsDto.builder()
            .currency(currency)
            .venueCountry(labelCountry)
            .provider(resolution.provider())
            .defaultMethod(resolution.defaultMethod())
            .methods(options)
            .build();
    }

    /**
     * TX2 of initiation: attach the provider order to the durable intent row.
     * Runs under the same per-booking advisory lock as the reserve step so a
     * concurrent initiation can't attach a second order — the loser's provider
     * order stays unused ("created") at Razorpay and simply expires.
     */
    @Transactional
    public PaymentDto attachGatewayOrder(Long paymentId, String bookingRef, String gatewayOrderId,
                                         String providerName) {
        paymentRepository.acquirePaymentLock(bookingRef.hashCode());
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", String.valueOf(paymentId)));
        if (payment.getGatewayOrderId() == null || payment.getGatewayOrderId().isBlank()) {
            payment.setGatewayOrderId(gatewayOrderId);
            // Recorded together with the order id: a refund later has to go back to
            // the SAME gateway, and without this it always assumed Razorpay.
            payment.setProviderName(providerName);
            payment = paymentRepository.save(payment);
        } else if (!payment.getGatewayOrderId().equals(gatewayOrderId)) {
            log.warn("Initiation race for booking {}: payment {} already has order {} — provider order {} orphaned",
                bookingRef, payment.getTransactionId(), payment.getGatewayOrderId(), gatewayOrderId);
        }
        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * TX1 of initiation: guards + authoritative booking binding (SEC-011) +
     * durable INITIATED intent. Caller identity and the client-controlled
     * binge header are never trusted — the booking snapshot from
     * booking-service decides the owner and tenant this payment binds to.
     */
    @Transactional
    public PaymentDto reserveInitiatedPayment(InitiatePaymentRequest request, Long customerId, String callerRole,
                                              String customerEmail, String customerName,
                                              String customerPhone, String customerPhoneCountryCode) {
        // Serialise concurrent initiations for the same booking to prevent duplicate INITIATED payments
        paymentRepository.acquirePaymentLock(request.getBookingRef().hashCode());

        // Guard 0: Fetch booking snapshot FIRST so terminally-closed bookings (CANCELLED,
        // NO_SHOW, EXPIRED) are rejected even if an INITIATED payment already exists from
        // before the cancellation. Fail-closed on unreachable booking-service.
        var snapshot = bookingAmountClient.fetchSnapshot(request.getBookingRef());
        if (snapshot == null) {
            throw new BusinessException(
                "Unable to verify booking balance — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (snapshot.status() != null) {
            String st = snapshot.status();
            if ("CANCELLED".equals(st) || "NO_SHOW".equals(st) || "EXPIRED".equals(st)) {
                throw new BusinessException(
                    "This booking is " + st.toLowerCase() + " and can no longer be paid. Please create a new booking.",
                    HttpStatus.CONFLICT);
            }
        }

        // ── SEC-011: bind to the authoritative booking owner + binge ─────────
        // A customer may only pay their own booking; the payment row is stamped
        // with the BOOKING's binge, not whatever X-Binge-Id the client sent.
        Long bookingOwnerId = snapshot.customerId();
        if ("CUSTOMER".equalsIgnoreCase(callerRole)
                && bookingOwnerId != null && !bookingOwnerId.equals(customerId)) {
            log.warn("Rejected payment initiation: user {} is not the owner ({}) of booking {}",
                customerId, bookingOwnerId, request.getBookingRef());
            throw new BusinessException("You can only pay for your own bookings.", HttpStatus.FORBIDDEN);
        }
        Long contextBinge = getCurrentBingeId();
        if (snapshot.bingeId() != null && contextBinge != null
                && !snapshot.bingeId().equals(contextBinge)) {
            throw new BusinessException(
                "The selected venue does not match this booking's venue. Please refresh and try again.",
                HttpStatus.CONFLICT);
        }
        Long authoritativeBingeId = snapshot.bingeId() != null ? snapshot.bingeId() : contextBinge;
        Long authoritativeCustomerId = bookingOwnerId != null ? bookingOwnerId : customerId;

        // Guard 1: payment already succeeded for this booking — reject duplicate attempt
        if (!findSuccessfulPaymentsForCurrentBinge(request.getBookingRef()).isEmpty()) {
            metrics.duplicateSuccessful();
            throw new BusinessException("Payment already completed for booking " + request.getBookingRef(), HttpStatus.CONFLICT);
        }

        // Guard 2 (idempotency): an INITIATED payment already exists — return it
        // instead of creating a duplicate (handles network-retry / double-click scenarios).
        // A row without a gateway order (crash between reserve and attach) is also
        // returned as-is: the caller sees the missing order id and completes phase 2/3.
        var existing = findExistingInitiatedPaymentForCurrentBinge(request.getBookingRef());
        if (existing.isPresent()) {
            metrics.duplicateInitiated();
            log.info("Returning existing INITIATED payment {} for booking {}",
                    existing.get().getTransactionId(), request.getBookingRef());
            return toPaymentDtoWithRefunds(existing.get());
        }
        // Detect customer-initiated retries: a FAILED payment exists for this booking
        // but we are creating a new INITIATED attempt. This is the payment retry rate signal.
        Long retryCheckBingeId = getCurrentBingeId();
        var priorFailed = retryCheckBingeId != null
            ? paymentRepository.findFirstByBookingRefAndStatusAndBingeIdOrderByCreatedAtDesc(
                request.getBookingRef(), PaymentStatus.FAILED, retryCheckBingeId)
            : paymentRepository.findFirstByBookingRefAndStatusOrderByCreatedAtDesc(
                request.getBookingRef(), PaymentStatus.FAILED);
        if (priorFailed.isPresent()) {
            metrics.paymentRetry();
        }
        // Guard 3: Validate amount against booking's remaining balance (prevent client-side tampering).
        BigDecimal remainingBalance = snapshot.remainingBalance();
        if (remainingBalance == null) {
            throw new BusinessException(
                "Unable to verify booking balance — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
        // remainingBalance is in the BASE currency (INR). A non-INR payment must (a) match the
        // currency the booking was FX-locked for, and (b) convert back to an INR-equivalent that
        // doesn't exceed the INR balance — using the SAME locked rate stored on the booking
        // (fxRate = foreign units per 1 INR). Validating server-side with the locked rate means a
        // stale or forged client rate cannot slip an under-payment through.
        String payCurrency = request.getCurrency() != null && !request.getCurrency().isBlank()
            ? request.getCurrency().trim().toUpperCase() : "INR";
        String bookingCurrency = snapshot.paymentCurrencyCode() != null
            ? snapshot.paymentCurrencyCode().trim().toUpperCase() : "INR";
        BigDecimal inrEquivalent;
        if ("INR".equals(payCurrency)) {
            inrEquivalent = request.getAmount();
        } else {
            if (!payCurrency.equals(bookingCurrency)) {
                throw new BusinessException(
                    "Payment currency " + payCurrency + " does not match this booking's currency "
                        + bookingCurrency + ". Please refresh the page and try again.",
                    HttpStatus.BAD_REQUEST);
            }
            BigDecimal fxRate = snapshot.fxRate();
            if (fxRate == null || fxRate.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(
                    "No valid exchange rate is stored for this booking — please contact support.",
                    HttpStatus.BAD_REQUEST);
            }
            inrEquivalent = request.getAmount().divide(fxRate, 2, java.math.RoundingMode.HALF_UP);
        }
        // 1-paisa tolerance absorbs foreign→INR rounding.
        if (inrEquivalent.subtract(remainingBalance).compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(
                String.format("Payment amount %s %s (≈ ₹%.2f) exceeds remaining booking balance ₹%.2f",
                    request.getAmount(), payCurrency, inrEquivalent, remainingBalance),
                HttpStatus.BAD_REQUEST);
        }
        // Guard 4: the chosen rail must be one this VENUE actually offers. The UI
        // renders the same resolution, so this only ever trips on a hand-crafted
        // request — but without it a client could post UPI for a US venue and the
        // charge would die at the gateway with an opaque error instead of here
        // with a clear one.
        //
        // Scope: customer checkout only, and only when the venue's country is
        // known. Admin/offline settlement (CASH) is deliberately exempt, and a
        // legacy venue with no country has no authoritative market to judge
        // against — the catalogue fallback there is a guess, not a rule.
        String venueCountry = snapshot.bingeCountry();
        boolean customerCheckout = callerRole == null || callerRole.isBlank()
            || "CUSTOMER".equalsIgnoreCase(callerRole);
        if (customerCheckout && venueCountry != null && !venueCountry.isBlank()
                && request.getPaymentMethod() != null) {
            // Same provider preference as display and charge — otherwise this guard
            // would judge the rail against a gateway that will not be charging it,
            // and reject a method the customer was legitimately offered.
            var resolution = paymentMethodResolver.resolve(
                venueCountry, payCurrency, preferredProviderFor(snapshot.bingeId()));
            if (!resolution.methods().contains(request.getPaymentMethod())) {
                log.warn("Rejected payment method {} for booking {} — venue country {} offers {}",
                    request.getPaymentMethod(), request.getBookingRef(), venueCountry,
                    resolution.methods());
                throw new BusinessException(
                    request.getPaymentMethod() + " is not available for this venue. "
                        + "Please refresh and choose one of the offered payment methods.",
                    HttpStatus.BAD_REQUEST);
            }
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        // Durable initiation intent (PAY-005): no gateway order yet — the
        // provider call happens after this transaction commits and attaches
        // its order id in a second, short transaction.
        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(authoritativeCustomerId)
            .bingeId(authoritativeBingeId)
            .transactionId(transactionId)
            .gatewayOrderId(null)
            .amount(request.getAmount())
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.INITIATED)
            .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
            .customerEmail(customerEmail)
            .customerName(customerName)
            .customerPhone(customerPhone)
            .customerPhoneCountryCode(customerPhoneCountryCode)
            .build();

        payment = paymentRepository.save(payment);
        log.info("Payment intent reserved: {} for booking {} (binge {}, owner {})",
            transactionId, request.getBookingRef(), authoritativeBingeId, authoritativeCustomerId);

        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional
    public PaymentDto handleCallback(PaymentCallbackRequest request) {
        log.info("Handling payment callback for gatewayOrderId: {}", request.getGatewayOrderId());

        // Stable, gateway-assigned dedup key. Duplicate or replayed callbacks
        // with the same (orderId, paymentId, status) tuple short-circuit here
        // before any side effects — the explicit Razorpay/Adyen guidance.
        String eventId = webhookDedupService.razorpayEventId(
            request.getGatewayOrderId(), request.getGatewayPaymentId(), request.getStatus());
        if (webhookDedupService.isDuplicate(eventId)) {
            metrics.webhookDuplicate();
            log.info("Duplicate webhook delivery {} — returning cached state", eventId);
            return paymentRepository.findByGatewayOrderIdForUpdate(request.getGatewayOrderId())
                .map(this::toPaymentDtoWithRefunds)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Payment", "gatewayOrderId", request.getGatewayOrderId()));
        }

        Payment payment = paymentRepository.findByGatewayOrderIdForUpdate(request.getGatewayOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "gatewayOrderId", request.getGatewayOrderId()));

        // Idempotent: already in a terminal state — return without re-processing
        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Callback ignored — payment already in terminal state: {}", payment.getStatus());
            return toPaymentDtoWithRefunds(payment);
        }

        // Reject stale callbacks — payment must have been initiated within the last 24 hours
        if (payment.getCreatedAt() != null && payment.getCreatedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusHours(24))) {
            metrics.webhookStale();
            log.warn("Rejected stale payment callback for gatewayOrderId: {} (created: {})", request.getGatewayOrderId(), payment.getCreatedAt());
            throw new BusinessException("Payment callback expired — payment was initiated more than 24 hours ago", HttpStatus.BAD_REQUEST);
        }

        // Verify Razorpay signature on ALL callbacks to prevent forged success AND failure notifications.
        // Unsigned callbacks are rejected entirely — an attacker could otherwise forge failure
        // notifications to cancel legitimate INITIATED payments.
        // This MUST stay above every state-changing branch (including late-capture):
        // an unverified callback once drove FAILED→SUCCESS→REFUNDED transitions.
        if (request.getGatewaySignature() == null || request.getGatewaySignature().isBlank()) {
            metrics.webhookUnsigned();
            log.warn("Rejected unsigned payment callback for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Payment callback signature is required", HttpStatus.FORBIDDEN);
        }

        String paymentId = request.getGatewayPaymentId() != null ? request.getGatewayPaymentId() : "";
        String payload = request.getGatewayOrderId() + "|" + paymentId;
        if (!verifySignature(payload, request.getGatewaySignature())) {
            metrics.webhookInvalidSignature();
            metrics.signatureFailure();
            log.warn("Invalid payment callback signature for gatewayOrderId: {}", request.getGatewayOrderId());
            throw new BusinessException("Invalid payment signature", HttpStatus.FORBIDDEN);
        }

        metrics.webhookFresh();

        // Handle late captures for payments that were already cancelled with the booking.
        if (payment.getStatus() == PaymentStatus.FAILED
                && "Booking cancelled".equals(payment.getFailureReason())) {
            boolean gatewaySuccess = "success".equalsIgnoreCase(request.getStatus())
                || "captured".equalsIgnoreCase(request.getStatus())
                || "authorized".equalsIgnoreCase(request.getStatus());
            if (gatewaySuccess) {
                // Late capture: gateway captured money after booking was cancelled — auto-refund
                log.warn("Late capture detected for cancelled booking — auto-refunding payment {}",
                    payment.getTransactionId());
                PaymentStatus oldStatus = payment.getStatus();
                payment.setStatus(PaymentStatus.SUCCESS);
                payment.setGatewayPaymentId(request.getGatewayPaymentId());
                payment.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
                payment.setGatewayResponse(buildGatewayResponseSummary(request));
                payment = paymentRepository.save(payment);
                recordStatusChange(payment, oldStatus, PaymentStatus.SUCCESS, "Late capture — booking already cancelled");

                // PAY-006: the durable refund INTENT is created inside this
                // callback transaction (we already hold the pessimistic lock),
                // and the provider leg runs only AFTER this transaction commits
                // — remote money movement never happens inside a transaction
                // that could still roll back. A crash after commit leaves the
                // INITIATED intent for the reconciliation poller.
                Refund lateIntent = createRefundIntentRow(payment, payment.getAmount(),
                    "Auto-refund: booking was cancelled before payment capture", "SYSTEM", null);
                processRefundIntentAfterCommit(lateIntent.getId(), "Late-capture auto-refund");
                metrics.refundAutoLate();
                recordWebhookProcessed(eventId, request);
                return toPaymentDtoWithRefunds(payment);
            }
            // Non-success callback for cancelled booking — ignore silently
            log.info("Ignoring non-success callback for cancelled booking payment: {}", payment.getTransactionId());
            return toPaymentDtoWithRefunds(payment);
        }

        if ("success".equalsIgnoreCase(request.getStatus()) || "captured".equalsIgnoreCase(request.getStatus()) || "authorized".equalsIgnoreCase(request.getStatus())) {
            // ── Duplicate-capture reversal (PAY-007) ────────────────────────
            // A delayed callback for an order the customer retried (its row was
            // FAILED, checkout reopened, a second order captured) can arrive
            // here while the booking is already fully collected. The money DID
            // move, so we record SUCCESS and publish it for ledger truth — but
            // we immediately auto-refund this capture so at most one retained
            // capture remains per booking.
            boolean duplicateCapture = false;
            BigDecimal existingSuccess = payment.getBingeId() != null
                ? paymentRepository.sumSuccessfulPaymentsByBookingRefAndBingeId(
                    payment.getBookingRef(), payment.getBingeId())
                : paymentRepository.sumSuccessfulPaymentsByBookingRef(payment.getBookingRef());
            if (existingSuccess != null && existingSuccess.compareTo(BigDecimal.ZERO) > 0) {
                var dupSnapshot = bookingAmountClient.fetchSnapshot(payment.getBookingRef());
                if (dupSnapshot != null && dupSnapshot.totalAmount() != null
                        && existingSuccess.compareTo(dupSnapshot.totalAmount()) >= 0) {
                    duplicateCapture = true;
                }
            }

            PaymentStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(request.getGatewayPaymentId());
            payment.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            log.info("Payment successful: {}", payment.getTransactionId());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.SUCCESS,
                duplicateCapture ? "Gateway callback: success (duplicate capture — auto-refund issued)"
                                 : "Gateway callback: success");
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);

            if (duplicateCapture) {
                log.warn("DUPLICATE_CAPTURE: booking {} was already fully collected before capture {} — "
                    + "auto-refunding this payment (at most one retained capture per booking)",
                    payment.getBookingRef(), payment.getTransactionId());
                Refund dupIntent = createRefundIntentRow(payment, payment.getAmount(),
                    "Auto-refund: duplicate capture — booking already fully paid", "SYSTEM", null);
                processRefundIntentAfterCommit(dupIntent.getId(), "Duplicate-capture auto-refund");
                metrics.refundAutoLate();
            }
        } else {
            PaymentStatus oldStatus = payment.getStatus();
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(request.getErrorDescription() != null
                ? request.getErrorDescription()
                : "Payment failed at gateway");
            log.warn("Payment failed: {} — {}", payment.getTransactionId(), payment.getFailureReason());
            payment.setGatewayResponse(buildGatewayResponseSummary(request));
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.FAILED, payment.getFailureReason());
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_FAILED);
        }

        // Record the dedup marker AFTER business side effects so retries of a
        // crash between business commit and this call remain safe (we'll simply
        // process the same verified, state-idempotent event again).
        recordWebhookProcessed(eventId, request);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Record the webhook dedup marker INSIDE the callback transaction
     * (PAY-008): marker and business effects commit or roll back together. If
     * this transaction rolls back after the marker insert, the marker rolls
     * back too and the gateway's redelivery reprocesses the event — which is
     * safe, because the handler re-runs its terminal-state checks under the
     * payment row lock and converges.
     */
    private void recordWebhookProcessed(String eventId, PaymentCallbackRequest request) {
        try {
            String payload = request.getGatewayOrderId() + "|"
                + (request.getGatewayPaymentId() == null ? "" : request.getGatewayPaymentId()) + "|"
                + (request.getStatus() == null ? "" : request.getStatus());
            webhookDedupService.recordNew(eventId, payload);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Concurrent delivery won the race — safe to ignore.
            log.debug("Webhook dedup record already present for {}", eventId);
        } catch (Exception e) {
            log.warn("Failed to record webhook dedup marker for {}: {}", eventId, e.getMessage());
        }
    }

    /**
     * Settle a Stripe PaymentIntent reported by the {@code /webhooks/stripe}
     * endpoint. This is the AUTHORITATIVE confirmation for Stripe payments.
     *
     * <p>Unlike the Razorpay flow there is no signed browser callback to trust:
     * Stripe's redirect carries no signature, and a customer who closes the tab
     * mid-redirect would otherwise leave a paid booking stuck INITIATED. The
     * webhook body is HMAC-verified in the controller before this is reached, so
     * the caller is already authenticated — re-checking a per-request signature
     * here (as {@code handleCallback} does) would be checking the wrong thing.
     *
     * <p>The intent id is stored as {@code gatewayOrderId} at initiation, which is
     * how the webhook finds its payment.
     *
     * @param intentId Stripe {@code pi_…} identifier
     * @param succeeded true for payment_intent.succeeded, false for payment_failed
     */
    @Transactional
    public void settleStripeIntent(String intentId, boolean succeeded, String failureReason) {
        if (intentId == null || intentId.isBlank()) return;

        // Stripe retries webhooks aggressively (and re-sends on endpoint errors),
        // so dedup is mandatory rather than defensive.
        String eventId = "stripe:" + intentId + ":" + (succeeded ? "succeeded" : "failed");
        if (webhookDedupService.isDuplicate(eventId)) {
            metrics.webhookDuplicate();
            log.info("Duplicate Stripe webhook {} — ignoring", eventId);
            return;
        }

        Payment payment = paymentRepository.findByGatewayOrderIdForUpdate(intentId).orElse(null);
        if (payment == null) {
            log.warn("Stripe webhook for unknown intent {} — no matching payment", intentId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            log.info("Stripe webhook ignored — payment {} already {}",
                payment.getTransactionId(), payment.getStatus());
            return;
        }

        PaymentStatus oldStatus = payment.getStatus();
        if (succeeded) {
            // Duplicate-capture guard, mirroring the Razorpay callback path. Two
            // intents can both succeed for one booking: gateway idempotency is keyed
            // per payment ATTEMPT (it has to be, so partial payments work), and the
            // balance check at initiation cannot see money that is still in flight.
            // Money has genuinely moved, so the capture is recorded for ledger truth
            // and refunded rather than rejected.
            boolean duplicateCapture = false;
            BigDecimal existingSuccess = payment.getBingeId() != null
                ? paymentRepository.sumSuccessfulPaymentsByBookingRefAndBingeId(
                    payment.getBookingRef(), payment.getBingeId())
                : paymentRepository.sumSuccessfulPaymentsByBookingRef(payment.getBookingRef());
            if (existingSuccess != null && existingSuccess.compareTo(BigDecimal.ZERO) > 0) {
                var dupSnapshot = bookingAmountClient.fetchSnapshot(payment.getBookingRef());
                if (dupSnapshot != null && dupSnapshot.totalAmount() != null
                        && existingSuccess.compareTo(dupSnapshot.totalAmount()) >= 0) {
                    duplicateCapture = true;
                }
            }

            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(intentId);
            payment.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            payment.setGatewayResponse("Stripe webhook: payment_intent.succeeded");
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.SUCCESS,
                duplicateCapture ? "Stripe webhook: success (duplicate capture — auto-refund issued)"
                                 : "Stripe webhook: success");
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
            log.info("Stripe payment successful: {}", payment.getTransactionId());

            if (duplicateCapture) {
                log.warn("DUPLICATE_CAPTURE (stripe): booking {} was already fully collected before "
                    + "capture {} — auto-refunding this payment",
                    payment.getBookingRef(), payment.getTransactionId());
                Refund dupIntent = createRefundIntentRow(payment, payment.getAmount(),
                    "Auto-refund: duplicate capture — booking already fully paid", "SYSTEM", null);
                processRefundIntentAfterCommit(dupIntent.getId(), "Duplicate-capture auto-refund");
                metrics.refundAutoLate();
            }
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(failureReason != null && !failureReason.isBlank()
                ? truncate(failureReason, 250) : "Payment failed at Stripe");
            payment.setGatewayResponse("Stripe webhook: payment_intent.payment_failed");
            payment = paymentRepository.save(payment);
            recordStatusChange(payment, oldStatus, PaymentStatus.FAILED, payment.getFailureReason());
            publishPaymentEvent(payment, KafkaTopics.PAYMENT_FAILED);
            log.warn("Stripe payment failed: {} — {}", payment.getTransactionId(), payment.getFailureReason());
        }

        // Marker recorded after the business effect, matching the Razorpay path:
        // a crash in between simply reprocesses a state-idempotent event.
        try {
            webhookDedupService.recordNew(eventId, "stripe intent " + intentId);
        } catch (Exception e) {
            log.warn("Failed to record Stripe webhook dedup marker for {}: {}", eventId, e.getMessage());
        }
    }

    /**
     * Simulate payment success (development / testing only).
     * Allowed on INITIATED and FAILED payments; FAILED payments are "retried" in-place
     * so the bookingRef matches. Returns existing SUCCESS unchanged (idempotent).
     */
    @Transactional
    public PaymentDto simulatePayment(String transactionId) {
        if (!paymentSimulationEnabled) {
            throw new BusinessException("Payment simulation is disabled", HttpStatus.FORBIDDEN);
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);

        // Already succeeded — idempotent return
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return toPaymentDtoWithRefunds(payment);
        }

        // Fully or partially refunded — simulation is not applicable
        if (payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(
                "Cannot simulate a " + payment.getStatus() + " payment", HttpStatus.CONFLICT);
        }

        // INITIATED or FAILED ? simulate success
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setGatewayPaymentId("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        payment.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        payment.setGatewayResponse("Simulated payment success");
        payment = paymentRepository.save(payment);

        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        log.info("Simulated payment success for: {}", transactionId);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Cancel an INITIATED payment (customer-initiated, before it reaches the gateway).
     * The customerId check ensures customers can only cancel their own payments.
     */
    @Transactional
    public PaymentDto cancelPayment(String transactionId, Long requesterId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);

        if (!payment.getCustomerId().equals(requesterId)) {
            throw new BusinessException("Not authorized to cancel this payment", HttpStatus.FORBIDDEN);
        }

        if (payment.getStatus() != PaymentStatus.INITIATED) {
            throw new BusinessException(
                "Only INITIATED payments can be cancelled. Current status: " + payment.getStatus(),
                HttpStatus.BAD_REQUEST);
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("Cancelled by customer");
        payment = paymentRepository.save(payment);

        auditLogService.record(
            String.valueOf(requesterId), AuditLogService.ACTION_PAYMENT_CANCEL, "PAYMENT",
            payment.getTransactionId(), payment.getAmount(), payment.getCurrency(),
            payment.getBingeId(),
            java.util.Map.of("bookingRef", payment.getBookingRef()));

        log.info("Payment {} cancelled by customer {}", transactionId, requesterId);
        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto getPaymentByTransactionId(String transactionId, Long requesterId, String requesterRole) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "transactionId", transactionId));
        ensurePaymentInCurrentBinge(payment, "transactionId", transactionId);
        ensurePaymentAccess(payment, requesterId, requesterRole);
        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> getPaymentsByBookingRef(String bookingRef, Long requesterId, String requesterRole) {
        List<Payment> payments = findPaymentsByBookingRefForCurrentBinge(bookingRef);
        boolean admin = isAdminRole(requesterRole);
        if (payments.isEmpty()) {
            // Empty list previously short-circuited as 200 [] for everyone.
            // For non-admin callers that lets an attacker enumerate booking refs
            // (no payments yet ⇒ same response as a real-but-empty result, so they
            // can probe the booking-ref keyspace). Treat unknown / non-owned as 404
            // so a foreign ref is indistinguishable from a non-existent one.
            if (admin) {
                return List.of();
            }
            throw new ResourceNotFoundException("Payment", "bookingRef", bookingRef);
        }
        if (admin) {
            return toPaymentDtoListWithRefunds(payments);
        }

        List<Payment> ownedPayments = payments.stream()
                .filter(payment -> payment.getCustomerId() != null && payment.getCustomerId().equals(requesterId))
                .toList();
        if (ownedPayments.isEmpty()) {
            throw new BusinessException("Not authorized to access payments for this booking", HttpStatus.FORBIDDEN);
        }

        return toPaymentDtoListWithRefunds(ownedPayments);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> getCustomerPayments(Long customerId) {
        return toPaymentDtoListWithRefunds(findCustomerPaymentsForCurrentBinge(customerId));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PaymentDto> getCustomerPaymentsPaginated(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        Long bingeId = com.skbingegalaxy.common.context.BingeContext.getBingeId();
        org.springframework.data.domain.Page<Payment> page = (bingeId != null)
            ? paymentRepository.findByCustomerIdAndBingeIdOrderByCreatedAtDesc(customerId, bingeId, pageable)
            : paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        return page.map(p -> {
            BigDecimal totalRefunded = refundRepository.sumCompletedRefundsByPaymentId(p.getId(), REFUNDED_STATUSES);
            BigDecimal remaining = p.getAmount().subtract(totalRefunded);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
            int refundCount = (int) refundRepository.countByPaymentIdAndStatusIn(p.getId(), REFUNDED_STATUSES);
            return buildPaymentDto(p, totalRefunded, remaining, refundCount);
        });
    }

    /**
     * Customer lifetime aggregates (FE-001). Captured-ever semantics for
     * lifetime spend (same PAY-010 ledger rule as the admin dashboard), with
     * settled refunds reported separately so the UI can show net if it wants.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCustomerPaymentSummary(Long customerId) {
        BigDecimal lifetimeSpend = paymentRepository.sumAmountByCustomerIdAndStatusIn(
            customerId, CAPTURED_STATUSES);
        long totalTransactions = paymentRepository.countByCustomerId(customerId);
        long capturedTransactions = paymentRepository.countByCustomerIdAndStatusIn(
            customerId, CAPTURED_STATUSES);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("lifetimeSpend", lifetimeSpend);
        body.put("totalTransactions", totalTransactions);
        body.put("capturedTransactions", capturedTransactions);
        return body;
    }

    /**
     * Initiate a refund for an admin (PAY-006 — durable intent first):
     * <ol>
     *   <li>{@link #reserveRefundIntent} commits an INITIATED refund row with a
     *       stable gateway receipt BEFORE any provider I/O (own transaction,
     *       under the pessimistic payment lock + over-refund guard);</li>
     *   <li>{@link #processReservedRefund} moves the money OUTSIDE any DB
     *       transaction and finalizes the outcome in a second transaction.</li>
     * </ol>
     * A crash or timeout anywhere leaves the durable intent behind; the
     * reconciliation poller resolves it by looking the receipt up at the
     * provider — never by blindly re-sending. At-most-once money movement.
     */
    public RefundDto initiateRefund(RefundRequest request, String initiatedBy) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ONE) < 0) {
            throw new BusinessException("Refund amount must be at least ₹1.00", HttpStatus.BAD_REQUEST);
        }
        Long intentId = self.reserveRefundIntent(request.getPaymentId(), request.getAmount(),
            request.getReason(), initiatedBy, null, true);
        return self.processReservedRefund(intentId, false);
    }

    /**
     * TX-A of the refund saga: validates eligibility + over-refund under the
     * pessimistic payment lock and commits the durable INITIATED intent with
     * its stable receipt. {@code REQUIRES_NEW} so the intent survives whatever
     * the caller's transaction later does.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Long reserveRefundIntent(Long paymentId, BigDecimal amount, String reason,
                                    String initiatedBy, Long retryOfId, boolean enforceBingeScope) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId.toString()));
        if (enforceBingeScope) {
            ensurePaymentInCurrentBinge(payment, "id", paymentId);
        }
        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(
                "Cannot refund a payment with status: " + payment.getStatus()
                    + ". Only SUCCESS or PARTIALLY_REFUNDED payments are eligible.",
                HttpStatus.BAD_REQUEST);
        }
        return createRefundIntentRow(payment, amount, reason, initiatedBy, retryOfId).getId();
    }

    /**
     * Creates the durable refund-intent row. Caller must hold the pessimistic
     * payment row lock (or be inside the transaction that already does).
     * Shared by {@link #reserveRefundIntent} and the late-capture auto-refund,
     * which already holds the lock inside the callback transaction.
     */
    private Refund createRefundIntentRow(Payment payment, BigDecimal amount, String reason,
                                         String initiatedBy, Long retryOfId) {
        // DB-level authoritative sum to prevent race-condition over-refunds.
        // INITIATED intents count too — they may still claim provider money.
        BigDecimal alreadyClaimed = refundRepository.sumByPaymentIdAndRefundStatusIn(
            payment.getId(), ACTIVE_REFUND_STATUSES);
        BigDecimal remaining = payment.getAmount().subtract(alreadyClaimed);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("This payment has already been fully refunded", HttpStatus.CONFLICT);
        }
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(
                String.format("Refund amount ₹%.2f exceeds remaining refundable amount ₹%.2f",
                    amount, remaining),
                HttpStatus.BAD_REQUEST);
        }

        Refund intent = refundRepository.save(Refund.builder()
            .payment(payment)
            .amount(amount)
            .reason(reason)
            .status(PaymentStatus.INITIATED)
            .refundStatus(com.skbingegalaxy.payment.entity.RefundStatus.INITIATED)
            .retryOfId(retryOfId)
            .initiatedBy(initiatedBy)
            .build());
        // Stable per-attempt receipt — needs the generated id, hence the
        // second save inside the same transaction.
        intent.setGatewayReceipt("rfd-" + payment.getTransactionId() + "-" + intent.getId());
        intent = refundRepository.save(intent);
        log.info("Refund intent {} reserved: {} {} for payment {} (receipt {})",
            intent.getId(), amount, payment.getCurrency(), payment.getTransactionId(),
            intent.getGatewayReceipt());
        return intent;
    }

    /**
     * Phase 2+3 of the refund saga: provider leg OUTSIDE any DB transaction,
     * then outcome finalization in its own transaction. Also invoked by the
     * reconciliation poller for intents stranded by a crash/timeout — with
     * {@code recoverFirst} it asks the provider for the receipt BEFORE creating
     * anything, so a refund that actually went through is adopted, not repeated.
     *
     * <p>Outcome mapping:
     * <ul>
     *   <li>provider processed → SUCCEEDED (payment recomputed, event published);</li>
     *   <li>provider accepted → PROCESSING (webhook/reconciliation settles);</li>
     *   <li>provider definitively rejected → FAILED (admin queue);</li>
     *   <li>ambiguous (timeout/IO) → row STAYS INITIATED for reconciliation —
     *       never marked FAILED, because the money may have moved.</li>
     * </ul>
     */
    public RefundDto processReservedRefund(Long refundId, boolean recoverFirst) {
        Refund intent = refundRepository.findWithPaymentById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        if (intent.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.INITIATED) {
            return toRefundDto(intent); // already settled/failed by webhook or a concurrent worker
        }
        Payment payment = intent.getPayment();
        boolean offline = !isGatewayBacked(payment);

        RazorpayGatewayClient.GatewayRefundResult gatewayResult;
        if (offline) {
            // Cash / admin-recorded / simulated payments: no gateway leg exists.
            // The refund row is book-keeping for money returned offline.
            gatewayResult = new RazorpayGatewayClient.GatewayRefundResult(
                "RFD-LOCAL-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                "processed");
        } else if ("stripe".equalsIgnoreCase(payment.getProviderName())) {
            // Refund must go back through the gateway that took the money. Stripe
            // charges are direct charges on the venue's connected account, so the
            // refund has to name that account too — a platform-scoped refund would
            // not find the charge at all.
            String stripeAccount = connectedAccountService
                .findForBinge(payment.getBingeId())
                .map(com.skbingegalaxy.payment.entity.PaymentConnectedAccount::getAccountId)
                .orElse(null);
            try {
                var stripeResult = stripeGatewayClient.createRefund(
                    payment.getGatewayPaymentId(), intent.getAmount(),
                    payment.getCurrency(), stripeAccount, intent.getGatewayReceipt());
                gatewayResult = new RazorpayGatewayClient.GatewayRefundResult(
                    stripeResult.refundId(), stripeResult.status());
            } catch (org.springframework.web.client.RestClientResponseException definite) {
                return toRefundDto(self.finalizeRefundFailure(refundId,
                    truncate("Stripe rejected the refund: " + definite.getMessage(), 250)));
            } catch (BusinessException definite) {
                return toRefundDto(self.finalizeRefundFailure(refundId,
                    truncate(definite.getMessage(), 250)));
            } catch (Exception ambiguous) {
                // Same fail-safe rule as Razorpay: a timeout may or may not have
                // refunded. Leave INITIATED rather than risk a double refund; the
                // idempotency key on the receipt makes the reconciliation retry safe.
                metrics.refundPendingGateway();
                log.error("Stripe refund intent {} AMBIGUOUS ({}). Left INITIATED for reconciliation.",
                    intent.getId(), ambiguous.getMessage());
                return toRefundDto(intent);
            }
        } else {
            // Ambiguity recovery: an earlier attempt may already have created
            // this refund at the provider. Adopt it instead of re-sending.
            if (recoverFirst) {
                Optional<RazorpayGatewayClient.GatewayRefundResult> existing =
                    razorpayGatewayClient.findRefundByReceipt(
                        payment.getGatewayPaymentId(), intent.getGatewayReceipt());
                if (existing == null) {
                    log.warn("Refund intent {} recovery lookup ambiguous — leaving INITIATED for next pass",
                        intent.getId());
                    return toRefundDto(intent);
                }
                if (existing.isPresent()) {
                    log.info("Refund intent {} recovered from provider: {} ({})",
                        intent.getId(), existing.get().refundId(), existing.get().status());
                    return toRefundDto(self.finalizeRefundOutcome(
                        refundId, existing.get().refundId(), existing.get().status(), false));
                }
                // Authoritative absence — safe to create below.
            }
            try {
                gatewayResult = razorpayGatewayClient.createRefund(
                    payment.getGatewayPaymentId(), intent.getAmount(),
                    payment.getCurrency(), intent.getGatewayReceipt());
            } catch (org.springframework.web.client.RestClientResponseException definite) {
                // Provider answered with an error status — the refund was NOT created.
                return toRefundDto(self.finalizeRefundFailure(refundId,
                    truncate("Razorpay rejected the refund: " + definite.getMessage(), 250)));
            } catch (BusinessException definite) {
                // Local definite failure (no gateway payment id / empty response).
                return toRefundDto(self.finalizeRefundFailure(refundId,
                    truncate(definite.getMessage(), 250)));
            } catch (Exception ambiguous) {
                // Timeout / connection drop: the provider may or may not have
                // processed it. The durable intent stays INITIATED; the
                // reconciliation poller resolves it via receipt lookup.
                metrics.refundPendingGateway();
                log.error("Refund intent {} AMBIGUOUS after provider call ({}). "
                    + "Left INITIATED for receipt-based reconciliation — NOT retried blindly.",
                    intent.getId(), ambiguous.getMessage());
                return toRefundDto(intent);
            }
        }
        return toRefundDto(self.finalizeRefundOutcome(
            refundId, gatewayResult.refundId(), gatewayResult.status(), offline));
    }

    /**
     * TX-B of the refund saga: records what the provider actually did.
     * Idempotent — only INITIATED intents transition; a webhook that settled
     * the row first wins. On acceptance (settled or processing) a retried
     * original is marked SUPERSEDED here, never before money moved.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Refund finalizeRefundOutcome(Long refundId, String gatewayRefundId,
                                        String gatewayStatus, boolean offline) {
        Refund refund = refundRepository.findWithPaymentById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        final Long parentPaymentId = refund.getPayment().getId();
        Payment payment = paymentRepository.findByIdForUpdate(parentPaymentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Payment", "id", parentPaymentId.toString()));
        if (refund.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.INITIATED) {
            return refund;
        }
        if ("failed".equalsIgnoreCase(gatewayStatus)) {
            return markRefundFailed(refund, payment,
                "Razorpay reported the refund as failed (refund id " + gatewayRefundId + ")");
        }

        boolean settled = "processed".equalsIgnoreCase(gatewayStatus);
        refund.setGatewayRefundId(gatewayRefundId);
        refund.setStatus(settled ? PaymentStatus.REFUNDED : PaymentStatus.INITIATED);
        refund.setRefundStatus(settled
            ? com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED
            : com.skbingegalaxy.payment.entity.RefundStatus.PROCESSING);
        refund.setRefundedAt(settled ? LocalDateTime.now(ZoneOffset.UTC) : null);
        refund.setGatewayResponse(offline
            ? "Local settle — payment was not gateway-backed (cash/simulated); money returned offline"
            : "Razorpay refund " + gatewayRefundId + " status=" + gatewayStatus);
        refund = refundRepository.save(refund);

        if (settled) {
            PaymentStatus oldStatus = payment.getStatus();
            recomputePaymentStatusFromSettledRefunds(payment);
            recordStatusChange(payment, oldStatus, payment.getStatus(),
                refund.getReason() != null ? refund.getReason() : "Refund settled");
            // Notify downstream (e.g. notification service sends refund email)
            publishRefundEvent(payment, refund);
            metrics.refundIssued();
        } else {
            metrics.refundPendingGateway();
            log.info("Refund {} accepted by gateway, awaiting settlement (payment {})",
                gatewayRefundId, payment.getTransactionId());
        }

        // Retry linkage: the provider accepted the new attempt — retire the
        // original FAILED row so the queue doesn't offer it again.
        if (refund.getRetryOfId() != null) {
            refundRepository.findById(refund.getRetryOfId()).ifPresent(original -> {
                original.setRefundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUPERSEDED);
                original.setRetryCount(original.getRetryCount() + 1);
                refundRepository.save(original);
            });
        }

        auditLogService.record(
            refund.getInitiatedBy(), AuditLogService.ACTION_REFUND_ISSUED, "PAYMENT",
            payment.getTransactionId(),
            refund.getAmount(), payment.getCurrency(), payment.getBingeId(),
            withMeta(Map.of(
                    "reason", refund.getReason() == null ? "" : refund.getReason(),
                    "bookingRef", payment.getBookingRef()),
                "refundId", gatewayRefundId,
                "gatewayStatus", settled ? "processed" : gatewayStatus));

        log.info("Refund {} of {} {} by {} for payment {} (booking: {}) — {}",
            gatewayRefundId, refund.getAmount(), payment.getCurrency(), refund.getInitiatedBy(),
            payment.getTransactionId(), payment.getBookingRef(),
            settled ? "SETTLED" : "PENDING GATEWAY SETTLEMENT");
        return refund;
    }

    /** TX-B failure branch: definite provider rejection → FAILED row for the admin queue. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Refund finalizeRefundFailure(Long refundId, String error) {
        Refund refund = refundRepository.findWithPaymentById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Payment", "id", refund.getPayment().getId().toString()));
        if (refund.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.INITIATED) {
            return refund;
        }
        return markRefundFailed(refund, payment, error);
    }

    private Refund markRefundFailed(Refund refund, Payment payment, String error) {
        metrics.refundGatewayFailed();
        refund.setStatus(PaymentStatus.FAILED);
        refund.setRefundStatus(com.skbingegalaxy.payment.entity.RefundStatus.FAILED);
        refund.setFailureReason(truncate(error, 250));
        refund = refundRepository.save(refund);
        log.error("Gateway refund of {} {} for payment {} FAILED: {}",
            refund.getAmount(), payment.getCurrency(), payment.getTransactionId(), error);
        auditLogService.record(
            refund.getInitiatedBy(), AuditLogService.ACTION_REFUND_FAILED, "PAYMENT",
            payment.getTransactionId(),
            refund.getAmount(), payment.getCurrency(), payment.getBingeId(),
            withMeta(Map.of("bookingRef", payment.getBookingRef()),
                "error", truncate(error, 300)));
        return refund;
    }

    /**
     * Runs the provider leg of a refund intent AFTER the surrounding
     * transaction commits (PAY-006) — or immediately when no transaction
     * synchronization is active (plain unit tests, future non-TX callers).
     * Either way the durable intent is already persisted, so a failure here
     * only delays settlement until the reconciliation poller.
     */
    private void processRefundIntentAfterCommit(Long intentId, String context) {
        Runnable process = () -> {
            try {
                self.processReservedRefund(intentId, false);
            } catch (Exception ex) {
                log.error("{} intent {} could not be processed now ({}). Reconciliation will complete it.",
                    context, intentId, ex.getMessage());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        process.run();
                    }
                });
        } else {
            process.run();
        }
    }

    /** True when the payment was captured by the real gateway (Razorpay order). */
    private static boolean isGatewayBacked(Payment payment) {
        return payment.getGatewayOrderId() != null
            && payment.getGatewayOrderId().startsWith("order_");
    }

    /**
     * Recomputes the parent payment's terminal money-state from SETTLED refunds
     * only. In-flight PROCESSING refunds do not flip the payment status — that
     * happens when the webhook/reconciliation settles them.
     */
    private void recomputePaymentStatusFromSettledRefunds(Payment payment) {
        BigDecimal settledTotal = refundRepository.sumCompletedRefundsByPaymentId(
            payment.getId(), REFUNDED_STATUSES);
        if (settledTotal.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else if (settledTotal.compareTo(BigDecimal.ZERO) > 0) {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        }
        paymentRepository.save(payment);
    }

    private static Map<String, Object> withMeta(Map<String, String> base, String... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * The booking's locked payment currency (native per-binge model). Admin-recorded
     * payments (cash / split / method change) must be denominated in the SAME
     * currency as the booking — the old hardcoded "INR" mislabelled every payment
     * taken at a foreign-currency venue. Best-effort: falls back to INR when
     * booking-service is unreachable so cash collection never blocks.
     */
    private String resolveBookingCurrency(String bookingRef) {
        try {
            var snap = bookingAmountClient.fetchSnapshot(bookingRef);
            if (snap != null && snap.paymentCurrencyCode() != null) {
                return snap.paymentCurrencyCode();
            }
        } catch (Exception e) {
            log.warn("Could not resolve currency for booking {} — defaulting to INR: {}",
                bookingRef, e.getMessage());
        }
        return "INR";
    }

    /**
     * Authoritative booking binding for admin manual writes (SEC-011).
     *
     * <p>The booking snapshot — not the request body — decides the tenant,
     * the owner and the collectable ceiling. Fail-closed: no snapshot, no
     * money movement. The admin's selected binge (already ownership-validated
     * by the controller's {@code requireManagedBinge}) must MATCH the
     * booking's binge, otherwise an admin could book-keep money onto another
     * tenant's booking and publish a success event against it.</p>
     */
    private com.skbingegalaxy.payment.client.BookingAmountClient.BookingSnapshot requireBoundSnapshotForAdminWrite(
            String bookingRef, BigDecimal amount, String action) {
        var snapshot = bookingAmountClient.fetchSnapshot(bookingRef);
        if (snapshot == null) {
            throw new BusinessException(
                "Unable to verify the booking — booking-service unavailable. Please retry.",
                HttpStatus.SERVICE_UNAVAILABLE);
        }
        String st = snapshot.status();
        if ("CANCELLED".equals(st) || "NO_SHOW".equals(st) || "EXPIRED".equals(st)) {
            throw new BusinessException(
                "This booking is " + st.toLowerCase() + " — record adjustments through the refund flow instead.",
                HttpStatus.CONFLICT);
        }
        Long selectedBinge = getCurrentBingeId();
        if (snapshot.bingeId() != null && selectedBinge != null
                && !snapshot.bingeId().equals(selectedBinge)) {
            throw new BusinessException(
                "This booking belongs to a different binge than the one you have selected.",
                HttpStatus.FORBIDDEN);
        }
        // Over-collection ceiling from the AUTHORITATIVE remaining balance
        // (1-paisa tolerance absorbs rounding) — the caller-supplied ceiling
        // is ignored entirely.
        if (snapshot.remainingBalance() != null
                && amount.subtract(snapshot.remainingBalance()).compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(
                String.format("Payment rejected — ₹%s exceeds the booking's remaining balance ₹%s (%s)",
                    amount, snapshot.remainingBalance(), action),
                HttpStatus.CONFLICT);
        }
        return snapshot;
    }

    /**
     * Record a cash payment collected directly by admin.
     * Creates a SUCCESS payment record so refunds can later be issued against it.
     * Idempotent: if a SUCCESS record already exists for the booking, returns it unchanged.
     */
    @Transactional
    public PaymentDto recordCashPayment(RecordCashPaymentRequest request, String adminEmail) {
        log.info("Recording cash payment for booking: {} by admin: {}", request.getBookingRef(), adminEmail);

        // Serialise with concurrent initiations/manual writes for the same booking.
        paymentRepository.acquirePaymentLock(request.getBookingRef().hashCode());

        // Idempotency: a SUCCESS payment already exists — return it
        var existingSuccess = findSuccessfulPaymentsForCurrentBinge(request.getBookingRef());
        if (!existingSuccess.isEmpty()) {
            log.info("Cash payment already recorded for booking {}", request.getBookingRef());
            return toPaymentDtoWithRefunds(existingSuccess.get(0));
        }

        var snapshot = requireBoundSnapshotForAdminWrite(
            request.getBookingRef(), request.getAmount(), "cash payment");

        String transactionId = "CASH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String gatewayOrderId = "CASH-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String currency = snapshot.paymentCurrencyCode() != null ? snapshot.paymentCurrencyCode() : "INR";

        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(snapshot.customerId() != null ? snapshot.customerId() : request.getCustomerId())
            .bingeId(snapshot.bingeId() != null ? snapshot.bingeId() : getCurrentBingeId())
            .transactionId(transactionId)
            .gatewayOrderId(gatewayOrderId)
            .amount(request.getAmount())
            .paymentMethod(PaymentMethod.CASH)
            .status(PaymentStatus.SUCCESS)
            .currency(currency)
            .paidAt(LocalDateTime.now(ZoneOffset.UTC))
            .gatewayResponse("Cash payment recorded by admin: " + adminEmail
                + (request.getNotes() != null && !request.getNotes().isBlank()
                    ? " | " + request.getNotes() : ""))
            .build();

        payment = paymentRepository.save(payment);
        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        auditLogService.record(
            adminEmail, AuditLogService.ACTION_CASH_RECORDED, "PAYMENT", transactionId,
            request.getAmount(), currency, payment.getBingeId(),
            java.util.Map.of(
                "bookingRef", request.getBookingRef(),
                "notes", request.getNotes() == null ? "" : request.getNotes()));
        log.info("Cash payment {} recorded for booking {} by admin {}",
            transactionId, request.getBookingRef(), adminEmail);

        return toPaymentDtoWithRefunds(payment);
    }

    /**
     * Record an additional payment for a booking with any payment method.
     * Used for split payments, method changes, or collecting remaining balances.
     * Idempotency guard: rejects duplicate requests with the same booking, method, and amount within 30 seconds.
     */
    @Transactional
    public PaymentDto addPayment(AddPaymentRequest request, String adminEmail) {
        log.info("Adding {} payment of {} for booking {} by admin {}",
            request.getPaymentMethod(), request.getAmount(), request.getBookingRef(), adminEmail);

        // Serialise with concurrent initiations/manual writes for the same booking.
        paymentRepository.acquirePaymentLock(request.getBookingRef().hashCode());

        // Idempotency guard: reject if an identical payment was recorded within the dedup window
        List<Payment> recentDupes = findRecentDuplicatesForCurrentBinge(
            request.getBookingRef(), request.getPaymentMethod(),
            request.getAmount(), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(dedupWindowSeconds));
        if (!recentDupes.isEmpty()) {
            log.info("Duplicate addPayment detected for booking {} — returning existing payment {}",
                    request.getBookingRef(), recentDupes.get(0).getTransactionId());
            return toPaymentDtoWithRefunds(recentDupes.get(0));
        }

        // SEC-011: authoritative binding — tenant, owner and the collectable
        // ceiling come from booking-service, never from the request body.
        // (The legacy request.bookingTotalAmount field is deliberately ignored.)
        var snapshot = requireBoundSnapshotForAdminWrite(
            request.getBookingRef(), request.getAmount(), "additional payment");

        // Second over-collection fence from this service's OWN ledger: the
        // booking-side collected amount is eventually consistent (updated via
        // payment.success events), so a burst of admin writes could race past
        // the snapshot ceiling alone. Payments rows are local and immediate.
        BigDecimal existingTotal = sumSuccessfulPaymentsForCurrentBinge(request.getBookingRef());
        if (existingTotal == null) existingTotal = BigDecimal.ZERO;
        if (snapshot.totalAmount() != null && existingTotal.add(request.getAmount())
                .subtract(snapshot.totalAmount()).compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessException(
                String.format("Payment rejected — would over-collect: existing ₹%s + ₹%s exceeds booking total ₹%s",
                    existingTotal, request.getAmount(), snapshot.totalAmount()),
                HttpStatus.CONFLICT);
        }

        String prefix = request.getPaymentMethod().name();
        String transactionId = prefix + "-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String gatewayOrderId = "ADM-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String currency = snapshot.paymentCurrencyCode() != null ? snapshot.paymentCurrencyCode() : "INR";

        Payment payment = Payment.builder()
            .bookingRef(request.getBookingRef())
            .customerId(snapshot.customerId() != null ? snapshot.customerId()
                : (request.getCustomerId() != null ? request.getCustomerId() : 0L))
            .bingeId(snapshot.bingeId() != null ? snapshot.bingeId() : getCurrentBingeId())
            .transactionId(transactionId)
            .gatewayOrderId(gatewayOrderId)
            .amount(request.getAmount())
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.SUCCESS)
            .currency(currency)
            .paidAt(LocalDateTime.now(ZoneOffset.UTC))
            .gatewayResponse("Payment recorded by admin: " + adminEmail
                + (request.getNotes() != null && !request.getNotes().isBlank()
                    ? " | " + request.getNotes() : ""))
            .build();

        payment = paymentRepository.save(payment);
        publishPaymentEvent(payment, KafkaTopics.PAYMENT_SUCCESS);
        auditLogService.record(
            adminEmail, AuditLogService.ACTION_PAYMENT_ADDED, "PAYMENT", transactionId,
            request.getAmount(), currency, payment.getBingeId(),
            java.util.Map.of(
                "bookingRef", request.getBookingRef(),
                "method", request.getPaymentMethod().name(),
                "notes", request.getNotes() == null ? "" : request.getNotes()));
        log.info("Additional payment {} recorded for booking {} by admin {}",
            transactionId, request.getBookingRef(), adminEmail);

        return toPaymentDtoWithRefunds(payment);
    }

    @Transactional(readOnly = true)
    public List<RefundDto> getRefundsForPayment(Long paymentId) {
        // Verify payment exists
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId.toString()));
        ensurePaymentInCurrentBinge(payment, "id", paymentId);
        return refundRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId)
            .stream().map(this::toRefundDto).toList();
    }

    /**
     * Customer-facing refund timeline for a booking. Returns every refund row
     * (any lifecycle state) in reverse-chronological order so the UI can show
     * "Refund initiated → processing → succeeded" history.
     *
     * <p>Object-level authorization (SEC-011): a CUSTOMER only ever sees
     * refunds whose parent payment belongs to them — binge scoping alone
     * would let any customer read another customer's refund timeline by
     * guessing a booking ref. Admin/system callers see the binge-scoped set.</p>
     */
    @Transactional(readOnly = true)
    public List<RefundDto> getRefundsForBooking(String bookingRef, Long userId, String role) {
        Long bingeId = getCurrentBingeId();
        var rows = (bingeId != null)
            ? refundRepository.findByBookingRefAndBingeIdOrderByCreatedAtDesc(bookingRef, bingeId)
            : refundRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef);
        var stream = rows.stream();
        if (role == null || "CUSTOMER".equalsIgnoreCase(role)) {
            stream = stream.filter(r -> r.getPayment() != null
                && r.getPayment().getCustomerId() != null
                && r.getPayment().getCustomerId().equals(userId));
        }
        return stream.map(this::toRefundDto).toList();
    }

    /**
     * Admin failed-refund queue. Lists every refund whose own per-attempt
     * lifecycle ended in {@link com.skbingegalaxy.payment.entity.RefundStatus#FAILED},
     * scoped to the currently selected binge.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefundDto> getFailedRefunds(
            org.springframework.data.domain.Pageable pageable) {
        Long bingeId = getCurrentBingeId();
        if (bingeId == null) {
            throw new BusinessException(
                "No binge selected for failed-refund query", HttpStatus.BAD_REQUEST);
        }
        return refundRepository
            .findByRefundStatusAndPayment_BingeIdOrderByCreatedAtDesc(
                com.skbingegalaxy.payment.entity.RefundStatus.FAILED, bingeId, pageable)
            .map(this::toRefundDto);
    }

    /**
     * Admin retry of a failed refund. Marks the original row as
     * {@link com.skbingegalaxy.payment.entity.RefundStatus#SUPERSEDED}, creates
     * a new {@link com.skbingegalaxy.payment.entity.RefundStatus#INITIATED}
     * attempt linked via {@code retry_of_id}, and (today, with the synchronous
     * gateway path) immediately settles it as {@code SUCCEEDED}.
     *
     * <p>The over-refund and pessimistic-lock guards from {@link #initiateRefund}
     * are reused so a retry can never push the parent payment past its full
     * refundable amount.
     */
    @Transactional
    public RefundDto retryFailedRefund(Long refundId, String adminEmail) {
        Refund original = refundRepository.findById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        ensurePaymentInCurrentBinge(original.getPayment(), "id", original.getPayment().getId());

        if (original.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            throw new BusinessException(
                "Only FAILED refunds can be retried. Current: " + original.getRefundStatus(),
                HttpStatus.CONFLICT);
        }

        // Maker-checker gate: above the configured threshold, the request must
        // be approved by a different admin first. We create the request here
        // and short-circuit with a 202 ACCEPTED via a typed business exception
        // carrying the approval id, so the caller can poll/notify the second
        // admin. Once approved, the executor calls
        // executeApprovedRefundRetry(approvalId) which performs this method's
        // body without re-checking the threshold.
        if (refundRetryApprovalThreshold != null
                && original.getAmount().compareTo(refundRetryApprovalThreshold) > 0) {
            // Are we already past an APPROVED gate for this same refund?
            // If yes, fall through and execute. If no, create a new request.
            // Here we only INITIATE — the executor path is on the
            // /admin/approvals/{id}/execute-refund-retry endpoint.
            com.skbingegalaxy.payment.entity.AdminApprovalRequest req = approvalService.createRequest(
                "REFUND_RETRY",
                "REFUND",
                String.valueOf(original.getId()),
                original.getAmount(),
                original.getPayment().getCurrency(),
                original.getPayment().getBingeId(),
                java.util.Map.of(
                    "refundId", String.valueOf(original.getId()),
                    "paymentId", String.valueOf(original.getPayment().getId()),
                    "bookingRef", original.getPayment().getBookingRef()),
                adminEmail,
                null,
                "Refund retry above threshold of " + refundRetryApprovalThreshold);
            throw new BusinessException(
                "Refund retry above ₹" + refundRetryApprovalThreshold
                    + " requires a second admin's approval. "
                    + "Approval request id: " + req.getId(),
                HttpStatus.ACCEPTED);
        }

        return doRetryFailedRefund(original, adminEmail, null);
    }

    /**
     * Domain action invoked by the maker-checker controller after a different
     * admin has APPROVED the request. Re-validates the approval, executes the
     * retry without the threshold gate, and stamps the approval as EXECUTED
     * so the same approval can never be replayed.
     */
    @Transactional
    public java.util.Map<String, Object> executeApprovedRefundRetry(Long approvalId, String executorEmail) {
        com.skbingegalaxy.payment.dto.AdminApprovalRequestDto approval = approvalService.get(approvalId);
        if (!"REFUND_RETRY".equals(approval.getActionType())) {
            throw new BusinessException(
                "Approval is for action " + approval.getActionType()
                    + " — not REFUND_RETRY",
                HttpStatus.BAD_REQUEST);
        }
        if (!"APPROVED".equals(approval.getStatus())) {
            throw new BusinessException(
                "Only APPROVED approvals can be executed. Current: " + approval.getStatus(),
                HttpStatus.CONFLICT);
        }
        Long refundId = Long.parseLong(approval.getResourceId());
        Refund original = refundRepository.findById(refundId)
            .orElseThrow(() -> new ResourceNotFoundException("Refund", "id", refundId.toString()));
        // SEC-010 defence in depth: the refund's own payment must live in the
        // binge the approval was raised for. A mismatch means the approval row
        // was tampered with or the refund was re-parented — never execute.
        if (!java.util.Objects.equals(approval.getBingeId(), original.getPayment().getBingeId())) {
            throw new BusinessException(
                "Approval binge does not match the refund's payment binge — refusing to execute",
                HttpStatus.CONFLICT);
        }
        if (original.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            throw new BusinessException(
                "Underlying refund is no longer FAILED (now: "
                    + original.getRefundStatus() + ") — approval cannot be executed",
                HttpStatus.CONFLICT);
        }
        RefundDto retried = doRetryFailedRefund(original, executorEmail, approvalId);
        approvalService.markExecuted(approvalId,
            retried.getGatewayRefundId() != null
                ? "Refund retried; new gateway id: " + retried.getGatewayRefundId()
                : "Refund retried; submitted to provider (" + retried.getRefundStatus()
                    + ") — reconciliation will confirm");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("approvalId", approvalId);
        body.put("refund", retried);
        return body;
    }

    /**
     * Inner refund-retry: shared by direct (below-threshold) and approval-driven
     * (above-threshold) call sites. PAY-006 flow: a fresh durable intent (linked
     * via {@code retry_of_id}) is committed first; the provider leg runs outside
     * any transaction; the ORIGINAL row is marked SUPERSEDED only inside the
     * finalize transaction, after the provider accepted the money movement —
     * so a definite gateway failure leaves the original FAILED and retryable.
     */
    private RefundDto doRetryFailedRefund(Refund original, String adminEmail, Long approvalId) {
        Long intentId = self.reserveRefundIntent(original.getPayment().getId(), original.getAmount(),
            original.getReason(), adminEmail, original.getId(), false);
        RefundDto retried = self.processReservedRefund(intentId, false);

        if (retried.getRefundStatus() == com.skbingegalaxy.payment.entity.RefundStatus.FAILED) {
            // Definite provider rejection: surface it (502) — the original row
            // was NOT superseded and stays in the failed-refund queue.
            throw new BusinessException(
                "Gateway refund failed: "
                    + (retried.getFailureReason() != null ? retried.getFailureReason() : "provider rejected the refund"),
                HttpStatus.BAD_GATEWAY);
        }

        log.info("Refund retry intent {} ({}) of {} (orig refund id {}) by admin {}{}",
            intentId, retried.getRefundStatus(), retried.getAmount(), original.getId(), adminEmail,
            approvalId != null ? " (approval " + approvalId + ")" : "");
        return retried;
    }

    /**
     * Settles an in-flight (PROCESSING) gateway refund from an authoritative
     * gateway signal — the {@code refund.processed}/{@code refund.failed}
     * webhook or the reconciliation poller. Idempotent: already-settled rows
     * are left untouched.
     *
     * @return short outcome string for logging
     */
    /**
     * Chargeback ledger entry (PAY-009): a LOST/ACCEPTED dispute means the
     * gateway already moved money back to the customer — the ledger must say
     * so with a real Refund row (so revenue, booking collected-amount and the
     * customer timeline all update through the same path as ordinary refunds),
     * never with a bare payment-status flip.
     *
     * <p>Idempotent by the gateway dispute id. The amount is clamped to the
     * remaining refundable amount so a dispute arriving after partial refunds
     * cannot double-count; a clamp is loudly logged for ops.</p>
     *
     * <p>Caller must already hold the payment row lock (the dispute handler
     * locks via {@code findByGatewayOrderIdForUpdate}) and run in a transaction.</p>
     */
    @Transactional
    public void applyChargeback(Payment payment, String gatewayDisputeId, BigDecimal amount, String reason) {
        if (refundRepository.findByGatewayRefundId(gatewayDisputeId).isPresent()) {
            log.info("Chargeback {} already recorded for payment {} — idempotent skip",
                gatewayDisputeId, payment.getTransactionId());
            return;
        }
        BigDecimal alreadyClaimed = refundRepository.sumByPaymentIdAndRefundStatusIn(
            payment.getId(), ACTIVE_REFUND_STATUSES);
        BigDecimal remaining = payment.getAmount().subtract(alreadyClaimed);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("CHARGEBACK_OVERLAP: dispute {} lost for payment {} but the payment is already fully "
                + "refunded/claimed locally. No ledger row created — ops must reconcile with the provider.",
                gatewayDisputeId, payment.getTransactionId());
            return;
        }
        BigDecimal chargeback = amount != null ? amount.min(remaining) : remaining;
        if (amount != null && chargeback.compareTo(amount) < 0) {
            log.warn("Chargeback {} clamped from {} to remaining refundable {} for payment {}",
                gatewayDisputeId, amount, chargeback, payment.getTransactionId());
        }

        Refund row = refundRepository.save(Refund.builder()
            .payment(payment)
            .amount(chargeback)
            .reason(reason)
            .gatewayRefundId(gatewayDisputeId)
            .status(PaymentStatus.REFUNDED)
            .refundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED)
            .initiatedBy("RAZORPAY_DISPUTE")
            .refundedAt(LocalDateTime.now(ZoneOffset.UTC))
            .gatewayResponse("Chargeback — funds deducted by the gateway (" + gatewayDisputeId + ")")
            .build());

        PaymentStatus oldStatus = payment.getStatus();
        recomputePaymentStatusFromSettledRefunds(payment);
        recordStatusChange(payment, oldStatus, payment.getStatus(), reason);
        publishRefundEvent(payment, row);
        log.warn("Chargeback ledger row {} of {} {} recorded for payment {} (booking {})",
            gatewayDisputeId, chargeback, payment.getCurrency(),
            payment.getTransactionId(), payment.getBookingRef());
    }

    /**
     * Dispute-won restoration (PAY-009): recompute the payment's money state
     * from its settled refund ledger instead of blindly stamping SUCCESS —
     * partial refunds settled during the dispute must survive the win.
     * Only a payment currently frozen in DISPUTED is touched.
     */
    @Transactional
    public void restoreAfterDisputeWon(Payment payment, String gatewayDisputeId) {
        if (payment.getStatus() != PaymentStatus.DISPUTED) {
            log.info("Dispute {} won but payment {} is {} (not DISPUTED) — leaving status untouched",
                gatewayDisputeId, payment.getTransactionId(), payment.getStatus());
            return;
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        recomputePaymentStatusFromSettledRefunds(payment); // downgrades to PARTIALLY_REFUNDED/REFUNDED if applicable
        recordStatusChange(payment, PaymentStatus.DISPUTED, payment.getStatus(),
            "Dispute " + gatewayDisputeId + " won — funds released by gateway");
    }

    /**
     * Reconciliation row-op (REL-002: one short transaction per row, never a
     * batch-wide one): marks a stale INITIATED payment FAILED. Re-checks the
     * status inside the transaction — a callback that landed in the meantime
     * wins and the row is left alone.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean markStaleInitiatedFailed(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.INITIATED) {
            return false;
        }
        PaymentStatus oldStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(truncate(reason, 250));
        paymentRepository.save(payment);
        statusHistoryRepository.save(com.skbingegalaxy.payment.entity.PaymentStatusHistory.builder()
            .paymentId(payment.getId())
            .bookingRef(payment.getBookingRef())
            .fromStatus(oldStatus)
            .toStatus(PaymentStatus.FAILED)
            .reason(truncate(reason, 250))
            .build());
        return true;
    }

    /**
     * Reconciliation row-op: the provider says the order is PAID but we still
     * have it INITIATED (missed callback). Status is deliberately NOT changed
     * — a human must reconcile — but the row is flagged for the ops queue.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean flagGatewayPaidMismatch(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.INITIATED) {
            return false;
        }
        // Idempotent: the row stays INITIATED until a human resolves it, so
        // every 5-minute pass would otherwise re-flag it and spam the status
        // history with an identical row per pass.
        if (payment.getFailureReason() != null
                && payment.getFailureReason().startsWith("Reconciliation: gateway reports PAID")) {
            return false;
        }
        payment.setFailureReason("Reconciliation: gateway reports PAID but callback was missed — needs manual review");
        paymentRepository.save(payment);
        statusHistoryRepository.save(com.skbingegalaxy.payment.entity.PaymentStatusHistory.builder()
            .paymentId(payment.getId())
            .bookingRef(payment.getBookingRef())
            .fromStatus(payment.getStatus())
            .toStatus(payment.getStatus()) // unchanged — needs human intervention
            .reason("Reconciliation: Razorpay order PAID but callback missed. Manual review required.")
            .build());
        return true;
    }

    @Transactional
    public String settleRefundFromGateway(String gatewayRefundId, String gatewayStatus, String source) {
        return settleRefundFromGateway(gatewayRefundId, null, gatewayStatus, source);
    }

    @Transactional
    public String settleRefundFromGateway(String gatewayRefundId, String receipt,
                                          String gatewayStatus, String source) {
        Refund lookup = refundRepository.findByGatewayRefundId(gatewayRefundId).orElse(null);
        if (lookup == null && receipt != null && !receipt.isBlank()) {
            // PAY-006 recovery: the intent exists but its finalize step crashed
            // before the gateway refund id was recorded — find it by our stable
            // receipt, which the provider echoes back in the webhook payload.
            lookup = refundRepository.findByGatewayReceipt(receipt).orElse(null);
            if (lookup != null) {
                log.info("Refund settle signal matched intent {} by receipt {} (gateway id {} was not yet recorded)",
                    lookup.getId(), receipt, gatewayRefundId);
            }
        }
        if (lookup == null) {
            log.warn("Refund settle signal from {} references unknown gateway refund {}", source, gatewayRefundId);
            return "unknown_refund";
        }
        final Refund found = lookup; // effectively-final capture for the lambdas below
        // Lock the parent payment FIRST (same order as initiateRefund) so the
        // settle cannot race a concurrent refund attempt, then re-read the row.
        Payment payment = paymentRepository.findByIdForUpdate(found.getPayment().getId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Payment", "id", found.getPayment().getId().toString()));
        Refund refund = refundRepository.findById(found.getId()).orElse(found);

        if (refund.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.PROCESSING
                && refund.getRefundStatus() != com.skbingegalaxy.payment.entity.RefundStatus.INITIATED) {
            return "already_settled:" + refund.getRefundStatus();
        }
        if (refund.getGatewayRefundId() == null || refund.getGatewayRefundId().isBlank()) {
            refund.setGatewayRefundId(gatewayRefundId);
        }

        if ("processed".equalsIgnoreCase(gatewayStatus)) {
            refund.setRefundStatus(com.skbingegalaxy.payment.entity.RefundStatus.SUCCEEDED);
            refund.setStatus(PaymentStatus.REFUNDED);
            refund.setRefundedAt(LocalDateTime.now(ZoneOffset.UTC));
            refund.setGatewayResponse("Settled via " + source);
            refundRepository.save(refund);

            PaymentStatus oldStatus = payment.getStatus();
            recomputePaymentStatusFromSettledRefunds(payment);
            recordStatusChange(payment, oldStatus, payment.getStatus(),
                "Gateway refund " + gatewayRefundId + " settled (" + source + ")");
            publishRefundEvent(payment, refund);
            metrics.refundSettled();
            auditLogService.record(
                source, AuditLogService.ACTION_REFUND_SETTLED, "PAYMENT", payment.getTransactionId(),
                refund.getAmount(), payment.getCurrency(), payment.getBingeId(),
                java.util.Map.of("refundId", gatewayRefundId, "bookingRef", payment.getBookingRef()));
            log.info("Refund {} settled as SUCCEEDED via {} (payment {})",
                gatewayRefundId, source, payment.getTransactionId());
            return "refund_settled";
        }

        if ("failed".equalsIgnoreCase(gatewayStatus)) {
            refund.setRefundStatus(com.skbingegalaxy.payment.entity.RefundStatus.FAILED);
            refund.setStatus(PaymentStatus.FAILED);
            refund.setFailureReason("Gateway reported refund failed (" + source + ")");
            refundRepository.save(refund);
            metrics.refundGatewayFailed();
            auditLogService.record(
                source, AuditLogService.ACTION_REFUND_FAILED, "PAYMENT", payment.getTransactionId(),
                refund.getAmount(), payment.getCurrency(), payment.getBingeId(),
                java.util.Map.of("refundId", gatewayRefundId, "bookingRef", payment.getBookingRef()));
            log.error("Refund {} FAILED at gateway (via {}) — surfaced in the failed-refund queue",
                gatewayRefundId, source);
            return "refund_failed";
        }

        return "still_pending";
    }

    /**
     * Admin dashboard statistics for the payment service.
     */
    @Transactional(readOnly = true, timeout = 10)
    public Map<String, Object> getPaymentStats() {
        // PAY-010 — ledger semantics: gross = everything EVER captured
        // (SUCCESS + DISPUTED + refund presentation states), immutable across
        // refunds; refunds/chargebacks are subtracted from the refund ledger
        // EXACTLY ONCE. The old current-status gross made a ₹100 charge with a
        // ₹30 refund report gross 0 / net −30 instead of gross 100 / net 70.
        BigDecimal capturedGross  = getCapturedGrossForCurrentBinge();
        BigDecimal totalRefunded  = getTotalRefundedForCurrentBinge();
        long successCount         = countPaymentsByStatusForCurrentBinge(PaymentStatus.SUCCESS);
        long failedCount          = countPaymentsByStatusForCurrentBinge(PaymentStatus.FAILED);
        long initiatedCount       = countPaymentsByStatusForCurrentBinge(PaymentStatus.INITIATED);
        long refundedCount        = countPaymentsByStatusForCurrentBinge(PaymentStatus.REFUNDED);
        long partialCount         = countPaymentsByStatusForCurrentBinge(PaymentStatus.PARTIALLY_REFUNDED);

        return Map.of(
            "totalRevenue",            capturedGross,
            "totalRefunded",           totalRefunded,
            "netRevenue",              capturedGross.subtract(totalRefunded),
            "successCount",            successCount,
            "failedCount",             failedCount,
            "initiatedCount",          initiatedCount,
            "refundedCount",           refundedCount,
            "partiallyRefundedCount",  partialCount
        );
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void recordStatusChange(Payment payment, PaymentStatus from, PaymentStatus to, String reason) {
        statusHistoryRepository.save(com.skbingegalaxy.payment.entity.PaymentStatusHistory.builder()
            .paymentId(payment.getId())
            .bookingRef(payment.getBookingRef())
            .fromStatus(from)
            .toStatus(to)
            .reason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason)
            .build());
    }

    private void publishPaymentEvent(Payment payment, String topic) {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef(payment.getBookingRef())
            .bingeId(payment.getBingeId())
            .transactionId(payment.getTransactionId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .paymentMethod(payment.getPaymentMethod().name())
            .status(payment.getStatus().name())
            .customerEmail(payment.getCustomerEmail())
            .customerName(payment.getCustomerName())
            .customerPhone(payment.getCustomerPhone())
            .customerPhoneCountryCode(payment.getCustomerPhoneCountryCode())
            .paidAt(payment.getPaidAt())
            .build();

        eventPublisher.publishEvent(new PaymentKafkaEvent(topic, payment.getBookingRef(), event));
        log.debug("Queued {} event for booking: {} (publishes after commit)", topic, payment.getBookingRef());
    }

    private void publishRefundEvent(Payment payment, Refund refund) {
        PaymentEvent event = PaymentEvent.builder()
            .bookingRef(payment.getBookingRef())
            .bingeId(payment.getBingeId())
            .transactionId(payment.getTransactionId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .paymentMethod(payment.getPaymentMethod().name())
            .status(payment.getStatus().name())
            .refundId(refund.getGatewayRefundId())
            .refundAmount(refund.getAmount())
            .refundReason(refund.getReason())
            .build();

        eventPublisher.publishEvent(new PaymentKafkaEvent(KafkaTopics.PAYMENT_REFUNDED, payment.getBookingRef(), event));
        log.debug("Queued payment.refunded event for booking: {} (publishes after commit)", payment.getBookingRef());
    }

    private String buildGatewayResponseSummary(PaymentCallbackRequest req) {
        return String.format("status=%s, paymentId=%s, error=%s",
            req.getStatus(), req.getGatewayPaymentId(), req.getErrorDescription());
    }

    /**
     * Maps a Payment to PaymentDto and enriches it with refund summary fields
     * (totalRefunded, remainingRefundable, refundCount) via DB aggregation.
     */
    private PaymentDto toPaymentDtoWithRefunds(Payment p) {
        BigDecimal totalRefunded = refundRepository.sumCompletedRefundsByPaymentId(p.getId(), REFUNDED_STATUSES);
        BigDecimal remaining = p.getAmount().subtract(totalRefunded);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
        int refundCount = (int) refundRepository.countByPaymentIdAndStatusIn(p.getId(), REFUNDED_STATUSES);

        return buildPaymentDto(p, totalRefunded, remaining, refundCount);
    }

    /**
     * Batch-optimised: maps a list of Payments to PaymentDtos with only 2 DB queries total
     * instead of 2×N (avoids the N+1 problem for list endpoints).
     */
    private List<PaymentDto> toPaymentDtoListWithRefunds(List<Payment> payments) {
        if (payments.isEmpty()) return List.of();

        List<Long> ids = payments.stream().map(Payment::getId).toList();
        Map<Long, BigDecimal> sumMap = new java.util.HashMap<>();
        for (Object[] row : refundRepository.sumCompletedRefundsByPaymentIds(ids, REFUNDED_STATUSES)) {
            sumMap.put((Long) row[0], (BigDecimal) row[1]);
        }
        Map<Long, Long> countMap = new java.util.HashMap<>();
        for (Object[] row : refundRepository.countRefundsByPaymentIds(ids, REFUNDED_STATUSES)) {
            countMap.put((Long) row[0], (Long) row[1]);
        }

        return payments.stream().map(p -> {
            BigDecimal totalRefunded = sumMap.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal remaining = p.getAmount().subtract(totalRefunded);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
            int refundCount = countMap.getOrDefault(p.getId(), 0L).intValue();
            return buildPaymentDto(p, totalRefunded, remaining, refundCount);
        }).toList();
    }

    private PaymentDto buildPaymentDto(Payment p, BigDecimal totalRefunded,
                                        BigDecimal remaining, int refundCount) {
        return PaymentDto.builder()
            .id(p.getId())
            .bookingRef(p.getBookingRef())
            .customerId(p.getCustomerId())
            .transactionId(p.getTransactionId())
            .gatewayOrderId(p.getGatewayOrderId())
            .gatewayPaymentId(p.getGatewayPaymentId())
            .amount(p.getAmount())
            .gatewayFee(p.getGatewayFee())
            .tax(p.getTax())
            .paymentMethod(p.getPaymentMethod())
            .status(p.getStatus())
            .currency(p.getCurrency())
            .failureReason(p.getFailureReason())
            .paidAt(p.getPaidAt())
            .createdAt(p.getCreatedAt())
            .totalRefunded(totalRefunded)
            .remainingRefundable(remaining)
            .refundCount(refundCount)
            .razorpayKeyId(p.getStatus() == PaymentStatus.INITIATED ? razorpayKeyId : null)
            .build();
    }

    private RefundDto toRefundDto(Refund r) {
        return RefundDto.builder()
            .id(r.getId())
            .paymentId(r.getPayment().getId())
            .bookingRef(r.getPayment().getBookingRef())
            .amount(r.getAmount())
            .reason(r.getReason())
            .gatewayRefundId(r.getGatewayRefundId())
            .status(r.getStatus())
            .refundStatus(r.getRefundStatus())
            .retryOfId(r.getRetryOfId())
            .retryCount(r.getRetryCount())
            .failureReason(r.getFailureReason())
            .initiatedBy(r.getInitiatedBy())
            .refundedAt(r.getRefundedAt())
            .createdAt(r.getCreatedAt())
            .build();
    }

    private Long getCurrentBingeId() {
        return BingeContext.getBingeId();
    }

    private List<Payment> findSuccessfulPaymentsForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByBookingRefAndStatusAndBingeId(bookingRef, PaymentStatus.SUCCESS, bingeId)
            : paymentRepository.findByBookingRefAndStatus(bookingRef, PaymentStatus.SUCCESS);
    }

    private Optional<Payment> findExistingInitiatedPaymentForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findFirstByBookingRefAndStatusAndBingeIdOrderByCreatedAtDesc(bookingRef, PaymentStatus.INITIATED, bingeId)
            : paymentRepository.findFirstByBookingRefAndStatusOrderByCreatedAtDesc(bookingRef, PaymentStatus.INITIATED);
    }

    private List<Payment> findPaymentsByBookingRefForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByBookingRefAndBingeIdOrderByCreatedAtDesc(bookingRef, bingeId)
            : paymentRepository.findByBookingRefOrderByCreatedAtDesc(bookingRef);
    }

    private List<Payment> findCustomerPaymentsForCurrentBinge(Long customerId) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findByCustomerIdAndBingeIdOrderByCreatedAtDesc(customerId, bingeId)
            : paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    private List<Payment> findRecentDuplicatesForCurrentBinge(String bookingRef, PaymentMethod paymentMethod,
                                                              BigDecimal amount, LocalDateTime since) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.findRecentDuplicatesByBingeId(bookingRef, paymentMethod, amount, bingeId, since)
            : paymentRepository.findRecentDuplicates(bookingRef, paymentMethod, amount, since);
    }

    private BigDecimal sumSuccessfulPaymentsForCurrentBinge(String bookingRef) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.sumSuccessfulPaymentsByBookingRefAndBingeId(bookingRef, bingeId)
            : paymentRepository.sumSuccessfulPaymentsByBookingRef(bookingRef);
    }

    /**
     * Every payment state whose money was captured at some point (PAY-010).
     * DISPUTED is included: the capture happened; a lost dispute subtracts via
     * its chargeback Refund row, not by leaving this sum.
     */
    private static final List<PaymentStatus> CAPTURED_STATUSES = List.of(
        PaymentStatus.SUCCESS, PaymentStatus.PARTIALLY_REFUNDED,
        PaymentStatus.REFUNDED, PaymentStatus.DISPUTED);

    private BigDecimal getCapturedGrossForCurrentBinge() {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.sumAmountByStatusInAndBingeId(CAPTURED_STATUSES, bingeId)
            : paymentRepository.sumAmountByStatusIn(CAPTURED_STATUSES);
    }

    private BigDecimal getTotalRefundedForCurrentBinge() {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? refundRepository.sumAllCompletedRefundsByBingeId(REFUNDED_STATUSES, bingeId)
            : refundRepository.sumAllCompletedRefunds(REFUNDED_STATUSES);
    }

    private long countPaymentsByStatusForCurrentBinge(PaymentStatus status) {
        Long bingeId = getCurrentBingeId();
        return bingeId != null
            ? paymentRepository.countByStatusAndBingeId(status, bingeId)
            : paymentRepository.countByStatus(status);
    }

    private void ensurePaymentInCurrentBinge(Payment payment, String field, Object value) {
        Long bingeId = getCurrentBingeId();
        if (bingeId != null && !bingeId.equals(payment.getBingeId())) {
            throw new ResourceNotFoundException("Payment", field, value);
        }
    }

    private void ensurePaymentAccess(Payment payment, Long requesterId, String requesterRole) {
        if (isAdminRole(requesterRole)) {
            return;
        }
        if (requesterId == null || payment.getCustomerId() == null || !payment.getCustomerId().equals(requesterId)) {
            throw new BusinessException("Not authorized to access this payment", HttpStatus.FORBIDDEN);
        }
    }

    private boolean isAdminRole(String requesterRole) {
        return "ADMIN".equalsIgnoreCase(requesterRole) || "SUPER_ADMIN".equalsIgnoreCase(requesterRole);
    }

    /**
     * Verify HMAC-SHA256 signature from Razorpay webhook/callback.
     */
    private boolean verifySignature(String payload, String expectedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(razorpayKeySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String generated = bytesToHex(hash);
            return java.security.MessageDigest.isEqual(
                generated.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedSignature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
