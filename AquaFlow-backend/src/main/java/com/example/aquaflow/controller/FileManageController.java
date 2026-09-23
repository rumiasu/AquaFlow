package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.FileInfo;
import com.example.aquaflow.mapper.FileInfoMapper;
import com.example.aquaflow.util.CosUtil;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件管理（<b>站长专属</b>）：上传 / 列表 / 删除均限 {@code STATION_MANAGER}。
 *
 * <p>与 {@code CommonController#upload} 的区别：那里是"登录即可"的通用图片上传（顾客也会用），
 * 这里是站长对自己站点文件资料的管理入口，带归属与清理语义。</p>
 */
@RestController
@RequestMapping("/api/files")
@Slf4j
public class FileManageController {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".pdf", ".doc", ".docx", ".xls", ".xlsx"
    ));
    /** [AQ-039] category 参与拼接对象路径（public/<category>），只允许字母/数字/下划线/中划线 */
    private static final java.util.regex.Pattern CATEGORY_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    @Autowired
    private CosUtil cosUtil;

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @RequireRole({"STATION_MANAGER"})
    @PostMapping("/upload")
    public Result<FileInfo> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(value = "category", defaultValue = "general") String category) {
        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.error("文件大小不能超过10MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            return Result.error("文件名无效");
        }
        String extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return Result.error("不支持的文件类型: " + extension);
        }
        // [AQ-039] category 会拼进对象路径，必须白名单校验，杜绝 "public/../../private/..." 目录穿越
        if (category == null || !CATEGORY_PATTERN.matcher(category).matches()) {
            return Result.error("非法的文件分类");
        }

        try {
            String dir = "public/" + category;
            String objectName = cosUtil.uploadPublic(file.getBytes(), dir, extension);

            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileName(originalFilename);
            fileInfo.setFileSize(file.getSize());
            fileInfo.setFileType(resolveFileType(file.getContentType()));
            fileInfo.setMimeType(file.getContentType());
            fileInfo.setObjectName(objectName);
            fileInfo.setCategory(category);
            // v45 站隔离：归属站取登录态（不信任请求参数），NULL 只留给平台级/开发者维护的文件
            fileInfo.setStationId(AuthContext.requireStationId());

            Long uid = AuthContext.getUserId();
            fileInfo.setUploaderId(uid != null ? uid.intValue() : null);
            fileInfoMapper.insert(fileInfo);

            // 注入临时访问 URL
            fileInfo.setUrl(cosUtil.generatePublicUrl(objectName));

            return Result.success(fileInfo);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.error("文件上传失败");
        }
    }

    /**
     * 本站可见的文件列表（本站 + 平台级）。
     *
     * <p>⚠️ v45 起必须带水站条件：原实现调 `listAll()` 返回**全部水站**的文件名与临时 URL，
     * 任何站长 token 都能看到别人的资源（跨租户泄露，AGENTS §8.23）。站点取自登录态。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<FileInfo>> list(@RequestParam(value = "category", required = false) String category) {
        Long stationId = AuthContext.requireStationId();
        List<FileInfo> list = category != null && !category.isEmpty()
                ? fileInfoMapper.listVisibleByCategory(stationId, category) : fileInfoMapper.listVisible(stationId);
        // 为每条记录注入临时访问 URL
        for (FileInfo file : list) {
            try {
                file.setUrl(cosUtil.generatePublicUrl(file.getObjectName()));
            } catch (Exception e) {
                log.warn("生成文件URL失败, objectName={}, error={}", file.getObjectName(), e.getMessage());
            }
        }
        return Result.success(list);
    }

    @RequireRole({"STATION_MANAGER"})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        FileInfo file = fileInfoMapper.getById(id);
        if (file == null) {
            return Result.error("文件不存在");
        }
        // v45 站隔离：他站的文件连"存在"都不该暴露给别站，直接按无权处理
        if (file.getStationId() != null && !file.getStationId().equals(AuthContext.requireStationId())) {
            return Result.error("无权操作他站文件");
        }
        if (AuthContext.isManager()) {
            Long uid = AuthContext.getUserId();
            if (file.getUploaderId() != null && !file.getUploaderId().equals(uid.intValue())) {
                return Result.error("无权删除他人文件");
            }
        }
        // 删除 COS 文件。
        // [2026-09-16] 必须容错：COS_SECRET_ID/KEY 未配置时 RequiredConfigChecker 只 WARN 不拒启
        // （见 §3），此时 cosClient.deleteObject 抛异常 → 整个请求变成 code=500，
        // 站长连一条废记录都删不掉（实测：删自己的文件返回 code=500 且 DB 行仍在）。
        // 对象存储不可用是可预期的运维状态，不该升级成系统故障：这里降级为 WARN，
        // 继续清掉 DB 记录；存储里的残留对象交给运维核对（记录没了也就无从引用）。
        try {
            cosUtil.delete(file.getObjectName());
        } catch (Exception e) {
            log.warn("[FileManage] 对象存储删除失败，仍清除数据库记录: objectName={}, error={}",
                    file.getObjectName(), e.getMessage());
        }
        fileInfoMapper.deleteById(id);
        return Result.success();
    }

    private String resolveFileType(String mimeType) {
        if (mimeType == null) return "other";
        if (mimeType.startsWith("image/")) return "image";
        if (mimeType.startsWith("video/")) return "video";
        if (mimeType.contains("pdf") || mimeType.contains("document") || mimeType.contains("word") || mimeType.contains("excel")) return "document";
        return "other";
    }
}
