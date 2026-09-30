package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.mapper.GrossProfitMapper;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.BusinessTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * 成本口径提示：前端必须原样展示，不能让站长以为这是"当时的真实利润"。
     *
     * <p>⚠️ 这里是<b>画面上的那一行小字</b>，不是文档：[2026-09-26] 产品反馈"利润里太罗嗦了"，
     * 原来的四句长解释已**收进小程序页面的「利润怎么算」帮助弹窗**，这里只留一句最重要的
     * （成本不是批次快照、改了就变）。**不要再把这行写回一段话** —— 站长要的是数字，
     * 不是每次打开报表都读一遍口径论文。</p>
     *
     * <p>⚠️ 这里<b>不要写 Markdown 记号</b>（曾出现 {@code **当前**}）：这段文案是直接渲染到
     * 小程序界面上的，星号会原样显示成乱码一样的字符。要强调就用中文书名号或「」。</p>
     *
     * <p>⚠️ [2026-09-26 产品口径] 面向站长的用词是<b>利润</b>，不是「毛利」——
     * 本仓的用户是小水站（"不是大公司，都是一些小买卖"），"毛利"是财务术语。
     * 代码 / 端点 / 表名里的 GrossProfit 保持不变（改的是下发给站长看的文案）。</p>
     */
    private static final String COST_BASIS_NOTE =
            "利润按当前成本价算：改了成本价，历史期间的利润也会跟着变。";

    /**
     * 净利口径提示。[2026-09-19 新增净利] 前端必须原样展示 ——
     * 净利比利润更容易被误读成"今天到手的钱"。
     *
     * <p>⚠️ 同 {@link #COST_BASIS_NOTE}：这是<b>画面上的那一行</b>，只留"最容易被误读的那一句"
     * （按哪个时间统计、工钱什么时候产生）。明细口径、票单为何为负、人工调整为何不计入，
     * 都收进了页面的「利润怎么算」弹窗。</p>
     *
     * <p>⚠️ 与 {@link #COST_BASIS_NOTE} 同一个坑：这段文案直接渲染在小程序界面上，
     * <b>不要写 Markdown 记号</b>。</p>
     */
    private static final String PROFIT_BASIS_NOTE =
            "净利 = 收入 − 进货成本 − 计件工钱。按下单时间统计，工钱在送达时才产生（没送完的单先不计工钱）。";

    /**
     * 「利润怎么算」帮助弹窗里的**长解释**（[2026-09-26] 从上面两条口径提示里搬出来的）。
     *
     * <p>产品反馈"利润里太罗嗦了"：这两段原文加起来三百多字，每次打开报表都铺在屏幕上。
     * 现在屏幕上是两条**一行版**（{@link #COST_BASIS_NOTE} / {@link #PROFIT_BASIS_NOTE}），
     * 完整解释（含"为什么一张水票单的利润会是负的""人工调整为什么不计入"这些会被误读成
     * 报表坏了的口径）收进页面底部的「利润怎么算」，需要时点一下才看。</p>
     *
     * <p>⚠️ 口径文案只有这一个来源，<b>前端不要自己抄一份进 js</b>：抄一份就是两处口径，
     * 改了一边另一边必然漂移（本仓"计价双轨"的同形问题）。</p>
     *
     * <p>⚠️ 同样不要写 Markdown 记号（渲染在 wx.showModal 里，星号会原样显示）。</p>
     */
    private static final String HELP_NOTE =
            "利润 = 该期间的销售收入 − 卖出数量 × 当前进货成本价；"
                    + "净利 = 收入 − 进货成本 − 计件工钱。\n\n"
                    + "1. 按「下单时间」统计这一批订单（不是按哪天送完）：它是这批生意本身的账，"
                    + "不是当天进账的现金。\n"
                    + "2. 工钱在该单送到时才产生，还没送完的单暂时不计工钱 —— 那几天净利会偏高。\n"
                    + "3. 迟到扣款、高温补贴这类人工调整不计入净利。\n"
                    + "4. 水票收入只在客户买票那一刻计一次：用票下的单不再重复计水费与配送费，"
                    + "所以单看某一张水票单的利润会是负的（成本在、收入不在它身上），请看期间合计。\n"
                    + "5. 明细里「卖出」与「成本合计」算的是本期实际履约的货（含用票兑出去的）；"
                    + "只卖票、本期还没兑货的商品会单列一行，只显示票款收入。\n"
                    + "6. 利润按当前成本价算：本版不做批次成本核算，改了成本价，历史期间的利润也会跟着变。\n"
                    + "7. 未填成本的商品不计入利润，只列收入（报表上写「未填成本」）。";

    @Autowired
    private GrossProfitMapper grossProfitMapper;

    /**
     * [F-16] 业务时钟（时间源统一收在 util 里，Controller 不直接持有 {@code Clock}，
     * 见 {@code config/ClockConfig}）。报表缺省区间"本月 1 日 ~ 今天"由它给出。
     */
    @Autowired
    private BusinessTime businessTime;

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

    /** 本站已上架但没填成本价的商品（报表里要显式提示"这些没算进利润"）。 */
    @GetMapping("/missing-cost")
    public Result<List<Map<String, Object>>> missingCost() {
        return Result.success(grossProfitMapper.listMissingCost(AuthContext.requireStationId()));
    }

    /**
     * 期间利润 + 净利报表。
     *
     * <p>利润（字段名仍是 {@code totalProfit}）的构成：{@code totalRevenue}（水费，来自
     * {@code order_item.subtotal}）与 {@code totalCost}（销量 × 进货成本）。
     * <b>2026-09-19 起同一响应里再给出净利</b>：
     * {@code orderCount} / {@code deliveryFee} / {@code floorFee} / {@code totalIncome}
     * / {@code wage} / {@code netProfit} —— 六个字段全部取自<b>同一订单集合</b>，
     * 看 {@code profitBasisNote} 了解口径。</p>
     *
     * <p>⚠️ 两个 null 语义：{@code totalProfit} 与 {@code netProfit} 在"有商品没填成本"时
     * <b>一起为 null</b>。只 null 一个会让站长拿另一个数字继续算，等于把缺失的成本当成 0。
     * 前端把这两个 null 渲染成「未填成本」并原样展示 {@code missingCostHint} ——
     * <b>不允许</b>因此把整张卡藏起来（站长会以为功能没了，而不是"还差一个成本价"）。</p>
     *
     * @param from 起始日（含），缺省 = 本月 1 号
     * @param to   结束日（含），缺省 = 今天
     */
    @GetMapping
    public Result<Map<String, Object>> report(@RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        Long stationId = AuthContext.requireStationId();
        // [F-16] 原先是 LocalDate.now() 直连：缺省区间"本月 1 日 ~ 今天"取 JVM 真实日期，
        // 跨零点会让"本月"和"今天"各自漂移（月初 0 点最明显），测试也无法固定这段期间。
        LocalDate start = (from == null || from.isEmpty()) ? businessTime.today().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate end = (to == null || to.isEmpty()) ? businessTime.today() : LocalDate.parse(to);
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
        //
        // [2026-09-25 架构评审问题 6] 这里按**商品**把票款与订单侧明细合并成同一张表：
        //   · 行「收入」= 订单侧水费 + 该商品本期购票实收（两个来源，同一维度）；
        //   · 行「成本」= 该商品本期全部履约销量 × 成本价（含用票兑出去的那些，见 GrossProfitMapper）；
        //   · 只有购票、本期没兑票的商品也要出行（否则站长看不到这笔收入的去向）。
        // 合计口径不变：totalRevenue = 订单侧 + 票款，totalCost = 全部履约成本。
        BigDecimal ticketRevenue = BigDecimal.ZERO;
        Map<Long, BigDecimal> ticketRevenueByProduct = new LinkedHashMap<>();
        Map<Long, String> ticketProductNames = new LinkedHashMap<>();
        for (Map<String, Object> tr : grossProfitMapper.ticketPurchaseRevenueByProduct(
                stationId, start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            Object pidRaw = tr.get("productId");
            if (pidRaw == null) continue;
            Long pid = ((Number) pidRaw).longValue();
            BigDecimal amount = dec(tr.get("ticketRevenue"));
            ticketRevenueByProduct.merge(pid, amount, BigDecimal::add);
            if (tr.get("productName") != null) {
                ticketProductNames.put(pid, tr.get("productName").toString());
            }
            ticketRevenue = ticketRevenue.add(amount);
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
            Long pid = row.get("productId") == null ? null : ((Number) row.get("productId")).longValue();
            BigDecimal rowTicketRevenue = pid == null
                    ? BigDecimal.ZERO : ticketRevenueByProduct.getOrDefault(pid, BigDecimal.ZERO);
            if (pid != null) ticketRevenueByProduct.remove(pid);   // 已并入本行，剩下的就是"只卖票没兑票"的
            BigDecimal revenueTotal = revenue.add(rowTicketRevenue);
            // ⚠️ 缺成本时**不给出行利润数字**（前端把它渲染成「未填成本」）：把 costAmount 当 0 直接相减，
            // 站长会以为这一单赚了整整一个售价 —— 那是最坏的一种"看起来正确"。
            BigDecimal profit = missing ? null : revenueTotal.subtract(costAmount);

            Map<String, Object> item = new HashMap<>(row);
            item.put("revenue", revenue);                  // 订单侧水费（不含票款）—— 保持原字段语义
            item.put("ticketRevenue", rowTicketRevenue);    // 该商品本期购票实收
            item.put("revenueTotal", revenueTotal);         // 上两者之和：行利润用的就是它
            item.put("costPriceText", missing ? "未填" : dec(row.get("costPrice")).toPlainString());
            item.put("profit", profit);
            item.put("profitText", missing ? "未填成本，无法计算"
                    : profit.toPlainString());
            item.put("profitRateText", missing || revenueTotal.signum() == 0 ? "—"
                    : profit.multiply(BigDecimal.valueOf(100))
                            .divide(revenueTotal, 1, RoundingMode.HALF_UP).toPlainString() + "%");
            items.add(item);

            totalRevenue = totalRevenue.add(revenue);
            totalCost = totalCost.add(costAmount);
            if (missing) missingCostKinds++;
        }

        // 只卖了票、本期没有兑票记录的商品：也要成行，否则这笔收入的去向在明细里查不到。
        // 它的利润口径特殊 —— 钱已确认、货还没出，所以给 null + 说明，**不**编一个 100% 利润率。
        for (Map.Entry<Long, BigDecimal> e : ticketRevenueByProduct.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("productId", e.getKey());
            item.put("productName", ticketProductNames.getOrDefault(e.getKey(), "商品" + e.getKey()));
            item.put("soldQty", 0);
            item.put("revenue", BigDecimal.ZERO);
            item.put("ticketRevenue", e.getValue());
            item.put("revenueTotal", e.getValue());
            item.put("costPrice", null);
            item.put("costPriceText", "未填");
            item.put("costAmount", BigDecimal.ZERO);
            item.put("missingCost", 0);
            item.put("profit", null);
            item.put("profitText", "本期只有购票收入，没有兑票记录");
            item.put("profitRateText", "—");
            items.add(item);
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
        // 合计利润只在**所有商品都有成本**时才给，否则给出 null + 提示
        data.put("totalProfit", missingCostKinds > 0 ? null : revenueAll.subtract(totalCost));
        data.put("missingCostKinds", missingCostKinds);
        data.put("costBasisNote", COST_BASIS_NOTE);
        // 缺成本时下发给站长看的那句话要**说清是哪一项**算不出来（前端原样展示，
        // 不许把 null 渲染成 0，也不许自己拼一句"算不出"就完事 —— 站长得知道去补什么）
        data.put("missingCostHint", missingCostKinds > 0
                ? "有 " + missingCostKinds + " 个商品没填进货成本，它们的利润算不出来（收入已计入合计，成本按 0 计）"
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
        // 收入合计 = 订单收入（水费 + 配送费 + 楼层费）+ 水票收入（买票实收）。
        // ⚠️ **不含押金**（押金是可退的负债，不是收入）。
        // 配送费 / 楼层费只算非水票单 —— 票单的那部分已被票抵掉、钱在购票时收过（见 GrossProfitMapper）。
        BigDecimal totalIncome = revenueAll.add(deliveryFee).add(floorFee);
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
        // 长解释：只给页面底部的「利润怎么算」弹窗用，**不要**铺在报表正文里（[2026-09-26] 产品反馈太罗嗦）
        data.put("helpNote", HELP_NOTE);
        return Result.success(data);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
