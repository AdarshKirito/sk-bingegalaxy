package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.dto.BookingBingeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Reads the internal (X-Internal-Secret) binge snapshot. The PUBLIC
 * {@code /binges/{id}} endpoint must not be used here: it strips
 * {@code adminId}, which silently null'd every ownership check and made all
 * admin payment endpoints return 403, and it hides non-approved venues that
 * admins still manage.
 */
@FeignClient(name = "booking-service",
             configuration = InternalApiFeignConfig.class,
             fallback = BookingBingeClientFallback.class)
public interface BookingBingeClient {

    /**
     * @param userId requesting admin — when present the response includes the
     *               module keys that user may NOT use (V71 matrix), letting
     *               this service enforce DISPUTES / FAILED_REFUNDS in the same
     *               round trip as the ownership check.
     */
    @GetMapping("/api/v1/bookings/internal/binges/{id}")
    ApiResponse<BookingBingeDto> getBinge(@PathVariable("id") Long bingeId,
                                          @RequestParam(value = "userId", required = false) Long userId);
}