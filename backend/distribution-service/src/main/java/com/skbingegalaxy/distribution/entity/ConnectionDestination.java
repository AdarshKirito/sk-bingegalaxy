package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A destination reached through a particular connection, and the commercial terms that
 * apply there. This is where the connectivity/destination split earns its keep: one
 * Bókun connection can reach Viator and GetYourGuide on completely different commission,
 * settlement and payment terms.
 */
@Entity
@Table(name = "connection_destinations")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ConnectionDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "destination_code", nullable = false, length = 40)
    private String destinationCode;

    /** Off by default. Distribution is opt-in at every level. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Basis points (2000 = 20%), matching the existing Stripe Connect convention. */
    @Column(name = "commission_bps")
    private Integer commissionBps;

    /**
     * Who takes the traveller's money.
     *
     * <p>Defaults to {@code CHANNEL_COLLECTS} because that is how the two most relevant
     * destinations actually work: <b>Viator is merchant of record</b>, collecting the
     * full payment and paying the operator the net rate <em>after</em> the experience;
     * <b>GetYourGuide is Merchant of Record</b> as commercial agent, paying retail minus
     * a 20–35% commission monthly.
     *
     * <p>An earlier design assumed a single agency model — "you collect as normal, the
     * channel commissions separately". That would have told venue operators to expect
     * cash at checkout that never arrives, which is the most expensive kind of wrong.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_responsibility", nullable = false, length = 28)
    @Builder.Default
    private PaymentResponsibility paymentResponsibility = PaymentResponsibility.CHANNEL_COLLECTS;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_model", nullable = false, length = 28)
    @Builder.Default
    private SettlementModel settlementModel = SettlementModel.COMMISSION_SETTLEMENT;

    /**
     * Hold back N concurrent slots from this destination — protection against selling
     * the last unit to a channel whose confirmation may lag.
     */
    @Column(name = "safety_inventory", nullable = false)
    @Builder.Default
    private int safetyInventory = 0;

    /** Stop NEW sales while honouring reservations already taken. Not the same as pausing. */
    @Column(name = "stop_sell", nullable = false)
    @Builder.Default
    private boolean stopSell = false;

    @Column(name = "stop_sell_reason", length = 300)
    private String stopSellReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    public enum PaymentResponsibility {
        VENUE_COLLECTS,
        SK_BINGE_COLLECTS,
        /** The destination is merchant of record — Viator and GetYourGuide both are. */
        CHANNEL_COLLECTS,
        PAY_AT_VENUE,
        VIRTUAL_CARD,
        MIXED_OR_PARTIAL
    }

    public enum SettlementModel {
        /** Destination remits retail minus commission. */
        COMMISSION_SETTLEMENT,
        /** Destination remits a pre-agreed net rate; its markup is invisible to us. */
        NET_RATE_SETTLEMENT,
        /** No third party in the money path. */
        DIRECT_SETTLEMENT
    }
}
