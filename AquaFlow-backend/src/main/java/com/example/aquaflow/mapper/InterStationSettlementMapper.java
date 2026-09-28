package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.InterStationSettlement;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 站间结算台账 Mapper（v67）。正本口径见 {@code docs/design/31-站间结算算例-水票计价-决策件.md}。
 *
 * <p><b>两类 SQL，务必分清</b>：</p>
 * <ol>
 *   <li>{@link #listLiveCrossStationOrders} —— <b>实时算</b>（只读、不落库）：
 *       "钱在谁手上 ≠ 营收算谁"的跨站单，以及它按三种 {@code basis} 各该结多少。
 *       <b>欠多少的真相源在这里</b>，不在表里 —— 这样它永远与订单/支付流水一致，
 *       不需要在"收款成功""换站""取消"等 6 个写入点挂钩子（漏一个就静默少一笔）。</li>
 *   <li>其余方法 —— <b>台账表本身</b>：只有两种人工动作会落行
 *       （站长改价 {@code repricingIfPending}、站长登记结清 {@code settleIfPending}），
 *       外加订单取消后的冲销 {@code reverse}。</li>
 * </ol>
 *
 * <p>⚠️ <b>"钱在谁手上"取的是 {@code payment_record} 里 {@code status = 2 已支付} 那条的站别</b>
 * （payment_record.status：1 待支付 / 2 已支付 / 3 已退款 / 4 已取消，见 {@code sql/schema.sql}）：
 * · 退款冲正流水是**负金额 + 已退款(3)**，天然被排除；
 * · 一单一条活跃流水的约束由 {@code uk_payment_active_order} 保证，但**不覆盖**"已退款后再收一次"，
 *   故这里仍用 {@code max(station_id)} 分组收敛成一行（实测真实库"一单多个 PAID 流水站" = 0 条；
 *   真出现时取站点 id 较大的那个是**确定性**的，不会一查一个样）。</p>
 */
@Mapper
public interface InterStationSettlementMapper {

    /**
     * 跨站单台账（实时算）。两个参数都可为空：
     *
     * @param stationId 只看与本站相关的（钱在它手上 <b>或</b> 营收算给它）。传 null = 全平台（仅测试/对账用）
     * @param orderId   只看某一单（登记结清/改价前取数用）
     */
    @Select("<script>"
            + "select o.id                                                                   as orderId, "
            + "       o.station_id                                                           as ownerStationId, "
            + "       o.payment_method                                                       as paymentMethod, "
            + "       ps.station_id                                                          as payStationId, "
            + "       coalesce(o.settle_station_id, o.delivery_station_id, o.station_id)     as settleStationId, "
            + "       (o.water_amount + o.delivery_fee + o.floor_fee)                        as revenueAmount, "
            + "       o.water_amount                                                         as waterAmount, "
            + "       (o.delivery_fee + o.floor_fee)                                         as coveredFeeAmount, "
            + "       coalesce((select sum(tr.decrease_qty) from ticket_record tr "
            + "                  where tr.order_id = o.id and tr.decrease_qty > 0), 0)      as ticketQty, "
            + "       coalesce((select sum(tr.decrease_qty * tr.unit_price) from ticket_record tr "
            + "                  where tr.order_id = o.id and tr.decrease_qty > 0), 0)      as ticketActualAmount, "
            + "       st.id                                                                  as settleRowId, "
            + "       st.status                                                              as settleStatus, "
            + "       st.basis                                                               as settleBasis, "
            + "       st.amount                                                              as settleAmount, "
            + "       st.ticket_qty                                                          as settleTicketQty, "
            + "       st.unit_price                                                          as settleUnitPrice, "
            + "       st.fee_amount                                                          as settleFeeAmount, "
            + "       st.settled_time                                                        as settledTime, "
            + "       st.settle_note                                                         as settleNote "
            + "from orders o "
            + "join (select order_id, max(station_id) as station_id from payment_record "
            + "       where status = 2 and order_id is not null group by order_id) ps on ps.order_id = o.id "
            + "left join inter_station_settlement st on st.order_id = o.id "
            + "where o.status != 5 "
            + "  and o.payment_status = 2 "
            + "  and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) != ps.station_id "
            + "<if test='orderId != null'> and o.id = #{orderId} </if>"
            + "<if test='stationId != null'>"
            + "  and (ps.station_id = #{stationId} "
            + "       or coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId}) "
            + "</if>"
            + "order by o.id desc"
            + "</script>")
    List<Map<String, Object>> listLiveCrossStationOrders(@Param("stationId") Long stationId,
                                                         @Param("orderId") Long orderId);

    @Select("select * from inter_station_settlement where order_id = #{orderId}")
    InterStationSettlement findByOrderId(@Param("orderId") Long orderId);

    @Insert("insert into inter_station_settlement "
            + "(order_id, from_station_id, to_station_id, amount, basis, ticket_qty, unit_price, "
            + " fee_amount, status, snapshot_time, settled_time, settled_by, settle_note, create_time, update_time) "
            + "values (#{orderId}, #{fromStationId}, #{toStationId}, #{amount}, #{basis}, #{ticketQty}, #{unitPrice}, "
            + " #{feeAmount}, #{status}, #{snapshotTime}, #{settledTime}, #{settledBy}, #{settleNote}, now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InterStationSettlement row);

    /**
     * 登记结清（CAS：只在"待结清"时生效）。
     *
     * <p>⚠️ 必须带 expected-state：无门槛的 UPDATE 会把**已冲销**的行也改成已结清
     * （订单取消后那笔应付已经不成立），同 AGENTS §6「所有状态改写必须 CAS 并检查受影响行数」。
     * 返回 0 行 = 已经被别人改过 / 已冲销，调用方要如实报错，不要静默当成成功。</p>
     */
    @Update("update inter_station_settlement "
            + "set status = 2, settled_time = now(), settled_by = #{by}, settle_note = #{note}, update_time = now() "
            + "where order_id = #{orderId} and status = 1")
    int settleIfPending(@Param("orderId") Long orderId, @Param("by") Long by, @Param("note") String note);

    /**
     * 改价（CAS：只在"待结清"时生效）—— 卖票站站长把默认的"折算实付"改成"按挂牌价"。
     *
     * <p>改价后**必须**落一行快照：否则这个单下次实时算又变回实付价（改了个寂寞）。</p>
     */
    @Update("update inter_station_settlement "
            + "set basis = #{basis}, amount = #{amount}, ticket_qty = #{ticketQty}, unit_price = #{unitPrice}, "
            + "    fee_amount = #{feeAmount}, snapshot_time = now(), update_time = now() "
            + "where order_id = #{orderId} and status = 1")
    int repricingIfPending(@Param("orderId") Long orderId, @Param("basis") Integer basis,
                           @Param("amount") java.math.BigDecimal amount,
                           @Param("ticketQty") Integer ticketQty,
                           @Param("unitPrice") java.math.BigDecimal unitPrice,
                           @Param("feeAmount") java.math.BigDecimal feeAmount);

    /**
     * 冲销（订单取消/退款后那笔应付不再成立）。⚠️ **改状态、不删行**（唯一键建在 order_id 上，一单一笔）。
     * <p>COS 语义：{@code status != 3} 是幂等的（重复冲销返回 0 行，不报错也不重复改）。</p>
     */
    @Update("update inter_station_settlement set status = 3, update_time = now() "
            + "where order_id = #{orderId} and status != 3")
    int reverse(@Param("orderId") Long orderId);

    /**
     * <b>已登记结清、但那笔应付的依据已经没了</b>的台账行（订单被取消 / 已退款）——
     * 需要人工冲销的那一批。
     *
     * <p>⚠️ <b>为什么单独查</b>：{@link #listLiveCrossStationOrders} 的 WHERE 带
     * {@code o.status != 5 and o.payment_status = 2}，所以订单一旦取消/退款，那一行就**从台账里消失**，
     * 站长既看不到"我结过一笔、现在不该结了"，也没有入口去冲销 ——
     * 而冲销正是为这种情形设计的（{@code docs/design/31} §4 第 3 条）。</p>
     *
     * <p>判据刻意用「订单状态或支付状态」而不是"能不能对上 live"：后者要把 live 的复杂 WHERE
     * 抄第二遍（两处必然分叉）；这里只问一句"这单还算不算数"。</p>
     */
    @Select("select st.* from inter_station_settlement st join orders o on o.id = st.order_id "
            + "where st.status = 2 "
            + "and (st.from_station_id = #{stationId} or st.to_station_id = #{stationId}) "
            + "and (o.status = 5 or o.payment_status is null or o.payment_status <> 2) "
            + "order by st.id desc limit 200")
    List<InterStationSettlement> listSettledButVoid(@Param("stationId") Long stationId);

    /** 站名（列表里要显示"谁欠我 / 我欠谁"）—— 只读 station 表，不 join 别的东西。 */
    @Select("<script>select id, name from station where id in "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Map<String, Object>> stationNames(@Param("ids") List<Long> ids);
}
