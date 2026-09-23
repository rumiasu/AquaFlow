package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BarrelRecordMapper {

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

    /**
     * 无条件的通用状态更新。
     * ⚠️ 只允许用于「不需要前置状态保证」的场景（如纯还桶留痕、数据修复）。
     * 审批流转一律用下面三个 CAS 方法，避免并发下把已处理的单子改回去。
     */
    @Update("update barrel_record set status = #{status}, handle_note = #{handleNote} where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("handleNote") String handleNote);

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
     * 2 → 3 已退押金。
     * <p>必须已经确认收桶（status=2）才能退钱，杜绝"桶没收到就先退钱"。
     * 同时把 {@code deposit_refund} 刷成<b>实际核销出来的金额</b>——
     * 申请单上那个数是客户申请时按当时批次算的估值，实际退款以核销为准。</p>
     */
    @Update("update barrel_record set status = 3, handle_note = #{handleNote}, " +
            "deposit_refund = #{refund} where id = #{id} and status = 2")
    int finishRefund(@Param("id") Long id, @Param("handleNote") String handleNote,
                     @Param("refund") java.math.BigDecimal refund);

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

    @Select("select * from barrel_record where station_id = #{stationId} order by create_time desc limit #{limit}")
    List<BarrelRecord> listByStationId(@Param("stationId") Long stationId, @Param("limit") int limit);
}
