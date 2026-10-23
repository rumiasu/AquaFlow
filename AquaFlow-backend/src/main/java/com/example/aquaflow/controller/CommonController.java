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
     * data保留可预览的临时URL；另附objectName供商品持久化，不从签名URL反推对象键。
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
            return new ImageUploadReceipt(url, objectName);
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            // [2026-09-20] 文案由 CosUtil 统一给：未配密钥时直接说"对象存储未配置"，
            // 否则只有一句笼统的"文件上传失败"，联调的人分不清是没配还是配错了。
            return Result.error(cosUtil.uploadFailureMessage());
        }
    }

    /** 仅此上传响应追加对象键；旧调用方仍按Result&lt;String&gt;读取data，无须改公共Result。 */
    public static final class ImageUploadReceipt extends Result<String> {
        private final String objectName;

        public ImageUploadReceipt(String url, String objectName) {
            setCode(0);
            setData(url);
            this.objectName = objectName;
        }

        public String getObjectName() { return objectName; }
    }
}
