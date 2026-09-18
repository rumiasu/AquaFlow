package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.EarningKind;
import com.example.aquaflow.entity.StaffEarning;
import com.example.aquaflow.mapper.StaffEarningMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送员自助：我的工资（只读）。2026-09-18 新增。规格见 {@code docs/design/18}。
 *
 * <p><b>身份只取自 {@code AuthContext}，本接口不接受任何 staffId 参数</b> —— 一旦接受，
 * 配送员改个数字就能看到同事的工钱。这与站长端的 {@code GET /api/manager/earnings?staffId=}
 * 是<b>两个端点</b>：那个必须带 staffId，因为站长要看全站，且其校验点是"这人在不在本站"。</p>
 *
 * <p><b>不影响"发钱"这件事</b>：发钱是站长在线下做的动作（微信转账/现金），
 * 系统只留痕（{@code staff_payroll.paid_time}）。所以本页只读、不提供任何"申请提现"入口 ——
 * 那要支付牌照与资金存管，本项目连微信支付渠道都还没接。</p>
 */
@RestController
@RequestMapping("/api/delivery/earnings")
@RequireRole({"DELIVERY", "STATION_MANAGER"})
public class DeliveryEarningController {

    /** 结算单列表一次最多给几条（配送员只需要看最近几张）。 */
    private static final int PAYROLL_LIMIT = 20;

    /** 口径说明：由后端下发，前端不复述（本仓约定：展示文案不在前端拼） */
    private static final String NOTE =
            "「未结合计」是还没被任何结算单算进去的工钱，金额以站长生成的结算单为准；"
                    + "「已发放」由站长线下转账后登记，到账时间以实际收款为准。";

    @Autowired
    private StaffEarningMapper staffEarningMapper;

    @Autowired
    private StaffPayrollMapper staffPayrollMapper;

    /**
     * 我的收益：期间明细 + 期间合计 + 未结合计 + 我的结算单。
     *
     * @param from 起始日（含），缺省 = 本月 1 日
     * @param to   结束日（含），缺省 = 今天
     */
    @GetMapping
    public Result<Map<String, Object>> myEarnings(@RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        Long staffId = AuthContext.getUserId();
        LocalDate startDate = (from == null || from.isEmpty())
                ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate endDate = (to == null || to.isEmpty()) ? LocalDate.now() : LocalDate.parse(to);
        if (endDate.isBefore(startDate)) {
            return Result.error("结束日期不能早于开始日期");
        }
        // ⚠️ 上界必须是「结束日 + 1 天」：写成 <= 结束日 时，结束日是 LocalDate 会退化成
        // <= 当天 00:00:00，**今天的工钱一条都看不到**（AGENTS §8.19）。
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime endExclusive = endDate.plusDays(1).atStartOfDay();

        List<Map<String, Object>> items = new ArrayList<>();
        for (StaffEarning e : staffEarningMapper.listByStaff(staffId, start, endExclusive)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("orderId", e.getOrderId());
            row.put("kind", e.getKind());
            // 类型文案由后端下发（前端禁止自带映射表；`PENALTY` 已经是负数落库，前端不要再取反）
            row.put("kindText", EarningKind.textOf(e.getKind()));
            row.put("qty", e.getQty());
            row.put("unitAmount", e.getUnitAmount());
            row.put("amount", e.getAmount());
            row.put("note", e.getNote());
            row.put("createTime", e.getCreateTime());
            items.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", startDate.toString());
        data.put("to", endDate.toString());
        data.put("items", items);
        data.put("periodTotal", staffEarningMapper.sumByStaff(staffId, start, endExclusive));
        data.put("unsettledTotal", staffEarningMapper.sumUnsettledByStaff(staffId));
        // 结算单实体自带 statusText（见 entity/StaffPayroll），前端直接展示，不要自己映射 1/2/3
        data.put("payrolls", staffPayrollMapper.listByStaff(staffId, PAYROLL_LIMIT));
        data.put("note", NOTE);
        return Result.success(data);
    }
}
