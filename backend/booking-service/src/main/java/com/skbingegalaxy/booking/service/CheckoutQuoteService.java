package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CheckoutPreviewRequest;
import com.skbingegalaxy.booking.dto.CheckoutPreviewResponse;
import com.skbingegalaxy.booking.dto.TaxComputationResult;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.entity.LoyaltyMembership;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import com.skbingegalaxy.booking.tax.provider.TaxContext;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.money.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a {@link CheckoutPreviewResponse} for the GET /checkout/preview
 * endpoint.
 *
 * <p>Reuses {@link PricingService} for surge resolution (kept lightweight —
 * event/addon resolution is left to the caller for now). Output is fully
 * deterministic for a given input + tax-rule + FX-rate state, so the
 * frontend can render a 4-currency footer + tax breakdown without doing
 * any math itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CheckoutQuoteService {

    private final EventTypeRepository eventTypeRepository;
    private final AddOnRepository addOnRepository;
    private final PricingService pricingService;
    private final TaxService taxService;
    private final CurrencyService currencyService;
    private final EnrollmentService enrollmentService;
    private final RedeemEngine redeemEngine;

    public CheckoutPreviewResponse preview(CheckoutPreviewRequest req, Long customerId) {
        if (req == null) throw new BusinessException("Preview request is required");

        Long bingeId = BingeContext.getBingeId();
        if (bingeId == null) {
            bingeId = req.getBingeId();
        }

        // ── Subtotal ──────────────────────────────────────────────────
        BigDecimal baseAmount = BigDecimal.ZERO;
        BigDecimal addOnTotal = BigDecimal.ZERO;
        BigDecimal guestAmount = BigDecimal.ZERO;
        int guests = req.getNumberOfGuests() != null ? Math.max(req.getNumberOfGuests(), 1) : 1;
        int durMin = req.getDurationMinutes() != null ? req.getDurationMinutes() : 60;

        // Base price + per-guest charge — resolved through the SAME pricing engine
        // and using the EXACT formula BookingService.createBooking applies, so the
        // quote the customer sees matches what they will actually be charged:
        //   baseAmount  = basePrice + hourlyRate * (durationMinutes / 60)
        //   guestAmount = pricePerGuest * max(guests - 1, 0)   (first guest included)
        // When eventTypeId is absent (anonymous "add details" preview) the base
        // stays zero and only add-ons + tax are quoted.
        if (req.getEventTypeId() != null) {
            try {
                PricingService.ResolvedEventPrice eventPrice =
                    pricingService.resolveEventPrice(customerId, req.getEventTypeId());
                BigDecimal durationDecimalHours = BigDecimal.valueOf(durMin)
                    .divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP);
                baseAmount = eventPrice.basePrice()
                    .add(eventPrice.hourlyRate().multiply(durationDecimalHours)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
                guestAmount = eventPrice.pricePerGuest()
                    .multiply(BigDecimal.valueOf(Math.max(guests - 1, 0)));
            } catch (RuntimeException ex) {
                log.warn("Skipping unresolvable event {} during preview: {}",
                    req.getEventTypeId(), ex.getMessage());
            }
        }

        // Add-ons
        if (req.getAddOnIds() != null) {
            for (Long addOnId : req.getAddOnIds()) {
                try {
                    PricingService.ResolvedAddonPrice ap = pricingService.resolveAddonPrice(customerId, addOnId);
                    addOnTotal = addOnTotal.add(MoneyUtil.zeroIfNull(ap.price()));
                } catch (Exception ex) {
                    log.warn("Skipping unknown add-on {} during preview: {}", addOnId, ex.getMessage());
                }
            }
        }

        BigDecimal subtotal = baseAmount.add(addOnTotal).add(guestAmount);

        // ── Surge ────────────────────────────────────────────────────
        BigDecimal surgeAmount = BigDecimal.ZERO;
        if (req.getBookingDate() != null && req.getStartTime() != null) {
            PricingService.SurgeResult surge =
                pricingService.resolveSurge(req.getBookingDate(), req.getStartTime());
            if (surge != null && surge.multiplier() != null && surge.multiplier().compareTo(BigDecimal.ONE) != 0) {
                BigDecimal surged = subtotal.multiply(surge.multiplier());
                surgeAmount = surged.subtract(subtotal);
                subtotal = surged;
            }
        }

        // Loyalty preview is read-only; BookingService performs the actual burn.
        BigDecimal loyaltyDiscount = quoteLoyaltyDiscount(req, customerId, bingeId, subtotal);
        BigDecimal couponDiscount  = BigDecimal.ZERO; // hook
        BigDecimal afterDiscount = subtotal.subtract(loyaltyDiscount).subtract(couponDiscount);
        if (afterDiscount.signum() < 0) afterDiscount = BigDecimal.ZERO;

        BigDecimal platformFee = BigDecimal.ZERO; // future

        // ── Tax ──────────────────────────────────────────────────────
        // Start from the canonical venue-aware context (place of supply = venue),
        // then layer the customer's billing address on top — billing wins over
        // venue per TaxContext.resolvedState(). Previously this context omitted the
        // venue jurisdiction entirely, so a GST/geo-scoped rule was dropped from the
        // quote even though the booking applied it.
        TaxContext ctx = taxService.venueContext(bingeId)
            .billingCountryCode(req.getBillingAddress() != null ? req.getBillingAddress().getCountryCode() : null)
            .billingStateCode(req.getBillingAddress() != null ? req.getBillingAddress().getStateCode() : null)
            .billingCity(req.getBillingAddress() != null ? req.getBillingAddress().getCity() : null)
            .billingPostalCode(req.getBillingAddress() != null ? req.getBillingAddress().getPostalCode() : null)
            .customerType(req.getCustomerType() != null ? req.getCustomerType() : "B2C")
            .buyerHasTaxId(req.getBillingAddress() != null
                && req.getBillingAddress().getTaxId() != null
                && !req.getBillingAddress().getTaxId().isBlank())
            .build();

        TaxComputationResult tax = taxService.compute(ctx, afterDiscount, baseAmount, addOnTotal, guestAmount);
        BigDecimal taxTotal = tax.getTotalTax() != null ? tax.getTotalTax() : BigDecimal.ZERO;
        BigDecimal totalBase = afterDiscount.add(taxTotal).add(platformFee);

        // ── Currency conversion ──────────────────────────────────────
        String displayCcy = req.getDisplayCurrencyCode() != null && !req.getDisplayCurrencyCode().isBlank()
            ? req.getDisplayCurrencyCode().toUpperCase() : CurrencyService.BASE_CURRENCY;
        String paymentCcy = req.getPaymentCurrencyCode() != null && !req.getPaymentCurrencyCode().isBlank()
            ? req.getPaymentCurrencyCode().toUpperCase() : displayCcy;

        BigDecimal fxDisplay = currencyService.getRate(displayCcy);
        BigDecimal fxPayment = currencyService.getRate(paymentCcy);

        BigDecimal displayTotal = currencyService.convertFromBase(totalBase, displayCcy);
        BigDecimal paymentTotal = currencyService.convertFromBase(totalBase, paymentCcy);

        // ── Breakdown ────────────────────────────────────────────────
        List<CheckoutPreviewResponse.BreakdownItem> breakdown = new ArrayList<>();
        breakdown.add(item("SUBTOTAL", "Subtotal", baseAmount.add(addOnTotal).add(guestAmount), displayCcy));
        if (surgeAmount.signum() != 0) {
            breakdown.add(item("SURGE", "Surge", surgeAmount, displayCcy));
        }
        if (loyaltyDiscount.signum() > 0) {
            breakdown.add(item("LOYALTY", "Loyalty discount", loyaltyDiscount.negate(), displayCcy));
        }
        if (couponDiscount.signum() > 0) {
            breakdown.add(item("COUPON", "Coupon discount", couponDiscount.negate(), displayCcy));
        }
        if (tax.getLines() != null) {
            int idx = 0;
            for (TaxComputationResult.TaxLine ln : tax.getLines()) {
                breakdown.add(item("TAX_LINE_" + (idx++), ln.getName() + " (" + (ln.getRateBps() / 100.0) + "%)",
                    ln.getAmount(), displayCcy));
            }
        }
        if (platformFee.signum() != 0) {
            breakdown.add(item("PLATFORM_FEE", "Platform fee", platformFee, displayCcy));
        }
        breakdown.add(item("TOTAL", "Total", totalBase, displayCcy));

        return CheckoutPreviewResponse.builder()
            .baseCurrencyCode(CurrencyService.BASE_CURRENCY)
            .displayCurrencyCode(displayCcy)
            .paymentCurrencyCode(paymentCcy)
            .subtotalBase(baseAmount.add(addOnTotal).add(guestAmount))
            .surgeAmountBase(surgeAmount)
            .discountAmountBase(loyaltyDiscount.add(couponDiscount))
            .loyaltyRedemptionBase(loyaltyDiscount)
            .platformFeeBase(platformFee)
            .taxAmountBase(taxTotal)
            .totalBase(totalBase)
            .displayTotal(displayTotal)
            .paymentTotal(paymentTotal)
            .fxRateDisplay(fxDisplay)
            .fxRatePayment(fxPayment)
            .fxSource("MANUAL")
            .tax(tax)
            .taxLines(tax.getLines())
            .breakdown(breakdown)
            .build();
    }

    private CheckoutPreviewResponse.BreakdownItem item(String key, String label,
                                                       BigDecimal amountBase, String displayCcy) {
        return CheckoutPreviewResponse.BreakdownItem.builder()
            .key(key)
            .label(label)
            .amountBase(amountBase)
            .amountDisplay(currencyService.convertFromBase(amountBase, displayCcy))
            .build();
    }

    private BigDecimal quoteLoyaltyDiscount(CheckoutPreviewRequest req, Long customerId,
                                            Long bingeId, BigDecimal subtotal) {
        if (customerId == null || bingeId == null || req.getRedeemLoyaltyPoints() == null
                || req.getRedeemLoyaltyPoints() <= 0 || subtotal == null || subtotal.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        try {
            LoyaltyMembership membership = enrollmentService.findForCustomer(customerId).orElse(null);
            if (membership == null) return BigDecimal.ZERO;
            RedeemEngine.RedeemQuote quote = redeemEngine.quote(new RedeemEngine.QuoteRequest(
                    membership.getId(), bingeId, req.getRedeemLoyaltyPoints(),
                    subtotal, java.time.LocalDateTime.now(ZoneOffset.UTC)));
            return quote.eligible() ? quote.currencyValue() : BigDecimal.ZERO;
        } catch (RuntimeException ex) {
            log.debug("Loyalty preview skipped for customer {} binge {}: {}",
                    customerId, bingeId, ex.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @SuppressWarnings("unused")
    private Optional<EventType> findEvent(Long id) {
        return id == null ? Optional.empty() : eventTypeRepository.findById(id);
    }
}
