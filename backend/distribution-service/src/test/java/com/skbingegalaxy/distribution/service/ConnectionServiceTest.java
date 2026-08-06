package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.ConnectionDto;
import com.skbingegalaxy.distribution.dto.EnableDestinationRequest;
import com.skbingegalaxy.distribution.entity.ConnectionDestination;
import com.skbingegalaxy.distribution.entity.Destination;
import com.skbingegalaxy.distribution.dto.CreateConnectionRequest;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.entity.Provider;
import com.skbingegalaxy.distribution.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Distribution connections (slice 3)")
class ConnectionServiceTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionDestinationRepository connectionDestinationRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private ProviderCapabilityRepository capabilityRepository;
    @Mock private DestinationRepository destinationRepository;
    @Mock private CredentialStore credentialStore;

    @InjectMocks private ConnectionService service;

    private static Provider provider(String code, Provider.AuthMethod auth, boolean active) {
        return Provider.builder()
            .code(code).displayName(code).active(active)
            .providerKind(Provider.ProviderKind.BOTH)
            .authMethod(auth)
            .certificationState(Provider.CertificationState.NONE)
            .build();
    }

    private static CreateConnectionRequest request(String code, String ref) {
        CreateConnectionRequest r = new CreateConnectionRequest();
        r.setProviderCode(code);
        r.setCredentialRef(ref);
        return r;
    }

    @Nested
    @DisplayName("Credentials never reach the database")
    class Credentials {

        @Test
        @DisplayName("a provider needing a credential is refused when none is provisioned")
        void refusesWhenSecretMissing() {
            when(providerRepository.findById("VIATOR"))
                .thenReturn(Optional.of(provider("VIATOR", Provider.AuthMethod.API_KEY, true)));
            when(connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(credentialStore.resolve("viator/binge-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(1L, 9L, request("VIATOR", "viator/binge-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No credential is provisioned");

            // Verified BEFORE the row exists: a connection that looks configured but
            // cannot authenticate fails later, far from its cause.
            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("only a masked hint is stored — never the reference's secret")
        void storesMaskedHintOnly() {
            when(providerRepository.findById("VIATOR"))
                .thenReturn(Optional.of(provider("VIATOR", Provider.AuthMethod.API_KEY, true)));
            when(connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(credentialStore.resolve("viator/binge-4821")).thenReturn(Optional.of("s3cr3t"));
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ConnectionDto dto = service.create(1L, 9L, request("VIATOR", "viator/binge-4821"));

            assertThat(dto.getCredentialHint()).isEqualTo("••••4821");
            // The DTO has no field capable of carrying a secret, and the reference is
            // withheld too — it names the env var holding the secret.
            assertThat(ConnectionDto.class.getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("secret")
                             || f.getName().equals("credentialRef"));
        }

        @Test
        @DisplayName("a platform-managed provider takes no credential, and says so")
        void platformManagedRejectsCredential() {
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(any(), any(), any()))
                .thenReturn(Optional.empty());

            // Refused rather than ignored: dropping it silently would leave the operator
            // believing a credential is in play when none is.
            assertThatThrownBy(() -> service.create(1L, 9L, request("SIMULATOR", "some/ref")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("takes no credential");
        }

        @Test
        @DisplayName("a platform-managed provider connects with no credential at all")
        void platformManagedConnectsCleanly() {
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(capabilityRepository.findByProviderCode("SIMULATOR")).thenReturn(List.of());

            ConnectionDto dto = service.create(1L, 9L, request("SIMULATOR", null));

            assertThat(dto.getCredentialHint()).isNull();
            assertThat(dto.isCredentialConfigured()).isFalse();
            verifyNoInteractions(credentialStore);
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a new connection starts PENDING, never ACTIVE")
        void startsPending() {
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionRepository.findByBingeIdAndProviderCodeAndEnvironment(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(capabilityRepository.findByProviderCode(any())).thenReturn(List.of());

            ConnectionDto dto = service.create(1L, 9L, request("SIMULATOR", null));

            // Certification, a pilot or a signed agreement stands between created and live.
            assertThat(dto.getStatus()).isEqualTo(Connection.ConnectionStatus.PENDING);
            assertThat(dto.getEnvironment()).isEqualTo(Connection.Environment.SANDBOX);
        }

        @Test
        @DisplayName("an INACTIVE provider cannot be connected to")
        void inactiveProviderRefused() {
            when(providerRepository.findById("VIATOR"))
                .thenReturn(Optional.of(provider("VIATOR", Provider.AuthMethod.API_KEY, false)));

            // Seeding every real provider inactive is only a control if create checks it.
            assertThatThrownBy(() -> service.create(1L, 9L, request("VIATOR", "r")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not yet available");
        }

        /**
         * The transition that did not exist.
         *
         * <p>create produced PENDING and resume returned to PENDING, but nothing could
         * reach ACTIVE — and ACTIVE is what ResellerAuthenticator requires to
         * authenticate a reseller and what a listing requires to publish. A venue could
         * complete every step the console offered and still have a channel that could
         * not sell, with no error anywhere to explain it.
         */
        @Test
        @DisplayName("activate verifies and goes live when everything is in place")
        void activateGoesLive() {
            Connection pending = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.PENDING)
                // The inbound half. A PLATFORM_MANAGED provider carries no credentialRef,
                // so this key is the ONLY thing a reseller can authenticate with.
                .resellerKeyHash("a".repeat(64))
                .build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(pending));
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of(
                ConnectionDestination.builder().id(3L).connectionId(5L)
                    .destinationCode("SIMULATOR").enabled(true).build()));
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(capabilityRepository.findByProviderCode(any())).thenReturn(List.of());
            when(destinationRepository.findAll()).thenReturn(List.of());

            ConnectionDto dto = service.activate(1L, 5L);

            assertThat(dto.getStatus()).isEqualTo(Connection.ConnectionStatus.ACTIVE);
            assertThat(pending.getLastVerifiedAt())
                .as("activation is a verification, and the console shows when it last passed")
                .isNotNull();
        }

        @Test
        @DisplayName("activate refuses a connection no reseller could authenticate against")
        void activateNeedsAResellerKey() {
            Connection pending = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.PENDING).build();   // no key issued
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(pending));
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of(
                ConnectionDestination.builder().id(3L).connectionId(5L)
                    .destinationCode("SIMULATOR").enabled(true).build()));

            // Live with no key is the failure this whole method exists to prevent: the
            // console calls it live and every reseller gets a 401 that looks exactly
            // like a wrong key.
            assertThatThrownBy(() -> service.activate(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reseller key");
            assertThat(pending.getStatus()).isEqualTo(Connection.ConnectionStatus.PENDING);
        }

        @Test
        @DisplayName("issuing a key stores only its digest, and rotation invalidates the old one")
        void issuingAKeyStoresOnlyTheDigest() {
            Connection c = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.PENDING).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(c));
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ConnectionService.IssuedResellerKey first = service.issueResellerKey(1L, 5L);

            assertThat(first.key()).startsWith("skbg_octo_");
            assertThat(first.replacedPrevious()).isFalse();
            // The plaintext must never be what is persisted — that is the entire reason
            // this is a hash and not a reference to a live secret.
            assertThat(c.getResellerKeyHash())
                .isNotEqualTo(first.key())
                .isEqualTo(com.skbingegalaxy.distribution.octo.ResellerKeys.sha256Hex(first.key()));

            String firstHash = c.getResellerKeyHash();
            ConnectionService.IssuedResellerKey second = service.issueResellerKey(1L, 5L);

            assertThat(second.replacedPrevious()).isTrue();
            // One live key per connection: an overlapping key would stay valid after the
            // operator believes they have replaced it, which defeats rotation.
            assertThat(c.getResellerKeyHash()).isNotEqualTo(firstHash);
        }

        @Test
        @DisplayName("a revoked connection cannot be given a key")
        void revokedConnectionGetsNoKey() {
            Connection revoked = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.REVOKED).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.issueResellerKey(1L, 5L))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("activate refuses a connection that reaches nowhere")
        void activateNeedsADestination() {
            Connection pending = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.PENDING).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(pending));
            when(providerRepository.findById("SIMULATOR")).thenReturn(
                Optional.of(provider("SIMULATOR", Provider.AuthMethod.PLATFORM_MANAGED, true)));
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of());

            // Live and reaching nowhere is worse than not live: the console would show a
            // healthy channel that cannot sell a single thing.
            assertThatThrownBy(() -> service.activate(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("destination");
            assertThat(pending.getStatus()).isEqualTo(Connection.ConnectionStatus.PENDING);
        }

        @Test
        @DisplayName("activate refuses when the credential no longer resolves")
        void activateNeedsALiveCredential() {
            Connection pending = Connection.builder().id(5L).bingeId(1L).providerCode("VIATOR")
                .status(Connection.ConnectionStatus.PENDING).credentialRef("viator-key").build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(pending));
            when(providerRepository.findById("VIATOR")).thenReturn(
                Optional.of(provider("VIATOR", Provider.AuthMethod.API_KEY, true)));
            when(credentialStore.resolve("viator-key")).thenReturn(Optional.empty());

            // A secret can be rotated away between creation and activation. "Configured"
            // has to mean the secret is actually there, or the channel fails later and
            // far from the cause.
            assertThatThrownBy(() -> service.activate(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer provisioned");
            assertThat(pending.getStatus()).isEqualTo(Connection.ConnectionStatus.PENDING);
        }

        @Test
        @DisplayName("activate refuses a provider a super-admin has since withdrawn")
        void activateRespectsProviderWithdrawal() {
            Connection pending = Connection.builder().id(5L).bingeId(1L).providerCode("VIATOR")
                .status(Connection.ConnectionStatus.PENDING).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(pending));
            when(providerRepository.findById("VIATOR")).thenReturn(
                Optional.of(provider("VIATOR", Provider.AuthMethod.API_KEY, false)));

            assertThatThrownBy(() -> service.activate(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer available");
        }

        @Test
        @DisplayName("activating a REVOKED connection is refused, not silently resurrected")
        void revokedCannotBeActivated() {
            Connection revoked = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.REVOKED).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(revoked));

            // Revocation is terminal; settlements and reservations still reference it.
            assertThatThrownBy(() -> service.activate(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("activating an already-active connection is a no-op, not an error")
        void activateIsIdempotent() {
            Connection active = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.ACTIVE).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(active));
            when(providerRepository.findById("SIMULATOR")).thenReturn(Optional.empty());
            when(capabilityRepository.findByProviderCode(any())).thenReturn(List.of());
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of());
            when(destinationRepository.findAll()).thenReturn(List.of());

            // A double-clicked button is not an incident.
            assertThat(service.activate(1L, 5L).getStatus())
                .isEqualTo(Connection.ConnectionStatus.ACTIVE);
            verify(connectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("resume returns to PENDING, not straight to ACTIVE")
        void resumeGoesToPending() {
            Connection paused = Connection.builder().id(5L).bingeId(1L).providerCode("SIMULATOR")
                .status(Connection.ConnectionStatus.PAUSED).build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(paused));
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(providerRepository.findById("SIMULATOR")).thenReturn(Optional.empty());
            when(capabilityRepository.findByProviderCode(any())).thenReturn(List.of());
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of());
            when(destinationRepository.findAll()).thenReturn(List.of());

            // Resuming is the venue's decision; being live again is the provider's.
            assertThat(service.resume(1L, 5L).getStatus())
                .isEqualTo(Connection.ConnectionStatus.PENDING);
        }

        @Test
        @DisplayName("revoke clears the credential pointer and is terminal")
        void revokeClearsPointer() {
            Connection live = Connection.builder().id(5L).bingeId(1L).providerCode("VIATOR")
                .status(Connection.ConnectionStatus.ACTIVE)
                .credentialRef("viator/binge-1").credentialHint("••••ge-1").build();
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(live));
            when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(providerRepository.findById("VIATOR")).thenReturn(Optional.empty());
            when(capabilityRepository.findByProviderCode(any())).thenReturn(List.of());
            when(connectionDestinationRepository.findByConnectionId(5L)).thenReturn(List.of());
            when(destinationRepository.findAll()).thenReturn(List.of());

            ConnectionDto dto = service.revoke(1L, 5L, "compromised");

            assertThat(dto.getStatus()).isEqualTo(Connection.ConnectionStatus.REVOKED);
            assertThat(live.getCredentialRef()).isNull();
            assertThat(dto.getCredentialHint()).isNull();
        }
    }

    @Nested
    @DisplayName("Pointing a connection at a destination")
    class Destinations {

        private void givenConnection(Connection.ConnectionStatus status) {
            when(connectionRepository.findByIdAndBingeId(5L, 1L)).thenReturn(Optional.of(
                Connection.builder().id(5L).bingeId(1L).providerCode("VIATOR")
                    .status(status).build()));
        }

        private EnableDestinationRequest request(String code) {
            EnableDestinationRequest r = new EnableDestinationRequest();
            r.setDestinationCode(code);
            return r;
        }

        private Destination destination(String code, String operatedBy, boolean active) {
            return Destination.builder().code(code).displayName(code)
                .operatedByProviderCode(operatedBy).active(active)
                .deliversReservations(true).build();
        }

        @Test
        @DisplayName("an INACTIVE destination is refused")
        void inactiveDestinationRefused() {
            givenConnection(Connection.ConnectionStatus.ACTIVE);
            when(destinationRepository.findById("VIATOR"))
                .thenReturn(Optional.of(destination("VIATOR", "VIATOR", false)));

            // Every real destination ships inactive. Without this a venue could publish
            // to a marketplace the platform has not turned on.
            assertThatThrownBy(() -> service.enableDestination(1L, 5L, request("VIATOR")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not yet available");
            verify(connectionDestinationRepository, never()).save(any());
        }

        @Test
        @DisplayName("a destination operated by a DIFFERENT provider is refused")
        void wrongProviderRefused() {
            givenConnection(Connection.ConnectionStatus.ACTIVE);
            when(destinationRepository.findById("GETYOURGUIDE"))
                .thenReturn(Optional.of(destination("GETYOURGUIDE", "GETYOURGUIDE", true)));

            // The credential authenticates against VIATOR; reaching GetYourGuide with it
            // would be using a key for the wrong system entirely.
            assertThatThrownBy(() -> service.enableDestination(1L, 5L, request("GETYOURGUIDE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not reachable through");
        }

        @Test
        @DisplayName("a revoked connection cannot reach new destinations")
        void revokedRefused() {
            givenConnection(Connection.ConnectionStatus.REVOKED);

            assertThatThrownBy(() -> service.enableDestination(1L, 5L, request("VIATOR")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("a valid destination is attached, DISABLED by default")
        void attachedDisabledByDefault() {
            givenConnection(Connection.ConnectionStatus.ACTIVE);
            when(destinationRepository.findById("VIATOR"))
                .thenReturn(Optional.of(destination("VIATOR", "VIATOR", true)));
            when(connectionDestinationRepository
                .findByConnectionIdAndDestinationCode(5L, "VIATOR")).thenReturn(Optional.empty());
            when(connectionDestinationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var dto = service.enableDestination(1L, 5L, request("VIATOR"));

            // Configuring terms and going on sale are two decisions. Collapsing them
            // would publish the moment a venue saved terms it was still negotiating.
            assertThat(dto.isEnabled()).isFalse();
            // Merchant-of-record default: telling a venue it collects at checkout would
            // misstate its cash flow.
            assertThat(dto.getPaymentResponsibility())
                .isEqualTo(ConnectionDestination.PaymentResponsibility.CHANNEL_COLLECTS);
        }

        @Test
        @DisplayName("the same destination cannot be attached twice")
        void duplicateRefused() {
            givenConnection(Connection.ConnectionStatus.ACTIVE);
            when(destinationRepository.findById("VIATOR"))
                .thenReturn(Optional.of(destination("VIATOR", "VIATOR", true)));
            when(connectionDestinationRepository
                .findByConnectionIdAndDestinationCode(5L, "VIATOR"))
                .thenReturn(Optional.of(ConnectionDestination.builder().id(9L).build()));

            assertThatThrownBy(() -> service.enableDestination(1L, 5L, request("VIATOR")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("already reaches");
        }
    }

    @Nested
    @DisplayName("Tenancy")
    class Tenancy {

        @Test
        @DisplayName("another venue's connection is not found, not forbidden-but-visible")
        void cannotTouchAnotherVenuesConnection() {
            when(connectionRepository.findByIdAndBingeId(5L, 999L)).thenReturn(Optional.empty());

            // Scoped by bingeId in the QUERY. A findById followed by a check would still
            // let one venue confirm another's connection exists.
            assertThatThrownBy(() -> service.pause(999L, 5L, "nope"))
                .isInstanceOf(ResourceNotFoundException.class);
            verify(connectionRepository, never()).save(any());
        }
    }
}
