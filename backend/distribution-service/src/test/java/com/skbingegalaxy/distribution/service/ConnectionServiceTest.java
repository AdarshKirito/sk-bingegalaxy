package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.common.exception.BusinessException;
import com.skbingegalaxy.common.exception.ResourceNotFoundException;
import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.dto.ConnectionDto;
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
