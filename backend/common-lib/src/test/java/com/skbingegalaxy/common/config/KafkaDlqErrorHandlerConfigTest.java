package com.skbingegalaxy.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression fence for the shared Kafka consumer error handler.
 *
 * <p>We keep this at the unit level — DLT routing itself is exercised by
 * Spring Kafka's own test suite, and reproducing a full {@code
 * MessageListenerContainer} would make the test slow and flaky. What
 * matters to us is the contract: the bean exists and the exceptions we
 * listed as fatal are classified as <em>not retryable</em>. If a future
 * refactor drops those classifications, the poison-pill outage returns,
 * and this test flips red.</p>
 */
@SuppressWarnings("unchecked")
class KafkaDlqErrorHandlerConfigTest {

    private DefaultErrorHandler handler;

    @BeforeEach
    void setUp() {
        KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
        handler = new KafkaDlqErrorHandlerConfig().kafkaErrorHandler(template);
    }

    @Test
    @DisplayName("bean is constructed with the DLT recoverer wired")
    void beanIsCreated() {
        assertThat(handler).isNotNull();
    }

    @Test
    @DisplayName("DeserializationException is classified as non-retryable")
    void deserializationExceptionIsNotRetryable() {
        // removeClassification returns the prior classification; Boolean.FALSE
        // means the exception was on the no-retry list.
        assertThat(handler.removeClassification(DeserializationException.class))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("IllegalArgumentException is non-retryable")
    void illegalArgumentNotRetryable() {
        assertThat(handler.removeClassification(IllegalArgumentException.class))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("NullPointerException is non-retryable")
    void nullPointerNotRetryable() {
        assertThat(handler.removeClassification(NullPointerException.class))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("ClassCastException is non-retryable")
    void classCastNotRetryable() {
        assertThat(handler.removeClassification(ClassCastException.class))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("generic RuntimeException is NOT on the no-retry list")
    void genericRuntimeIsRetryable() {
        assertThat(handler.removeClassification(RuntimeException.class))
            .isNull();
    }
}
