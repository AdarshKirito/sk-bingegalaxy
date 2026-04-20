package com.skbingegalaxy.notification.provider;

import java.util.Map;

/**
 * Abstraction over WhatsApp delivery backends.
 */
public interface WhatsAppProvider {

    String send(String toPhone, String body);

    /**
     * Send a WhatsApp message using a pre-approved Content SID template.
     * Default implementation falls back to raw body send.
     */
    default String sendWithContentSid(String toPhone, String contentSid, Map<String, String> variables) {
        // Fallback: providers that don't support Content SID send raw text
        String body = variables != null ? variables.toString() : "";
        return send(toPhone, body);
    }

    String providerName();
}
