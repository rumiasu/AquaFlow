package com.example.aquaflow.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

public String generateAccessToken(Long userId, String userType, String role,
                                            Long stationId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("userType", userType);
        claims.put("role", role);
        claims.put("tokenType", "access");
        if ("staff".equals(userType) && stationId != null) {
            claims.put("stationId", stationId);
        }
        // Set iat to 1 hour ago to avoid clock skew issues
        long issuedAtMillis = System.currentTimeMillis() - 3600000;
        claims.put("iat", issuedAtMillis / 1000); // iat is in seconds
        return Jwts.builder()
                .subject(userType + ":" + userId)
                .claims(claims)
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(Long userId, String userType) {
        // Set iat to 1 hour ago to avoid clock skew issues
        Date issuedAt = new Date(System.currentTimeMillis() - 3600000);
        return Jwts.builder()
                .subject(userType + ":" + userId)
                .claims(Map.of(
                        "userId", userId,
                        "userType", userType,
                        "tokenType", "refresh"
                ))
                .issuedAt(issuedAt)
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .clockSkewSeconds(120)
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

    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Number.class).longValue();
    }

    public String getUserType(String token) {
        Claims claims = parseToken(token);
        return claims.get("userType", String.class);
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }
}
