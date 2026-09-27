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
        return generateAccessToken(userId, userType, role, stationId, null);
    }

    /**
     * 签发 access token（可携带一个"待绑定 openid"）。
     *
     * <p><b>为什么需要 pendingOpenid</b>：员工首次进入配送端时还没有 staff 记录（userId 是负数占位），
     * 但后续 {@code POST /api/auth/select-role} 需要知道"这个微信是哪个 openid"才能建员工记录。
     * 旧实现是靠客户端把 openid 放在请求体里回传（{@code _pendingOpenid}）——
     * 那等于让调用方自报身份：任何人都能把别人的 openid 填进来，抢绑到该微信账号上
     * （配合 {@code uk_staff_openid} 唯一键，可顶掉真实主人后续的登录）。
     * 现在改为在 wx-login-staff 签发时就把它签进 JWT，select-role 只认 token 里的值，
     * 客户端传什么都不影响结果。</p>
     *
     * @param pendingOpenid 仅 UNSELECTED 会话需要；其他场景传 null，claim 不写入
     */
    public String generateAccessToken(Long userId, String userType, String role,
                                      Long stationId, String pendingOpenid) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("userType", userType);
        claims.put("role", role);
        claims.put("tokenType", "access");
        if ("staff".equals(userType) && stationId != null) {
            claims.put("stationId", stationId);
        }
        if (pendingOpenid != null && !pendingOpenid.isEmpty()) {
            claims.put("pendingOpenid", pendingOpenid);
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

    /**
     * 签一个 refresh token。
     *
     * <p>⚠️ <b>`jti` 不能去掉</b>（2026-09-26 真机实测事故）：本方法原先只有
     * {@code userId / userType / tokenType} 三个 claim，而 {@code iat} 与 {@code exp}
     * 都是<b>秒级</b> —— 同一个人在同一秒内签发两次，两个 token 会<b>逐字节相同</b>。
     * 后果不是"轮换不生效"这么轻：{@code user_token} 上只有<b>非唯一</b>索引
     * {@code idx_refresh_token}，于是同一秒的两次登录/刷新会落下<b>两行同值记录</b>，
     * 而 {@code UserTokenMapper.findByRefreshToken} 是单行查询 —— 下一次刷新直接抛
     * {@code TooManyResultsException}（HTTP 200 + code=500「系统错误」），
     * 客户端的 401 自动刷新失败 = 登录态卡死，只能重新登录。
     * 真实库上确实留下了这样的重复行（user_token id 113/114、129/130），
     * 并落了一条 SYSTEM 告警。</p>
     *
     * <p>因此这里显式加一个随机 {@code jti}：每次都不同 → 重复行不可能再产生，
     * 顺带让"轮换后旧 token 立刻失效"在同一秒内也成立。</p>
     */
    public String generateRefreshToken(Long userId, String userType) {
        // Set iat to 1 hour ago to avoid clock skew issues
        Date issuedAt = new Date(System.currentTimeMillis() - 3600000);
        return Jwts.builder()
                .subject(userType + ":" + userId)
                .id(java.util.UUID.randomUUID().toString())
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
