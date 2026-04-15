package com.skbingegalaxy.notification.provider;

/**
 * Abstraction over WhatsApp delivery backends.
 */
public interface WhatsAppProvider {

    String send(String toPhone, String body);

    String providerName();
}
