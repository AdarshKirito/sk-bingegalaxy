package com.skbingegalaxy.booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FX rate row. Stores how many units of THIS currency equal one unit of the
 * BASE currency (INR). To convert an INR amount to this currency:
 *   <pre>display = inr * rateToBase</pre>
 * To convert FROM this currency back to INR:
 *   <pre>inr = display / rateToBase</pre>
 */
@Entity
@Table(name = "currency_rates")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CurrencyRate {

    /** ISO-4217 code (e.g. "USD"). */
    @Id
    @Column(length = 8)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 8)
    private String symbol;

    @Column(name = "rate_to_base", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateToBase;

    @Column(name = "decimal_digits", nullable = false)
    @Builder.Default
    private Integer decimalDigits = 2;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "is_base", nullable = false)
    @Builder.Default
    private boolean base = false;

    @UpdateTimestamp
    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    /** When TRUE the rate was set by an admin and should NOT be auto-overwritten by FX fetcher. */
    @Column(name = "manual_override", nullable = false)
    @Builder.Default
    private boolean manualOverride = false;

    /** May this currency be shown to customers as a display currency? */
    @Column(name = "supports_display", nullable = false)
    @Builder.Default
    private boolean supportsDisplay = true;

    /** May this currency be charged via a payment gateway? */
    @Column(name = "supports_payment", nullable = false)
    @Builder.Default
    private boolean supportsPayment = false;

    /** Can the platform settle in this currency? */
    @Column(name = "supports_settlement", nullable = false)
    @Builder.Default
    private boolean supportsSettlement = false;

    /** Where the FX rate came from: MANUAL, ECB, OPENEXCHANGE, FIXER. */
    @Column(name = "fx_source", nullable = false, length = 40)
    @Builder.Default
    private String fxSource = "MANUAL";

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
