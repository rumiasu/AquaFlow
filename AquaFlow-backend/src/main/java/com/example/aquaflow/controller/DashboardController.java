package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.service.DashboardService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站长经营看板（<b>全部限 {@code STATION_MANAGER}</b>）。
 *
 * <p>水站维度一律由后端按登录站长判定（{@code AuthContext.requireStationId()}），前端不传 stationId。
 * 指标口径若与对账（{@code ReconciliationService}）不一致，以对账为准并在注释里写明差异原因。</p>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    // [2026-09-18 删除] GET /order-status 与 GET /order-trend：两个端点零前端调用，且口径与
    // GET /api/dashboard/report 分叉 —— 同一个指标两套算法，正是本仓"口径分叉"的老来源
    // （docs/audit/history/review/2026-09-16-死端点评估.md 判"删除"，已执行）。看板数据一律走下面的 /report。
    // 回归：ManagerOrderControllerRemovedIntegrationTest 断言这两条路径返回 404。

    // [2026-09-27 删除] GET /today 与 GET /overview —— 同源的第二批，产品批准后执行。
    //
    // **原来为什么存在**：站长端**最早的**看板读数入口（今日单量/库存；客户数/订单数汇总）。
    // 后来看板页改用 /report（含环比、趋势、多维分布）之后，它没跟着删。
    //
    // **为什么删**（核实见 docs/audit/history/review/2026-09-16-死端点评估.md #2 与演练报告 §9.1）：
    //   ① 两端小程序**零真调用**；前端包装函数已于 2026-09-19 显式删除
    //      （miniapp-delivery/api/station-mgmt.js:5-13 留了墓碑、config/api.js:264 删了两个常量）；
    //   ② 它带的 pendingOrders 只有"按状态数"，与待分配列表的付款闸门**口径分叉** ——
    //      站长照着这个数去列表里找会少几条，属"看着有数、实际对不上"；
    //   ③ 同类查询（/order-status、/order-trend）已在 2026-09-18 先删过一批，理由相同。
    //
    // 伴随清理（删完即孤儿的，一并删掉）：
    //   · OrderMapper.countTodayByStationId / countByStationId / countByStationIdAndStatus
    //     ⚠️ 删除登记表原写"countByStationIdAndStatus 另有他用，**不能删**" —— **该判定是错的**：
    //     2026-09-27 重新逐处核实，它的 3 处真调用全在这两个端点里（本文件原 :51/:52/:72）。
    //     **旧判定会过期，动删除前必须自己重证一遍**（AGENTS §0.3）。
    //   · CustomerMapper.countByStationId
    //   · 本类原 5 个注入字段（customerMapper / addressMapper / orderMapper / inventoryMapper /
    //     inventoryService）：其中 addressMapper **本来就是死字段**（只在声明行出现过一次）
    //
    // **删掉会怎样**：唯一影响是守护用例 DashboardNoticeSearchFeedbackIntegrationTest 少断言两条路径
    // （已同步改）。**不要再加回来** —— 加回来就是"同一指标第二套算法"。

    /**
     * 综合数据报表。
     * GET /api/dashboard/report?range=today|7d|30d
     *
     * <p>返回：周期汇总 + 环比对比（自动取上一等长周期）+ 按天趋势（补零）+
     * 状态/支付方式分布 + 热销商品/客户 TOP5 + 配送员业绩 + 下单时段分布 + 欠桶提醒。
     * 水站取自登录站长，不接受前端传参。</p>
     */
    @RequireRole({"STATION_MANAGER"})
    @GetMapping("/report")
    public Result<?> report(@RequestParam(defaultValue = "7d") String range) {
        Long stationId = AuthContext.requireStationId();
        return Result.success(dashboardService.report(stationId, range));
    }
}
