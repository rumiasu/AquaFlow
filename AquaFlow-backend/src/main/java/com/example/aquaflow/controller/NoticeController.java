package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.annotation.RequireStation;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Notice;
import com.example.aquaflow.mapper.NoticeMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告管理接口（后端: NoticeController）
 */
@RestController
@RequestMapping("/api/notices")
@Slf4j
public class NoticeController {

    @Autowired
    private NoticeMapper noticeMapper;

    /** 客户/员工可见：已发布公告列表 */
    @GetMapping
    public Result<List<Notice>> listPublished() {
        return Result.success(noticeMapper.listPublished());
    }

    /** 公告详情 */
    @GetMapping("/{id}")
    public Result<Notice> getById(@PathVariable Long id) {
        Notice notice = noticeMapper.getById(id);
        if (notice == null) {
            return Result.error("公告不存在");
        }
        // [AQ-038] 旧实现不做发布状态与站点校验，遍历 id 即可读到他站未发布草稿。
        // 员工：仅可看本水站公告；顾客/未登录：仅可见已发布（status=1）公告。
        if ("staff".equals(AuthContext.getUserType())) {
            Long stationId = AuthContext.getStationId();
            if (stationId == null || notice.getStationId() == null || !stationId.equals(notice.getStationId())) {
                return Result.error("无权查看其它水站的公告");
            }
        } else if (notice.getStatus() == null || !Integer.valueOf(1).equals(notice.getStatus())) {
            return Result.error("公告不存在");
        }
        return Result.success(notice);
    }

    /**
     * 管理端：本水站全部公告（**含草稿与已下架**）。
     *
     * <p>站别取自登录态、按登录站过滤（杜绝跨站可见）。[2026-09-18 修复] 原来底层 SQL 多带一个
     * {@code status = 1}，于是站长保存的草稿立刻从列表消失、下架后再也点不回来 —— 管理列表
     * 要能管理**没发布的那部分**，否则「草稿 / 下架」这两个状态在界面上等于不存在。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @GetMapping("/all")
    public Result<List<Notice>> listAll() {
        return Result.success(noticeMapper.listForStation(AuthContext.requireStationId()));
    }

    /** 管理端：发布公告（归属强制绑定登录站） */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @PostMapping
    public Result<Notice> save(@RequestBody Notice notice) {
        notice.setStationId(AuthContext.requireStationId());
        notice.setStatus(notice.getStatus() != null ? notice.getStatus() : 1);
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        noticeMapper.insert(notice);
        return Result.success(notice);
    }

    /** 管理端：编辑公告（先校验归属，禁止改他站公告） */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Notice notice) {
        Notice existing = noticeMapper.getById(id);
        if (existing == null) {
            return Result.error("公告不存在");
        }
        if (existing.getStationId() != null && !existing.getStationId().equals(AuthContext.requireStationId())) {
            return Result.error("无权编辑其它水站的公告");
        }
        notice.setId(id);
        notice.setStationId(existing.getStationId());
        noticeMapper.update(notice);
        return Result.success();
    }

    /** 管理端：删除公告（先校验归属） */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Notice existing = noticeMapper.getById(id);
        if (existing == null) {
            return Result.error("公告不存在");
        }
        if (existing.getStationId() != null && !existing.getStationId().equals(AuthContext.requireStationId())) {
            return Result.error("无权删除其它水站的公告");
        }
        noticeMapper.delete(id);
        return Result.success();
    }
}
