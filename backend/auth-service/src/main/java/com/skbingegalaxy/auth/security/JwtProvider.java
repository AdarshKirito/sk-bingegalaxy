package com.skbingegalaxy.auth.security;

import com.skbingegalaxy.auth.entity.User;
import com.skbingegalaxy.common.enums.AuthorityScope;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    /**
     * Expected issuer ({@code iss}) claim. Stamped on every new token and verified when
     * present to prevent tokens signed by a different trust domain (or an attacker replaying
     * a token from another environment with a shared secret) from being accepted.
     */
    @Value("${app.jwt.issuer:skbingegalaxy-auth}")
    private String jwtIssuer;

    /**
     * Expected audience ({@code aud}) claim. Stamped on every new token and verified when
     * present to prevent a token issued for another relying party from being used here.
     */
    @Value("${app.jwt.audience:skbingegalaxy-web}")
    private String jwtAudience;

    @PostConstruct
    void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret must be set (env: JWT_SECRET). Cannot start auth-service without a JWT signing key.");
        }
        byte[] keyBytes = io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (256 bits) for HMAC-SHA256. Current length: " + keyBytes.length);
        }
    }

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    public String generateToken(User user) {
        return buildToken(user, jwtExpirationMs, "access", Collections.emptySet());
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshExpirationMs, "refresh", Collections.emptySet());
    }

    /**
     * Generate a JWT that additionally carries an Authority Handover delegation. When
     * {@code delegatedScopes} is non-empty, the token includes:
     * <ul>
     *   <li>{@code delegatedScopes} — comma-joined scope codes (e.g. "CURRENCIES,LOYALTY")</li>
     *   <li>{@code delegationExpiresAt} — epoch millis after which gateway must ignore the
     *       delegation even if the JWT is still otherwise valid (defence-in-depth in case
     *       a grant is revoked but token-revocation list propagation is delayed)</li>
     * </ul>
     * The native {@code role} claim is NOT modified. The gateway is responsible for
     * rewriting {@code X-User-Role} on a per-path basis when delegation grants the
     * relevant scope. This keeps the JWT truthful about the user's native role.
     */
    public String generateToken(User user, Set<AuthorityScope> delegatedScopes, long delegationExpiresAtMillis) {
        return buildToken(user, jwtExpirationMs, "access", delegatedScopes, delegationExpiresAtMillis);
    }

    public String generateRefreshToken(User user, Set<AuthorityScope> delegatedScopes, long delegationExpiresAtMillis) {
        return buildToken(user, refreshExpirationMs, "refresh", delegatedScopes, delegationExpiresAtMillis);
    }

    private String buildToken(User user, long expirationMs, String tokenType, Set<AuthorityScope> delegatedScopes) {
        return buildToken(user, expirationMs, tokenType, delegatedScopes, 0L);
    }

    private String buildToken(User user, long expirationMs, String tokenType,
                              Set<AuthorityScope> delegatedScopes, long delegationExpiresAtMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("firstName", user.getFirstName());
        claims.put("phone", user.getPhone());
        claims.put("phoneCountryCode", user.getPhoneCountryCode());
        claims.put("token_type", tokenType);

        if (delegatedScopes != null && !delegatedScopes.isEmpty()) {
            // Comma-joined string keeps JWT compact and avoids Jackson List<Enum> quirks
            // when parsed by gateway code that uses raw jjwt without the auth-service
            // class path.
            claims.put("delegatedScopes",
                delegatedScopes.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
            if (delegationExpiresAtMillis > 0) {
                claims.put("delegationExpiresAt", delegationExpiresAtMillis);
            }
        }

        return Jwts.builder()
            .claims(claims)
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(user.getId()))
            .issuer(jwtIssuer)
            .audience().add(jwtAudience).and()
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * Parse and validate a token's signature, expiration, and (when present) issuer/audience.
     * <p>
     * Issuer/audience checks are applied only if those claims are present in the token,
     * so tokens minted by a previous build (before iss/aud rollout) continue to validate
     * until they expire. Newly minted tokens always carry both claims.
     */
    public Claims parseToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String iss = claims.getIssuer();
        if (iss != null && !jwtIssuer.equals(iss)) {
            throw new JwtException("Unexpected token issuer");
        }
        java.util.Set<String> aud = claims.getAudience();
        if (aud != null && !aud.isEmpty() && !aud.contains(jwtAudience)) {
            throw new JwtException("Unexpected token audience");
        }
        return claims;
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates that the token is a genuine refresh token (not an access token).
     * Prevents access tokens from being used at the /refresh endpoint.
     */
    public boolean validateRefreshToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "refresh".equals(claims.get("token_type", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getJtiFromToken(String token) {
        return parseToken(token).getId();
    }

    public java.time.LocalDateTime getExpiryFromToken(String token) {
        Date exp = parseToken(token).getExpiration();
        return exp == null ? null
            : java.time.LocalDateTime.ofInstant(exp.toInstant(), java.time.ZoneId.systemDefault());
    }

    public String getTokenType(String token) {
        return parseToken(token).get("token_type", String.class);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
