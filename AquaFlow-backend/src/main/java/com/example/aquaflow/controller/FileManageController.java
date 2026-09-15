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

    @RequireRole({"STATION_MANAGER"})
    @GetMapping
    public Result<List<FileInfo>> list(@RequestParam(value = "category", required = false) String category) {
        List<FileInfo> list = category != null && !category.isEmpty()
                ? fileInfoMapper.listByCategory(category) : fileInfoMapper.listAll();
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
        if (AuthContext.isManager()) {
            Long uid = AuthContext.getUserId();
            if (file.getUploaderId() != null && !file.getUploaderId().equals(uid.intValue())) {
                return Result.error("无权删除他人文件");
            }
        }
        // 删除 COS 文件
        cosUtil.delete(file.getObjectName());
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
