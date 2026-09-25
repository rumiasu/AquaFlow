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

    /**
     * 归属水站（v45）。
     *
     * <p>⚠️ <b>NULL 是有语义的</b>：表示**平台级**文件（预置 banner、开发者维护的通用图），全站站长可见；
     * 非空则只有该站可见/可删。别把它当"没填上的脏数据"清掉 —— 那会让平台预置资源从所有站长列表里消失。</p>
     *
     * <p>本列存在的直接原因是修跨租户泄露：此前 `listAll()` 无任何水站过滤，
     * 任何站长都能列出全部水站的文件名与 COS 预签名 URL（AGENTS §8.23）。</p>
     */
    private Long stationId;

    private Integer uploaderId;
    private String uploaderName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 临时访问 URL（由 Controller 注入，不入库） */
    private transient String url;
}
