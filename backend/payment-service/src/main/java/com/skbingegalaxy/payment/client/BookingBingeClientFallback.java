package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.dto.BookingBingeDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback for booking-service circuit breaker.
 * Returns null data so callers can handle gracefully.
 */
@Component
@Slf4j
public class BookingBingeClientFallback implements BookingBingeClient {

    @Override
    public ApiResponse<BookingBingeDto> getBinge(Long bingeId) {
        log.warn("Circuit breaker OPEN: booking-service unavailable for binge id={}", bingeId);
        return ApiResponse.error("Booking service temporarily unavailable");
    }
}
