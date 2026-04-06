package com.skbingegalaxy.auth.security;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private JwtProvider jwtProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider();
        // 256-bit base64-encoded secret for HS256
        String secret = Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-for-jwt-testing-only-256".getBytes());
        ReflectionTestUtils.setField(jwtProvider, "jwtSecret", secret);
        ReflectionTestUtils.setField(jwtProvider, "jwtExpirationMs", 3600000L); // 1 hour
        ReflectionTestUtils.setField(jwtProvider, "refreshExpirationMs", 86400000L); // 24 hours

        testUser = User.builder()
                .id(1L).firstName("John").lastName("Doe")
                .email("john@example.com").phone("9876543210")
                .password("encodedPassword").role(UserRole.CUSTOMER)
                .active(true).build();
    }

    @Test
    void generateToken_containsCorrectClaims() {
        String token = jwtProvider.generateToken(testUser);

        assertThat(token).isNotNull().isNotBlank();

        Claims claims = jwtProvider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("email", String.class)).isEqualTo("john@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("CUSTOMER");
        assertThat(claims.get("firstName", String.class)).isEqualTo("John");
    }

    @Test
    void generateRefreshToken_isValid() {
        String refreshToken = jwtProvider.generateRefreshToken(testUser);

        assertThat(refreshToken).isNotNull().isNotBlank();
        assertThat(jwtProvider.validateToken(refreshToken)).isTrue();
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = jwtProvider.generateToken(testUser);
        assertThat(jwtProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertThat(jwtProvider.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtProvider, "jwtExpirationMs", -1000L);
        String token = jwtProvider.generateToken(testUser);
        assertThat(jwtProvider.validateToken(token)).isFalse();
    }

    @Test
    void validateToken_nullToken_returnsFalse() {
        assertThat(jwtProvider.validateToken(null)).isFalse();
    }

    @Test
    void validateToken_emptyToken_returnsFalse() {
        assertThat(jwtProvider.validateToken("")).isFalse();
    }

    @Test
    void getUserIdFromToken_returnsCorrectId() {
        String token = jwtProvider.generateToken(testUser);
        Long userId = jwtProvider.getUserIdFromToken(token);
        assertThat(userId).isEqualTo(1L);
    }

    @Test
    void generateToken_differentUsers_differentTokens() {
        User user2 = User.builder()
                .id(2L).firstName("Jane").lastName("Doe")
                .email("jane@example.com").role(UserRole.ADMIN)
                .password("pass").active(true).build();

        String token1 = jwtProvider.generateToken(testUser);
        String token2 = jwtProvider.generateToken(user2);

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void generateToken_adminRole_hasAdminClaim() {
        testUser.setRole(UserRole.ADMIN);
        String token = jwtProvider.generateToken(testUser);
        Claims claims = jwtProvider.parseToken(token);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void parseToken_returnsClaims_withExpirationInFuture() {
        String token = jwtProvider.generateToken(testUser);
        Claims claims = jwtProvider.parseToken(token);
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }
}
