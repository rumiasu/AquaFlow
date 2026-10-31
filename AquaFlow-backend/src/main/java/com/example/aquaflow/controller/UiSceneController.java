package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.util.CosUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Public decorative image only; never accepts a caller-supplied object key. */
@RestController
public class UiSceneController {
    private final CosUtil cos;
    public UiSceneController(CosUtil cos) { this.cos = cos; }

    @GetMapping("/api/ui-assets/scene-home-water")
    public Result<Map<String, Object>> scene() {
        if (!cos.isCredentialsConfigured()) return Result.error("场景图片暂不可用");
        try {
            String url = cos.generatePublicUrl("public/ui/scene-home-water.webp");
            if (url == null || url.isBlank()) return Result.error("场景图片暂不可用");
            // The signer may return a 30-minute cached 24-hour URL; use a conservative refresh deadline.
            return Result.success(Map.of("url", url, "expiresAt", System.currentTimeMillis() + 23L * 60 * 60 * 1000));
        } catch (RuntimeException e) {
            return Result.error("场景图片暂不可用");
        }
    }
}
