package com.skbingegalaxy.distribution.controller;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.distribution.dto.ConnectionDto;
import com.skbingegalaxy.distribution.dto.CreateConnectionRequest;
import com.skbingegalaxy.distribution.service.ConnectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * The controller is thin on purpose, but the one thing it must get right is where
 * {@code bingeId} comes from.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionController")
class ConnectionControllerTest {

    @Mock private ConnectionService connectionService;
    @InjectMocks private ConnectionController controller;

    @Test
    @DisplayName("every venue-scoped call refuses to proceed without a selected venue")
    void requiresBingeHeader() {
        // Without this the service would receive a null bingeId and silently scope a
        // query to "no venue", returning an empty list that looks like a real answer.
        assertThatThrownBy(() -> controller.list(null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Select a venue");
        assertThatThrownBy(() -> controller.create(null, 1L, new CreateConnectionRequest()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.pause(null, 1L, "x"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.resume(null, 1L))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.revoke(null, 1L, "x"))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("the venue comes from the gateway header, never from the body")
    void bingeComesFromHeader() {
        CreateConnectionRequest req = new CreateConnectionRequest();
        req.setProviderCode("SIMULATOR");
        when(connectionService.create(eq(42L), eq(9L), any()))
            .thenReturn(ConnectionDto.builder().id(1L).bingeId(42L).build());

        var response = controller.create(42L, 9L, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(connectionService).create(eq(42L), eq(9L), same(req));
    }

    @Test
    @DisplayName("listing delegates with the header's venue")
    void listDelegates() {
        when(connectionService.listForBinge(42L)).thenReturn(List.of());
        assertThat(controller.list(42L).getBody().getData()).isEmpty();
        verify(connectionService).listForBinge(42L);
    }

    @Test
    @DisplayName("providers is not venue-scoped — the catalogue is platform-wide")
    void providersNeedsNoBinge() {
        when(connectionService.listConnectableProviders()).thenReturn(List.of());
        assertThat(controller.listProviders().getBody().getData()).isEmpty();
    }

    @Test
    @DisplayName("pause, resume and revoke each pass the venue through")
    void lifecycleDelegates() {
        when(connectionService.pause(42L, 7L, "why")).thenReturn(ConnectionDto.builder().build());
        when(connectionService.resume(42L, 7L)).thenReturn(ConnectionDto.builder().build());
        when(connectionService.revoke(42L, 7L, "why")).thenReturn(ConnectionDto.builder().build());

        controller.pause(42L, 7L, "why");
        controller.resume(42L, 7L);
        controller.revoke(42L, 7L, "why");

        verify(connectionService).pause(42L, 7L, "why");
        verify(connectionService).resume(42L, 7L);
        verify(connectionService).revoke(42L, 7L, "why");
    }
}
