package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.entity.Notice;
import com.example.aquaflow.mapper.NoticeMapper;
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
        return Result.success(notice);
    }

    /** 管理端：全部公告 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/all")
    public Result<List<Notice>> listAll() {
        return Result.success(noticeMapper.listAll());
    }

    /** 管理端：发布公告 */
    @RequireRole({"STATION_MANAGER"})
    @PostMapping
    public Result<Notice> save(@RequestBody Notice notice) {
        notice.setStatus(notice.getStatus() != null ? notice.getStatus() : 1);
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        noticeMapper.insert(notice);
        return Result.success(notice);
    }

    /** 管理端：编辑公告 */
    @RequireRole({"STATION_MANAGER"})
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Notice notice) {
        notice.setId(id);
        noticeMapper.update(notice);
        return Result.success();
    }

    /** 管理端：删除公告 */
    @RequireRole({"STATION_MANAGER"})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noticeMapper.delete(id);
        return Result.success();
    }
}
