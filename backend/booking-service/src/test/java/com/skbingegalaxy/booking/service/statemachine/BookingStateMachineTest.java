package com.skbingegalaxy.booking.service.statemachine;

import com.skbingegalaxy.booking.entity.Booking;
import com.skbingegalaxy.booking.entity.BookingEventType;
import com.skbingegalaxy.booking.repository.BookingRepository;
import com.skbingegalaxy.booking.service.BookingEventLogService;
import com.skbingegalaxy.common.enums.BookingStatus;
import com.skbingegalaxy.common.enums.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of {@link BookingStateMachine} — every cell of the
 * transition table is exercised positively (allowed event by allowed role),
 * a parameterized matrix covers the impossible-state grid, and a dedicated
 * block hammers the SUPER_ADMIN override path.
 *
 * <p>The machine has exactly two collaborators ({@link BookingRepository},
 * {@link BookingEventLogService}) — both mocked — so these are pure unit
 * tests with zero Spring context.
 */
@ExtendWith(MockitoExtension.class)
class BookingStateMachineTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingEventLogService eventLogService;

    @InjectMocks private BookingStateMachine stateMachine;

    private Booking booking;

    @BeforeEach
    void setUp() {
        booking = newBooking(BookingStatus.PENDING);
        // Lenient: many tests exercise rejection paths where save() is never
        // called — Mockito strict mode would otherwise flag this stub.
        org.mockito.Mockito.lenient()
            .when(bookingRepository.save(any(Booking.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking newBooking(BookingStatus status) {
        return Booking.builder()
            .id(42L)
            .bookingRef("SKBG25TEST01")
            .status(status)
            .paymentStatus(PaymentStatus.PENDING)
            .bingeId(11L)
            .customerId(7L)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ALLOWED TRANSITIONS — every cell of the table
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Allowed transitions (every table cell)")
    class AllowedTransitions {

        @Test
        void pending_paymentSucceeded_bySystem_confirms() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.PAYMENT_SUCCEEDED,
                TransitionActor.system(), "Payment captured");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(bookingRepository).save(booking);
            verify(eventLogService).logEventFull(eq(booking), eq(BookingEventType.CONFIRMED),
                eq("PENDING"), eq(null), eq("SYSTEM"), eq(null),
                anyString(), eq("Payment captured"), eq(null), eq(null));
        }

        @Test
        void pending_adminConfirm_byAdmin_confirms() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.ADMIN_CONFIRM,
                TransitionActor.admin(99L, "Admin Alice"), "Manual confirm");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(eventLogService).logEventFull(any(), eq(BookingEventType.CONFIRMED),
                eq("PENDING"), eq(99L), eq("ADMIN"), eq("Admin Alice"),
                anyString(), eq("Manual confirm"), any(), any());
        }

        @Test
        void pending_adminConfirm_bySuperAdmin_confirms() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.ADMIN_CONFIRM,
                TransitionActor.superAdmin(1L, "Root"), "ok");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        void pending_customerCancel_byCustomer_cancels() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CUSTOMER_CANCEL,
                TransitionActor.customer(7L, "Bob"), "Changed plans");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(eventLogService).logEventFull(any(), eq(BookingEventType.CANCELLED),
                eq("PENDING"), eq(7L), eq("CUSTOMER"), eq("Bob"),
                anyString(), eq("Changed plans"), any(), any());
        }

        @Test
        void pending_adminCancel_byAdmin_cancels() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.ADMIN_CANCEL,
                TransitionActor.admin(99L, "Admin Alice"), "Fraud check");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void pending_systemAutoCancel_bySystem_cancels() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.SYSTEM_AUTO_CANCEL,
                TransitionActor.system(), "Payment failed");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void pending_markNoShow_bySystem_marksNoShow() {
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.MARK_NO_SHOW,
                TransitionActor.system(), "Auto: missed start");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
            verify(eventLogService).logEventFull(any(), eq(BookingEventType.NO_SHOW),
                eq("PENDING"), any(), eq("SYSTEM"), any(),
                anyString(), anyString(), any(), any());
        }

        @Test
        void confirmed_checkIn_byAdmin_checksIn() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CHECK_IN,
                TransitionActor.admin(99L, "Admin Alice"), "Front desk");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
            verify(eventLogService).logEventFull(any(), eq(BookingEventType.CHECKED_IN),
                eq("CONFIRMED"), any(), eq("ADMIN"), any(),
                anyString(), anyString(), any(), any());
        }

        @Test
        void confirmed_customerCancel_cancels() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CUSTOMER_CANCEL,
                TransitionActor.customer(7L, "Bob"), null);
            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void confirmed_adminCancel_cancels() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.ADMIN_CANCEL,
                TransitionActor.admin(99L, "A"), null);
            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void confirmed_systemAutoCancel_cancels() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.SYSTEM_AUTO_CANCEL,
                TransitionActor.system(), "saga rollback");
            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

        @Test
        void confirmed_markNoShow_marksNoShow() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.MARK_NO_SHOW,
                TransitionActor.system(), "auto");
            assertThat(out.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
        }

        @Test
        void checkedIn_checkOut_byAdmin_completes() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CHECK_OUT,
                TransitionActor.admin(99L, "Admin Alice"), "Closing");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            verify(eventLogService).logEventFull(any(), eq(BookingEventType.COMPLETED),
                eq("CHECKED_IN"), any(), eq("ADMIN"), any(),
                anyString(), eq("Closing"), any(), any());
        }

        @Test
        void checkedIn_checkOut_bySystem_completes() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CHECK_OUT,
                TransitionActor.system(), "nightly audit");
            assertThat(out.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        void checkedIn_undoCheckIn_byAdmin_revertsToConfirmed() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.UNDO_CHECK_IN,
                TransitionActor.admin(99L, "Admin Alice"), "wrong booking");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(eventLogService).logEventFull(any(),
                eq(BookingEventType.CHECK_IN_REVERTED),
                eq("CHECKED_IN"), any(), eq("ADMIN"), any(),
                anyString(), eq("wrong booking"), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMPOSSIBLE TRANSITIONS — must throw 409
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Impossible transitions throw 409")
    class ImpossibleTransitions {

        static Stream<Arguments> impossiblePairs() {
            return Stream.of(
                // Terminal states reject every regular event
                Arguments.of(BookingStatus.COMPLETED,  BookingTransitionEvent.ADMIN_CANCEL,    TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.COMPLETED,  BookingTransitionEvent.CUSTOMER_CANCEL, TransitionActor.customer(7L, "c")),
                Arguments.of(BookingStatus.COMPLETED,  BookingTransitionEvent.CHECK_IN,        TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.COMPLETED,  BookingTransitionEvent.MARK_NO_SHOW,    TransitionActor.system()),
                Arguments.of(BookingStatus.CANCELLED,  BookingTransitionEvent.CHECK_IN,        TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.CANCELLED,  BookingTransitionEvent.PAYMENT_SUCCEEDED, TransitionActor.system()),
                Arguments.of(BookingStatus.CANCELLED,  BookingTransitionEvent.CHECK_OUT,       TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.CANCELLED,  BookingTransitionEvent.MARK_NO_SHOW,    TransitionActor.system()),
                Arguments.of(BookingStatus.NO_SHOW,    BookingTransitionEvent.CHECK_IN,        TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.NO_SHOW,    BookingTransitionEvent.ADMIN_CANCEL,    TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.NO_SHOW,    BookingTransitionEvent.CHECK_OUT,       TransitionActor.system()),
                // PENDING can't be checked-in or checked-out without confirmation
                Arguments.of(BookingStatus.PENDING,    BookingTransitionEvent.CHECK_IN,        TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.PENDING,    BookingTransitionEvent.CHECK_OUT,       TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.PENDING,    BookingTransitionEvent.UNDO_CHECK_IN,   TransitionActor.admin(1L, "a")),
                // CONFIRMED can't undo or auto-payment-succeed (already paid)
                Arguments.of(BookingStatus.CONFIRMED,  BookingTransitionEvent.UNDO_CHECK_IN,   TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.CONFIRMED,  BookingTransitionEvent.CHECK_OUT,       TransitionActor.admin(1L, "a")),
                // CHECKED_IN can't be cancelled
                Arguments.of(BookingStatus.CHECKED_IN, BookingTransitionEvent.ADMIN_CANCEL,    TransitionActor.admin(1L, "a")),
                Arguments.of(BookingStatus.CHECKED_IN, BookingTransitionEvent.CUSTOMER_CANCEL, TransitionActor.customer(7L, "c")),
                Arguments.of(BookingStatus.CHECKED_IN, BookingTransitionEvent.MARK_NO_SHOW,    TransitionActor.system())
            );
        }

        @ParameterizedTest(name = "{0} + {1} ({2}) → 409")
        @MethodSource("impossiblePairs")
        void impossibleTransition_throwsConflict(BookingStatus from,
                                                 BookingTransitionEvent event,
                                                 TransitionActor actor) {
            booking.setStatus(from);

            assertThatThrownBy(() -> stateMachine.transition(booking, event, actor, "x"))
                .isInstanceOf(InvalidTransitionException.class)
                .satisfies(ex -> {
                    InvalidTransitionException ite = (InvalidTransitionException) ex;
                    assertThat(ite.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ite.getFrom()).isEqualTo(from);
                    assertThat(ite.getEvent()).isEqualTo(event);
                });

            verify(bookingRepository, never()).save(any());
            verifyNoInteractions(eventLogService);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ROLE REJECTION — rule exists but actor lacks permission
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Role rejection (rule exists, role wrong)")
    class RoleRejection {

        @Test
        void customer_cannot_adminConfirm() {
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.ADMIN_CONFIRM,
                TransitionActor.customer(7L, "c"), null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("role not permitted");
        }

        @Test
        void admin_cannot_paymentSucceeded() {
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.PAYMENT_SUCCEEDED,
                TransitionActor.admin(99L, "a"), null))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void system_cannot_checkIn() {
            booking.setStatus(BookingStatus.CONFIRMED);
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.CHECK_IN,
                TransitionActor.system(), null))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void admin_cannot_customerCancel() {
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.CUSTOMER_CANCEL,
                TransitionActor.admin(99L, "a"), null))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void customer_cannot_systemAutoCancel() {
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.SYSTEM_AUTO_CANCEL,
                TransitionActor.customer(7L, "c"), null))
                .isInstanceOf(InvalidTransitionException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  REASON-REQUIRED guards
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Reason-required transitions")
    class ReasonRequired {

        @Test
        void undoCheckIn_blankReason_throws() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.UNDO_CHECK_IN,
                TransitionActor.admin(99L, "a"), "  "))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("reason is required");
        }

        @Test
        void undoCheckIn_nullReason_throws() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            assertThatThrownBy(() -> stateMachine.transition(booking,
                BookingTransitionEvent.UNDO_CHECK_IN,
                TransitionActor.admin(99L, "a"), null))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void undoCheckIn_withReason_succeeds() {
            booking.setStatus(BookingStatus.CHECKED_IN);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.UNDO_CHECK_IN,
                TransitionActor.admin(99L, "Alice"), "Wrong booking");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IDEMPOTENT REPLAY (Kafka retries don't double-fire)
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Idempotent replays return without re-emitting audit")
    class IdempotentReplay {

        @Test
        void paymentSucceeded_onAlreadyConfirmed_isNoOp() {
            booking.setStatus(BookingStatus.CONFIRMED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.PAYMENT_SUCCEEDED,
                TransitionActor.system(), "replay");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            verify(bookingRepository, never()).save(any());
            verifyNoInteractions(eventLogService);
        }

        @Test
        void customerCancel_onAlreadyCancelled_isNoOp() {
            booking.setStatus(BookingStatus.CANCELLED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CUSTOMER_CANCEL,
                TransitionActor.customer(7L, "c"), "replay");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(bookingRepository, never()).save(any());
            verifyNoInteractions(eventLogService);
        }

        @Test
        void checkOut_onAlreadyCompleted_isNoOp() {
            booking.setStatus(BookingStatus.COMPLETED);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.CHECK_OUT,
                TransitionActor.system(), "replay");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            verify(bookingRepository, never()).save(any());
            verifyNoInteractions(eventLogService);
        }

        @Test
        void markNoShow_onAlreadyNoShow_isNoOp() {
            booking.setStatus(BookingStatus.NO_SHOW);
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.MARK_NO_SHOW,
                TransitionActor.system(), "replay");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
            verify(bookingRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SUPER_ADMIN OVERRIDE PATH
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Override path (SUPER_ADMIN recovery)")
    class OverridePath {

        @Test
        void cancelled_to_confirmed_bySuperAdmin_succeeds() {
            booking.setStatus(BookingStatus.CANCELLED);
            Booking out = stateMachine.override(booking, BookingStatus.CONFIRMED,
                TransitionActor.superAdmin(1L, "Root"),
                "Customer disputed cancellation; CC verified");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

            ArgumentCaptor<String> descCap = ArgumentCaptor.forClass(String.class);
            verify(eventLogService).logEventFull(any(),
                eq(BookingEventType.MANUAL_REVIEW_FLAGGED),
                eq("CANCELLED"), eq(1L), eq("SUPER_ADMIN"), eq("Root"),
                descCap.capture(),
                eq("Customer disputed cancellation; CC verified"),
                any(), any());
            assertThat(descCap.getValue()).contains("[OVERRIDE]")
                                          .contains("CANCELLED → CONFIRMED");
        }

        @Test
        void noShow_to_checkedIn_bySuperAdmin_succeeds() {
            booking.setStatus(BookingStatus.NO_SHOW);
            Booking out = stateMachine.override(booking, BookingStatus.CHECKED_IN,
                TransitionActor.superAdmin(1L, "Root"),
                "Customer arrived late but staff confirmed presence");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        }

        @Test
        void completed_to_checkedIn_bySuperAdmin_succeeds() {
            booking.setStatus(BookingStatus.COMPLETED);
            Booking out = stateMachine.override(booking, BookingStatus.CHECKED_IN,
                TransitionActor.superAdmin(1L, "Root"),
                "Premature checkout: session continuing");

            assertThat(out.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        }

        @Test
        void admin_cannot_override() {
            booking.setStatus(BookingStatus.CANCELLED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.CONFIRMED,
                TransitionActor.admin(99L, "a"), "trying"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("requires SUPER_ADMIN");
            verify(bookingRepository, never()).save(any());
        }

        @Test
        void customer_cannot_override() {
            booking.setStatus(BookingStatus.CANCELLED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.CONFIRMED,
                TransitionActor.customer(7L, "c"), "trying"))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void system_cannot_override() {
            booking.setStatus(BookingStatus.CANCELLED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.CONFIRMED,
                TransitionActor.system(), "trying"))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void blankReason_throws() {
            booking.setStatus(BookingStatus.CANCELLED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.CONFIRMED,
                TransitionActor.superAdmin(1L, "Root"), "  "))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("reason is mandatory");
        }

        @Test
        void targetOutsideAllowList_throws() {
            // CANCELLED → COMPLETED is NOT in OVERRIDE_TARGETS
            booking.setStatus(BookingStatus.CANCELLED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.COMPLETED,
                TransitionActor.superAdmin(1L, "Root"), "force"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not in the allow-list");
        }

        @Test
        void overrideFromActiveState_throws() {
            // CONFIRMED is not a valid SOURCE for override (not in OVERRIDE_TARGETS keys)
            booking.setStatus(BookingStatus.CONFIRMED);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.PENDING,
                TransitionActor.superAdmin(1L, "Root"), "force"))
                .isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void noShow_to_completed_notAllowed() {
            booking.setStatus(BookingStatus.NO_SHOW);
            assertThatThrownBy(() -> stateMachine.override(booking, BookingStatus.COMPLETED,
                TransitionActor.superAdmin(1L, "Root"), "force"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not in the allow-list");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  canTransition() inspector
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("canTransition() inspector helper")
    class CanTransition {

        @Test
        void returnsTrue_forAllowedTransition() {
            assertThat(stateMachine.canTransition(BookingStatus.PENDING,
                BookingTransitionEvent.PAYMENT_SUCCEEDED, TransitionActor.system())).isTrue();
            assertThat(stateMachine.canTransition(BookingStatus.CONFIRMED,
                BookingTransitionEvent.CHECK_IN, TransitionActor.admin(1L, "a"))).isTrue();
        }

        @Test
        void returnsFalse_forImpossibleTransition() {
            assertThat(stateMachine.canTransition(BookingStatus.COMPLETED,
                BookingTransitionEvent.ADMIN_CANCEL, TransitionActor.admin(1L, "a"))).isFalse();
        }

        @Test
        void returnsFalse_forWrongRole() {
            assertThat(stateMachine.canTransition(BookingStatus.PENDING,
                BookingTransitionEvent.PAYMENT_SUCCEEDED, TransitionActor.admin(1L, "a"))).isFalse();
        }

        @Test
        void returnsFalse_forNullArgs() {
            assertThat(stateMachine.canTransition(null,
                BookingTransitionEvent.CHECK_IN, TransitionActor.system())).isFalse();
            assertThat(stateMachine.canTransition(BookingStatus.PENDING,
                null, TransitionActor.system())).isFalse();
            assertThat(stateMachine.canTransition(BookingStatus.PENDING,
                BookingTransitionEvent.PAYMENT_SUCCEEDED, null)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Defensive null/error handling
    // ─────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Defensive guards")
    class Defensive {

        @Test
        void nullBooking_throws() {
            assertThatThrownBy(() -> stateMachine.transition(null,
                BookingTransitionEvent.PAYMENT_SUCCEEDED, TransitionActor.system(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullEvent_throws() {
            assertThatThrownBy(() -> stateMachine.transition(booking,
                null, TransitionActor.system(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullActor_defaultsToSystem() {
            // PAYMENT_SUCCEEDED + SYSTEM is allowed → null actor should be
            // treated as system and succeed.
            Booking out = stateMachine.transition(booking,
                BookingTransitionEvent.PAYMENT_SUCCEEDED, null, "auto");
            assertThat(out.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        }

        @Test
        void invalidTransitionException_carriesMetadata() {
            booking.setStatus(BookingStatus.COMPLETED);
            try {
                stateMachine.transition(booking,
                    BookingTransitionEvent.CUSTOMER_CANCEL,
                    TransitionActor.customer(7L, "c"), "x");
            } catch (InvalidTransitionException ex) {
                assertThat(ex.getFrom()).isEqualTo(BookingStatus.COMPLETED);
                assertThat(ex.getEvent()).isEqualTo(BookingTransitionEvent.CUSTOMER_CANCEL);
                assertThat(ex.getActorRole()).isEqualTo("CUSTOMER");
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                return;
            }
            throw new AssertionError("expected InvalidTransitionException");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Single audit event per transition
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void successfulTransition_emitsExactlyOneAuditRow() {
        stateMachine.transition(booking, BookingTransitionEvent.PAYMENT_SUCCEEDED,
            TransitionActor.system(), "Payment captured");

        verify(eventLogService, times(1)).logEventFull(any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any());
        verify(bookingRepository, times(1)).save(any());
    }
}
