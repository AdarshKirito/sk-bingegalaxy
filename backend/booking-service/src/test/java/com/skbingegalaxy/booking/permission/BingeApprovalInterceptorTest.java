package com.skbingegalaxy.booking.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.entity.BingeApprovalStatus;
import com.skbingegalaxy.booking.repository.BingeRepository;
import com.skbingegalaxy.common.context.BingeContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rejected-venue freeze is a security control, so it is pinned by tests: a
 * regression here silently re-opens the hole where a rejected venue stayed fully
 * operable.
 */
@ExtendWith(MockitoExtension.class)
class BingeApprovalInterceptorTest {

    @Mock private BingeRepository repo;

    @AfterEach
    void clearContext() { BingeContext.clear(); }

    @SuppressWarnings("unchecked")
    private BingeApprovalInterceptor interceptor() {
        ObjectProvider<BingeRepository> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(repo);
        return new BingeApprovalInterceptor(provider, new ObjectMapper());
    }

    private HttpServletRequest req(String method, String uri, String role) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        lenient().when(r.getHeader("X-User-Role")).thenReturn(role);
        lenient().when(r.getMethod()).thenReturn(method);
        lenient().when(r.getRequestURI()).thenReturn(uri);
        return r;
    }

    private HttpServletResponse resp() throws Exception {
        HttpServletResponse r = mock(HttpServletResponse.class);
        lenient().when(r.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return r;
    }

    @Test
    void rejectedBinge_write_isBlocked() throws Exception {
        when(repo.findStatusById(7L)).thenReturn(Optional.of(BingeApprovalStatus.REJECTED));
        var resp = resp();
        boolean allowed = interceptor().preHandle(
            req("PUT", "/api/v1/bookings/admin/binges/7/customer-dashboard", "ADMIN"), resp, new Object());
        assertThat(allowed).isFalse();
    }

    @Test
    void rejectedBinge_eventCreateViaContext_isBlocked() throws Exception {
        BingeContext.setBingeId(7L);
        when(repo.findStatusById(7L)).thenReturn(Optional.of(BingeApprovalStatus.REJECTED));
        boolean allowed = interceptor().preHandle(
            req("POST", "/api/v1/bookings/admin/event-types", "ADMIN"), resp(), new Object());
        assertThat(allowed).isFalse();
    }

    @Test
    void approvedBinge_write_isAllowed() throws Exception {
        when(repo.findStatusById(7L)).thenReturn(Optional.of(BingeApprovalStatus.APPROVED));
        boolean allowed = interceptor().preHandle(
            req("PUT", "/api/v1/bookings/admin/binges/7/customer-dashboard", "ADMIN"), resp(), new Object());
        assertThat(allowed).isTrue();
    }

    @Test
    void rejectedBinge_lifecycleWrites_areAllowed() throws Exception {
        // Edit, delete, and re-request must keep working so the owner can recover it.
        lenient().when(repo.findStatusById(7L)).thenReturn(Optional.of(BingeApprovalStatus.REJECTED));
        var i = interceptor();
        assertThat(i.preHandle(req("PUT", "/api/v1/bookings/admin/binges/7", "ADMIN"), resp(), new Object())).isTrue();
        assertThat(i.preHandle(req("DELETE", "/api/v1/bookings/admin/binges/7", "ADMIN"), resp(), new Object())).isTrue();
        assertThat(i.preHandle(req("POST", "/api/v1/bookings/admin/binges/7/re-request", "ADMIN"), resp(), new Object())).isTrue();
        assertThat(i.preHandle(req("POST", "/api/v1/bookings/admin/binges/7/timezone-request", "ADMIN"), resp(), new Object())).isTrue();
    }

    @Test
    void superAdmin_bypasses() throws Exception {
        boolean allowed = interceptor().preHandle(
            req("PUT", "/api/v1/bookings/admin/binges/7/customer-dashboard", "SUPER_ADMIN"), resp(), new Object());
        assertThat(allowed).isTrue();
    }

    @Test
    void reads_areAllowed_evenWhenRejected() throws Exception {
        boolean allowed = interceptor().preHandle(
            req("GET", "/api/v1/bookings/admin/binges/7/cancellation-tiers", "ADMIN"), resp(), new Object());
        assertThat(allowed).isTrue();
    }

    @Test
    void noBingeInContextOrPath_isAllowed() throws Exception {
        boolean allowed = interceptor().preHandle(
            req("POST", "/api/v1/bookings/admin/some-global-thing", "ADMIN"), resp(), new Object());
        assertThat(allowed).isTrue();
    }
}
