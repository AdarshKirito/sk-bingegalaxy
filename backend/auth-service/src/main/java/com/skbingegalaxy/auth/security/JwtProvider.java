package com.skbingegalaxy.auth.security;

import com.skbingegalaxy.auth.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
        return buildToken(user, jwtExpirationMs, "access");
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshExpirationMs, "refresh");
    }

    private String buildToken(User user, long expirationMs, String tokenType) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("firstName", user.getFirstName());
        claims.put("phone", user.getPhone());
        claims.put("token_type", tokenType);

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
