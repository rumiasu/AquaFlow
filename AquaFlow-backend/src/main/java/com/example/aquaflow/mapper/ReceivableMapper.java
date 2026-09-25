package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 应收账款（2026-09-17，规格见 {@code docs/design/20} §1）。
 *
 * <p><b>金额的真相源仍是 {@code orders.payment_status}</b>，本类不新造口径：
 * 「待收款」= {@code payment_status = 1 AND status != 5}，与 {@code DashboardMapper}
 * 的 pendingAmount 逐字同源（AGENTS §1：支付状态只前进、不倒滚；谁改了那个口径，
 * 站长的待收款合计就会少一块）。应收账款实质是<b>给待收款加上账期维度</b>，
 * 所以这里只做「多一个 due_date / settlement_status 的读」，不改钱的判定。</p>
 *
 * <p>⚠️ {@code settlement_status} 只在<b>挂账单</b>（{@code due_date IS NOT NULL}）上有意义：
 * 即时结清的单不参与核销流程。判别式就是 {@code due_date IS NOT NULL}，
 * 所以下面的逾期/挂账统计全部带这个条件 —— 少了它，历史即时单会被算成"逾期挂账"。</p>
 *
 * <p><b>站别口径（v47，2026-09-18）：一律按「结算站」
 * {@code coalesce(settle_station_id, delivery_station_id, station_id)}。</b>
 * 三个查询（客户汇总 / 明细 / 核销前校验）必须<b>同口径</b>：
 * 否则会出现本类最坏的一种组合 —— <b>列表看得到、核销却报"订单不存在或不属于本水站"</b>
 * （跨站外派单：履约站看得见应收，却销不掉）。原先这三条里两条按 {@code station_id}、
 * 看板那条按两级 coalesce，同一个"待收款"在首页与台账是<b>两个订单集合</b>。</p>
 */
@Mapper
public interface ReceivableMapper {

    /**
     * 按客户汇总本站的待收款（含账龄与逾期）。
     *
     * <p>排序刻意是「先看逾期金额」：站长打开页面第一眼该看到的是"谁欠得最久"，
     * 而不是"谁欠得最多"。</p>
     *
     * <p>⚠️ {@code dueDays} 是<b>该客户在</b>{@code stationId} <b>这个站的账期</b>
     * （{@code customer_station_config.due_days}，v60 起账期是**站级**的），
     * 与订单级的 {@code earliestDueDate} 不是一回事：前者是"以后按多少天月结"，
     * 后者是"已有订单里最早哪天到期"。台账页两个都要显示 ——
     * 少了 {@code dueDays}，页面会把已设账期的客户一律显示成"未设账期（即时结清）"，
     * 而那种错误不会报错，只会让站长按错的方式报价。</p>
     *
     * <p>[v60] 原实现读的是客户级的 {@code company_info.due_days}。账期改站级之后
     * **必须一起改这里** —— 否则台账页会永远显示"未设账期"，而真正的账期在配置表里躺着。
     * 这正是 AGENTS.md 那条"废弃一列前先全仓 grep 它的读取点"要防的事。</p>
     */
    @Select("select o.customer_id as customerId, c.name as customerName, "
            + "count(*) as orderCount, "
            + "coalesce(sum(o.total_amount), 0) as outstandingAmount, "
            + "sum(case when o.due_date is not null then 1 else 0 end) as creditOrderCount, "
            + "min(o.due_date) as earliestDueDate, "
            + "max(csc.due_days) as dueDays, "
            + "coalesce(sum(case when o.due_date is not null and o.due_date < curdate() "
            + "                  then o.total_amount else 0 end), 0) as overdueAmount, "
            + "coalesce(sum(case when o.due_date is not null and o.due_date < curdate() "
            + "                  then 1 else 0 end), 0) as overdueOrderCount, "
            + "coalesce(max(case when o.due_date is not null and o.due_date < curdate() "
            + "                  then datediff(curdate(), o.due_date) else 0 end), 0) as maxOverdueDays "
            + "from orders o join customer c on c.id = o.customer_id "
            + "left join customer_station_config csc on csc.customer_id = o.customer_id "
            + "     and csc.station_id = #{stationId} "
            + "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "and o.payment_status = 1 and o.status <> 5 "
            + "group by o.customer_id, c.name "
            + "order by overdueAmount desc, outstandingAmount desc, o.customer_id asc")
    List<Map<String, Object>> listByCustomer(@Param("stationId") Long stationId);

    /**
     * 待收款明细（可按客户 / 只看逾期过滤）。
     *
     * <p>{@code order by o.due_date is null, o.due_date asc} = 有账期的排前面且按到期日升序
     * （最急的在最上），没账期的沉底。MySQL 里 {@code IS NULL} 返回 0/1，所以这样排序是稳的。</p>
     */
    @Select("<script>"
            + "select o.id as orderId, o.customer_id as customerId, o.create_time as createTime, "
            + "       o.total_amount as totalAmount, o.payment_method as paymentMethod, "
            + "       o.payment_status as paymentStatus, o.settlement_status as settlementStatus, "
            + "       o.due_date as dueDate, o.status as status, o.source as source, "
            + "       case when o.due_date is not null and o.due_date &lt; curdate() "
            + "            then datediff(curdate(), o.due_date) else 0 end as overdueDays "
            + "from orders o "
            + "where coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} "
            + "and o.payment_status = 1 and o.status &lt;&gt; 5 "
            + "<if test='customerId != null'> and o.customer_id = #{customerId} </if>"
            + "<if test='onlyOverdue'> and o.due_date is not null and o.due_date &lt; curdate() </if>"
            + "order by o.due_date is null, o.due_date asc, o.create_time asc"
            + "</script>")
    List<Map<String, Object>> listOrders(@Param("stationId") Long stationId,
                                         @Param("customerId") Long customerId,
                                         @Param("onlyOverdue") boolean onlyOverdue);

    /**
     * 核销前的逐单校验读（站别由 SQL 强制，不接受调用方传进来的归属）。
     *
     * <p>⚠️ 站别判据必须与上面两条列表查询<b>逐字同口径</b>（v47 结算站）：
     * 列表按结算站、校验按归属站的话，跨站外派单会"看得见却销不掉"，
     * 而错误文案是"订单不存在或不属于本水站"—— 站长只会以为是自己点错了。</p>
     *
     * @return 不属于本站 / 不存在时为 null —— 调用方必须据此报错，不能当成功
     */
    @Select("select id, customer_id as customerId, status, payment_status as paymentStatus, "
            + "       settlement_status as settlementStatus, total_amount as totalAmount, due_date as dueDate "
            + "from orders where id = #{orderId} "
            + "  and coalesce(settle_station_id, delivery_station_id, station_id) = #{stationId}")
    Map<String, Object> getForSettle(@Param("orderId") Long orderId, @Param("stationId") Long stationId);
}
