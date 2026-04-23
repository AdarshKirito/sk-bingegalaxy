package com.skbingegalaxy.auth.service;

import com.skbingegalaxy.auth.entity.RevokedToken;
import com.skbingegalaxy.auth.repository.RevokedTokenRepository;
import com.skbingegalaxy.auth.security.JwtProvider;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock private RevokedTokenRepository revokedTokenRepository;
    @Mock private JwtProvider jwtProvider;

    @InjectMocks private TokenRevocationService service;

    private String validToken;

    @BeforeEach
    void setUp() {
        validToken = "dummy.jwt.token";
    }

    @Test
    void revoke_newValidToken_persistsRow() {
        when(jwtProvider.getJtiFromToken(validToken)).thenReturn("jti-1");
        when(jwtProvider.getExpiryFromToken(validToken))
            .thenReturn(LocalDateTime.now().plusMinutes(15));
        when(jwtProvider.getUserIdFromToken(validToken)).thenReturn(42L);
        when(jwtProvider.getTokenType(validToken)).thenReturn("access");
        when(revokedTokenRepository.existsByJti("jti-1")).thenReturn(false);

        service.revoke(validToken);

        ArgumentCaptor<RevokedToken> captor = ArgumentCaptor.forClass(RevokedToken.class);
        verify(revokedTokenRepository).save(captor.capture());
        RevokedToken saved = captor.getValue();
        assertThat(saved.getJti()).isEqualTo("jti-1");
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getTokenType()).isEqualTo("access");
    }

    @Test
    void revoke_alreadyRevoked_isIdempotent() {
        when(jwtProvider.getJtiFromToken(validToken)).thenReturn("jti-1");
        when(revokedTokenRepository.existsByJti("jti-1")).thenReturn(true);

        service.revoke(validToken);

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revoke_expiredToken_isSkipped() {
        // Token already past its expiry — signature check will reject it anyway.
        when(jwtProvider.getJtiFromToken(validToken)).thenReturn("jti-1");
        when(revokedTokenRepository.existsByJti("jti-1")).thenReturn(false);
        when(jwtProvider.getExpiryFromToken(validToken))
            .thenReturn(LocalDateTime.now().minusMinutes(5));

        service.revoke(validToken);

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revoke_legacyTokenWithoutJti_isSkipped() {
        when(jwtProvider.getJtiFromToken(validToken)).thenReturn(null);

        service.revoke(validToken);

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revoke_unparseableToken_isSwallowed() {
        when(jwtProvider.getJtiFromToken("garbage")).thenThrow(new JwtException("bad"));

        // Must not propagate — logout should always succeed client-side.
        service.revoke("garbage");

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void revoke_nullOrBlank_isNoOp() {
        service.revoke(null);
        service.revoke("");
        service.revoke("   ");

        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void isRevoked_delegatesToRepository() {
        when(revokedTokenRepository.existsByJti("jti-x")).thenReturn(true);

        assertThat(service.isRevoked("jti-x")).isTrue();
        assertThat(service.isRevoked(null)).isFalse();
        assertThat(service.isRevoked("")).isFalse();
    }

    @Test
    void purgeExpired_delegatesToRepository() {
        when(revokedTokenRepository.deleteAllByExpiresAtBefore(any())).thenReturn(3);

        service.purgeExpired();

        verify(revokedTokenRepository).deleteAllByExpiresAtBefore(any());
    }
}
