package com.skbingegalaxy.distribution.service;

import com.skbingegalaxy.distribution.credential.CredentialStore;
import com.skbingegalaxy.distribution.entity.Connection;
import com.skbingegalaxy.distribution.repository.ConnectionRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Credential expiry sweep")
class CredentialWatchServiceTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private CredentialStore credentialStore;
    @InjectMocks private CredentialWatchService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiryWarningDays", 30);
        lenient().when(connectionRepository
            .findByCredentialExpiresAtBeforeAndStatusNot(any(), any())).thenReturn(List.of());
        lenient().when(connectionRepository.findByStatus(any())).thenReturn(List.of());
    }

    private static Connection active(String ref) {
        return Connection.builder().id(5L).bingeId(1L).providerCode("VIATOR")
            .status(Connection.ConnectionStatus.ACTIVE).credentialRef(ref).build();
    }

    @Test
    @DisplayName("a clean estate reports nothing")
    void quietWhenHealthy() {
        assertThat(service.sweep().isEmpty()).isTrue();
        verify(connectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an ACTIVE connection whose secret no longer resolves becomes DEGRADED")
    void unresolvableCredentialDegradesTheConnection() {
        Connection c = active("viator/binge-1");
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(c));
        when(credentialStore.resolve("viator/binge-1")).thenReturn(Optional.empty());
        when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Reachable in principle, cannot authenticate in practice — that IS degraded.
        // Marking it now means the console and the list agree before anyone is surprised
        // by bookings that simply stopped.
        assertThat(service.sweep().alreadyUnusable()).isEqualTo(1);
        assertThat(c.getStatus()).isEqualTo(Connection.ConnectionStatus.DEGRADED);
    }

    @Test
    @DisplayName("a resolvable credential leaves the connection ACTIVE")
    void healthyCredentialIsLeftAlone() {
        Connection c = active("viator/binge-1");
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(c));
        when(credentialStore.resolve("viator/binge-1")).thenReturn(Optional.of("s3cr3t"));

        assertThat(service.sweep().alreadyUnusable()).isZero();
        assertThat(c.getStatus()).isEqualTo(Connection.ConnectionStatus.ACTIVE);
        verify(connectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a connection with no credential reference is not degraded")
    void noCredentialIsNotAFault() {
        // A PLATFORM_MANAGED provider (the simulator) legitimately has none. Degrading
        // it would report a permanent fault on a connection that is working.
        when(connectionRepository.findByStatus(Connection.ConnectionStatus.ACTIVE))
            .thenReturn(List.of(active(null)));

        assertThat(service.sweep().alreadyUnusable()).isZero();
        verifyNoInteractions(credentialStore);
    }

    @Test
    @DisplayName("expiring credentials are counted, and REVOKED ones excluded")
    void expiringAreCountedExcludingRevoked() {
        when(connectionRepository.findByCredentialExpiresAtBeforeAndStatusNot(
                any(), eq(Connection.ConnectionStatus.REVOKED)))
            .thenReturn(List.of(Connection.builder().id(5L).bingeId(1L).providerCode("GOOGLE_TTD")
                .status(Connection.ConnectionStatus.ACTIVE)
                .credentialExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(5)).build()));

        // Revoked connections are excluded at the query, not filtered afterwards: warning
        // about a credential for a connection nobody uses is noise.
        assertThat(service.sweep().expiringSoon()).isEqualTo(1);
    }
}
