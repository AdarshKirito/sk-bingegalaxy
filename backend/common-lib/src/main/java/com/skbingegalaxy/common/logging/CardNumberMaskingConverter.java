package com.skbingegalaxy.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback PatternLayout converter that replaces card-number patterns in log
 * messages with a fixed "[CARD-REDACTED]" token before any appender writes them.
 *
 * Register via conversionRule in logback-spring.xml:
 *   <conversionRule conversionWord="msg"
 *       converterClass="com.skbingegalaxy.common.logging.CardNumberMaskingConverter"/>
 *
 * Overriding the built-in "msg" word means ALL pattern-based appenders (console,
 * file) automatically mask PANs without touching application code.
 *
 * For LogstashEncoder (JSON/Loki profile) use MaskingMessageJsonProvider instead,
 * since that encoder bypasses PatternLayout entirely.
 *
 * PCI-DSS 3.2.1 req 3.4: stored/transmitted PANs must be unreadable. Masking
 * in the log pipeline satisfies this for the log-transport path.
 */
public class CardNumberMaskingConverter extends ClassicConverter {

    /**
     * Matches common card PAN formats:
     * - 16-digit groups of 4 with optional space/dash separators (Visa, MC, Discover)
     * - Visa 13-digit
     * - Amex 15-digit (groups 4-6-5)
     * - Diners 14-digit
     * - JCB 15/16-digit
     *
     * The pattern is intentionally broad: false positives on phone numbers or
     * reference codes are acceptable; false negatives (missing a real PAN) are not.
     */
    private static final Pattern CARD_PATTERN = Pattern.compile(
        // 16-digit groups-of-4, optional separator — catches most Visa/MC/Discover
        "\\b(?:\\d{4}[\\s\\-]?){3}\\d{4}\\b"
        // Amex: 4-6-5
        + "|\\b3[47]\\d{2}[\\s\\-]?\\d{6}[\\s\\-]?\\d{5}\\b"
        // Generic 13-digit Visa (old format)
        + "|\\b4\\d{12}\\b"
        // Diners 14-digit
        + "|\\b3(?:0[0-5]|[68]\\d)\\d{11}\\b"
        // JCB 16-digit
        + "|\\b(?:2131|1800|35\\d{3})\\d{11}\\b"
    );

    private static final String REDACTED = "[CARD-REDACTED]";

    /** Apply card masking to a raw string. Also used by MaskingMessageJsonProvider. */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        return CARD_PATTERN.matcher(input).replaceAll(REDACTED);
    }

    @Override
    public String convert(ILoggingEvent event) {
        return mask(event.getFormattedMessage());
    }
}
