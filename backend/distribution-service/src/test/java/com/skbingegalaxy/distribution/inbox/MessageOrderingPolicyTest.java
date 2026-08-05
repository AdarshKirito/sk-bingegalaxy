package com.skbingegalaxy.distribution.inbox;

import com.skbingegalaxy.distribution.entity.ReservationInboxEntry.OrderingBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Inbound message ordering (distribution G-C)")
class MessageOrderingPolicyTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 8, 1, 11, 0);

    @Nested
    @DisplayName("Provider sequence")
    class Sequences {

        @Test
        @DisplayName("the first message for a reservation always applies")
        void firstMessageApplies() {
            assertThat(MessageOrderingPolicy.decide(5L, Optional.empty()).apply()).isTrue();
        }

        @Test
        @DisplayName("a higher sequence applies")
        void higherApplies() {
            var d = MessageOrderingPolicy.decide(6L, Optional.of(5L));
            assertThat(d.apply()).isTrue();
            assertThat(d.basis()).isEqualTo(OrderingBasis.PROVIDER_SEQUENCE);
        }

        @Test
        @DisplayName("THE BUG THIS EXISTS FOR: an older MODIFY arriving after a CANCEL is superseded")
        void olderMessageAfterCancelIsSuperseded() {
            // Cancel (seq 7) already applied; the modify it superseded (seq 6) arrives
            // late. Applying it in receipt order would resurrect a cancelled booking —
            // a traveller shows up to a slot the venue believes is free.
            var d = MessageOrderingPolicy.decide(6L, Optional.of(7L));

            assertThat(d.apply()).isFalse();
            assertThat(d.basis()).isEqualTo(OrderingBasis.PROVIDER_SEQUENCE);
            assertThat(d.reason()).contains("does not exceed");
        }

        @Test
        @DisplayName("an equal sequence is superseded, not re-applied")
        void equalIsSuperseded() {
            // The unique index should have caught a duplicate, but if the two ever raced
            // then re-applying could overwrite a later state with an identical-sequence
            // retry. Strictly-greater is the safe comparison.
            assertThat(MessageOrderingPolicy.decide(7L, Optional.of(7L)).apply()).isFalse();
        }
    }

    @Nested
    @DisplayName("Fallbacks")
    class Fallbacks {

        @Test
        @DisplayName("without sequences, provider timestamps order the messages")
        void timestampFallback() {
            var d = MessageOrderingPolicy.decide(null, null, T2, T1);
            assertThat(d.apply()).isTrue();
            assertThat(d.basis()).isEqualTo(OrderingBasis.PROVIDER_TIMESTAMP);

            assertThat(MessageOrderingPolicy.decide(null, null, T1, T2).apply()).isFalse();
        }

        @Test
        @DisplayName("a sequence on one side only falls back to the shared basis")
        void mixedSignalsFallBack() {
            // Comparing a sequence against a timestamp would compare incomparable values.
            var d = MessageOrderingPolicy.decide(9L, null, T2, T1);
            assertThat(d.apply()).isTrue();
            assertThat(d.basis()).isEqualTo(OrderingBasis.PROVIDER_TIMESTAMP);
        }

        @Test
        @DisplayName("with no shared signal it applies, but records that ordering was luck")
        void receiptOrderIsRecordedHonestly() {
            var d = MessageOrderingPolicy.decide(null, null, null, T1);

            assertThat(d.apply()).isTrue();
            // Refusing would strand every message from a provider that supplies neither.
            // But a reconciliation run must be able to tell "the provider ordered this"
            // from "we hoped", so the basis is recorded rather than assumed.
            assertThat(d.basis()).isEqualTo(OrderingBasis.RECEIPT_ORDER);
        }

        @Test
        @DisplayName("a first message with no ordering signal at all still applies")
        void firstWithNoSignal() {
            var d = MessageOrderingPolicy.decide(null, null, null, null);
            assertThat(d.apply()).isTrue();
            assertThat(d.basis()).isEqualTo(OrderingBasis.RECEIPT_ORDER);
        }
    }
}
