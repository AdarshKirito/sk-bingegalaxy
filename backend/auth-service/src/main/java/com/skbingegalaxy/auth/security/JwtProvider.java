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

@Component
public class JwtProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

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
        return buildToken(user, jwtExpirationMs);
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshExpirationMs);
    }

    private String buildToken(User user, long expirationMs) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("firstName", user.getFirstName());
        claims.put("phone", user.getPhone());

        return Jwts.builder()
            .claims(claims)
            .subject(String.valueOf(user.getId()))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getSigningKey())
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
