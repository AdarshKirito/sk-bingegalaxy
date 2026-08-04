package com.skbingegalaxy.distribution.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * One declared capability of one provider.
 *
 * <p><b>Rows, not a bitmask, and absence means false.</b> That is the whole safety
 * property: the console must never offer an action a provider cannot perform, so an
 * undeclared capability has to disable a control rather than enable one. Google is the
 * clearest case — {@code deliversReservations}, {@code supportsModification} and
 * {@code supportsCancellation} are all false, because Things to Do is a product feed
 * plus a deep link and no reservation ever flows back.
 *
 * <p>{@link #notes} carries the <em>evidence</em> for the value so a reviewer can audit
 * a claim instead of trusting it. Several capabilities are genuinely UNVERIFIED against
 * official documentation — counter-offer support, for instance — and those stay
 * disabled until somebody confirms them with the provider.
 */
@Entity
@Table(name = "provider_capabilities")
@IdClass(ProviderCapability.Pk.class)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProviderCapability {

    @Id
    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode;

    @Id
    @Column(name = "capability_key", nullable = false, length = 60)
    private String capabilityKey;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Numeric payload for capabilities that carry a limit (max options, feed staleness days). */
    @Column(name = "int_value")
    private Integer intValue;

    /** Why this value is what it is — an official doc reference, or UNVERIFIED. */
    @Column(length = 500)
    private String notes;

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    public static class Pk implements Serializable {
        private String providerCode;
        private String capabilityKey;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(providerCode, pk.providerCode)
                && Objects.equals(capabilityKey, pk.capabilityKey);
        }

        @Override public int hashCode() {
            return Objects.hash(providerCode, capabilityKey);
        }
    }
}
