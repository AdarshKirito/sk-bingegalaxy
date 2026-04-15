package com.skbingegalaxy.notification.model;

/**
 * Tracks the delivery lifecycle of a notification.
 */
public enum DeliveryStatus {
    /** Notification created but not yet dispatched. */
    PENDING,
    /** Successfully dispatched to the channel provider. */
    SENT,
    /** Confirmed delivered to the recipient (webhook callback). */
    DELIVERED,
    /** Delivery bounced / permanently failed. */
    BOUNCED,
    /** Recipient opened the notification (email open tracking). */
    OPENED,
    /** Recipient clicked a link in the notification. */
    CLICKED,
    /** Recipient marked as spam / complained. */
    COMPLAINED
}
