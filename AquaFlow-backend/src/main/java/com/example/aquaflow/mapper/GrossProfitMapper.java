package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 期间收入沿用既有口径；成本来自本站实际出库流水，不用当前价追算历史。 */
@Mapper
public interface GrossProfitMapper {

    // 2026-10-08：原先部分退款仍显示全额收入。现有退款按整类费用退，按原订单批次扣实退；成本事实保留。
    String REFUND_WATER = "(select coalesce(sum(-coalesce(r.water_amount,0)),0) from payment_record r where r.order_id=o.id and r.status=3 and r.amount<0 and r.payment_method<>3)";
    String REFUND_CONSUMPTION = "(select coalesce(sum(-(r.amount-coalesce(r.barrel_deposit,0))),0) from payment_record r where r.order_id=o.id and r.status=3 and r.amount<0 and r.payment_method<>3)";
    String SERVICE_RETAINED = "(" + REFUND_CONSUMPTION + "-" + REFUND_WATER + " < o.delivery_fee+o.floor_fee)";

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
     * 按既有结算站、订单创建日期与收入规则统计；实际出库流水提供不可追改的成本。
     * 未出库或遗留流水缺成本时 missingCost=1，调用方必须隐藏所选期间的利润。
     * 水票购票收入单独统计，兑票订单仍计实际成本；退款只按既有实退规则减少收入。
     */
    @Select("select oi.product_id as productId, "
            + "       max(oi.product_name_snapshot) as productName, "
            + "       sum(oi.quantity) as soldQty, "
            // ⚠️ [2026-09-25 架构评审问题 6] 收入与成本**必须分开过滤**：
            //    收入排除水票单（票款在买票那一刻已计，见 ticketPurchaseRevenueByProduct），
            //    成本**不排除**水票单 —— 货确实出去了、人确实送了。
            //    原实现把 `o.payment_method <> 3` 写在 WHERE 里，等于把整张票单（含成本事实）
            //    一起滤掉：报表只剩售票实收与工钱，兑票配送的进货成本凭空消失、毛利虚高。
            //    改法就是下面这一行 CASE WHEN（只过滤"收入"这一个聚合，不过滤行）。
            + "       round(sum(case when o.payment_method <> 3 and not (" + REFUND_WATER + " > 0 and " + REFUND_WATER + " >= o.water_amount) then oi.subtotal else 0 end), 2) as revenue, "
            + "       null as costPrice, "
            + "       round(sum(coalesce(c.costAmount,0)), 2) as costAmount, "
            + "       max(case when c.shippedQty=oi.quantity and c.unknownCost=0 then 0 else 1 end) as missingCost "
            + "  from order_item oi "
            + "  join orders o on o.id = oi.order_id "
            + "  left join (select station_id,product_id,ref_id, sum(case when type='CONSUME' then -delta else 0 end) shippedQty, "
            + "      max(case when cost_amount is null then 1 else 0 end) unknownCost, "
            + "      sum(case when type='CONSUME' then cost_amount else -cost_amount end) costAmount "
            + "      from inventory_record where type in ('CONSUME','CANCEL_RESTORE','REFUND_RESTORE') group by station_id,product_id,ref_id) c "
            + "      on c.station_id=coalesce(o.settle_station_id,o.delivery_station_id,o.station_id) and c.ref_id=o.id and c.product_id=oi.product_id "
            + " where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "   and o.status <> 5 "
            + "   and o.create_time >= #{start} and o.create_time < #{endExclusive} "
            + " group by oi.product_id "
            + " order by revenue desc")
    List<Map<String, Object>> grossProfitByProduct(@Param("stationId") Long stationId,
                                                   @Param("start") java.time.LocalDateTime start,
                                                   @Param("endExclusive") java.time.LocalDateTime endExclusive);

    // 独立催填已取消；旧待办调用保留兼容空列表，期间缺失由报表自身判断。
    default List<Map<String, Object>> listMissingCost(Long stationId) { return List.of(); }

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
     *
     * <p>[2026-09-25] 多带一个 {@code productName}：调用方要把这张表与
     * {@link #grossProfitByProduct} 的订单侧明细**按商品合并**（评审问题 6），
     * 而"本期只卖了票、还没兑票"的商品在订单侧根本不出行，没有名字就没法显示。</p>
     */
    @Select("<script>select p.ticket_water_type_id as productId, "
            + "       max(pr.name) as productName, "
            + "       round(coalesce(sum(p.amount <if test='refundSchema'>-coalesce((select sum(x.amount) from ticket_exit_refund x where x.original_payment_id=p.id),0)</if>), 0), 2) as ticketRevenue "
            + "  from payment_record p "
            + "  left join product pr on pr.id = p.ticket_water_type_id "
            + " where p.station_id = #{stationId} "
            + "   and p.order_id is null and p.status = 2 "
            + "   and p.ticket_water_type_id is not null "
            + "   and p.update_time >= #{start} and p.update_time &lt; #{endExclusive} "
            + " group by p.ticket_water_type_id</script>")
    List<Map<String, Object>> ticketPurchaseRevenueByProduct(@Param("stationId") Long stationId,
                                                             @Param("start") java.time.LocalDateTime start,
                                                             @Param("endExclusive") java.time.LocalDateTime endExclusive,
                                                             @Param("refundSchema") boolean refundSchema);

    default List<Map<String,Object>> ticketPurchaseRevenueByProduct(Long stationId, java.time.LocalDateTime start,
                                                                  java.time.LocalDateTime endExclusive) {
        return ticketPurchaseRevenueByProduct(stationId, start, endExclusive, false);
    }

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
            + "       round(coalesce(sum(case when o.payment_method <> 3 and " + SERVICE_RETAINED + " then o.delivery_fee else 0 end), 0), 2) as deliveryFee, "
            + "       round(coalesce(sum(case when o.payment_method <> 3 and " + SERVICE_RETAINED + " then o.floor_fee else 0 end), 0), 2) as floorFee "
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
