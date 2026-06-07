package com.skbingegalaxy.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

import java.io.IOException;

/**
 * Replaces the default LogstashEncoder message field with a PCI-safe masked version.
 *
 * Configure in logback-spring.xml inside the LogstashEncoder:
 *   <message class="com.skbingegalaxy.common.logging.MaskingMessageJsonProvider"/>
 *
 * This intercepts the "message" JSON field before it reaches Loki/OpenSearch,
 * ensuring card PANs never appear in the structured log store even if application
 * code accidentally logs raw payment data.
 */
public class MaskingMessageJsonProvider extends MessageJsonProvider {

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        String masked = CardNumberMaskingConverter.mask(event.getFormattedMessage());
        generator.writeStringField(getFieldName(), masked);
    }
}
