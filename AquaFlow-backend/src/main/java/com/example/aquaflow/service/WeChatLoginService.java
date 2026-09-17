package com.example.aquaflow.service;

import com.example.aquaflow.constant.WeChatApp;
import com.example.aquaflow.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 微信登录：code2Session（用 wx.login 的 code 换 openid + session_key）。
 *
 * <p>[2026-09-15] 本项目有**两个小程序**：客户端 miniapp-user 与员工端 miniapp-delivery，
 * 它们的 appid 不同。code 只能用**签发它的那一端**的 appid+secret 去换，
 * 换错端微信只回一句 {@code invalid appid}，现象是"某一端登录永远失败"。
 * 因此本方法强制调用方传 {@link WeChatApp}，不要再用单 appid 的写法。</p>
 *
 * <p>配置键：{@code wechat.miniapp.appid/secret} = 客户端；
 * {@code wechat.miniapp.staff-appid/staff-secret} = 员工端（环境变量 WX_STAFF_APP_ID / WX_STAFF_APP_SECRET）。
 * 员工端缺失**不阻塞启动**（见 {@code RequiredConfigChecker}），只在使用到员工端登录时抛明确业务错。</p>
 */
@Slf4j
@Service
public class WeChatLoginService {

    @Value("${wechat.miniapp.appid:}")
    private String customerAppid;

    @Value("${wechat.miniapp.secret:}")
    private String customerSecret;

    @Value("${wechat.miniapp.staff-appid:}")
    private String staffAppid;

    @Value("${wechat.miniapp.staff-secret:}")
    private String staffSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 员工端未配置时的统一提示：说清"改哪里"，否则只看到一句无信息量的登录失败 */
    private static final String STAFF_NOT_CONFIGURED =
            "员工端微信登录未配置：请配置 wechat.miniapp.staff-appid / staff-secret"
                    + "（环境变量 WX_STAFF_APP_ID / WX_STAFF_APP_SECRET）";

    /**
     * code2Session：用 wx.login 拿到的 code 换 openid + session_key
     * 文档: https://developers.weixin.qq.com/miniprogram/dev/api-backend/open-api/login/auth.code2Session.html
     *
     * @param app  签发该 code 的小程序端（客户端 / 员工端），必须与小程序实际 appid 一致
     * @param code wx.login 返回的 code
     */
    public Map<String, Object> code2Session(WeChatApp app, String code) {
        String appid = appidOf(app);
        String secret = secretOf(app);

        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                appid, secret, code);

        String response = restTemplate.getForObject(url, String.class);
        // #56: 不打印完整响应（包含session_key敏感信息），仅打印脱敏后的部分
        log.info("微信code2Session响应[{}]: {}", app, maskSessionKey(response));

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 同样必须脱敏：解析失败的响应里 session_key 是原样出现的，
            // 而这一支恰恰只在异常时触发，最容易被忽略而泄漏。
            log.error("解析微信响应失败: {}", maskSessionKey(response), e);
            throw new BusinessException("微信登录响应解析失败");
        }

        if (result.containsKey("errcode")) {
            Object errcodeObj = result.get("errcode");
            int errcode = errcodeObj instanceof Number ? ((Number) errcodeObj).intValue() : Integer.parseInt(errcodeObj.toString());
            if (errcode != 0) {
                String errmsg = result.getOrDefault("errmsg", "未知错误").toString();
                // 带上端名：40013(invalid appid) 的根因几乎总是"端与 appid 配错"，不写端名根本无从判断
                log.error("微信code2Session失败: app={}, errcode={}, errmsg={}", app, errcode, errmsg);
                throw new BusinessException("微信登录失败: " + errmsg);
            }
        }

        if (!result.containsKey("openid")) {
            throw new BusinessException("微信登录失败: 未获取到openid");
        }

        return result;
    }

    /**
     * code2Session 的响应含 {@code session_key}（可解密用户敏感数据），落日志前必须打码。
     * 所有打印该响应的地方都要过这一层 —— 包括异常分支。
     */
    private static String maskSessionKey(String response) {
        if (response == null) return "null";
        return response.replaceAll("\"session_key\":\"[^\"]*\"", "\"session_key\":\"***\"");
    }

    private String appidOf(WeChatApp app) {
        if (app == WeChatApp.STAFF) {
            if (isBlank(staffAppid)) {
                throw new BusinessException(STAFF_NOT_CONFIGURED);
            }
            return staffAppid;
        }
        if (isBlank(customerAppid)) {
            // RequiredConfigChecker 已把这条挡在启动前；留着是为了"有人绕过校验"时也给出可读错误
            throw new BusinessException("客户端微信登录未配置：缺少 WX_APP_ID");
        }
        return customerAppid;
    }

    private String secretOf(WeChatApp app) {
        if (app == WeChatApp.STAFF) {
            if (isBlank(staffSecret)) {
                throw new BusinessException(STAFF_NOT_CONFIGURED);
            }
            return staffSecret;
        }
        if (isBlank(customerSecret)) {
            throw new BusinessException("客户端微信登录未配置：缺少 WX_APP_SECRET");
        }
        return customerSecret;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
