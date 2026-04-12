package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.dto.CustomerDetailDto;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceCustomerDetailTest {

    @Mock private RateCodeRepository rateCodeRepository;
    @Mock private CustomerPricingProfileRepository customerPricingProfileRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private RateCodeChangeLogRepository rateCodeChangeLogRepository;
    @Mock private BookingRepository bookingRepository;

    @InjectMocks private PricingService pricingService;

    @AfterEach
    void tearDown() {
        BingeContext.clear();
        pricingService.clearCurrentAdminId();
    }

    // ── getCustomerDetail ─────────────────────────────────

    @Test
    @DisplayName("getCustomerDetail returns full data when rate code, audit log, and bookings exist")
    void getCustomerDetail_returnsFullData() {
        Long customerId = 5L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);

        RateCode vipRC = RateCode.builder().id(1L).name("VIP").build();
        CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(customerId)
                .bingeId(bingeId)
                .rateCode(vipRC)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.of(profile));

        RateCodeChangeLog log1 = RateCodeChangeLog.builder()
                .id(1L)
                .customerId(customerId)
                .bingeId(bingeId)
                .previousRateCodeName(null)
                .newRateCodeName("VIP")
                .changeType("ASSIGN")
                .changedByAdminId(100L)
                .changedAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();

        when(rateCodeChangeLogRepository.findByCustomerIdAndBingeIdOrderByChangedAtDesc(customerId, bingeId))
                .thenReturn(List.of(log1));

        EventType eventType = EventType.builder().id(1L).name("Birthday").build();
        Booking booking = Booking.builder()
                .bookingRef("BK-001")
                .customerId(customerId)
                .bingeId(bingeId)
                .eventType(eventType)
                .bookingDate(LocalDate.of(2026, 2, 14))
                .startTime(LocalTime.of(14, 0))
                .durationMinutes(120)
                .status(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.SUCCESS)
                .totalAmount(BigDecimal.valueOf(5000))
                .collectedAmount(BigDecimal.valueOf(5000))
                .pricingSource("RATE_CODE")
                .rateCodeName("VIP")
                .createdAt(LocalDateTime.of(2026, 2, 1, 8, 0))
                .build();

        when(bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bingeId, customerId))
                .thenReturn(List.of(booking));

        CustomerDetailDto result = pricingService.getCustomerDetail(customerId);

        assertThat(result.getCustomerId()).isEqualTo(customerId);
        assertThat(result.getCurrentRateCodeId()).isEqualTo(1L);
        assertThat(result.getCurrentRateCodeName()).isEqualTo("VIP");
        assertThat(result.getTotalReservations()).isEqualTo(1);

        // Audit trail
        assertThat(result.getRateCodeChanges()).hasSize(1);
        CustomerDetailDto.RateCodeChange change = result.getRateCodeChanges().get(0);
        assertThat(change.getChangeType()).isEqualTo("ASSIGN");
        assertThat(change.getNewRateCodeName()).isEqualTo("VIP");
        assertThat(change.getPreviousRateCodeName()).isNull();
        assertThat(change.getChangedByAdminId()).isEqualTo(100L);

        // Reservations
        assertThat(result.getReservations()).hasSize(1);
        CustomerDetailDto.ReservationSummary res = result.getReservations().get(0);
        assertThat(res.getBookingRef()).isEqualTo("BK-001");
        assertThat(res.getEventTypeName()).isEqualTo("Birthday");
        assertThat(res.getStatus()).isEqualTo("CONFIRMED");
        assertThat(res.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(res.getRateCodeName()).isEqualTo("VIP");
    }

    @Test
    @DisplayName("getCustomerDetail returns null rate code when no pricing profile exists")
    void getCustomerDetail_noProfile_returnsNullRateCode() {
        Long customerId = 6L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.empty());
        when(rateCodeChangeLogRepository.findByCustomerIdAndBingeIdOrderByChangedAtDesc(customerId, bingeId))
                .thenReturn(List.of());
        when(bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bingeId, customerId))
                .thenReturn(List.of());

        CustomerDetailDto result = pricingService.getCustomerDetail(customerId);

        assertThat(result.getCurrentRateCodeId()).isNull();
        assertThat(result.getCurrentRateCodeName()).isNull();
        assertThat(result.getTotalReservations()).isEqualTo(0);
        assertThat(result.getRateCodeChanges()).isEmpty();
        assertThat(result.getReservations()).isEmpty();
    }

    @Test
    @DisplayName("getCustomerDetail throws when no binge selected")
    void getCustomerDetail_noBinge_throws() {
        // BingeContext not set
        assertThatThrownBy(() -> pricingService.getCustomerDetail(1L))
                .isInstanceOf(com.skbingegalaxy.common.exception.BusinessException.class);
    }

    @Test
    @DisplayName("getCustomerDetail with profile but null rate code")
    void getCustomerDetail_profileNoRateCode() {
        Long customerId = 7L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);

        CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(customerId)
                .bingeId(bingeId)
                .rateCode(null)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.of(profile));
        when(rateCodeChangeLogRepository.findByCustomerIdAndBingeIdOrderByChangedAtDesc(customerId, bingeId))
                .thenReturn(List.of());
        when(bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bingeId, customerId))
                .thenReturn(List.of());

        CustomerDetailDto result = pricingService.getCustomerDetail(customerId);

        assertThat(result.getCurrentRateCodeId()).isNull();
        assertThat(result.getCurrentRateCodeName()).isNull();
    }

    @Test
    @DisplayName("getCustomerDetail handles multiple bookings and audit entries")
    void getCustomerDetail_multipleBookingsAndAudit() {
        Long customerId = 8L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);

        RateCode stdRC = RateCode.builder().id(2L).name("Standard").build();
        CustomerPricingProfile profile = CustomerPricingProfile.builder()
                .customerId(customerId).bingeId(bingeId).rateCode(stdRC)
                .eventPricings(new ArrayList<>()).addonPricings(new ArrayList<>())
                .build();

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.of(profile));

        RateCodeChangeLog log1 = RateCodeChangeLog.builder()
                .id(1L).changeType("ASSIGN").newRateCodeName("VIP")
                .changedAt(LocalDateTime.of(2026, 1, 1, 10, 0)).build();
        RateCodeChangeLog log2 = RateCodeChangeLog.builder()
                .id(2L).changeType("REASSIGN").previousRateCodeName("VIP").newRateCodeName("Standard")
                .changedAt(LocalDateTime.of(2026, 2, 1, 10, 0)).build();

        when(rateCodeChangeLogRepository.findByCustomerIdAndBingeIdOrderByChangedAtDesc(customerId, bingeId))
                .thenReturn(List.of(log2, log1));

        EventType et = EventType.builder().id(1L).name("Party").build();
        Booking b1 = Booking.builder().bookingRef("BK-A").customerId(customerId).bingeId(bingeId)
                .eventType(et).bookingDate(LocalDate.of(2026, 1, 10)).startTime(LocalTime.of(10, 0))
                .durationMinutes(60).status(BookingStatus.COMPLETED).paymentStatus(PaymentStatus.SUCCESS)
                .totalAmount(BigDecimal.valueOf(3000)).collectedAmount(BigDecimal.valueOf(3000))
                .createdAt(LocalDateTime.of(2026, 1, 5, 8, 0)).build();
        Booking b2 = Booking.builder().bookingRef("BK-B").customerId(customerId).bingeId(bingeId)
                .eventType(et).bookingDate(LocalDate.of(2026, 3, 5)).startTime(LocalTime.of(18, 0))
                .durationMinutes(180).status(BookingStatus.PENDING).paymentStatus(PaymentStatus.PENDING)
                .totalAmount(BigDecimal.valueOf(8000)).collectedAmount(BigDecimal.ZERO)
                .createdAt(LocalDateTime.of(2026, 3, 1, 11, 0)).build();

        when(bookingRepository.findByBingeIdAndCustomerIdOrderByCreatedAtDesc(bingeId, customerId))
                .thenReturn(List.of(b2, b1));

        CustomerDetailDto result = pricingService.getCustomerDetail(customerId);

        assertThat(result.getCurrentRateCodeName()).isEqualTo("Standard");
        assertThat(result.getTotalReservations()).isEqualTo(2);
        assertThat(result.getRateCodeChanges()).hasSize(2);
        assertThat(result.getReservations()).hasSize(2);
        assertThat(result.getReservations().get(0).getBookingRef()).isEqualTo("BK-B");
    }

    // ── logRateCodeChange (integration via saveCustomerPricing) ──

    @Test
    @DisplayName("logRateCodeChange saves audit entry for new rate code assignment")
    void logRateCodeChange_newAssignment_savesAuditLog() {
        Long customerId = 9L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);
        pricingService.setCurrentAdminId(100L);

        RateCode newRC = RateCode.builder().id(1L).name("VIP").active(true)
                .eventPricings(new ArrayList<>()).addonPricings(new ArrayList<>())
                .bingeId(bingeId).build();

        // No existing profile
        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.empty());
        when(rateCodeRepository.findByIdAndBingeId(1L, bingeId))
                .thenReturn(Optional.of(newRC));
        when(customerPricingProfileRepository.save(any(CustomerPricingProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest request =
                com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest.builder()
                        .customerId(customerId)
                        .rateCodeId(1L)
                        .eventPricings(List.of())
                        .addonPricings(List.of())
                        .build();

        pricingService.saveCustomerPricing(request);

        // Verify the rate code change was logged
        ArgumentCaptor<RateCodeChangeLog> captor = ArgumentCaptor.forClass(RateCodeChangeLog.class);
        verify(rateCodeChangeLogRepository).save(captor.capture());

        RateCodeChangeLog savedLog = captor.getValue();
        assertThat(savedLog.getCustomerId()).isEqualTo(customerId);
        assertThat(savedLog.getBingeId()).isEqualTo(bingeId);
        assertThat(savedLog.getPreviousRateCodeId()).isNull();
        assertThat(savedLog.getNewRateCodeId()).isEqualTo(1L);
        assertThat(savedLog.getNewRateCodeName()).isEqualTo("VIP");
        assertThat(savedLog.getChangeType()).isEqualTo("ASSIGN");
        assertThat(savedLog.getChangedByAdminId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("logRateCodeChange saves REASSIGN when switching rate codes")
    void logRateCodeChange_reassignment_savesCorrectType() {
        Long customerId = 11L;
        Long bingeId = 10L;
        BingeContext.setBingeId(bingeId);
        pricingService.setCurrentAdminId(101L);

        RateCode oldRC = RateCode.builder().id(1L).name("VIP").active(true)
                .eventPricings(new ArrayList<>()).addonPricings(new ArrayList<>())
                .bingeId(bingeId).build();
        RateCode newRC = RateCode.builder().id(2L).name("Standard").active(true)
                .eventPricings(new ArrayList<>()).addonPricings(new ArrayList<>())
                .bingeId(bingeId).build();

        CustomerPricingProfile existingProfile = CustomerPricingProfile.builder()
                .customerId(customerId)
                .bingeId(bingeId)
                .rateCode(oldRC)
                .eventPricings(new ArrayList<>())
                .addonPricings(new ArrayList<>())
                .build();

        when(customerPricingProfileRepository.findByCustomerIdAndBingeId(customerId, bingeId))
                .thenReturn(Optional.of(existingProfile));
        when(rateCodeRepository.findByIdAndBingeId(2L, bingeId))
                .thenReturn(Optional.of(newRC));
        when(customerPricingProfileRepository.save(any(CustomerPricingProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest request =
                com.skbingegalaxy.booking.dto.CustomerPricingSaveRequest.builder()
                        .customerId(customerId)
                        .rateCodeId(2L)
                        .eventPricings(List.of())
                        .addonPricings(List.of())
                        .build();

        pricingService.saveCustomerPricing(request);

        ArgumentCaptor<RateCodeChangeLog> captor = ArgumentCaptor.forClass(RateCodeChangeLog.class);
        verify(rateCodeChangeLogRepository).save(captor.capture());

        RateCodeChangeLog savedLog = captor.getValue();
        assertThat(savedLog.getChangeType()).isEqualTo("REASSIGN");
        assertThat(savedLog.getPreviousRateCodeId()).isEqualTo(1L);
        assertThat(savedLog.getPreviousRateCodeName()).isEqualTo("VIP");
        assertThat(savedLog.getNewRateCodeId()).isEqualTo(2L);
        assertThat(savedLog.getNewRateCodeName()).isEqualTo("Standard");
    }
}
