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
            + "adjustment_id, item_id, item_name, note, create_time) "
            + "values(#{stationId}, #{staffId}, #{orderId}, #{kind}, IFNULL(#{productId},0), #{qty}, #{unitAmount}, #{amount}, "
            + "#{adjustmentId}, #{itemId}, #{itemName}, #{note}, NOW())")
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

    // ===== 以下三个是「我的工资」自助查询（2026-09-18）=====
    // ⚠️ 它们**只按 staff_id**、刻意不带 station_id，与上面的站长查询正好相反：
    //    station_id 记的是**履约站**（跨站外派时与配送员所属站可能不同），而"我的工资"是
    //    个人的跨站累计口径 —— 带上站点过滤会让外派挣的那部分工钱凭空消失。
    //    站长的结算查询则必须锁本站（他只能结自己站的账），所以两者不能合并成一个方法。

    /** 我的收益明细（某期间，按时间倒序） */
    @Select("select * from staff_earning where staff_id = #{staffId} "
            + "and create_time >= #{start} and create_time < #{endExclusive} order by id desc")
    List<StaffEarning> listByStaff(@Param("staffId") Long staffId,
                                   @Param("start") java.time.LocalDateTime start,
                                   @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /** 我的期间收益合计 */
    @Select("select coalesce(sum(amount), 0) from staff_earning where staff_id = #{staffId} "
            + "and create_time >= #{start} and create_time < #{endExclusive}")
    BigDecimal sumByStaff(@Param("staffId") Long staffId,
                          @Param("start") java.time.LocalDateTime start,
                          @Param("endExclusive") java.time.LocalDateTime endExclusive);

    /** 我还没被任何结算单算进去的工钱合计（配送员最关心的那个数） */
    @Select("select coalesce(sum(amount), 0) from staff_earning where staff_id = #{staffId} "
            + "and payroll_id is null")
    BigDecimal sumUnsettledByStaff(@Param("staffId") Long staffId);

    /**
     * 该配送员在本站有没有履约痕迹（人工调整的归属判据之一）。
     *
     * <p>为什么不能只看 {@code staff.station_id}：跨站外派时人属于 B 站、给 A 站跑腿，
     * A 站站长要给他记一笔工钱完全正常。判据是「本站员工 <b>或</b> 在本站有过收益」的并集
     * —— 与客户归属用的 {@code countCustomerOfStation} 同一形状。</p>
     */
    @Select("select count(*) from staff_earning where station_id = #{stationId} and staff_id = #{staffId}")
    int countByStationAndStaff(@Param("stationId") Long stationId, @Param("staffId") Long staffId);

    // ===== 按自定义条目汇总（v44）=====
    // ⚠️ 只统计 item_id 非空的流水：自由文本的人工调整没有条目，进不了按条目汇总，
    //    它照旧体现在「未结合计 / 明细列表」里 —— 汇总不是合计，别让两者看起来互相矛盾。

    /** 某张结算单的按条目汇总 */
    @Select("select e.item_id as itemId, coalesce(i.name, e.item_name) as name, i.direction as direction, "
            + "coalesce(sum(e.amount), 0) as total "
            + "from staff_earning e left join staff_earning_item i on i.id = e.item_id "
            + "where e.payroll_id = #{payrollId} and e.item_id is not null "
            + "group by e.item_id, coalesce(i.name, e.item_name), i.direction "
            + "order by i.direction asc, name asc")
    List<com.example.aquaflow.dto.EarningItemSummaryVO> sumByItemForPayroll(@Param("payrollId") Long payrollId);

    /** 某人未结算部分的按条目汇总（与明细列表同一筛选口径） */
    @Select("select e.item_id as itemId, coalesce(i.name, e.item_name) as name, i.direction as direction, "
            + "coalesce(sum(e.amount), 0) as total "
            + "from staff_earning e left join staff_earning_item i on i.id = e.item_id "
            + "where e.station_id = #{stationId} and e.staff_id = #{staffId} "
            + "and e.payroll_id is null and e.item_id is not null "
            + "group by e.item_id, coalesce(i.name, e.item_name), i.direction "
            + "order by i.direction asc, name asc")
    List<com.example.aquaflow.dto.EarningItemSummaryVO> sumByItemUnsettled(@Param("stationId") Long stationId,
                                                                          @Param("staffId") Long staffId);
}
