package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BarrelRecordMapper {
    /** 独立申请固定批次后写入试算金额，审批后不得重新报价。 */
    @Update("update barrel_record set deposit_refund=#{amount} where id=#{id} and status=1")
    int setPendingRefund(@Param("id") Long id,@Param("amount") java.math.BigDecimal amount);

    @Insert("insert into barrel_record(customer_id, station_id, product_id, type, quantity, related_order_id, note, operator_id, create_time, status, handle_note, deposit_refund, client_token, over_before, over_after, delivered_qty, returned_qty, adjustment_id) " +
            // delivered_qty / returned_qty 只有 type=8（配送收发明细）才有业务值，
            // 其余类型不设置 → MyBatis 传 null，而这两列是 NOT NULL，直接插 null 会报
            // "Column 'delivered_qty' cannot be null"（DEFAULT 只在省略该列时生效）。
            // 所以这里显式兜底成 0。
            "values(#{customerId}, #{stationId}, #{productId}, #{type}, #{quantity}, #{relatedOrderId}, #{note}, #{operatorId}, #{createTime}, #{status}, #{handleNote}, #{depositRefund}, #{clientToken}, #{overBefore}, #{overAfter}, COALESCE(#{deliveredQty}, 0), COALESCE(#{returnedQty}, 0), #{adjustmentId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BarrelRecord barrelRecord);

    @Select("select * from barrel_record where id = #{id}")
    BarrelRecord getById(@Param("id") Long id);

    // [2026-09-30 F-19] 已删除无守卫的 updateStatus(id, status, handleNote)：全仓零调用。
    // 原注释要求"只用于不需要前置状态保证的场景"，但那种场景**从来没有出现过** ——
    // 实际流转全部走下面的 CAS 三件套（confirmReceived / finishRefund / reject）。
    // 留着它等于给下一个人一条现成的无守卫旁路（无 expected-state 的 updateStatus 属待清除旧路径，AGENTS §6）。

    // =========================================================================
    // 退桶状态机 1 → 2 → 3（DEF-7）：全部走 CAS，affected==0 代表状态已被别人改过
    // =========================================================================

    /**
     * 1 → 2 确认收到空桶。
     * <p>记录确认人与确认时间：退押金前必须有人为"桶确实收回来了"负责，
     * 否则出现"钱退了、桶没收到"时无从追溯。</p>
     */
    @Update("update barrel_record set status = 2, handle_note = #{handleNote}, " +
            "confirmed_by = #{operatorId}, confirmed_time = now() " +
            "where id = #{id} and status = 1")
    int confirmReceived(@Param("id") Long id, @Param("operatorId") Long operatorId,
                        @Param("handleNote") String handleNote);

    /**
     * 2 → 3 <b>退押金并当面交付</b>（两件事同一次点击，见 {@code docs/design/35} §7.2）。
     * <p>必须已经确认收桶（status=2）才能退钱，杜绝"桶没收到就先退钱"。
     * 同时把 {@code deposit_refund} 刷成<b>实际核销出来的金额</b>——
     * 申请单上那个数是客户申请时按当时批次算的估值，实际退款以核销为准。</p>
     *
     * <p>⚠️ <b>{@code refund_paid_time} / {@code refund_paid_by}（v66）必须与 status=3 在同一条
     * UPDATE 里写</b>：产品口径是"不现场给钱的不要退"，`status=3` 而交付时间为 NULL
     * 是<b>违规数据</b>。拆成两条语句（先置 3、再单独 markRefundPaid）会重新打开
     * "先核销、钱以后再给"的中间态 —— 那正是本次要消灭的情形。</p>
     *
     * @param paidBy 把押金交到顾客手上的人（staff.id）；可以≠核销人
     */
    @Update("update barrel_record set status = 3, handle_note = #{handleNote}, " +
            "deposit_refund = #{refund}, refund_paid_time = now(), refund_paid_by = #{paidBy} " +
            "where id = #{id} and status = 2")
    int finishRefund(@Param("id") Long id, @Param("handleNote") String handleNote,
                     @Param("refund") java.math.BigDecimal refund, @Param("paidBy") Long paidBy);

    /**
     * 交付确认（v66）：只补"押金已交到顾客手上"这一事实，<b>不动金额、不动状态</b>。
     *
     * <p>CAS 三条件缺一不可：{@code status = 3}（没核销就谈不上交付）、
     * {@code refund_paid_time is null}（已登记过就不覆盖 —— 幂等的实现点）、
     * {@code station_id}（跨站防线第二道，与控制器那道重复是故意的）。</p>
     *
     * <p>受影响 0 行<b>必须</b>由调用方分辨原因（已登记 = 幂等成功；非 3 或非本站 = 拒绝），
     * 不能一律当成功（AGENTS §8.20：拿不到行数就别返回 success）。</p>
     *
     * @return 受影响行数：1 = 本次写入；0 = 状态不符 / 已登记过 / 跨站
     */
    @Update("update barrel_record set refund_paid_time = now(), refund_paid_by = #{paidBy} " +
            "where id = #{id} and station_id = #{stationId} and status = 3 and refund_paid_time is null")
    int markRefundPaid(@Param("id") Long id, @Param("stationId") Long stationId,
                       @Param("paidBy") Long paidBy);

    // =========================================================================
    // 「已核销未交付」= 违规数据的只读查询（v66 / docs/design/35 §7.2）
    //
    // ⚠️ 三条 SQL 都必须带 `type = 2`：type=7（纯还桶）与 type=8（配送收发）也把
    //    status 写成 3，那是写入方留的处理标记、**不是**"已退押金"
    //    （判据同 BarrelRecord.getStatusText 为什么只对 type=2 下发文案）。
    //    漏了 type 条件会把正常流水全算成"没给钱就核销"，计数立刻失去意义。
    // =========================================================================

    /** 本站"已核销未交付"笔数（违规数据；历史存量单必然命中，那是事实不是错误）。 */
    @Select("select count(*) from barrel_record where station_id = #{stationId} " +
            "and type = 2 and status = 3 and refund_paid_time is null")
    int countRefundUndelivered(@Param("stationId") Long stationId);

    /** 见 {@link #countRefundUndelivered}：同口径的金额合计（站长要看到"压着多少钱"）。 */
    @Select("select coalesce(sum(deposit_refund), 0) from barrel_record where station_id = #{stationId} " +
            "and type = 2 and status = 3 and refund_paid_time is null")
    java.math.BigDecimal sumRefundUndelivered(@Param("stationId") Long stationId);

    /** 见 {@link #countRefundUndelivered}：违规明细（只读展示，供站长逐笔补登记交付）。 */
    @Select("select * from barrel_record where station_id = #{stationId} " +
            "and type = 2 and status = 3 and refund_paid_time is null order by id desc limit #{limit}")
    List<BarrelRecord> listRefundUndelivered(@Param("stationId") Long stationId, @Param("limit") int limit);

    /** 1 或 2 → 4 驳回（已退押金的 3 不允许再驳回） */
    @Update("update barrel_record set status = 4, handle_note = #{handleNote} " +
            "where id = #{id} and status in (1, 2)")
    int reject(@Param("id") Long id, @Param("handleNote") String handleNote);

    /** 按幂等 token 查记录（NULL 不参与唯一索引，所以这里只查非空 token） */
    @Select("select * from barrel_record where client_token = #{token} limit 1")
    BarrelRecord getByClientToken(@Param("token") String token);

    @Select("select * from barrel_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<BarrelRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from barrel_record where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId} order by create_time desc")
    List<BarrelRecord> listByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId, @Param("stationId") Long stationId);

    /** 订单详情只汇总该订单、客户和桶权益归属站的配送凭据，避免跨单/跨站串数。 */
    @Select("select delivered_qty as deliveredQty, returned_qty as returnedQty from barrel_record " +
            "where related_order_id = #{orderId} and customer_id = #{customerId} and station_id = #{stationId} " +
            "and type = 8 order by id")
    List<BarrelRecord> listDeliveryByOrder(@Param("orderId") Long orderId,
                                           @Param("customerId") Long customerId,
                                           @Param("stationId") Long stationId);

    @Select("select * from barrel_record where station_id = #{stationId} order by create_time desc limit #{limit}")
    List<BarrelRecord> listByStationId(@Param("stationId") Long stationId, @Param("limit") int limit);
}
