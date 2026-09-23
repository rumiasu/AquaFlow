package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.GrossProfitMapper;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进货成本与毛利：站长端。2026-09-17 新增（v39）。规格见 {@code docs/design/20} §2。
 *
 * <p><b>站点一律取自 {@code AuthContext.requireStationId()}</b> —— 成本价是每个水站自己的事，
 * 跨站能看到等于把 A 站的进货渠道泄露给 B 站。</p>
 *
 * <p>⚠️ <b>成本价不向顾客端暴露</b>：它是站长的商业机密（进价泄露 = 竞争对手知道你的底价）。
 * 本控制器所有端点都带 {@code @RequireRole("STATION_MANAGER")}。</p>
 *
 * <p>⚠️ 本版**不做**供应商表/采购单/应付账款/批次成本核算（{@code docs/design/16} §D4 明确不做上游供应链）。
 * 代价：成本改了以后历史毛利会用新成本重算。响应里带 {@code costBasisNote} 把这件事写给前端。</p>
 */
@RestController
@RequestMapping("/api/manager/gross-profit")
@RequireRole({"STATION_MANAGER"})
@Slf4j
public class ManagerGrossProfitController {

    /**
     * 成本口径提示：前端必须原样展示，不能让站长以为这是"当时的真实毛利"。
     *
     * <p>⚠️ 这里<b>不要写 Markdown 记号</b>（曾出现 {@code **当前**}）：这段文案是直接渲染到
     * 小程序界面上的，星号会原样显示成乱码一样的字符。要强调就用中文书名号或「」。</p>
     */
    private static final String COST_BASIS_NOTE =
            "毛利按当前的成本价计算：改了成本价之后，历史期间的毛利也会跟着变（本版不做批次成本核算）。"
                    + "未填成本的商品不计入毛利，只列收入。";

    /**
     * 净利口径提示。[2026-09-19 新增净利] 前端必须原样展示 ——
     * 净利比毛利更容易被误读成"今天到手的钱"。
     *
     * <p>⚠️ 与 {@link #COST_BASIS_NOTE} 同一个坑：这段文案直接渲染在小程序界面上，
     * <b>不要写 Markdown 记号</b>。</p>
     */
    private static final String PROFIT_BASIS_NOTE =
            "净利 = 水费收入 + 配送费 + 楼层费 − 进货成本 − 配送员计件工钱。"
                    + "按「下单时间」统计这一批订单（不是按哪天送完），所以是这批生意本身的账，"
                    + "不是当天进账的现金。工钱在该单送到时产生：还没送完的单暂时不计工钱，"
                    + "那几天净利会偏高。迟到扣款、高温补贴这类人工调整不计入日净利。";

    @Autowired
    private GrossProfitMapper grossProfitMapper;

    /**
     * 设置某商品在本站的进货成本价。
     *
     * <p>清除成本要**显式传 {@code clear: true}**；缺 {@code costPrice} 且没声明 clear 一律报错 ——
     * 详见 {@link com.example.aquaflow.dto.CostPriceDTO} 的注释（拼错的键不能变成一次静默清空）。</p>
     */
    @PutMapping("/cost")
    public Result<Void> setCost(@RequestBody com.example.aquaflow.dto.CostPriceDTO body) {
        Long stationId = AuthContext.requireStationId();
        if (body.getProductId() == null) {
            return Result.error("productId 不能为空");
        }

        BigDecimal cost;
        if (Boolean.TRUE.equals(body.getClear())) {
            cost = null;
        } else {
            if (body.getCostPrice() == null) {
                return Result.error("costPrice 不能为空（若要清除成本价请传 clear: true）");
            }
            if (body.getCostPrice().signum() < 0) {
                return Result.error("成本价不能为负");
            }
            cost = body.getCostPrice().setScale(2, RoundingMode.HALF_UP);
        }

        int affected = grossProfitMapper.updateCostPrice(stationId, body.getProductId(), cost);
        if (affected == 0) {
            // 拿不到受影响行数就不返回 success —— 否则站长会以为设好了，报表里却一直没有
            return Result.error("本站没有该商品的上架配置，请先在「商品管家」里上架");
        }
        log.info("[v39] 站长设置进货成本: stationId={}, productId={}, costPrice={}", stationId, body.getProductId(), cost);
        return Result.success();
    }

    /** 本站已上架但没填成本价的商品（报表里要显式提示"这些没算进毛利"）。 */
    @GetMapping("/missing-cost")
    public Result<List<Map<String, Object>>> missingCost() {
        return Result.success(grossProfitMapper.listMissingCost(AuthContext.requireStationId()));
    }

    /**
     * 期间毛利 + 净利报表。
     *
     * <p>毛利的构成：{@code totalRevenue}（水费，来自 {@code order_item.subtotal}）与
     * {@code totalCost}（销量 × 进货成本）。<b>2026-09-19 起同一响应里再给出净利</b>：
     * {@code orderCount} / {@code deliveryFee} / {@code floorFee} / {@code totalIncome}
     * / {@code wage} / {@code netProfit} —— 六个字段全部取自<b>同一订单集合</b>，
     * 看 {@code profitBasisNote} 了解口径。</p>
     *
     * <p>⚠️ 两个 null 语义：{@code totalProfit} 与 {@code netProfit} 在"有商品没填成本"时
     * <b>一起为 null</b>。只 null 一个会让站长拿另一个数字继续算，等于把缺失的成本当成 0。</p>
     *
     * @param from 起始日（含），缺省 = 本月 1 号
     * @param to   结束日（含），缺省 = 今天
     */
    @GetMapping
    public Result<Map<String, Object>> report(@RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        Long stationId = AuthContext.requireStationId();
        LocalDate start = (from == null || from.isEmpty()) ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate end = (to == null || to.isEmpty()) ? LocalDate.now() : LocalDate.parse(to);
        if (end.isBefore(start)) {
            return Result.error("结束日期不能早于开始日期");
        }

        // ⚠️ 上界用「结束日 + 1 天」而不是 <= 结束日：endDate 为当天时 <= 当天 在 SQL 里
        // 等价于 <= 当天 00:00:00，当天的销量一条都统计不到（AGENTS §8.19）。
        List<Map<String, Object>> rows = grossProfitMapper.grossProfitByProduct(
                stationId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        // [2026-09-20 产品口径] 水票收入**只在买票那一刻计一次**，用票下单/配送不再重算。
        // 所以收入有两个来源，必须分开统计再相加：漏了它水票收入就凭空消失（票单已从订单侧排除），
        // 按挂牌价再算一次又是重复计。
        BigDecimal ticketRevenue = BigDecimal.ZERO;
        for (Map<String, Object> tr : grossProfitMapper.ticketPurchaseRevenueByProduct(
                stationId, start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            ticketRevenue = ticketRevenue.add(dec(tr.get("ticketRevenue")));
        }

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int missingCostKinds = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal revenue = dec(row.get("revenue"));
            BigDecimal costAmount = dec(row.get("costAmount"));
            boolean missing = row.get("missingCost") != null
                    && Integer.valueOf(1).equals(((Number) row.get("missingCost")).intValue());
            // ⚠️ 缺成本时**不给出毛利数字**：把 costAmount 当 0 直接相减，站长会以为
            // 这一单赚了整整一个售价 —— 那是最坏的一种"看起来正确"。
            BigDecimal profit = missing ? null : revenue.subtract(costAmount);

            Map<String, Object> item = new HashMap<>(row);
            item.put("costPriceText", missing ? "未填" : dec(row.get("costPrice")).toPlainString());
            item.put("profit", profit);
            item.put("profitText", missing ? "未填成本，无法计算"
                    : profit.toPlainString());
            item.put("profitRateText", missing || revenue.signum() == 0 ? "—"
                    : profit.multiply(BigDecimal.valueOf(100))
                            .divide(revenue, 1, RoundingMode.HALF_UP).toPlainString() + "%");
            items.add(item);

            totalRevenue = totalRevenue.add(revenue);
            totalCost = totalCost.add(costAmount);
            if (missing) missingCostKinds++;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("from", start.toString());
        data.put("to", end.toString());
        data.put("items", items);
        // 收入按来源分开给（站长能看出钱从哪来）：订单侧 = 水费，仅非水票单；
        // 水票侧 = 客户买票时的实收。totalRevenue 是两者之和，页面必须能对上这一层加法。
        BigDecimal orderRevenue = totalRevenue;
        BigDecimal revenueAll = orderRevenue.add(ticketRevenue);
        data.put("orderRevenue", orderRevenue);
        data.put("ticketRevenue", ticketRevenue);
        data.put("totalRevenue", revenueAll);
        data.put("totalCost", totalCost);
        // 合计毛利只在**所有商品都有成本**时才给，否则给出 null + 提示
        data.put("totalProfit", missingCostKinds > 0 ? null : revenueAll.subtract(totalCost));
        data.put("missingCostKinds", missingCostKinds);
        data.put("costBasisNote", COST_BASIS_NOTE);
        data.put("missingCostHint", missingCostKinds > 0
                ? "有 " + missingCostKinds + " 个商品没填进货成本，它们的毛利算不出来（收入已计入合计，成本按 0 计）"
                : null);

        // ===== 净利（2026-09-19）：同一批订单的 收入 − 成本 − 工钱 =====
        // ⚠️ 三个数字必须来自**同一订单集合**（结算站 + 排除已取消 + 同一时间窗），
        // 混两批单算出来的净利没有意义 —— 前两个查询的判据与 grossProfitByProduct 逐字一致。
        Map<String, Object> fees = grossProfitMapper.orderCountAndFees(stationId,
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Map<String, Object> wageRow = grossProfitMapper.wageOfOrders(stationId,
                start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        int orderCount = fees == null || fees.get("orderCount") == null
                ? 0 : ((Number) fees.get("orderCount")).intValue();
        BigDecimal deliveryFee = fees == null ? BigDecimal.ZERO : dec(fees.get("deliveryFee"));
        BigDecimal floorFee = fees == null ? BigDecimal.ZERO : dec(fees.get("floorFee"));
        BigDecimal wage = wageRow == null ? BigDecimal.ZERO : dec(wageRow.get("wage"));
        // 收入合计 = 水费 + 配送费 + 楼层费。⚠️ **不含押金**（押金是可退的负债，不是收入）。
        BigDecimal totalIncome = totalRevenue.add(deliveryFee).add(floorFee);
        // ⚠️ 缺成本时净利**也必须为 null**（与 totalProfit 同一条命）：
        // 成本按 0 计会让净利凭空多出整整一个进货成本，那比不显示更糟。
        BigDecimal netProfit = missingCostKinds > 0
                ? null : totalIncome.subtract(totalCost).subtract(wage);

        data.put("orderCount", orderCount);
        data.put("deliveryFee", deliveryFee);
        data.put("floorFee", floorFee);
        data.put("totalIncome", totalIncome);
        data.put("wage", wage);
        data.put("netProfit", netProfit);
        data.put("profitBasisNote", PROFIT_BASIS_NOTE);
        return Result.success(data);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
