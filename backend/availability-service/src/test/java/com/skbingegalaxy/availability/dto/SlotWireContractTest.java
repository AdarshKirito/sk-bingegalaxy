package com.skbingegalaxy.availability.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day view as another service reads it.
 *
 * <p><b>Why this is pinned here.</b> distribution-service's OCTO availability endpoint
 * consumes this payload over HTTP to decide which windows a reseller may buy. It reads
 * {@code date}, {@code closed}, {@code fullyBlocked}, {@code availableSlots}, and within
 * each slot {@code startMinute} and {@code available}. Nothing in the compiler connects
 * the two modules — availability-service must not know that a distribution context exists
 * — so a rename here is a silent break there.
 *
 * <p><b>And silent is the word.</b> If {@code startMinute} disappeared or changed meaning,
 * the consumer would find no free slots, publish no windows, and every reseller would see
 * a venue that is permanently fully booked. No error, no alert, no failed request — just a
 * venue that quietly stops selling through every channel at once.
 *
 * <p><b>The unit is the fragile part.</b> {@code SlotDto} carries both {@code startHour}
 * and {@code startMinute}, and {@code startMinute} is <i>minutes from midnight</i> (540 =
 * 09:00), not the minute component of {@code startHour}. {@code BlockedSlot.startHour} is
 * the same quantity under a legacy column name — {@code getStartHour() / 30} is used as a
 * half-hour index, which only works because it holds minutes. Three fields, two names, one
 * unit, and the only thing recording that today is a comment. Tidying the "inconsistency"
 * would compile, look like a cleanup, and publish midnight availability to every channel.
 *
 * <p><b>What this does not cover.</b> It pins the shape and the unit as expressed by the
 * DTO, not the construction inside {@code AvailabilityService.buildSlots}. A change there
 * that kept the field names but passed the wrong quantity would still pass this test. The
 * consuming half of the pair is
 * {@code distribution-service}'s {@code OctoSupplierContractTest}, which asserts that a
 * slot at {@code startMinute} 1080 becomes an 18:00 window.
 */
@DisplayName("Day availability wire contract (producing side)")
class SlotWireContractTest {

    /**
     * Configured the way Spring Boot configures the one that actually serialises this
     * response — {@code JavaTimeModule} registered <b>and</b> timestamp output disabled.
     *
     * <p>Both halves are load-bearing, and the second one caught this test out. A bare
     * {@code JsonMapper} with only the module writes a {@code LocalDate} as the array
     * {@code [2026,9,1]}, so the first run of {@link #dateIsAnIsoString} failed. The
     * production payload is an ISO string, because Boot disables
     * {@code WRITE_DATES_AS_TIMESTAMPS} by default — so the honest fix was to make the
     * test mapper match production, not to rewrite the assertion around the array. Pinning
     * the array form would have recorded a contract the consumer cannot read: it does
     * {@code LocalDate.parse(String.valueOf(day.get("date")))}.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    /**
     * Built exactly as {@code AvailabilityService} builds it for a 09:00 slot:
     * {@code startHour(minute / 60)}, {@code startMinute(minute)}, where {@code minute} is
     * the loop variable counting minutes from midnight.
     */
    private static final int NINE_AM = 9 * 60;

    private static SlotDto nineAmSlot() {
        int endMinute = NINE_AM + 30;
        return SlotDto.builder()
            .startHour(NINE_AM / 60)
            .endHour(endMinute / 60)
            .startMinute(NINE_AM)
            .endMinute(endMinute)
            .label("09:00 - 09:30")
            .available(true)
            .build();
    }

    private static JsonNode dayJson() throws Exception {
        return MAPPER.valueToTree(DayAvailabilityDto.builder()
            .date(LocalDate.of(2026, 9, 1))
            .closed(false)
            .fullyBlocked(false)
            .availableSlots(List.of(nineAmSlot()))
            .blockedSlots(List.of())
            .build());
    }

    @Test
    @DisplayName("startMinute is minutes from midnight, not the minute part of startHour")
    void startMinuteIsMinutesFromMidnight() {
        SlotDto slot = nineAmSlot();

        // 540, not 0. A consumer reading this as a wall-clock offset — which is exactly
        // what the OCTO availability endpoint does — gets 09:00. Reading it as a minute
        // component would get 00:00 and offer the venue at midnight.
        assertThat(slot.getStartMinute()).isEqualTo(540);
        assertThat(slot.getStartHour()).isEqualTo(9);

        // The invariant tying the two together. If someone ever makes startMinute hold a
        // minute component, this is what says so.
        assertThat(slot.getStartHour()).isEqualTo(slot.getStartMinute() / 60);
    }

    @Test
    @DisplayName("the day payload carries every field distribution-service reads")
    void dayPayloadCarriesTheFieldsConsumersRead() throws Exception {
        JsonNode day = dayJson();

        // Named individually rather than as a count: a test asserting "five fields" would
        // still pass after a rename, which is the failure it exists to catch.
        assertThat(day.has("date")).isTrue();
        assertThat(day.has("closed")).isTrue();
        assertThat(day.has("fullyBlocked")).isTrue();
        assertThat(day.has("availableSlots")).isTrue();

        JsonNode slot = day.get("availableSlots").get(0);
        assertThat(slot.has("startMinute")).isTrue();
        assertThat(slot.has("available")).isTrue();
        assertThat(slot.get("startMinute").asInt()).isEqualTo(540);
    }

    @Test
    @DisplayName("the date serialises as an ISO string, which is how it is parsed back")
    void dateIsAnIsoString() throws Exception {
        // The consumer does LocalDate.parse(String.valueOf(day.get("date"))). A switch to
        // an epoch number or an array would compile everywhere and fail only at the point
        // where a reseller asks what is for sale.
        assertThat(dayJson().get("date").asText()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("blockedSlots exists here but is deliberately not published to resellers")
    void blockedSlotsIsPresentForFirstPartyConsumersOnly() throws Exception {
        // Kept in the payload because the customer date picker needs it. The OCTO layer
        // strips it: when a venue is deliberately closed off is operational information a
        // third party has no business seeing. Pinned so that the omission downstream stays
        // a deliberate choice rather than something that quietly became impossible.
        assertThat(dayJson().has("blockedSlots")).isTrue();
    }
}
