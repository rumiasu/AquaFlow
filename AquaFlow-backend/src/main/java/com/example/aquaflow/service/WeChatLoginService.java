package com.example.aquaflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class WeChatLoginService {

    @Value("${wechat.miniapp.appid}")
    private String appid;

    @Value("${wechat.miniapp.secret}")
    private String secret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> code2Session(String code) {
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                appid, secret, code);

        String response = restTemplate.getForObject(url, String.class);
        log.info("微信code2Session响应: {}", response);

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("解析微信响应失败: {}", response, e);
            throw new RuntimeException("微信登录响应解析失败");
        }

        if (result.containsKey("errcode")) {
            Object errcodeObj = result.get("errcode");
            int errcode = errcodeObj instanceof Number ? ((Number) errcodeObj).intValue() : Integer.parseInt(errcodeObj.toString());
            if (errcode != 0) {
                String errmsg = result.getOrDefault("errmsg", "未知错误").toString();
                log.error("微信code2Session失败: errcode={}, errmsg={}", errcode, errmsg);
                throw new RuntimeException("微信登录失败: " + errmsg);
            }
        }

        if (!result.containsKey("openid")) {
            throw new RuntimeException("微信登录失败: 未获取到openid");
        }

        return result;
    }
}
