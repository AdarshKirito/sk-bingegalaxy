package com.skbingegalaxy.payment.client;

import com.skbingegalaxy.common.dto.ApiResponse;
import com.skbingegalaxy.payment.dto.BookingBingeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "booking-service", fallback = BookingBingeClientFallback.class)
public interface BookingBingeClient {

    @GetMapping("/api/v1/bookings/binges/{id}")
    ApiResponse<BookingBingeDto> getBinge(@PathVariable("id") Long bingeId);
}