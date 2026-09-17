package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffEarning;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配送员收益明细（v37）。
 *
 * <p>⚠️ {@code auto_uk} 是<b>生成列</b>，<b>绝不出现在 INSERT 的列清单里</b>
 * —— 往生成列写值 MySQL 直接报错。它的作用是给自动收益做幂等兜底
 * （{@code uk_earning_auto}），不需要调用方关心。</p>
 */
@Mapper
public interface StaffEarningMapper {

    /**
     * 写一条收益明细。
     *
     * <p>{@code amount} 由服务层按 kind 决定符号后传入（扣减类为负数）。</p>
     */
    @Insert("insert into staff_earning(station_id, staff_id, order_id, kind, product_id, qty, unit_amount, amount, "
            + "adjustment_id, note, create_time) "
            + "values(#{stationId}, #{staffId}, #{orderId}, #{kind}, IFNULL(#{productId},0), #{qty}, #{unitAmount}, #{amount}, "
            + "#{adjustmentId}, #{note}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StaffEarning earning);

    @Select("select * from staff_earning where id = #{id}")
    StaffEarning getById(@Param("id") Long id);

    /** 某订单已产生的收益（排查"这单的工钱算在哪"） */
    @Select("select * from staff_earning where order_id = #{orderId} order by id asc")
    List<StaffEarning> listByOrderId(@Param("orderId") Long orderId);

    /** 结算单下的明细 */
    @Select("select * from staff_earning where payroll_id = #{payrollId} order by id asc")
    List<StaffEarning> listByPayroll(@Param("payrollId") Long payrollId);

    /** 某人在某站、某期间内**未结算**的明细（生成结算单时用） */
    @Select("select * from staff_earning where station_id = #{stationId} and staff_id = #{staffId} "
            + "and payroll_id is null and create_time >= #{start} and create_time < #{endExclusive} "
            + "order by id asc")
    List<StaffEarning> listUnsettled(@Param("stationId") Long stationId,
                                     @Param("staffId") Long staffId,
                                     @Param("start") java.time.LocalDateTime start,
                                     @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /**
     * 把某期间内未结算的明细挂到结算单上。
     *
     * <p>⚠️ 时间上界必须是 <b>{@code < 结束日 + 1 天}</b>，不要写 {@code <= 结束日}：
     * {@code endDate} 是 {@code LocalDate}（当天）时，{@code <= 当天} 在 SQL 里等价于
     * {@code <= 当天 00:00:00}，<b>当天的收益一条都结算不到</b>
     * （AGENTS §8.19 记录过同一个坑：站长看板永远停在昨天）。</p>
     *
     * @return 受影响行数（调用方据此算本期合计，必须校验）
     */
    @Update("update staff_earning set payroll_id = #{payrollId} "
            + "where station_id = #{stationId} and staff_id = #{staffId} "
            + "and payroll_id is null and create_time >= #{start} and create_time < #{endExclusive}")
    int attachToPayroll(@Param("payrollId") Long payrollId,
                        @Param("stationId") Long stationId,
                        @Param("staffId") Long staffId,
                        @Param("start") java.time.LocalDateTime start,
                        @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /** 结算单明细金额合计（对账 E-PAY 的一半） */
    @Select("select coalesce(sum(amount), 0) from staff_earning where payroll_id = #{payrollId}")
    BigDecimal sumByPayroll(@Param("payrollId") Long payrollId);

    /** 某人未结算收益合计（前端提示"还有多少没结"） */
    @Select("select coalesce(sum(amount), 0) from staff_earning where station_id = #{stationId} "
            + "and staff_id = #{staffId} and payroll_id is null")
    BigDecimal sumUnsettled(@Param("stationId") Long stationId, @Param("staffId") Long staffId);
}
