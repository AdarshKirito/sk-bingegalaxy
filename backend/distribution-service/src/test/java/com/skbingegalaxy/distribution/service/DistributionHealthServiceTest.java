package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.DistributionHealthDto;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Distribution health (slice 7)")
class DistributionHealthServiceTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ListingMappingRepository listingRepository;
    @Mock private ReservationInboxRepository inboxRepository;
    @Mock private CredentialStore credentialStore;

    @InjectMocks private DistributionHealthService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiryWarningDays", 30);
        lenient().when(listingRepository.findByBingeId(any())).thenReturn(List.of());
        lenient().when(inboxRepository.countByConnectionIdInAndStatus(any(), any())).thenReturn(0L);
    }

    private static Connection connection(Connection.ConnectionStatus status, String ref,
                                         LocalDateTime expiresAt) {
        return Connection.builder().id(1L).bingeId(1L).providerCode("VIATOR")
            .status(status).credentialRef(ref).credentialExpiresAt(expiresAt).build();
    }

    @Test
    @DisplayName("a venue with nothing configured has no alerts — silence means nothing to do")
    void quietWhenNothingConfigured() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        DistributionHealthDto health = service.forBinge(1L);

        // An empty alert list has to be trustworthy, or an operator learns to ignore it.
        assertThat(health.getAlerts()).isEmpty();
        assertThat(health.getConnectionsTotal()).isZero();
    }

    @Test
    @DisplayName("a credential that no longer resolves is CRITICAL — the channel is dead now")
    void missingCredentialIsCritical() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(
            List.of(connection(Connection.ConnectionStatus.ACTIVE, "viator/binge-1", null)));
        when(credentialStore.resolve("viator/binge-1")).thenReturn(Optional.empty());

        DistributionHealthDto health = service.forBinge(1L);

        assertThat(health.getCredentialsMissing()).isEqualTo(1);
        assertThat(health.getAlerts()).first()
            .satisfies(a -> {
                assertThat(a.getSeverity()).isEqualTo("CRITICAL");
                // An alert with no action is just anxiety.
                assertThat(a.getAction()).contains("Provision the secret");
            });
    }

    @Test
    @DisplayName("a REVOKED connection's cleared pointer is not reported as missing")
    void revokedIsNotMissingCredential() {
        // Clearing the pointer is PART of revoking, so counting it as a fault would put
        // a permanent false CRITICAL on every venue that ever revoked a connection.
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(
            List.of(connection(Connection.ConnectionStatus.REVOKED, null, null)));

        assertThat(service.forBinge(1L).getCredentialsMissing()).isZero();
    }

    @Test
    @DisplayName("an expiring credential is a WARNING, ranked BELOW an outage")
    void expiringRanksBelowMissing() {
        LocalDateTime soon = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
            connection(Connection.ConnectionStatus.ACTIVE, "gone", soon)));
        when(credentialStore.resolve("gone")).thenReturn(Optional.empty());

        List<DistributionHealthDto.Alert> alerts = service.forBinge(1L).getAlerts();

        // Missing means the channel is dead RIGHT NOW; expiring means it will be.
        // Ordering them the other way would bury the outage under the warning.
        assertThat(alerts.get(0).getSeverity()).isEqualTo("CRITICAL");
        assertThat(alerts).extracting(DistributionHealthDto.Alert::getSeverity)
            .containsExactly("CRITICAL", "WARNING");
    }

    @Test
    @DisplayName("failed inbound messages are CRITICAL — they may be lost reservations")
    void failedInboxIsCritical() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(
            List.of(connection(Connection.ConnectionStatus.ACTIVE, null, null)));
        when(inboxRepository.countByConnectionIdInAndStatus(
            any(), org.mockito.ArgumentMatchers.eq(ReservationInboxEntry.Status.FAILED)))
            .thenReturn(3L);

        DistributionHealthDto health = service.forBinge(1L);

        assertThat(health.getInboxFailed()).isEqualTo(3);
        assertThat(health.getAlerts()).anySatisfy(a -> {
            assertThat(a.getSeverity()).isEqualTo("CRITICAL");
            assertThat(a.getMessage()).contains("lost reservations");
        });
    }

    @Test
    @DisplayName("a paused connection is INFO, not a fault")
    void pausedIsInformational() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(
            List.of(connection(Connection.ConnectionStatus.PAUSED, null, null)));

        // Someone chose this. Reporting a deliberate pause as a problem trains operators
        // to dismiss the whole panel.
        assertThat(service.forBinge(1L).getAlerts())
            .singleElement()
            .satisfies(a -> assertThat(a.getSeverity()).isEqualTo("INFO"));
    }

    @Test
    @DisplayName("superseded messages are counted but raise no alert")
    void supersededIsCountedNotAlerted() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(
            List.of(connection(Connection.ConnectionStatus.ACTIVE, null, null)));
        when(inboxRepository.countByConnectionIdInAndStatus(
            any(), org.mockito.ArgumentMatchers.eq(ReservationInboxEntry.Status.SUPERSEDED)))
            .thenReturn(12L);

        DistributionHealthDto health = service.forBinge(1L);

        // Each SUPERSEDED row is the system working as designed; only a SPIKE means a
        // provider's delivery is degrading, which is a judgement for a human looking at
        // the number, not a rule that fires an alert.
        assertThat(health.getInboxSuperseded()).isEqualTo(12);
        assertThat(health.getAlerts()).isEmpty();
    }
}
