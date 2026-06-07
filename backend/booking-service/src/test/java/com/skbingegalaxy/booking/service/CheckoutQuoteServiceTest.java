package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CheckoutPreviewRequest;
import com.skbingegalaxy.booking.dto.CheckoutPreviewResponse;
import com.skbingegalaxy.booking.dto.TaxComputationResult;
import com.skbingegalaxy.booking.loyalty.v2.engine.RedeemEngine;
import com.skbingegalaxy.booking.loyalty.v2.service.EnrollmentService;
import com.skbingegalaxy.booking.repository.AddOnRepository;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the checkout quote computes the SAME price the booking-creation path
 * charges. This guards the bug where {@code baseAmount}/{@code guestAmount} were
 * hardcoded to zero, so {@code /checkout/preview} silently omitted the event
 * price. We assert the actual numbers rather than trusting the endpoint to "work".
 */
@ExtendWith(MockitoExtension.class)
class CheckoutQuoteServiceTest {

    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private PricingService pricingService;
    @Mock private TaxService taxService;
    @Mock private CurrencyService currencyService;
    @Mock private EnrollmentService enrollmentService;
    @Mock private RedeemEngine redeemEngine;

    @InjectMocks private CheckoutQuoteService checkoutQuoteService;

    @Test
    void preview_computesBaseAndGuestUsingCanonicalFormula() {
        // basePrice 5000 + hourlyRate 1000/hr * 2h = 7000 base
        // pricePerGuest 200 * (3 guests - 1 included) = 400 guest charge
        // subtotal = 7000 + 0 add-ons + 400 = 7400; no surge/loyalty/tax => total 7400
        when(pricingService.resolveEventPrice(any(), eq(1L))).thenReturn(
            new PricingService.ResolvedEventPrice(
                new BigDecimal("5000"), new BigDecimal("1000"), new BigDecimal("200"),
                "DEFAULT", null));
        when(taxService.compute(any(com.skbingegalaxy.booking.tax.provider.TaxContext.class),
                any(), any(), any(), any())).thenReturn(
            TaxComputationResult.builder().totalTax(BigDecimal.ZERO).build());
        when(currencyService.getRate(anyString())).thenReturn(BigDecimal.ONE);
        when(currencyService.convertFromBase(any(), anyString()))
            .thenAnswer(inv -> inv.getArgument(0)); // INR display => identity

        CheckoutPreviewRequest req = CheckoutPreviewRequest.builder()
            .bingeId(1L)
            .eventTypeId(1L)
            .durationMinutes(120)
            .numberOfGuests(3)
            .build();

        CheckoutPreviewResponse res = checkoutQuoteService.preview(req, 42L);

        // The whole point: base price is no longer zero.
        assertThat(res.getSubtotalBase()).isEqualByComparingTo("7400");
        assertThat(res.getTotalBase()).isEqualByComparingTo("7400");
    }

    @Test
    void preview_withoutEventType_quotesZeroBase() {
        // Anonymous "add details to see pricing" path: no eventTypeId => base stays 0,
        // and crucially resolveEventPrice must NOT be invoked.
        when(taxService.compute(any(com.skbingegalaxy.booking.tax.provider.TaxContext.class),
                any(), any(), any(), any())).thenReturn(
            TaxComputationResult.builder().totalTax(BigDecimal.ZERO).build());
        when(currencyService.getRate(anyString())).thenReturn(BigDecimal.ONE);
        when(currencyService.convertFromBase(any(), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        CheckoutPreviewRequest req = CheckoutPreviewRequest.builder()
            .bingeId(1L)
            .durationMinutes(60)
            .numberOfGuests(1)
            .build();

        CheckoutPreviewResponse res = checkoutQuoteService.preview(req, null);

        assertThat(res.getSubtotalBase()).isEqualByComparingTo("0");
        assertThat(res.getTotalBase()).isEqualByComparingTo("0");
    }
}
