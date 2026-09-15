package com.example.aquaflow.controller;

import com.example.aquaflow.common.Result;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.CosUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 通用能力接口。
 *
 * <p>当前只有图片上传：<b>登录即可用</b>（内部校验 {@code AuthContext.getUserId()}），
 * 限制 5MB + 扩展名白名单（jpg/jpeg/png/webp），落到对象存储的 {@code public/} 前缀。
 * 它既不是"公开接口"、也不限具体角色 —— 站长传商品图、顾客传退款凭证都走它。</p>
 */
@RestController
@RequestMapping("/api/common")
@Slf4j
public class CommonController {

    @Autowired
    private CosUtil cosUtil;

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".webp"
    ));

    /**
     * 通用图片上传（公开访问，24h 有效）。
     * 返回可直接用于 &lt;image&gt; 的临时 URL。
     */
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        if (AuthContext.getUserId() == null) {
            return Result.error("请先登录");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.error("文件大小不能超过5MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            return Result.error("文件类型不支持");
        }
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return Result.error("仅支持 JPG、JPEG、PNG、WEBP 格式");
        }

        log.info("文件上传: {}, 用户: {}", originalFilename, AuthContext.getUserId());
        try {
            String objectName = cosUtil.uploadPublic(file.getBytes(), "public/common", extension);
            String url = cosUtil.generatePublicUrl(objectName);
            return Result.success(url);
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            return Result.error("文件上传失败");
        }
    }
}
