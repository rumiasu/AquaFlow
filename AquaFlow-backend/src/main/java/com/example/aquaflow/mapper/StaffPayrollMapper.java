package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffPayroll;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 配送员工资结算单（v37）。
 *
 * <p>状态改写一律走 CAS（{@link #updateStatusIf}）并检查受影响行数 ——
 * 这是本仓对状态字段的硬约定（AGENTS §6）。</p>
 */
@Mapper
public interface StaffPayrollMapper {

    @Insert("insert into staff_payroll(payroll_no, station_id, staff_id, period_start, period_end, "
            + "total_amount, status, operator_id, note, create_time, update_time) "
            + "values(#{payrollNo}, #{stationId}, #{staffId}, #{periodStart}, #{periodEnd}, "
            + "IFNULL(#{totalAmount},0.00), #{status}, #{operatorId}, #{note}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StaffPayroll payroll);

    /** 单据号要在拿到自增 id 之后生成（与押金条 lot_no 同款做法） */
    @Update("update staff_payroll set payroll_no = #{payrollNo}, update_time = NOW() where id = #{id}")
    int setPayrollNo(@Param("id") Long id, @Param("payrollNo") String payrollNo);

    @Select("select * from staff_payroll where id = #{id}")
    StaffPayroll getById(@Param("id") Long id);

    @Select("select * from staff_payroll where station_id = #{stationId} order by id desc limit #{limit}")
    List<StaffPayroll> listByStation(@Param("stationId") Long stationId, @Param("limit") int limit);

    /**
     * 「我的工资」自助查询：只按 staff_id（理由同 {@code StaffEarningMapper.listByStaff} ——
     * 工资是按人累计的，不是按站）。
     */
    @Select("select * from staff_payroll where staff_id = #{staffId} order by id desc limit #{limit}")
    List<StaffPayroll> listByStaff(@Param("staffId") Long staffId, @Param("limit") int limit);

    /** 本站某状态的结算单张数（站长首页「待办聚合」用：草稿 = 待确认） */
    @Select("select count(*) from staff_payroll where station_id = #{stationId} and status = #{status}")
    int countByStatus(@Param("stationId") Long stationId, @Param("status") Integer status);

    @Select("select * from staff_payroll where station_id = #{stationId} and staff_id = #{staffId} "
            + "and period_start = #{periodStart} and period_end = #{periodEnd}")
    StaffPayroll getByPeriod(@Param("stationId") Long stationId, @Param("staffId") Long staffId,
                             @Param("periodStart") java.time.LocalDate periodStart,
                             @Param("periodEnd") java.time.LocalDate periodEnd);

    /** 同步本期合计（挂完明细后调用；派生值，真相源是 staff_earning） */
    @Update("update staff_payroll set total_amount = #{totalAmount}, update_time = NOW() where id = #{id}")
    int setTotalAmount(@Param("id") Long id, @Param("totalAmount") BigDecimal totalAmount);

    /**
     * 状态 CAS。
     *
     * @param status       目标状态
     * @param expectStatus 期望的当前状态（<b>不要凭参数名猜顺序</b>：本仓在
     *                     {@code PaymentRecordMapper} 上因为顺序写反静默失败过 3 次）
     * @return 受影响行数；0 = 状态已被改过
     *
     * <p>⚠️ [2026-09-23 改名] 原名 {@code updateStatusIf}，与 {@code OrderMapper.updateStatusIf}
     * <b>同名但参数顺序相反</b>（这里是"新在前"，那里是"期望在前"）。现改名为
     * {@code updateStatusTo}，让"第二个参数是目标状态"写在名字里。新写 CAS 请沿用：
     * {@code updateStatusIf} = (期望, 新)、{@code updateStatusTo} = (新, 期望)。</p>
     */
    @Update("update staff_payroll set status = #{status}, update_time = NOW() "
            + "where id = #{id} and status = #{expectStatus}")
    int updateStatusTo(@Param("id") Long id, @Param("status") Integer status,
                       @Param("expectStatus") Integer expectStatus);

    /** 标记已发放：只允许从「已确认」迁入，并同时落发钱时间（留痕） */
    @Update("update staff_payroll set status = 3, paid_time = NOW(), operator_id = #{operatorId}, "
            + "update_time = NOW() where id = #{id} and status = 2")
    int markPaid(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /** 撤销结算单（仅草稿或已确认）：把明细退回未结算池 */
    @Update("update staff_payroll set total_amount = 0, update_time = NOW() where id = #{id} and status = 1")
    int clearTotalIfDraft(@Param("id") Long id);
}
