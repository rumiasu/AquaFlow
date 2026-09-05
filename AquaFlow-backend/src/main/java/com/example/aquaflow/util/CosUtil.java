package com.example.aquaflow.util;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.region.Region;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯云 COS 文件操作工具类。
 * <p>统一负责上传、删除、生成临时访问 URL。Bucket 保持私有读写。</p>
 */
@Slf4j
public class CosUtil {

    private final COSClient cosClient;
    private final String bucketName;
    private final String region;

    private static final int PUBLIC_EXPIRY_SECONDS = 24 * 3600;
    private static final int PRIVATE_EXPIRY_SECONDS = 3600;

    /** 预签名 URL 缓存：key = objectName + expirySeconds → value = {url, expireAt} */
    private static final long URL_CACHE_TTL_MS = 30 * 60 * 1000;
    private final Map<String, CachedUrl> urlCache = new ConcurrentHashMap<>();

    public CosUtil(String region, String secretId, String secretKey, String bucketName) {
        this.region = region;
        this.bucketName = bucketName;
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        this.cosClient = new COSClient(cred, clientConfig);
    }

    // ==================== 上传 ====================

    public String upload(byte[] bytes, String objectName) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(detectContentType(objectName));
        metadata.setContentLength(bytes.length);
        cosClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes), metadata);
        log.info("COS 上传成功: {}", objectName);
        return objectName;
    }

    // ==================== 删除 ====================

    public void delete(String objectName) {
        if (objectName == null || objectName.isEmpty()) return;
        cosClient.deleteObject(bucketName, objectName);
        log.info("COS 删除成功: {}", objectName);
    }

    // ==================== 生成临时访问 URL（带缓存） ====================

    public String generatePublicUrl(String objectName) {
        return getCachedUrl(objectName, PUBLIC_EXPIRY_SECONDS);
    }

    public String generatePrivateUrl(String objectName) {
        return getCachedUrl(objectName, PRIVATE_EXPIRY_SECONDS);
    }

    private String getCachedUrl(String objectName, int expirySeconds) {
        if (objectName == null || objectName.isEmpty()) return null;
        String cacheKey = objectName + ":" + expirySeconds;
        long now = System.currentTimeMillis();
        CachedUrl cached = urlCache.get(cacheKey);
        if (cached != null && cached.expireAt > now) {
            return cached.url;
        }
        String url = doGeneratePresignedUrl(objectName, expirySeconds);
        urlCache.put(cacheKey, new CachedUrl(url, now + URL_CACHE_TTL_MS));
        return url;
    }

    private String doGeneratePresignedUrl(String objectName, int expirySeconds) {
        Date expiration = new Date(System.currentTimeMillis() + (long) expirySeconds * 1000);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectName)
                .withMethod(HttpMethodName.GET)
                .withExpiration(expiration);
        URL url = cosClient.generatePresignedUrl(request);
        return url.toString();
    }

    // ==================== 便捷路径生成 ====================

    public String uploadPublic(byte[] bytes, String dir, String extension) {
        String objectName = dir + "/" + UUID.randomUUID() + extension;
        return upload(bytes, objectName);
    }

    public String uploadPrivate(byte[] bytes, String dir, String extension) {
        String objectName = dir + "/" + UUID.randomUUID() + extension;
        return upload(bytes, objectName);
    }

    // ==================== 工具 ====================

    private static class CachedUrl {
        final String url;
        final long expireAt;
        CachedUrl(String url, long expireAt) { this.url = url; this.expireAt = expireAt; }
    }

    private String detectContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
        return "application/octet-stream";
    }
}
