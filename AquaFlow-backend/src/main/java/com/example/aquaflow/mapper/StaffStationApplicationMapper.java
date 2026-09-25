package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.StaffStationApplication;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * staff_station_application 表 Mapper —— 配送员-水站绑定/解绑申请审批记录。
 * <p>当前归属关系<b>永远</b>由 {@code staff.station_id} 决定，本张表只记录历史申请。</p>
 */
@Mapper
public interface StaffStationApplicationMapper {

    @Insert("INSERT INTO staff_station_application(staff_id, station_id, type, status, apply_note, " +
            "handle_staff_id, handle_note, create_time, handle_time) VALUES(#{staffId}, #{stationId}, #{type}, #{status}, " +
            "#{applyNote}, #{handleStaffId}, #{handleNote}, #{createTime}, #{handleTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StaffStationApplication app);

    @Select("SELECT * FROM staff_station_application WHERE id = #{id}")
    StaffStationApplication getById(@Param("id") Long id);

    /**
     * 落审批结果（<b>CAS：仅当申请仍是待审批</b>）。
     *
     * <p>[2026-09-25 架构评审问题 8] 旧实现 {@code handle(...)} 是「按 id 无条件更新 + 返回 void」：
     * 两个站长各自读到"待审批"后都能写成功（后写覆盖前写），"同意"与"拒绝"并发时
     * 后者会悄悄覆盖前者 —— 审批记录与员工实际归属就对不上了。
     * <b>先查再写不是互斥</b>；返回受影响行数才能让调用方知道这一次有没有抢到。</p>
     *
     * @return 受影响行数；0 = 申请已被别人处理（或不存在）—— 调用方**必须**据此拒绝本次操作
     */
    @Update("UPDATE staff_station_application SET status = #{status}, handle_staff_id = #{handleStaffId}, " +
            "handle_note = #{handleNote}, handle_time = NOW() " +
            "WHERE id = #{id} AND status = " + StaffStationApplication.STATUS_PENDING)
    int handleIfPending(@Param("id") Long id, @Param("status") Integer status,
                        @Param("handleStaffId") Long handleStaffId, @Param("handleNote") String handleNote);

    /**
     * 把该员工**其它**仍在待审批的绑定/解绑申请一并置为已取消（同事务内）。
     *
     * <p>为什么必须有：一个配送员可以对多个站各留一条待审批申请（去重判据是
     * {@code staff_id + station_id + type}，防不住跨站）。归属一旦定下来，其余那些申请
     * <b>在业务上已经不可能生效</b> —— 让它们继续挂在对方站长的待办里，
     * 对方点「同意」只会得到一句"已被其他水站接收"，等于留了一个永远批不掉的申请。</p>
     *
     * @param exceptId 刚刚生效的那条申请，不参与取消
     * @return 受影响行数
     */
    @Update("UPDATE staff_station_application SET status = " + StaffStationApplication.STATUS_CANCELLED +
            ", handle_time = NOW() WHERE staff_id = #{staffId} AND status = " +
            StaffStationApplication.STATUS_PENDING + " AND id <> #{exceptId}")
    int cancelOtherPending(@Param("staffId") Long staffId, @Param("exceptId") Long exceptId);

    /*
     * 已删除：void handle(id, status, handleStaffId, handleNote)（2026-09-25，架构评审问题 8）。
     * 它是无条件更新（无 status 条件、无受影响行数），四个审批端点全用它 ——
     * 现已全部换成上面的 handleIfPending 并检查行数。不要为了"少写两行"把它加回来：
     * 读后写在没有行数校验时**不报错、只静默覆盖**，正是本问题的形状。
     */

    @Update("UPDATE staff_station_application SET status = " + StaffStationApplication.STATUS_CANCELLED +
            ", handle_time = NOW() WHERE id = #{id}")
    void cancel(@Param("id") Long id);

    /** 某配送员的所有申请 (按时间倒序) */
    @Select("SELECT * FROM staff_station_application WHERE staff_id = #{staffId} ORDER BY id DESC")
    List<StaffStationApplication> listByStaff(@Param("staffId") Long staffId);

    /** 某水站收到的指定状态申请 (站长审批列表) */
    @Select("SELECT * FROM staff_station_application WHERE station_id = #{stationId} AND status = #{status} ORDER BY id DESC")
    List<StaffStationApplication> listByStationAndStatus(@Param("stationId") Long stationId, @Param("status") Integer status);

    /**
     * 判断同一个 staff + station + type 是否已有"待审批"申请。
     * V1 不允许同一个配送员对同一个水站同时存在多个"待审批绑定申请"。
     * @return 存在返回 >0
     */
    @Select("SELECT COUNT(*) FROM staff_station_application " +
            "WHERE staff_id = #{staffId} AND station_id = #{stationId} AND type = #{type} " +
            "  AND status = " + StaffStationApplication.STATUS_PENDING)
    int countPendingDup(@Param("staffId") Long staffId, @Param("stationId") Long stationId, @Param("type") Integer type);
}
