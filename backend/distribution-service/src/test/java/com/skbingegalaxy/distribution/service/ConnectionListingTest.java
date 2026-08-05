package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.ConnectionDto;
import com.skbingegalaxy.distribution.dto.ProviderDto;
import com.skbingegalaxy.distribution.entity.*;
import com.skbingegalaxy.distribution.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The read side, which is what the console renders — and therefore where a wrong answer
 * becomes a wrong operator decision rather than a stack trace.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Connection listing")
class ConnectionListingTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private ProviderCapabilityRepository capabilityRepository;
    @Mock private DestinationRepository destinationRepository;
    @Mock private CredentialStore credentialStore;

    @InjectMocks private ConnectionService service;

    @Test
    @DisplayName("no connections short-circuits without querying the catalogue")
    void emptyShortCircuits() {
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        assertThat(service.listForBinge(1L)).isEmpty();
    }

    @Test
    @DisplayName("only ENABLED capabilities reach the console")
    void onlyEnabledCapabilitiesSurface() {
        Provider p = Provider.builder().code("VIATOR").displayName("Viator").active(true)
            .providerKind(Provider.ProviderKind.BOTH)
            .authMethod(Provider.AuthMethod.API_KEY)
            .certificationState(Provider.CertificationState.PILOT_REQUIRED).build();
        when(providerRepository.findByActiveTrueOrderByDisplayNameAsc()).thenReturn(List.of(p));
        when(capabilityRepository.findByProviderCodeIn(any())).thenReturn(List.of(
            ProviderCapability.builder().providerCode("VIATOR")
                .capabilityKey("supportsCancellation").enabled(true).build(),
            ProviderCapability.builder().providerCode("VIATOR")
                .capabilityKey("supportsCounterOffer").enabled(false)
                .notes("UNVERIFIED - fail closed").build()));
        when(credentialStore.supportsWrite()).thenReturn(false);

        List<ProviderDto> providers = service.listConnectableProviders();

        // The disabled row still travels, carrying its evidence, so a reviewer can audit
        // the claim. It is the CONSOLE's job not to render a control for it.
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).getCapabilities()).hasSize(2);
        assertThat(providers.get(0).isRequiresCredential()).isTrue();
        assertThat(providers.get(0).isCredentialSubmissionSupported()).isFalse();
    }

    @Test
    @DisplayName("no active providers means nothing is connectable")
    void noActiveProvidersMeansNothingConnectable() {
        when(providerRepository.findByActiveTrueOrderByDisplayNameAsc()).thenReturn(List.of());
        // Every real provider is seeded inactive; this is that control observed.
        assertThat(service.listConnectableProviders()).isEmpty();
    }

    @Test
    @DisplayName("a feed-only destination is flagged so the inbox renders no rows for it")
    void feedOnlyDestinationFlagged() {
        Connection c = Connection.builder().id(7L).bingeId(1L).providerCode("GOOGLE_TTD")
            .status(Connection.ConnectionStatus.ACTIVE)
            .environment(Connection.Environment.PRODUCTION).build();
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(c));
        when(connectionDestinationRepository.findByConnectionIdIn(any())).thenReturn(List.of(
            ConnectionDestination.builder().id(3L).connectionId(7L)
                .destinationCode("GOOGLE_TTD").enabled(true)
                .paymentResponsibility(ConnectionDestination.PaymentResponsibility.CHANNEL_COLLECTS)
                .settlementModel(ConnectionDestination.SettlementModel.COMMISSION_SETTLEMENT)
                .build()));
        when(providerRepository.findAll()).thenReturn(List.of(
            Provider.builder().code("GOOGLE_TTD").displayName("Google Things to Do")
                .providerKind(Provider.ProviderKind.DESTINATION)
                .authMethod(Provider.AuthMethod.SFTP_FEED)
                .certificationState(Provider.CertificationState.PROVIDER_REVIEWED).build()));
        when(capabilityRepository.findByProviderCodeIn(any())).thenReturn(List.of());
        when(destinationRepository.findAll()).thenReturn(List.of(
            Destination.builder().code("GOOGLE_TTD").displayName("Google Things to Do")
                .operatedByProviderCode("GOOGLE_TTD")
                .deliversReservations(false).build()));

        ConnectionDto dto = service.listForBinge(1L).get(0);

        // Google never delivers a reservation back: it is a feed plus a deep link. An
        // earlier design showed a Google booking in the inbox; that object does not exist.
        assertThat(dto.getDestinations().get(0).isDeliversReservations()).isFalse();
        assertThat(dto.getProviderName()).isEqualTo("Google Things to Do");
    }

    @Test
    @DisplayName("credentialConfigured is resolved live, not inferred from the hint")
    void credentialConfiguredIsResolvedLive() {
        Connection c = Connection.builder().id(7L).bingeId(1L).providerCode("VIATOR")
            .status(Connection.ConnectionStatus.ACTIVE)
            .credentialRef("viator/binge-1").credentialHint("••••ge-1").build();
        when(connectionRepository.findByBingeIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(c));
        when(connectionDestinationRepository.findByConnectionIdIn(any())).thenReturn(List.of());
        when(providerRepository.findAll()).thenReturn(List.of());
        when(capabilityRepository.findByProviderCodeIn(any())).thenReturn(List.of());
        when(destinationRepository.findAll()).thenReturn(List.of());
        when(credentialStore.resolve("viator/binge-1")).thenReturn(Optional.empty());

        ConnectionDto dto = service.listForBinge(1L).get(0);

        // The hint survives a rotation that removed the secret. Trusting it would show a
        // connection as configured when it can no longer authenticate.
        assertThat(dto.getCredentialHint()).isEqualTo("••••ge-1");
        assertThat(dto.isCredentialConfigured()).isFalse();
    }

    @Test
    @DisplayName("masking keeps only the last four characters")
    void maskingKeepsTail() {
        assertThat(ConnectionService.mask("viator/binge-4821")).isEqualTo("••••4821");
        assertThat(ConnectionService.mask("ab")).isEqualTo("••••ab");
        assertThat(ConnectionService.mask(null)).isNull();
        assertThat(ConnectionService.mask("  ")).isNull();
    }
}
