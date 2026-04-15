package com.skbingegalaxy.notification.provider;

/**
 * Abstraction over SMS delivery backends (Twilio, SNS, Exotel, …).
 */
public interface SmsProvider {

    /** Send an SMS and return the provider-assigned message SID / ID. */
    String send(String toPhone, String body);

    /** Human-readable name shown in logs and failure reasons. */
    String providerName();
}
