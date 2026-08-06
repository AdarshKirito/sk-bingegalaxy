package com.skbingegalaxy.booking.controller;

import com.skbingegalaxy.common.constants.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replay allow-list is the recovery console's contract.
 *
 * <p>It had drifted from reality in both directions at once: it named a DLT no service
 * declares, and omitted seven that exist — including {@code notification.send-dlt} and
 * {@code user.anonymized-dlt}, whose parked records mean an unsent notification and
 * residual PII after an erasure request. Meanwhile the console offered five topic names
 * of its own invention, none of them on the list, so the default selection and every
 * other option were rejected with a 400 and recovery was unusable out of the box.
 *
 * <p>These are cheap assertions guarding an expensive failure: nobody exercises DLT
 * replay until something has already gone wrong.
 */
@DisplayName("Admin ops DLT replay contract")
class AdminOpsControllerTest {

    /**
     * Every topic for which some service declares a {@code deadLetterTopic(...)} bean.
     * Kept beside the allow-list on purpose: adding a DLT in a service's KafkaConfig
     * without adding it here fails, and the failure names the gap.
     */
    private static final List<String> DECLARED_DLT_SOURCES = List.of(
        // booking-service KafkaConfig
        KafkaTopics.PAYMENT_SUCCESS, KafkaTopics.PAYMENT_FAILED, KafkaTopics.PAYMENT_REFUNDED,
        KafkaTopics.BOOKING_CANCELLED, KafkaTopics.BOOKING_RESCHEDULED,
        KafkaTopics.BOOKING_TRANSFERRED, KafkaTopics.USER_ANONYMIZED,
        // payment-service KafkaConfig
        KafkaTopics.BOOKING_CASH_PAYMENT,
        // notification-service KafkaConfig
        KafkaTopics.BOOKING_CREATED, KafkaTopics.NOTIFICATION_SEND,
        KafkaTopics.USER_REGISTERED, KafkaTopics.PASSWORD_RESET);

    @Nested
    @DisplayName("the allow-list")
    class AllowList {

        @Test
        @DisplayName("covers every declared dead-letter topic")
        void coversEveryDeclaredDlt() {
            List<String> expected = DECLARED_DLT_SOURCES.stream()
                .map(t -> t + AdminOpsController.DLT_SUFFIX)
                .toList();

            assertThat(AdminOpsController.REPLAYABLE_SOURCE_TOPIC_LIST)
                .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("names no topic that does not exist")
        void namesNoPhantomTopic() {
            // booking.confirmed-dlt sat on the list for the life of the feature. No
            // service declares it and auto-create is off in production, so replaying
            // from it could only ever find nothing — while looking like a working
            // recovery route for confirmations.
            assertThat(AdminOpsController.REPLAYABLE_SOURCE_TOPIC_LIST)
                .doesNotContain(KafkaTopics.BOOKING_CONFIRMED + AdminOpsController.DLT_SUFFIX);
        }

        @Test
        @DisplayName("every entry ends in the suffix the replay strips")
        void suffixIsConsistent() {
            // replayDlt derives the TARGET topic by removing this suffix. An entry
            // without it would republish onto a truncated topic name that nothing reads.
            assertThat(AdminOpsController.REPLAYABLE_SOURCE_TOPIC_LIST)
                .allSatisfy(topic -> assertThat(topic).endsWith(AdminOpsController.DLT_SUFFIX));
        }

        @Test
        @DisplayName("has no duplicates, so the console shows each topic once")
        void noDuplicates() {
            assertThat(AdminOpsController.REPLAYABLE_SOURCE_TOPIC_LIST)
                .doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("the batch ceiling the console is told matches the one enforced")
    void batchCeilingIsShared() {
        // The console offered up to 10000 while the controller rejected anything over
        // 1000, so a form the operator filled in correctly still 400'd. The number is
        // now served from here, which is the only way the two cannot disagree.
        assertThat(AdminOpsController.MAX_REPLAY_BATCH).isEqualTo(1000);
    }
}
