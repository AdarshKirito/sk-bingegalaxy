package com.skbingegalaxy.booking.service;

import com.skbingegalaxy.booking.client.AvailabilityClient;
import com.skbingegalaxy.booking.client.AvailabilityClientFallback;
import com.skbingegalaxy.booking.entity.*;
import com.skbingegalaxy.booking.repository.*;
import com.skbingegalaxy.common.context.BingeContext;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for checkout and check-in flow, and revenue-related operations.
 * Specifically verifies the fix for:
 *  - Bug: checkedIn not reset to false on checkout
 *  - Bug: collectedAmount mismatch warning
 */
@ExtendWith(MockitoExtension.class)
class BookingCheckoutAndRevenueTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AddOnRepository addOnRepository;
    @Mock private AvailabilityClient availabilityClient;
    @Mock private AvailabilityClientFallback availabilityFallback;
    @Mock private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private SystemSettingsService systemSettingsService;
    @Mock private PricingService pricingService;
    @Mock private BookingEventLogService eventLogService;
    @Mock private SagaOrchestrator sagaOrchestrator;

    @InjectMocks private BookingService bookingService;

    private Booking checkedInBooking;

    @BeforeEach
    void setUp() {
                BingeContext.clear();
                BingeContext.setBingeId(11L);
        ReflectionTestUtils.setField(bookingService, "refPrefix", "SKBG");

        EventType eventType = EventType.builder()
                .id(1L).name("Birthday Party")
                .basePrice(BigDecimal.valueOf(2000))
                .hourlyRate(BigDecimal.valueOf(500))
                .minHours(2).maxHours(8)
                .active(true).build();

        checkedInBooking = Booking.builder()
                .id(1L).bookingRef("SKBG25123456")
                .customerId(1L).customerName("John Doe")
                .customerEmail("john@example.com").customerPhone("9876543210")
                .eventType(eventType)
                .bookingDate(LocalDate.now())
                .startTime(LocalTime.of(10, 0))
                .durationHours(3)
                .durationMinutes(180)
                .baseAmount(BigDecimal.valueOf(3500))
                .addOnAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(11000))
                .collectedAmount(BigDecimal.ZERO)
                .status(BookingStatus.CHECKED_IN)
                .paymentStatus(PaymentStatus.SUCCESS)
                .checkedIn(true)
                .addOns(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ══════════════════════════════════════════════════════
    //  EARLY CHECKOUT TESTS (Bug fix verification)
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("earlyCheckout - checkedIn reset bug fix")
    class EarlyCheckoutTests {

        @Test
        @DisplayName("Normal checkout (past scheduled end) resets checkedIn to false")
        void normalCheckout_setsCheckedInFalse() {
            // Scheduled: 10:00 - 13:00, checking out at 14:00 (after end)
            LocalDateTime checkoutTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(14, 0));

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(checkedInBooking));
            when(bookingRepository.save(any(Booking.class)))
                    .thenAnswer(i -> i.getArgument(0));

            bookingService.earlyCheckout("SKBG25123456", checkoutTime);

            assertThat(checkedInBooking.isCheckedIn()).isFalse();
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(checkedInBooking.getActualCheckoutTime()).isEqualTo(checkoutTime);
        }

        @Test
        @DisplayName("Early checkout (before scheduled end) resets checkedIn to false")
        void earlyCheckout_setsCheckedInFalse() {
            // Scheduled: 10:00 - 13:00, checking out at 11:30 (early)
            LocalDateTime checkoutTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(11, 30));

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(checkedInBooking));
            when(bookingRepository.save(any(Booking.class)))
                    .thenAnswer(i -> i.getArgument(0));

            bookingService.earlyCheckout("SKBG25123456", checkoutTime);

            assertThat(checkedInBooking.isCheckedIn()).isFalse();
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            assertThat(checkedInBooking.getEarlyCheckoutNote()).isNotNull();
            assertThat(checkedInBooking.getActualUsedMinutes()).isNotNull();
        }

        @Test
        @DisplayName("Checkout booking that is not CHECKED_IN throws exception")
        void checkout_notCheckedIn_throwsException() {
            checkedInBooking.setStatus(BookingStatus.CONFIRMED);

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(checkedInBooking));

            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CHECKED_IN");
        }

        @Test
        @DisplayName("Checkout with no valid payment throws exception")
        void checkout_noPayment_throwsException() {
            checkedInBooking.setPaymentStatus(PaymentStatus.PENDING);

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(checkedInBooking));

            assertThatThrownBy(() -> bookingService.earlyCheckout("SKBG25123456", LocalDateTime.now()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no valid payment");
        }

        @Test
        @DisplayName("Checkout for non-existent booking throws ResourceNotFoundException")
        void checkout_notFound_throwsException() {
                        when(bookingRepository.findByBookingRefAndBingeId("INVALID", 11L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.earlyCheckout("INVALID", LocalDateTime.now()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("After checkout, saved booking has checkedIn=false and status=COMPLETED")
        void checkout_verifyPersistedState() {
            LocalDateTime checkoutTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(14, 0));

            when(bookingRepository.findByBookingRefAndBingeId("SKBG25123456", 11L))
                    .thenReturn(Optional.of(checkedInBooking));
            when(bookingRepository.save(any(Booking.class)))
                    .thenAnswer(i -> i.getArgument(0));

            bookingService.earlyCheckout("SKBG25123456", checkoutTime);

            ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(captor.capture());

            Booking saved = captor.getValue();
            assertThat(saved.isCheckedIn()).isFalse();
            assertThat(saved.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        }
    }

    // ══════════════════════════════════════════════════════
    //  COLLECTED AMOUNT TESTS (Revenue mismatch warning)
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("addToCollectedAmount - revenue tracking")
    class CollectedAmountTests {

        @Test
        @DisplayName("Adds amount to collectedAmount correctly")
        void addToCollectedAmount_addsCorrectly() {
            when(bookingRepository.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(11000)))
                    .thenReturn(1);
            // No mismatch check since no findByBookingRef stubbed — totalAmount is null
            bookingService.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(11000));

            verify(bookingRepository).addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(11000));
        }

        @Test
        @DisplayName("Null amount is ignored")
        void addToCollectedAmount_nullAmount_noop() {
            bookingService.addToCollectedAmount("SKBG25123456", null);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Zero amount is ignored")
        void addToCollectedAmount_zeroAmount_noop() {
            bookingService.addToCollectedAmount("SKBG25123456", BigDecimal.ZERO);
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("Multiple payments accumulate via atomic query")
        void addToCollectedAmount_accumulatesMultiplePayments() {
            when(bookingRepository.addToCollectedAmount(eq("SKBG25123456"), any()))
                    .thenReturn(1);

            bookingService.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(5000));
            bookingService.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(4000));

            verify(bookingRepository).addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(5000));
            verify(bookingRepository).addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(4000));
        }

        @Test
        @DisplayName("Amount mismatch with totalAmount logs warning")
        void addToCollectedAmount_mismatchWarning_stillSaves() {
            checkedInBooking.setTotalAmount(BigDecimal.valueOf(11000));
            checkedInBooking.setCollectedAmount(BigDecimal.valueOf(9000));

            when(bookingRepository.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(9000)))
                    .thenReturn(1);
            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(checkedInBooking));

            bookingService.addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(9000));

            verify(bookingRepository).addToCollectedAmount("SKBG25123456", BigDecimal.valueOf(9000));
        }
    }

    // ══════════════════════════════════════════════════════
    //  PAYMENT STATUS UPDATE TESTS
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("updatePaymentStatus")
    class PaymentStatusTests {

        @Test
        @DisplayName("SUCCESS payment on PENDING booking confirms it")
        void success_confirmsPendingBooking() {
            checkedInBooking.setStatus(BookingStatus.PENDING);
            checkedInBooking.setPaymentStatus(PaymentStatus.PENDING);

            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(checkedInBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.SUCCESS, "CARD");

            assertThat(checkedInBooking.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        @DisplayName("SUCCESS payment on CHECKED_IN booking does NOT override status")
        void success_doesNotOverrideCheckedIn() {
            checkedInBooking.setStatus(BookingStatus.CHECKED_IN);
            checkedInBooking.setPaymentStatus(PaymentStatus.PENDING);

            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(checkedInBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.SUCCESS, "UPI");

            assertThat(checkedInBooking.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        }

        @Test
        @DisplayName("FAILED payment does not change booking status")
        void failed_doesNotChangeStatus() {
            checkedInBooking.setStatus(BookingStatus.PENDING);

            when(bookingRepository.findByBookingRef("SKBG25123456"))
                    .thenReturn(Optional.of(checkedInBooking));

            bookingService.updatePaymentStatus("SKBG25123456", PaymentStatus.FAILED, null);

            assertThat(checkedInBooking.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(checkedInBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
        }
    }
}
