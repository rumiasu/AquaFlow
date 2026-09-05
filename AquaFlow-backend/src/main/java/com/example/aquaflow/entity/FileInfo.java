package com.example.aquaflow.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FileInfo {
    private Integer id;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String mimeType;
    /** COS 对象键（如 public/product/abc.jpg） */
    private String objectName;
    private String category;
    private Integer uploaderId;
    private String uploaderName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 临时访问 URL（由 Controller 注入，不入库） */
    private transient String url;
}
