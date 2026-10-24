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
     * 通用图片上传（预览链接24h有效；桶仍私有）。
     * data是临时预览URL；已存储但预览不可用时为空并标previewUnavailable。
     * objectName供商品持久化，不从签名URL反推对象键。
     */
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        if (AuthContext.getUserId() == null) {
            return rejected("请先登录", "UPLOAD_LOGIN_REQUIRED");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return rejected("文件大小不能超过5MB", "UPLOAD_FILE_TOO_LARGE");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            return rejected("文件类型不支持", "UPLOAD_FILE_TYPE_INVALID");
        }
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return rejected("仅支持 JPG、JPEG、PNG、WEBP 格式", "UPLOAD_FILE_TYPE_INVALID");
        }

        if (!cosUtil.isCredentialsConfigured()) {
            return rejected("图片上传暂不可用，请联系水站管理员", "UPLOAD_STORAGE_UNAVAILABLE");
        }

        log.info("文件上传: {}, 用户: {}", originalFilename, AuthContext.getUserId());
        String objectName;
        try {
            objectName = cosUtil.uploadPublic(file.getBytes(), "public/common", extension);
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            // COS SDK可能已经重试；最终拒绝或超时也不能证明第一次PUT没有成功。
            return new ImageUploadFailure("图片上传暂未确认，请稍后查看", "unknown", "UPLOAD_STORAGE_UNCONFIRMED");
        }
        try {
            String url = cosUtil.generatePublicUrl(objectName);
            if (url == null || url.isBlank()) throw new IllegalStateException("empty preview");
            return new ImageUploadReceipt(url, objectName);
        } catch (Exception e) {
            // [2026-10-24] PUT已确认后，预览失败不应丢掉已验证对象键并诱导重复上传。
            log.warn("图片已存储但预览暂不可用，diagnostic=UPLOAD_PREVIEW_UNAVAILABLE");
            return new ImageUploadReceipt(null, objectName);
        }
    }

    private static Result<String> rejected(String message, String diagnosticCode) {
        return new ImageUploadFailure(message, "rejected", diagnosticCode);
    }

    /** 附加固定诊断分类，不包含账号、桶名、签名URL或SDK原始报文。 */
    public static final class ImageUploadFailure extends Result<String> {
        private final String uploadOutcome;
        private final String uploadDiagnosticCode;

        public ImageUploadFailure(String message, String uploadOutcome, String uploadDiagnosticCode) {
            setCode(1);
            setMessage(message);
            this.uploadOutcome = uploadOutcome;
            this.uploadDiagnosticCode = uploadDiagnosticCode;
        }

        public String getUploadOutcome() { return uploadOutcome; }
        public String getUploadDiagnosticCode() { return uploadDiagnosticCode; }
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
        public String getUploadOutcome() { return "stored"; }
        public boolean isPreviewUnavailable() { return getData() == null; }
        public String getUploadDiagnosticCode() { return getData() == null ? "UPLOAD_PREVIEW_UNAVAILABLE" : null; }
    }
}
