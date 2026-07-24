package com.skbingegalaxy.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.service.AuthorityLockGuard;
import com.skbingegalaxy.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorityLockInterceptorTest {

    @Mock private AuthorityLockGuard guard;

    @SuppressWarnings("unchecked")
    private AuthorityLockInterceptor interceptor() {
        ObjectProvider<AuthorityLockGuard> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(guard);
        return new AuthorityLockInterceptor(provider, new ObjectMapper());
    }

    @Test
    void resolveCapability_mapsKnownPaths() {
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/taxes")).isEqualTo("TAXES");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/event-types/5")).isEqualTo("EVENT_TYPES");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/add-ons")).isEqualTo("ADD_ONS");
        // "/add-ons" must NOT swallow "/addon-categories" — distinct fragments.
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/addon-categories/global")).isEqualTo("CATEGORIES");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/event-categories")).isEqualTo("CATEGORIES");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/venue-rooms/3/blocks")).isEqualTo("VENUE_ROOMS");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/binges/2/cancellation-tiers")).isEqualTo("CANCELLATION");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/binges/2/cancellation-policy")).isEqualTo("CANCELLATION");
        // Surge rules live in AdminBookingController (no inline guard) — must be enforced here.
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/pricing/surge-rules")).isEqualTo("PRICING");
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/pricing/surge-rules/4/toggle-active")).isEqualTo("PRICING");
    }

    @Test
    void resolveCapability_nullForNonLockablePaths() {
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/bookings")).isNull();
        // Rate-codes / customer pricing are enforced inline in AdminPricingController — NOT here.
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/pricing/rate-codes")).isNull();
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/pricing/customer/7")).isNull();
        assertThat(AuthorityLockInterceptor.resolveCapability("/api/v1/bookings/admin/binges/2/approve")).isNull();
        assertThat(AuthorityLockInterceptor.resolveCapability(null)).isNull();
    }

    @Test
    void preHandle_readsAreNeverBlocked() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");

        boolean proceed = interceptor().preHandle(req, mock(HttpServletResponse.class), new Object());

        assertThat(proceed).isTrue();
        verifyNoInteractions(guard);
    }

    @Test
    void preHandle_blocksLockedMutation_writes423() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/v1/bookings/admin/taxes");
        when(req.getHeader("X-User-Role")).thenReturn("ADMIN");
        when(req.getHeader("X-Authority-Delegated")).thenReturn(null);
        doThrow(new BusinessException("\"TAXES\" is locked", HttpStatus.LOCKED))
                .when(guard).requireUnlocked("TAXES", "ADMIN", false);

        HttpServletResponse res = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(res.getWriter()).thenReturn(new PrintWriter(body));

        boolean proceed = interceptor().preHandle(req, res, new Object());

        assertThat(proceed).isFalse();
        verify(res).setStatus(HttpStatus.LOCKED.value());
        assertThat(body.toString()).contains("locked");
    }

    @Test
    void preHandle_allowsUnlockedMutation() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("PUT");
        when(req.getRequestURI()).thenReturn("/api/v1/bookings/admin/event-types/9");
        when(req.getHeader("X-User-Role")).thenReturn("ADMIN");
        when(req.getHeader("X-Authority-Delegated")).thenReturn(null);
        // guard.requireUnlocked is a no-op when unlocked.

        boolean proceed = interceptor().preHandle(req, mock(HttpServletResponse.class), new Object());

        assertThat(proceed).isTrue();
        verify(guard).requireUnlocked("EVENT_TYPES", "ADMIN", false);
    }
}
