package com.example.aquaflow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WeChatLoginService {

    @Value("${wechat.miniapp.appid}")
    private String appid;

    @Value("${wechat.miniapp.secret}")
    private String secret;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, String> code2Session(String code) {
        String url = String.format(
                "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                appid, secret, code);

        String response = restTemplate.getForObject(url, String.class);
        log.info("微信code2Session响应: {}", response);

        Map<String, String> result = parseJson(response);

        if (result.containsKey("errcode") && !"0".equals(result.get("errcode"))) {
            String errMsg = result.getOrDefault("errmsg", "未知错误");
            log.error("微信code2Session失败: errcode={}, errmsg={}", result.get("errcode"), errMsg);
            throw new RuntimeException("微信登录失败: " + errMsg);
        }

        if (!result.containsKey("openid")) {
            throw new RuntimeException("微信登录失败: 未获取到openid");
        }

        return result;
    }

    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        Pattern pattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"?([^\"},]+)\"?");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            map.put(matcher.group(1), matcher.group(2));
        }
        return map;
    }
}
