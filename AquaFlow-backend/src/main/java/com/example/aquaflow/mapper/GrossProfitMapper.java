package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 进货成本与毛利（v39）。
 *
 * <p>规格见 {@code docs/design/20} §2。本 mapper 只做两件事：写站级成本价、按期间算毛利。</p>
 *
 * <p>⚠️ <b>刻意不做批次成本核算</b>：不做供应商表、采购单、应付账款，也不做加权平均/先进先出。
 * 代价是"成本变了之后历史毛利用新成本重算"——对独立小水站这是可接受的
 * （他们算账就是这么算的），但接口里必须把这件事说明白，不能让站长以为看到的是当时真实毛利。</p>
 */
@Mapper
public interface GrossProfitMapper {

    /**
     * 设置某商品在本站的进货成本价。
     *
     * <p>只更新已有行（本站必须已上架该商品）：进货价挂在"本站就这么卖"这个前提上，
     * 没上架就没有成本可言。{@code affected = 0} 时调用方要给出可读提示，
     * 而不是静默成功（本仓在"删别人的地址却返回成功"上踩过这个坑，AGENTS §8.20）。</p>
     *
     * @return 受影响行数；0 = 本站没有该商品的上架配置
     */
    @Update("update inventory set cost_price = #{costPrice}, update_time = NOW() "
            + "where station_id = #{stationId} and product_id = #{productId}")
    int updateCostPrice(@Param("stationId") Long stationId,
                        @Param("productId") Long productId,
                        @Param("costPrice") BigDecimal costPrice);

    /**
     * 期间毛利报表：按商品汇总销量、收入、成本与毛利。
     *
     * <p>口径说明（四条，都容易踩错）：</p>
     * <ol>
     *   <li>按 <b>{@code coalesce(settle_station_id, delivery_station_id, station_id)}</b>
     *       （v47 <b>结算站</b> = 本单营收归谁）统计，<b>不是</b> {@code station_id}（归属站）——
     *       跨站外派单的水费 + 配送费 + 楼层费归实际配送站（2026-09-18 产品裁定），毛利是"这笔生意
     *       赚了多少"，所以它出现在<b>送货那一站</b>的报表里。换成绩效口径（工钱）才用履约站
     *       （见 {@code docs/design/18}）。
     *       ⚠️ 改写前这里按 {@code station_id} 统计，与本仓看板/客户画像（两级 coalesce）**归两个站**。</li>
     *   <li><b>成本 join 必须与统计条件用同一个站</b>（{@code i.station_id = 上面那个 coalesce}）——
     *       进货价是站级的（v39：{@code inventory.cost_price}），A 站的成本算 B 站的售价
     *       会得到一个既不是 A 也不是 B 的毛利，而且**看起来完全正常**（没有任何报错）。
     *       只改 where 不改 join = 正是这个错误。</li>
     *   <li><b>排除已取消(5)</b>：取消单不该进营收，也不该进毛利。</li>
     *   <li>时间上界用「结束日 + 1 天」（调用方传 {@code endExclusive}）——
     *       写 {@code <= 结束日} 会让当天的销量一条都统计不到（AGENTS §8.19）。</li>
     * </ol>
     *
     * <p>{@code costPrice} 为 NULL 的商品，{@code costAmount} 按 0 计、
     * 并由 {@code missingCost} 标出 —— <b>调用方必须据此把毛利显示为"未填成本"而不是全额</b>，
     * 否则站长会以为自己赚了整整一个售价。</p>
     */
    @Select("select oi.product_id as productId, "
            + "       max(oi.product_name_snapshot) as productName, "
            + "       sum(oi.quantity) as soldQty, "
            + "       round(sum(oi.subtotal), 2) as revenue, "
            + "       max(i.cost_price) as costPrice, "
            + "       round(sum(oi.quantity * coalesce(i.cost_price, 0)), 2) as costAmount, "
            + "       case when max(i.cost_price) is null then 1 else 0 end as missingCost "
            + "  from order_item oi "
            + "  join orders o on o.id = oi.order_id "
            + "  left join inventory i on i.station_id = coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) "
            + "       and i.product_id = oi.product_id "
            + " where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "   and o.status <> 5 "
            // [2026-09-20 产品口径] 水票支付的订单（payment_method=3）**不计订单侧收入**：
            // 水票的钱在「买进来」那一刻就确认了（见 ticketPurchaseRevenueByProduct），
            // 这里再按挂牌水价记一次就是重复计。
            // ⚠️ 成本（销量×成本）与工钱**不排除**票单 —— 货确实出去了、人确实送了。
            // 所以期间口径自洽，但**单张票单的毛利会是负的**（成本在、收入不在），看期间合计即可。
            + "   and o.payment_method <> 3 "
            + "   and o.create_time >= #{start} and o.create_time < #{endExclusive} "
            + " group by oi.product_id "
            + " order by revenue desc")
    List<Map<String, Object>> grossProfitByProduct(@Param("stationId") Long stationId,
                                                   @Param("start") java.time.LocalDateTime start,
                                                   @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /**
     * 本站已上架、但**没填成本价**的商品。
     *
     * <p>用途：毛利报表上要显式提示"这 N 个商品没填成本，毛利算不出来" ——
     * 没有这个提示，站长会以为报表里的毛利就是全部，而实际漏了一半商品。</p>
     */
    @Select("select p.id as productId, p.name as productName "
            + "  from inventory i join product p on p.id = i.product_id "
            + " where i.station_id = #{stationId} and i.enabled = 1 and i.cost_price is null "
            + " order by p.name asc")
    List<Map<String, Object>> listMissingCost(@Param("stationId") Long stationId);

    /**
     * 期间内**买水票的实收**（按商品汇总）。
     *
     * <p>[2026-09-20 产品口径] 水票收入**只在买票那一刻计一次**，之后用票下单 / 配送都不再重算 ——
     * 与"微信 / 现金每单收一次"是不同的模型。所以它是收入的**另一个来源**，必须单独统计：
     * 只看 {@code order_item.subtotal} 会让水票收入凭空消失（票单已被上面那条排除）。</p>
     *
     * <p>口径三条：① 只认 {@code order_id IS NULL}（在线购票那种无订单流水，见 design/19、design/20 §7）；
     * ② 只认 {@code status = 2 已收款}（站长确认收款才算真的收了钱）；
     * ③ 时间窗按 {@code update_time}（= 确认收款那一刻；该列是 ON UPDATE CURRENT_TIMESTAMP），
     * <b>不是</b> {@code create_time}（那是客户提交申请的时间）。</p>
     *
     * <p>⚠️ 已知边界：票的面值里可能含押金（票可抵整单），而押金是负债不是收入 ——
     * 本版不拆这一层（要拆得逐单回溯票抵明细），所以站长看到的是"票的实收总额"。</p>
     */
    @Select("select ticket_water_type_id as productId, "
            + "       round(coalesce(sum(amount), 0), 2) as ticketRevenue "
            + "  from payment_record "
            + " where station_id = #{stationId} "
            + "   and order_id is null and status = 2 "
            + "   and ticket_water_type_id is not null "
            + "   and update_time >= #{start} and update_time < #{endExclusive} "
            + " group by ticket_water_type_id")
    List<Map<String, Object>> ticketPurchaseRevenueByProduct(@Param("stationId") Long stationId,
                                                             @Param("start") java.time.LocalDateTime start,
                                                             @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /**
     * 同一订单集合里的<b>单数</b>与<b>配送费 / 楼层费</b>合计（净利用）。
     *
     * <p>⚠️ 判据必须与 {@link #grossProfitByProduct} <b>逐字一致</b>（结算站 + 排除已取消 +
     * 同一时间窗）—— 两批不同的单混在一起算出来的净利没有任何意义。所以这里刻意
     * <b>不 join {@code order_item}</b>：一旦 join，配送费会被明细行数放大
     * （一单 2 个商品 = 把同一笔配送费收了两次）。</p>
     *
     * <p>本查询恒返回一行（{@code count(*)} 是聚合），金额字段用 coalesce 兜 0。</p>
     */
    @Select("select count(*) as orderCount, "
            // [2026-09-20] 金额只算**非水票单**：票单的配送费 / 楼层费已被票抵掉，钱在购票时收过了
            // （见 ticketPurchaseRevenueByProduct），算进来就是重复。单数仍是全部单（票单也是单）。
            + "       round(coalesce(sum(case when o.payment_method <> 3 then o.delivery_fee else 0 end), 0), 2) as deliveryFee, "
            + "       round(coalesce(sum(case when o.payment_method <> 3 then o.floor_fee else 0 end), 0), 2) as floorFee "
            + "  from orders o "
            + " where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "   and o.status <> 5 "
            + "   and o.create_time >= #{start} and o.create_time < #{endExclusive}")
    Map<String, Object> orderCountAndFees(@Param("stationId") Long stationId,
                                          @Param("start") java.time.LocalDateTime start,
                                          @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /**
     * 这批订单产生的<b>计件工钱</b>合计（净利用）。
     *
     * <p>口径三条，改之前先读：</p>
     * <ol>
     *   <li><b>按 {@code staff_earning.order_id} 挂到这批订单上</b>，不是按工钱自己的
     *       {@code create_time} 筛。否则"今天下单、明天送完"会变成"今天有收入、明天才有成本"，
     *       日净利在两天里一正一负地跳。</li>
     *   <li><b>人工调整不计入</b>（{@code order_id IS NULL} 的行被这个 join 天然排除）——
     *       迟到扣款 / 高温补贴不是"某一天订单"的成本，摊进日净利会让"今天扣了罚款"
     *       莫名其妙地减少今天的利润。</li>
     *   <li>收益在订单进入<b>送达</b>时才产生（{@code StaffEarningService} 文件头），
     *       所以单还没送完时工钱是 0 —— 那几天的净利会暂时偏高，这是本版的已知口径边界，
     *       已随响应里的 {@code profitBasisNote} 告知前端。</li>
     * </ol>
     */
    @Select("select round(coalesce(sum(se.amount), 0), 2) as wage "
            + "  from staff_earning se join orders o on o.id = se.order_id "
            + " where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "   and o.status <> 5 "
            + "   and o.create_time >= #{start} and o.create_time < #{endExclusive}")
    Map<String, Object> wageOfOrders(@Param("stationId") Long stationId,
                                     @Param("start") java.time.LocalDateTime start,
                                     @Param("endExclusive") java.time.LocalDateTime endExclusive);
}
