package com.example.aquaflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信订阅消息通知服务
 * 用于发送订单状态变更、配送通知等给小程序用户。
 *
 * <p>[AQ-028] 现状说明：本服务当前**全项目 0 处调用**（死代码）。微信支付回调 / 微信退款
 * 亦未接入（用户明确"微信可模拟、暂不接真实支付"）。保留此文件作为后续接入的脚手架，
 * 接入时需：① 在微信后台配置模板 ID 并替换下面的占位常量；② 由业务侧注入调用本服务；
 * ③ 补支付回调验签入口。在此之前不应假定"订阅消息已发送成功"。</p>
 */
@Slf4j
@Service
public class WeChatNotifyService {

    @Value("${wechat.miniapp.appid}")
    private String appid;

    @Value("${wechat.miniapp.secret}")
    private String secret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // access_token 缓存
    private volatile String accessToken;
    private volatile LocalDateTime tokenExpireTime;

    /** [AQ-047] 脱敏：access_token 等密钥绝不进日志 */
    private static String maskSecret(String s) {
        if (s == null) return "null";
        return s.replaceAll("(\"access_token\"\\s*:\\s*\")[^\"]+", "$1***");
    }

    /** [AQ-047] openid 脱敏 */
    private static String maskOpenid(String openid) {
        if (openid == null) return "null";
        if (openid.length() <= 4) return "***";
        return openid.substring(0, 4) + "****";
    }

    /**
     * 获取 access_token（带缓存，有效期2小时）
     */
    public String getAccessToken() {
        if (accessToken != null && tokenExpireTime != null && LocalDateTime.now().isBefore(tokenExpireTime)) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && tokenExpireTime != null && LocalDateTime.now().isBefore(tokenExpireTime)) {
                return accessToken;
            }
            String url = String.format(
                    "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                    appid, secret);
            try {
                String response = restTemplate.getForObject(url, String.class);
                Map<String, Object> result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
                if (result.containsKey("access_token")) {
                    accessToken = result.get("access_token").toString();
                    int expiresIn = result.containsKey("expires_in") ? ((Number) result.get("expires_in")).intValue() : 7200;
                    tokenExpireTime = LocalDateTime.now().plusSeconds(expiresIn - 300); // 提前5分钟过期
                    log.info("获取access_token成功，有效期{}秒", expiresIn);
                    return accessToken;
                } else {
                    log.error("获取access_token失败: {}", maskSecret(response));
                    throw new RuntimeException("获取access_token失败");
                }
            } catch (Exception e) {
                log.error("获取access_token异常", e);
                throw new RuntimeException("获取access_token失败: " + e.getMessage());
            }
        }
    }

    /**
     * 发送订阅消息
     * @param openid 接收者openid
     * @param templateId 消息模板ID
     * @param data 模板数据
     * @param page 跳转页面（可选）
     */
    public void sendSubscribeMessage(String openid, String templateId, Map<String, Object> data, String page) {
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token;

        Map<String, Object> body = new HashMap<>();
        body.put("touser", openid);
        body.put("template_id", templateId);
        body.put("data", data);
        if (page != null && !page.isEmpty()) {
            body.put("page", page);
        }

        try {
            String response = restTemplate.postForObject(url, body, String.class);
            Map<String, Object> result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
            Object errcode = result.get("errcode");
            if (errcode != null && ((Number) errcode).intValue() != 0) {
                log.error("发送订阅消息失败: openid={}, templateId={}, response={}", maskOpenid(openid), templateId, maskSecret(response));
            } else {
                log.info("发送订阅消息成功: openid={}, templateId={}", maskOpenid(openid), templateId);
            }
        } catch (Exception e) {
            log.error("发送订阅消息异常: openid={}, templateId={}", maskOpenid(openid), templateId, e);
        }
    }

    /**
     * 发送订单配送通知
     * @param openid 客户openid
     * @param orderNo 订单号
     * @param receiverName 收货人
     * @param receiverPhone 收货电话
     * @param address 收货地址
     */
    public void sendDeliveryNotification(String openid, String orderNo, String receiverName,
                                          String receiverPhone, String address) {
        // 模板ID需要在微信后台配置后填入
        String templateId = "DELIVERY_TEMPLATE_ID";

        Map<String, Object> data = new HashMap<>();
        data.put("thing1", buildDataItem(orderNo));      // 订单编号
        data.put("thing2", buildDataItem(receiverName));  // 收货人
        data.put("thing3", buildDataItem(receiverPhone)); // 联系电话
        data.put("thing4", buildDataItem(address));       // 收货地址

        sendSubscribeMessage(openid, templateId, data, "/pages/order/detail?id=" + orderNo);
    }

    /**
     * 发送订单完成通知
     */
    public void sendOrderFinishedNotification(String openid, String orderNo, String amount) {
        String templateId = "FINISHED_TEMPLATE_ID";

        Map<String, Object> data = new HashMap<>();
        data.put("thing1", buildDataItem(orderNo));  // 订单编号
        data.put("amount2", buildDataItem(amount));   // 金额

        sendSubscribeMessage(openid, templateId, data, "/pages/order/detail?id=" + orderNo);
    }

    /**
     * 构建模板数据项（微信要求value字段为object含value属性）
     */
    private Map<String, String> buildDataItem(String value) {
        Map<String, String> item = new HashMap<>();
        // 微信订阅消息thing类型限制20个字符
        if (value != null && value.length() > 20) {
            value = value.substring(0, 20);
        }
        item.put("value", value != null ? value : "");
        return item;
    }
}
