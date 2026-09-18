package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 站长数据看板报表查询。
 *
 * <p><b>口径约定（所有 SQL 必须遵守）：</b></p>
 * <ul>
 *   <li>水站归属一律用 {@code coalesce(settle_station_id, delivery_station_id, station_id) = #{stationId}}
 *       —— <b>结算站</b>口径（v47）：水费 + 配送费 + 楼层费归实际配送的那一站。
 *       后两级的回退是<b>防御</b>（漏写 settle 的历史/未来行不丢营收），不是常态：正常路径必须写
 *       {@code orders.settle_station_id}（写入点清单见 {@code sql/migration_v47_order_settle_station.sql}）。
 *       且 <b>stationId 只能来自登录站长</b>，绝不接受前端传入。</li>
 *   <li>时间窗为左闭右开 {@code [start, end)}，方便"今日/近7天/近30天"与上一周期对齐比较。</li>
 *   <li>营业额(grossAmount) = 未取消订单的应收合计；已收款(paidAmount) 以
 *       {@code payment_status=2} 为准；待收款(pendingAmount) = {@code payment_status=1 且未取消}。
 *       三者分开返回，前端不做二次推导。</li>
 * </ul>
 *
 * <p>⚠️ 本类只管"钱与单量"的看板口径；<b>押金 / 水票 / 桶权益的站别不在这里决定</b> ——
 * 它们一律按归属站（{@code station_id}），那是"客户买在哪个站的资产"，与营收归谁是两件事
 * （见 {@code barrelFlow} / {@code owedCustomers}：它们本来就按归属站，别顺手改成结算站）。</p>
 */
@Mapper
public interface DashboardMapper {

    String RANGE = "coalesce(settle_station_id, delivery_station_id, station_id) = #{stationId} "
            + "and create_time >= #{start} and create_time < #{end}";

    /** 周期汇总（一行） */
    @Select("select count(*) as orderCount, "
            + "sum(case when status = 4 then 1 else 0 end) as completedOrders, "
            + "sum(case when status = 5 then 1 else 0 end) as cancelledOrders, "
            + "coalesce(sum(case when status != 5 then total_amount else 0 end), 0) as grossAmount, "
            + "coalesce(sum(case when payment_status = 2 then total_amount else 0 end), 0) as paidAmount, "
            + "coalesce(sum(case when payment_status = 1 and status != 5 then total_amount else 0 end), 0) as pendingAmount, "
            + "count(distinct customer_id) as activeCustomers, "
            + "coalesce(sum(case when status != 5 then quantity else 0 end), 0) as totalQuantity "
            + "from orders where " + RANGE)
    Map<String, Object> summary(@Param("stationId") Long stationId,
                                @Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);

    /** 本周期新增客户：首次在本站下单的时间落在区间内 */
    @Select("select count(*) from ("
            + "select customer_id, min(create_time) as first_at from orders "
            + "where coalesce(settle_station_id, delivery_station_id, station_id) = #{stationId} "
            + "group by customer_id having min(create_time) >= #{start} and min(create_time) < #{end}"
            + ") t")
    int countNewCustomers(@Param("stationId") Long stationId,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);

    /** 按天趋势：单量 + 营业额 */
    @Select("select date(create_time) as dt, count(*) as cnt, "
            + "coalesce(sum(case when status != 5 then total_amount else 0 end), 0) as amount "
            + "from orders where " + RANGE + " group by date(create_time) order by dt")
    List<Map<String, Object>> dailyTrend(@Param("stationId") Long stationId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /** 下单时段分布（0-23 点），用于排班参考 */
    @Select("select hour(create_time) as hr, count(*) as cnt from orders "
            + "where " + RANGE + " group by hour(create_time) order by hr")
    List<Map<String, Object>> hourDistribution(@Param("stationId") Long stationId,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    /** 订单状态分布 */
    @Select("select status, count(*) as cnt from orders where " + RANGE + " group by status order by cnt desc")
    List<Map<String, Object>> statusDistribution(@Param("stationId") Long stationId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    /** 支付方式构成 */
    @Select("select payment_method as method, count(*) as cnt, "
            + "coalesce(sum(total_amount), 0) as amount from orders "
            + "where " + RANGE + " and status != 5 group by payment_method order by cnt desc")
    List<Map<String, Object>> payMethodDistribution(@Param("stationId") Long stationId,
                                                    @Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);

    /** 热销商品 TOP N（按销量） */
    @Select("select oi.product_id as productId, oi.product_name_snapshot as productName, "
            + "sum(oi.quantity) as qty, coalesce(sum(oi.subtotal), 0) as revenue "
            + "from order_item oi join orders o on o.id = oi.order_id "
            + "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "and o.create_time >= #{start} and o.create_time < #{end} and o.status != 5 "
            + "group by oi.product_id, oi.product_name_snapshot order by qty desc limit #{limit}")
    List<Map<String, Object>> topProducts(@Param("stationId") Long stationId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("limit") int limit);

    /** 客户消费排行 TOP N */
    @Select("select o.customer_id as customerId, c.name as customerName, count(*) as orders, "
            + "coalesce(sum(o.total_amount), 0) as amount "
            + "from orders o left join customer c on c.id = o.customer_id "
            + "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "and o.create_time >= #{start} and o.create_time < #{end} and o.status != 5 "
            + "group by o.customer_id, c.name order by amount desc limit #{limit}")
    List<Map<String, Object>> topCustomers(@Param("stationId") Long stationId,
                                           @Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("limit") int limit);

    /** 配送员业绩 */
    @Select("select o.delivery_staff_id as staffId, s.name as staffName, count(*) as totalOrders, "
            + "sum(case when o.status = 4 then 1 else 0 end) as completedOrders, "
            + "coalesce(sum(case when o.status != 5 then o.total_amount else 0 end), 0) as amount "
            + "from orders o left join staff s on s.id = o.delivery_staff_id "
            + "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "and o.create_time >= #{start} and o.create_time < #{end} "
            + "and o.delivery_staff_id is not null "
            + "group by o.delivery_staff_id, s.name order by totalOrders desc")
    List<Map<String, Object>> staffPerformance(@Param("stationId") Long stationId,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    /** 桶流转：type=1 新增押金桶 / type=2 退桶 */
    @Select("select type, coalesce(sum(quantity), 0) as qty from barrel_record "
            + "where station_id = #{stationId} and type in (1, 2) "
            + "and create_time >= #{start} and create_time < #{end} group by type")
    List<Map<String, Object>> barrelFlow(@Param("stationId") Long stationId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * 当前欠桶客户 TOP N（瞬时值，用于提醒催收）。
     * 欠桶改为按商品记录后需先按客户汇总 Σ max(0, over_qty)：
     * over 可为负（多还桶/水站暂存，合法状态），不能拿负值抵销其他商品的欠桶。
     */
    @Select("select o.customer_id as customerId, c.name as customerName, "
            + "sum(greatest(o.over_qty, 0)) as owedQty "
            + "from customer_barrel_over o left join customer c on c.id = o.customer_id "
            + "where o.station_id = #{stationId} "
            + "group by o.customer_id, c.name "
            + "having owedQty > 0 order by owedQty desc limit #{limit}")
    List<Map<String, Object>> owedCustomers(@Param("stationId") Long stationId,
                                            @Param("limit") int limit);
}
