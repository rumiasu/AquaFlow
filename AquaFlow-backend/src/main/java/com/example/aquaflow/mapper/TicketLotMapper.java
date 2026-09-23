package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketLot;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 水票批次（v36）。余额的真相源是 {@code Σ ticket_lot.remain_qty}。
 *
 * <p>与 {@code CustomerBarrelLotMapper} 同构 —— 桶账验证过的模型，水票照抄。</p>
 */
@Mapper
public interface TicketLotMapper {

    /**
     * 插入批次。{@code lot_no} 先填占位号（唯一、≤32 字符），拿到自增 id 后再由
     * {@link #setLotNo} 改成正式号 {@code TMyyyymmdd-000001} —— 与押金条 DP 同款做法
     * （正式号里带 id，所以只能先插后改）。
     */
    @Insert("insert into ticket_lot(lot_no, customer_id, station_id, product_id, unit_price, qty, remain_qty, "
            + "source_type, price_source, is_migrated, payment_record_id, status, operator_id, note, create_time, update_time) "
            + "values(#{lotNo}, #{customerId}, #{stationId}, #{productId}, #{unitPrice}, #{qty}, #{remainQty}, "
            + "#{sourceType}, #{priceSource}, #{isMigrated}, #{paymentRecordId}, #{status}, #{operatorId}, #{note}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketLot lot);

    @Update("update ticket_lot set lot_no = #{lotNo}, update_time = NOW() where id = #{id}")
    int setLotNo(@Param("id") Long id, @Param("lotNo") String lotNo);

    @Select("select * from ticket_lot where id = #{id}")
    TicketLot getById(@Param("id") Long id);

    /**
     * 可用于消耗的批次，按 **FIFO**（先买先扣）排序。
     *
     * <p>FIFO 是刻意的：先买的票先花掉，剩下的就是后买的批次。
     * 与押金条/桶账的核销顺序保持同一套语义，避免"退票退的是哪一批"各处理解不同。</p>
     */
    @Select("select * from ticket_lot where customer_id = #{customerId} and station_id = #{stationId} "
            + "and product_id = #{productId} and status = 1 and remain_qty > 0 order by id asc")
    List<TicketLot> listUsableFifo(@Param("customerId") Long customerId,
                                   @Param("stationId") Long stationId,
                                   @Param("productId") Long productId);

    /**
     * 扣减批次剩余张数（CAS）。
     *
     * <p>⚠️ 条件里必须带 {@code remain_qty >= #{qty}}：本仓在并发桶账上踩过
     * 「普通 SELECT 读到旧快照、两个事务双双通过」的坑（AGENTS §8.2），
     * 所以判断与扣减必须在同一条 SQL 里完成，且调用方<b>必须检查返回值</b>。</p>
     *
     * <p>⚠️ <b>{@code status} 的赋值必须排在 {@code remain_qty} 前面</b>，不要"顺手"调换顺序：
     * MySQL 的 {@code SET} 子句<b>从左到右求值</b>，后面的表达式看到的是<b>已更新后</b>的列值。
     * 写成 {@code set remain_qty = remain_qty - q, status = case when remain_qty - q = 0 ...} 时，
     * CASE 里的 {@code remain_qty} 已经是 0，算出 {@code -q}，永远不等于 0 ——
     * 于是批次扣光了状态也停在「有效」，永不置为「已退完」
     * （实测：用例断言 status=2 却拿到 1，排查时极易误判成"扣减没生效"）。</p>
     *
     * @return 受影响行数；0 = 被并发改过或余额不足，调用方应跳过该批次
     */
    @Update("update ticket_lot set status = case when remain_qty = #{qty} then 2 else status end, "
            + "remain_qty = remain_qty - #{qty}, update_time = NOW() "
            + "where id = #{id} and status = 1 and remain_qty >= #{qty}")
    int decrementRemain(@Param("id") Long id, @Param("qty") Integer qty);

    /** 批次剩余张数合计（E8 的一半） */
    @Select("select coalesce(sum(remain_qty), 0) from ticket_lot where customer_id = #{customerId} "
            + "and station_id = #{stationId} and product_id = #{productId} and status = 1")
    int sumRemain(@Param("customerId") Long customerId,
                  @Param("stationId") Long stationId,
                  @Param("productId") Long productId);

    /** 批次剩余张数的金额价值合计（E8 的另一半） */
    @Select("select coalesce(sum(remain_qty * unit_price), 0) from ticket_lot where customer_id = #{customerId} "
            + "and station_id = #{stationId} and product_id = #{productId} and status = 1")
    BigDecimal sumRightAmount(@Param("customerId") Long customerId,
                              @Param("stationId") Long stationId,
                              @Param("productId") Long productId);
}
