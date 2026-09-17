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
     * <p>口径说明（三条，都容易踩错）：</p>
     * <ol>
     *   <li>按 <b>{@code orders.station_id}</b>（营收归属站）统计，不是履约站 ——
     *       毛利是"这笔生意赚了多少"，跨站外派单的钱记在归属站，
     *       换成绩效口径（工钱）才用履约站（见 {@code docs/design/18}）。</li>
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
            + "  left join inventory i on i.station_id = o.station_id and i.product_id = oi.product_id "
            + " where o.station_id = #{stationId} and o.status <> 5 "
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
}
