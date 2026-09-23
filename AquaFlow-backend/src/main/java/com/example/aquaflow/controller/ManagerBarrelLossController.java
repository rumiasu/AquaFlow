package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.BarrelRecordType;
import com.example.aquaflow.mapper.BarrelLossMapper;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桶损耗统计：站长端<b>只读</b>。2026-09-18 新增（规格见 {@code docs/design/20} §3.5）。
 *
 * <p><b>本类刻意不含任何写入口，而且写入口已由产品决定不做</b>：桶是跟水厂换的，
 * 破损/丢失由水厂承担（属水厂的问题），不是水站的账 —— 客户弄丢的那部分记在
 * {@code customer_barrel_over}（欠桶台账，应收口径）。所以这个读数的用途只剩一个：
 * 让站长确认"系统确实不记这项"，以及将来若水厂侧要记账时有个现成的出口。</p>
 *
 * <p>⚠️ <b>"0" 有两种含义，必须让站长分得清</b>：因为<b>没有任何流程会写 3/4</b>，
 * 所以本端点返回 0 时是"没有登记过"、"本站不记损耗"，<b>不等于</b>"没有发生损耗"。
 * 这件事通过响应里的 {@code note} 明确下发，不让前端自己拼（本仓前端禁止自带口径文案）。</p>
 *
 * <p>站点取自 {@code AuthContext.requireStationId()}，不接受请求参数里的 stationId ——
 * 损耗是本站经营数据，跨站可见等于把库存底细漏给同行。</p>
 */
@RestController
@RequestMapping("/api/manager/barrel-loss")
@RequireRole({"STATION_MANAGER"})
public class ManagerBarrelLossController {

    /**
     * 口径说明：必须原样下发，不能让站长把"没登记"读成"没损耗"。
     *
     * <p>2026-09-18 产品决定：<b>本站不做桶损耗出账</b> —— 桶是跟水厂换的，
     * 破损与丢失由水厂承担（属水厂的问题），不是水站的账。所以这里<b>不会有写入方</b>，
     * 数字长期为 0 是预期的；客户弄丢的桶记在「欠桶台账」（那是应收，不是损耗）。</p>
     */
    private static final String NOTE =
            "桶是跟水厂换的：破损与丢失由水厂承担，本站不做损耗出账，因此系统里没有登记损耗的入口。"
                    + "这里的 0 表示「没有登记过」，并不代表没有发生损耗；"
                    + "客户弄丢/欠着的桶在「欠桶台账」里，那是应收，不是损耗。";

    @Autowired
    private BarrelLossMapper barrelLossMapper;

    /**
     * 期间损耗汇总。
     *
     * @param from 起始日（含），缺省 = 不限
     * @param to   结束日（含），缺省 = 不限
     */
    @GetMapping
    public Result<Map<String, Object>> stats(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        Long stationId = AuthContext.requireStationId();

        LocalDate startDate = (from == null || from.isEmpty()) ? LocalDate.of(1970, 1, 1) : LocalDate.parse(from);
        LocalDate endDate = (to == null || to.isEmpty()) ? LocalDate.of(2998, 12, 31) : LocalDate.parse(to);
        if (endDate.isBefore(startDate)) {
            return Result.error("结束日期不能早于开始日期");
        }
        // ⚠️ 上界用「结束日 + 1 天」而不是 <= 结束日：否则结束日当天的记录一条都统计不到（AGENTS §8.19）
        List<Map<String, Object>> rows = barrelLossMapper.lossByProduct(
                stationId, startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());

        int totalLost = 0;
        int totalDamaged = 0;
        for (Map<String, Object> row : rows) {
            // 类型文案由后端下发（本仓前端禁止自带映射表）
            row.put("lostText", BarrelRecordType.textOf(BarrelRecordType.LOST));
            row.put("damagedText", BarrelRecordType.textOf(BarrelRecordType.DAMAGED));
            totalLost += intOf(row.get("lostQty"));
            totalDamaged += intOf(row.get("damagedQty"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", startDate.toString());
        data.put("to", endDate.toString());
        data.put("items", rows);
        data.put("totalLost", totalLost);
        data.put("totalDamaged", totalDamaged);
        data.put("totalLoss", totalLost + totalDamaged);
        data.put("hasWriteEntry", false);   // 目前没有任何流程写 3/4，前端据此决定怎么提示
        data.put("note", NOTE);
        return Result.success(data);
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof BigDecimal bd) {
            return bd.intValue();
        }
        return ((Number) v).intValue();
    }
}
