package com.skbingegalaxy.payment.service;

import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.payment.client.BookingBingeClient;
import com.skbingegalaxy.payment.dto.BookingBingeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentBingeScopeServiceTest {

    @Mock
    private BookingBingeClient bookingBingeClient;

    @InjectMocks
    private PaymentBingeScopeService paymentBingeScopeService;

    @AfterEach
    void tearDown() {
        BingeContext.clear();
    }

    @Test
    @DisplayName("Payment actions require a selected binge")
    void requireSelectedBingeRejectsMissingContext() {
        assertThatThrownBy(() -> paymentBingeScopeService.requireSelectedBinge("accessing payments"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Select a binge before accessing payments")
            .extracting("status")
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Payment admin actions reject another admin's binge")
    void requireManagedBingeRejectsForeignBinge() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(new BookingBingeDto(11L, 44L, true)));

        assertThatThrownBy(() -> paymentBingeScopeService.requireManagedBinge(9L, "ADMIN", "managing payments"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Access denied: you do not own this binge")
            .extracting("status")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Payment admin actions allow super admins")
    void requireManagedBingeAllowsSuperAdmin() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(new BookingBingeDto(11L, 44L, true)));

        BookingBingeDto resolved = paymentBingeScopeService.requireManagedBinge(9L, "SUPER_ADMIN", "managing payments");

        assertThat(resolved.getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("Payment admin actions reject an unknown binge")
    void requireManagedBingeRejectsUnknownBinge() {
        BingeContext.setBingeId(11L);
        when(bookingBingeClient.getBinge(11L)).thenReturn(ApiResponse.ok(null));

        assertThatThrownBy(() -> paymentBingeScopeService.requireManagedBinge(9L, "ADMIN", "managing payments"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Binge");
    }
}