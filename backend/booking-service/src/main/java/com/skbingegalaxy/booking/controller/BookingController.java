package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.booking.dto.*;
import com.skbingegalaxy.booking.service.BookingService;
import com.skbingegalaxy.booking.service.PricingService;
import com.skbingegalaxy.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final PricingService pricingService;

    @PostMapping
    public ResponseEntity<ApiResponse<BookingDto>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Email") String email,
            @RequestHeader(value = "X-User-Name", defaultValue = "Customer") String name,
            @RequestHeader(value = "X-User-Phone", defaultValue = "") String phone) {
        BookingDto booking = bookingService.createBooking(request, userId, name, email, phone);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Booking created successfully", booking));
    }

    @GetMapping("/{bookingRef}")
    public ResponseEntity<ApiResponse<BookingDto>> getBooking(@PathVariable String bookingRef) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getByRef(bookingRef)));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyBookings(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerBookings(userId)));
    }

    @GetMapping("/my/current")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyCurrentBookings(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerCurrentBookings(userId, clientDate)));
    }

    @GetMapping("/my/past")
    public ResponseEntity<ApiResponse<List<BookingDto>>> getMyPastBookings(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clientDate) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getCustomerPastBookings(userId, clientDate)));
    }

    @GetMapping("/event-types")
    public ResponseEntity<ApiResponse<List<EventTypeDto>>> getEventTypes() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getActiveEventTypes()));
    }

    @GetMapping("/add-ons")
    public ResponseEntity<ApiResponse<List<AddOnDto>>> getAddOns() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getActiveAddOns()));
    }

    @GetMapping("/booked-slots")
    public ResponseEntity<ApiResponse<List<BookedSlotDto>>> getBookedSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookedSlotsForDate(date)));
    }

    @GetMapping("/my-pricing")
    public ResponseEntity<ApiResponse<ResolvedPricingDto>> getMyPricing(
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(pricingService.resolveCustomerPricing(userId)));
    }
}
