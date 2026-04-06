package com.skbingegalaxy.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@FeignClient(name = "availability-service", fallback = AvailabilityClientFallback.class)
public interface AvailabilityClient {

    @GetMapping("/api/v1/availability/internal/check")
    Boolean checkSlotAvailable(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @RequestParam("date") LocalDate date,
        @RequestParam("startMinute") int startMinute,
        @RequestParam("durationMinutes") int durationMinutes
    );
}
