package com.skbingegalaxy.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.dto.CreateBookingRequest;
import com.skbingegalaxy.booking.entity.EventType;
import com.skbingegalaxy.booking.repository.EventTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Disabled("Requires PostgreSQL and Kafka infrastructure – run with Testcontainers or a live environment")
class BookingFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventTypeRepository eventTypeRepository;

    @MockBean AvailabilityClient availabilityClient;
    @SuppressWarnings("unused")
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setUp() {
        if (eventTypeRepository.findByActiveTrue().isEmpty()) {
            EventType et = new EventType();
            et.setName("Test Event");
            et.setDescription("Integration test event type");
            et.setBasePrice(BigDecimal.valueOf(1000));
            et.setHourlyRate(BigDecimal.valueOf(200));
            et.setPricePerGuest(BigDecimal.ZERO);
            et.setMinHours(1);
            et.setMaxHours(8);
            et.setActive(true);
            eventTypeRepository.save(et);
        }
    }

    // ── Booking flow tests ──────────────────────────────────

    @Test
    void createBooking_andRetrieveByRef() throws Exception {
        EventType eventType = eventTypeRepository.findByActiveTrue().get(0);
                when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt())).thenReturn(true);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(eventType.getId())
                .bookingDate(LocalDate.now().plusDays(7))
                .startTime(LocalTime.of(14, 0))
                .durationHours(3)
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .header("X-User-Name", "Test User")
                        .header("X-User-Email", "test@example.com")
                        .header("X-User-Phone", "9876543210")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookingRef").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.customerName").value("Test User"))
                .andReturn();

        String bookingRef = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("bookingRef").asText();

        mockMvc.perform(get("/api/bookings/" + bookingRef)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingRef").value(bookingRef))
                .andExpect(jsonPath("$.data.customerEmail").value("test@example.com"));
    }

    @Test
    void adminCancelBooking() throws Exception {
        EventType eventType = eventTypeRepository.findByActiveTrue().get(0);
                when(availabilityClient.checkSlotAvailable(anyString(), any(), anyLong(), anyInt(), anyInt())).thenReturn(true);

        CreateBookingRequest request = CreateBookingRequest.builder()
                .eventTypeId(eventType.getId())
                .bookingDate(LocalDate.now().plusDays(10))
                .startTime(LocalTime.of(10, 0))
                .durationHours(2)
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "2")
                        .header("X-User-Name", "Jane Doe")
                        .header("X-User-Email", "jane@example.com")
                        .header("X-User-Phone", "1234567890")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String bookingRef = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("bookingRef").asText();

        mockMvc.perform(post("/api/bookings/admin/" + bookingRef + "/cancel")
                        .param("reason", "Integration test cancel")
                        .header("X-User-Id", "1")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    // ── RBAC enforcement tests ──────────────────────────────

    @Test
    void adminEndpoint_withAdminRole_succeeds() throws Exception {
        mockMvc.perform(get("/api/bookings/admin/dashboard-stats")
                        .param("clientDate", LocalDate.now().toString())
                        .header("X-User-Id", "1")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void adminEndpoint_withCustomerRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/bookings/admin/dashboard-stats")
                        .param("clientDate", LocalDate.now().toString())
                        .header("X-User-Id", "1")
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoint_withoutRoleHeader_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/bookings/admin/dashboard-stats")
                        .param("clientDate", LocalDate.now().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerEndpoint_withoutRoleHeader_succeeds() throws Exception {
        mockMvc.perform(get("/api/bookings/event-types"))
                .andExpect(status().isOk());
    }
}
