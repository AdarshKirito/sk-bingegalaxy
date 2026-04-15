package com.skbingegalaxy.availability.client;

import com.skbingegalaxy.availability.dto.BookingBingeDto;
import com.skbingegalaxy.common.dto.ApiResponse;
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
