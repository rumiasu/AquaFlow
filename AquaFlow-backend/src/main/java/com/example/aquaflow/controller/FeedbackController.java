package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.dto.FeedbackCreateDTO;
import com.example.aquaflow.entity.Feedback;
import com.example.aquaflow.mapper.FeedbackMapper;
import com.example.aquaflow.util.AuthContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 意见反馈接口（后端: FeedbackController）
 * 配送员/站长/客户提交；客户可查自己的反馈记录
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private FeedbackMapper feedbackMapper;

    /** 客户/员工提交反馈（自动识别身份） */
    @PostMapping
    public Result<Void> submit(@RequestBody @Valid FeedbackCreateDTO params) {
        String category = params.getCategory();
        String content = params.getContent();
        String contact = params.getContact();

        Feedback fb = new Feedback();
        if ("customer".equals(AuthContext.getUserType())) {
            fb.setCustomerId(AuthContext.requireCustomerId());
        } else {
            fb.setStaffId(AuthContext.getUserId());
        }
        fb.setCategory(category);
        fb.setContent(content);
        fb.setContact(contact);
        fb.setCreateTime(LocalDateTime.now());
        feedbackMapper.insert(fb);

        return Result.success();
    }

    /** 当前登录客户/员工的反馈记录 */
    @GetMapping("/my")
    public Result<List<Feedback>> my() {
        List<Feedback> list;
        if ("customer".equals(AuthContext.getUserType())) {
            list = feedbackMapper.listByCustomerId(AuthContext.requireCustomerId());
        } else {
            list = feedbackMapper.listByStaffId(AuthContext.getUserId());
        }
        return Result.success(list);
    }

    /** 管理端：查看客户反馈汇总 */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customers")
    public Result<List<Feedback>> customerFeedback() {
        if (AuthContext.isManager()) {
            return Result.success(feedbackMapper.listCustomerFeedbackByStation(AuthContext.requireStationId()));
        }
        return Result.success(feedbackMapper.listCustomerFeedback());
    }
}