package com.skbingegalaxy.availability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingBingeDto {
    private Long id;
    private Long adminId;
    private boolean active;
    /** Per-binge opening time (nullable; falls back to global config). */
    private LocalTime openTime;
    /** Per-binge closing time (nullable; falls back to global config). */
    private LocalTime closeTime;
    /** Optional per-day operating hours; overrides open/close for the matching weekday. */
    private java.util.List<BingeDayHoursDto> openingHours;
    /** IANA timezone identifier (e.g. "Asia/Kolkata"). Used to compute venue-local "today". */
    private String timezone;
    /** V71 module matrix: modules the requesting user may NOT use here (null when no userId sent). */
    private java.util.List<String> deniedModules;
}