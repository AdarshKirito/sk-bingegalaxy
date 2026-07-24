package com.skbingegalaxy.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A venue's gateway account for receiving money — today a Stripe Connect
 * connected account (V16).
 *
 * <p>Direct-charge model: charges are created ON this account, in
 * {@link #country}, which is what makes the venue's local payment rails (UPI for
 * an Indian venue) available to customers anywhere. The platform takes its cut as
 * an application fee rather than by holding the funds.
 *
 * <p>{@link #chargesEnabled} is the flag that gates checkout. An account created
 * but still mid-KYC exists and looks real, yet cannot take money — routing a
 * customer to it would fail at the final step, so the resolver must check this
 * rather than merely "does a row exist".
 */
@Entity
@Table(name = "payment_connected_accounts", indexes = {
    @Index(name = "idx_connected_account_binge", columnList = "bingeId", unique = true),
    @Index(name = "idx_connected_account_acct", columnList = "accountId", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentConnectedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The venue this account belongs to. One account per venue (unique). */
    @Column(nullable = false, unique = true)
    private Long bingeId;

    /** Gateway that owns the account; "stripe" today. */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String provider = "stripe";

    /** Stripe's {@code acct_…} identifier. */
    @Column(nullable = false, unique = true, length = 128)
    private String accountId;

    /**
     * ISO-3166 alpha-2 of the account, fixed by Stripe at creation. Immutable in
     * practice: changing a venue's country requires re-onboarding a new account,
     * because settlement currency, available rails and KYC all derive from it.
     */
    @Column(nullable = false, length = 2)
    private String country;

    /** Stripe will accept charges on this account (KYC sufficient). */
    @Column(nullable = false)
    @Builder.Default
    private boolean chargesEnabled = false;

    /** Stripe will pay out to the venue's bank. */
    @Column(nullable = false)
    @Builder.Default
    private boolean payoutsEnabled = false;

    /** The owner finished the onboarding form (may still be under review). */
    @Column(nullable = false)
    @Builder.Default
    private boolean detailsSubmitted = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** Ready to take real money. */
    public boolean isChargeable() {
        return chargesEnabled && accountId != null && !accountId.isBlank();
    }
}
