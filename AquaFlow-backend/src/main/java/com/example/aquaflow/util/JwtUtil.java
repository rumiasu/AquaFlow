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
import java.util.Map;

/**
 * JWT 工具类：生成和校验 access_token / refresh_token
 */
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

    /**
     * 生成 access_token（短时效，携带业务 claims）
     */
    public String generateAccessToken(Integer userId, String userType, String role,
                                       Integer stationId, Integer factoryId) {
        return Jwts.builder()
                .subject(userType + ":" + userId)
                .claims(Map.of(
                        "userId", userId,
                        "userType", userType,
                        "role", role,
                        "stationId", stationId != null ? stationId : 0,
                        "factoryId", factoryId != null ? factoryId : 0,
                        "tokenType", "access"
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成 refresh_token（长效，仅含用户标识）
     */
    public String generateRefreshToken(Integer userId, String userType) {
        return Jwts.builder()
                .subject(userType + ":" + userId)
                .claims(Map.of(
                        "userId", userId,
                        "userType", userType,
                        "tokenType", "refresh"
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析并校验 token，返回 claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 token 是否有效（未过期 + 签名正确）
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 从 token 中提取 userId
     */
    public Integer getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Integer.class);
    }

    /**
     * 从 token 中提取 userType (staff / customer)
     */
    public String getUserType(String token) {
        Claims claims = parseToken(token);
        return claims.get("userType", String.class);
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }
}
