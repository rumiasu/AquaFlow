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

    /**
     * 客户/员工提交反馈（自动识别身份）。
     *
     * <p>[2026-09-18 匿名提交] 身份照旧按登录态写入 {@code customer_id}/{@code staff_id}
     * （<b>库里永远留着真实身份</b>，脱敏发生在站长端的查询 SQL 上，见
     * {@link FeedbackMapper#listCustomerFeedbackByStation}），另外落一个 {@code anonymous} 标记。</p>
     *
     * <p>⚠️ 匿名只认请求体里的显式 {@code true}：{@code null}（没传）/ {@code false} 都是实名。
     * 这里<b>归一成 true/false 再落库</b>（列是 {@code NOT NULL DEFAULT 0}，不能把 null 写进去）——
     * 别改成直接传 {@code params.getAnonymous()}，那会让"没传 anonymous"变成写 NULL。</p>
     */
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
        fb.setAnonymous(Boolean.TRUE.equals(params.getAnonymous()));
        fb.setCreateTime(LocalDateTime.now());
        feedbackMapper.insert(fb);

        return Result.success();
    }

    /**
     * 当前登录客户/员工的反馈记录。
     *
     * <p>⚠️ <b>本端点不做匿名脱敏，是有意的</b>：这是客户<b>自己</b>的记录，
     * 他当然知道自己是谁提的。若在这里也置空 customerId / customerName，
     * 「我的反馈」会退化成一片空白（看不到自己提过什么），而脱敏收益为零 ——
     * 匿名要防的是<b>站长</b>知道是谁，不是防提交人自己。见
     * {@link FeedbackMapper#listCustomerFeedbackByStation}。</p>
     */
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

    /**
     * 管理端：落到本站的顾客反馈。
     *
     * <p>[2026-09-18] 原先下面还有一个 {@code return ... listCustomerFeedback()} 的兜底分支，
     * 那条 SQL 是 <b>全平台</b>查询（{@code where customer_id is not null}，无站过滤）。
     * 它当时不可达（类上 {@code @RequireRole("STATION_MANAGER")} + {@code isManager()} 恒真），
     * 但留着就是一个"跨站读"模板：将来谁把角色放宽一行，全平台反馈立刻对站长可见。
     * 已删除，站别只认 {@code AuthContext.requireStationId()}。</p>
     *
     * <p>[2026-09-18 匿名提交] 匿名记录（{@code anonymous = 1}）的
     * {@code customerId} / {@code customerName} <b>不会出现在响应里</b>（SQL 层已置 NULL），
     * {@code content} / {@code category} / {@code contact} / {@code createTime} 照常返回。
     * 脱敏在 {@link FeedbackMapper#listCustomerFeedbackByStation} 里完成，
     * <b>本方法不做也不该做二次置空</b> —— 在 Java 里"补一刀"会让人误以为 SQL 是可以放松的。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/customers")
    public Result<List<Feedback>> customerFeedback() {
        return Result.success(feedbackMapper.listCustomerFeedbackByStation(AuthContext.requireStationId()));
    }
}