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
import java.util.Objects;

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

    /** 管理端：仅编辑明确属于本站的公告，系统公告不可由站长改写。 */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Notice notice) {
        Long stationId = AuthContext.requireStationId();
        Notice existing = noticeMapper.getById(id);
        if (existing == null) {
            return Result.error("公告不存在");
        }
        if (!stationId.equals(existing.getStationId())) {
            return Result.error("仅可编辑本站公告");
        }
        notice.setId(id);
        notice.setStationId(stationId);
        // 2026-10-10：预读后记录可被删除，旧 void UPDATE 仍报保存成功；SQL 带本站条件并检查结果。
        if (noticeMapper.update(notice) == 0) {
            // changed-rows 连接在内容及 NOW 均不变时也返回 0，不能误报同值重复保存失败。
            Notice saved = noticeMapper.getById(id);
            if (saved == null || !stationId.equals(saved.getStationId())
                    || !Objects.equals(saved.getTitle(), notice.getTitle())
                    || !Objects.equals(saved.getContent(), notice.getContent())
                    || !Objects.equals(saved.getType(), notice.getType())
                    || !Objects.equals(saved.getStatus(), notice.getStatus())) {
                return Result.error("公告不存在或内容已变化，请刷新后重试");
            }
        }
        return Result.success();
    }

    /** 管理端：仅删除明确属于本站的公告，系统公告不可由站长删除。 */
    @RequireRole({"STATION_MANAGER"})
    @RequireStation
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Notice existing = noticeMapper.getById(id);
        if (existing == null) {
            return Result.error("公告不存在");
        }
        if (!AuthContext.requireStationId().equals(existing.getStationId())) {
            return Result.error("仅可删除本站公告");
        }
        noticeMapper.delete(id);
        return Result.success();
    }
}
