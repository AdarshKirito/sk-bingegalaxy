package com.skbingegalaxy.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Inbound Razorpay dispute webhook payload.
 *
 * Razorpay POSTs to our /webhooks/razorpay endpoint with Content-Type:
 * application/json and an X-Razorpay-Signature HMAC-SHA256 header.
 *
 * Supported event types:
 *   payment.dispute.created   — chargeback opened, amount held by gateway
 *   payment.dispute.under_review — merchant evidence submitted, under review
 *   payment.dispute.won       — dispute resolved in merchant's favour, funds released
 *   payment.dispute.lost      — dispute resolved against merchant, amount deducted
 *   payment.dispute.accepted  — merchant accepted the chargeback, no contest
 *
 * The full Razorpay webhook envelope wraps these inside:
 *   { "entity": "event", "event": "payment.dispute.created",
 *     "payload": { "payment": { "entity": {...} }, "dispute": { "entity": {...} } } }
 *
 * We flatten the relevant fields here for processing.
 */
@Data
public class DisputeWebhookRequest {

    /** Full Razorpay event name e.g. "payment.dispute.created". */
    @JsonProperty("event")
    private String event;

    @JsonProperty("payload")
    private Payload payload;

    @Data
    public static class Payload {
        @JsonProperty("payment")
        private PaymentEntity payment;

        @JsonProperty("dispute")
        private DisputeEntity dispute;
    }

    @Data
    public static class PaymentEntity {
        @JsonProperty("entity")
        private PaymentFields entity;
    }

    @Data
    public static class PaymentFields {
        @JsonProperty("id")
        private String id;               // rzp payment id  e.g. "pay_xxx"

        @JsonProperty("order_id")
        private String orderId;           // rzp order id — maps to our gatewayOrderId

        @JsonProperty("amount")
        private Long amountPaise;         // amount in smallest currency unit (paise)

        @JsonProperty("currency")
        private String currency;
    }

    @Data
    public static class DisputeEntity {
        @JsonProperty("entity")
        private DisputeFields entity;
    }

    @Data
    public static class DisputeFields {
        @JsonProperty("id")
        private String id;               // rzp dispute id  e.g. "disp_xxx"

        @JsonProperty("payment_id")
        private String paymentId;

        @JsonProperty("amount")
        private Long amountPaise;

        @JsonProperty("currency")
        private String currency;

        @JsonProperty("amount_deducted")
        private Long amountDeductedPaise;

        @JsonProperty("reason_code")
        private String reasonCode;

        @JsonProperty("respond_by")
        private Long respondByEpoch;     // Unix epoch seconds

        @JsonProperty("status")
        private String status;

        @JsonProperty("phase")
        private String phase;

        @JsonProperty("created_at")
        private Long createdAtEpoch;
    }
}
