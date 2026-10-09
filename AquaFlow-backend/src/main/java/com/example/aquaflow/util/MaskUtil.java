package com.example.aquaflow.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 日志脱敏 —— <b>全仓唯一实现</b>。
 *
 * <p><b>为什么要有这个类</b>：本仓原先有**两份**同口径的 openid 脱敏（{@code AuthTokenService#maskOpenid}
 * 与 {@code WeChatLoginService#maskOpenid}），靠注释互相提醒"改一处要同步另一处"。
 * 而 2026-09-30 的 F-35 正是这么来的 —— {@code WeChatLoginService} 只脱敏了 {@code session_key}，
 * {@code openid} 明文落日志，与 [AQ-047] 的口径分叉。**靠注释同步的口径迟早会分叉**，
 * 所以把实现收到一处，调用方只剩"调用"这一件事。</p>
 *
 * <p>⚠️ 本类只负责脱敏或省略，不负责"该不该打码"的判据：凡是把微信响应或 openid 写进日志的地方，
 * 都必须过这里；新增这类日志时先问一句"这条会不会带出用户标识或密钥"。</p>
 */
public final class MaskUtil {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OMITTED_RESPONSE = "[微信响应内容已省略：无法安全解析]";

    private MaskUtil() {}

    /**
     * openid 脱敏（[AQ-047]）：保留前 4 位 + {@code ****}；长度 ≤ 4 一律 {@code ***}。
     *
     * @param openid 原始 openid；{@code null} 回字面量 {@code "null"}（与日志里其它 null 的显示一致）
     */
    public static String maskOpenid(String openid) {
        if (openid == null) {
            return "null";
        }
        if (openid.length() <= 4) {
            return "***";
        }
        return openid.substring(0, 4) + "****";
    }

    /**
     * 微信 {@code code2Session} 响应的脱敏：同时打码 {@code session_key} 与 {@code openid}。
     *
     * <p>所有打印该响应的地方都要过这一层 —— <b>包括异常分支</b>（解析失败的响应里
     * {@code session_key} 是原样出现的，而那一支恰恰只在异常时触发，最容易被漏掉）。</p>
     *
     * <p>按 JSON 对象解析，兼容空白布局和转义字段名；openid 复用 {@link #maskOpenid}。
     * 非对象或无法解析的响应只输出固定省略提示，不尝试把原串放进日志。</p>
     */
    public static String maskCode2SessionResponse(String response) {
        if (response == null) {
            return "null";
        }
        try {
            if (!(JSON.readTree(response) instanceof ObjectNode object)) {
                return OMITTED_RESPONSE;
            }
            var openid = object.get("openid");
            if (openid != null) {
                object.put("openid", openid.isTextual() ? maskOpenid(openid.asText()) : "***");
            }
            if (object.has("session_key")) {
                object.put("session_key", "***");
            }
            return JSON.writeValueAsString(object);
        } catch (Exception ignored) {
            return OMITTED_RESPONSE;
        }
    }
}
