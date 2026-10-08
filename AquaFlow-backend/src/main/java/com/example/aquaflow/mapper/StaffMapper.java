package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Staff;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Staff 表 Mapper。
 * <p><b>V1 Binding 模型:</b> 当前归属关系只由 staff.station_id 表达 (NULL=未绑定)。
 * 绑定/解绑申请请使用 {@link StaffStationApplicationMapper}。
 */
@Mapper
public interface StaffMapper {

    @Insert("INSERT INTO staff(name, phone, openid, password_hash, role, station_id, status, create_time, update_time) " +
            "VALUES(#{name}, #{phone}, #{openid}, #{passwordHash}, #{role}, #{stationId}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Staff staff);

    @Select("SELECT * FROM staff WHERE status = 1")
    List<Staff> listAll();

    @Select("SELECT * FROM staff WHERE id = #{id}")
    Staff getById(@Param("id") Long id);

    /**
     * 读员工并**加行锁**（`FOR UPDATE`）—— 绑定/解绑审批统一锁序的第一步。
     *
     * <p>[2026-09-25 返工 R6] 为什么审批类流程必须**先锁员工行、再动申请行**：
     * 「同意绑定」原来先 CAS 申请行、再写 {@code staff.station_id}、最后
     * {@link StaffStationApplicationMapper#cancelOtherPending} 去锁该员工的**其它**申请行。
     * 两个站的站长同时同意同一个配送员时，时序可以是
     * 「T1 锁申请甲 → T2 锁申请乙 → T1 拿到员工行 → T2 等员工行 → T1 的 cancelOtherPending 等申请乙」
     * ⇒ **循环等待**，MySQL 挑一个回滚，用户看到 500/SYSTEM 告警（本仓约定：业务竞争必须是 code=1）。
     * 员工行是这段关系的聚合根：谁要改 {@code staff.station_id} 或该员工的申请状态，都先锁它，
     * 冲突就退化成"排队"，不再有环。</p>
     *
     * @return 员工行（不存在返回 null）；锁持有到事务结束
     */
    @Select("SELECT * FROM staff WHERE id = #{id} FOR UPDATE")
    Staff getByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE staff SET name=#{name}, phone=#{phone}, openid=#{openid}, password_hash=#{passwordHash}, " +
            "role=#{role}, station_id=#{stationId}, status=#{status}, update_time=NOW() WHERE id=#{id}")
    void update(Staff staff);

    /**
     * 白名单更新：只允许改 name / phone / status。
     * <p>
     * 与 {@link #update(Staff)} 的区别：update() 会全量写入 role / station_id / password_hash，
     * 只能用于服务端内部流程，绝不能直接接收客户端入参，否则站长可自行提权。
     */
    @Update("UPDATE staff SET name=#{name}, phone=#{phone}, status=#{status}, update_time=NOW() WHERE id=#{id}")
    void updateBaseInfo(@Param("id") Long id,
                        @Param("name") String name,
                        @Param("phone") String phone,
                        @Param("status") Integer status);

    /**
     * 员工管理白名单 CAS；归属、角色和原状态必须仍与锁内核验一致。
     * 返回 0 时调用方必须拒绝，不能报告已更新；历史 NULL 状态可通过显式合法状态修复。
     */
    @Update("UPDATE staff SET name=#{name}, phone=#{phone}, status=#{status}, update_time=NOW() "
            + "WHERE id=#{id} AND station_id=#{stationId} AND role=#{expectedRole} "
            + "AND status <=> #{expectedStatus}")
    int updateBaseInfoIf(@Param("id") Long id,
                         @Param("stationId") Long stationId,
                         @Param("expectedRole") String expectedRole,
                         @Param("expectedStatus") Integer expectedStatus,
                         @Param("name") String name,
                         @Param("phone") String phone,
                         @Param("status") Integer status);

    /**
     * 只给**还没有密码**的账号补初始密码（CAS：{@code password_hash} 仍为空才写）。
     *
     * <p>为什么不用 {@link #update(Staff)}（2026-09-29 收口）：那是**整行覆盖**（含 role /
     * station_id / status），一个"只配补个密码"的启动任务不该握着改员工归属与角色的能力 ——
     * 同 {@link #updateBaseInfo} 的白名单思路。带条件的 UPDATE 还让"先到者填、后到者不覆盖"：
     * 用户自己改过的密码永远不会被启动逻辑冲掉。</p>
     *
     * @return 受影响行数；0 = 已有密码（**不是错误**，调用方不要当失败处理）
     */
    @Update("UPDATE staff SET password_hash = #{passwordHash}, update_time = NOW() "
            + "WHERE id = #{id} AND (password_hash IS NULL OR password_hash = '')")
    int updatePasswordIfEmpty(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 本人改密的按列更新（2026-09-29 下沉收口）：与 {@link #updatePasswordIfEmpty} 的区别是
     * **不做"只填空"CAS** —— 旧密码已在服务层验过，这里就是要覆盖。与 {@link #update(Staff)}
     * 的区别是只碰 password_hash 一列：全量写会把读改写窗口内的 role / station_id 快照写回去（lost update）。
     *
     * @return 受影响行数（应恒为 1；0 = 员工行在验证后被删，调用方按失败处理）
     */
    @Update("UPDATE staff SET password_hash = #{passwordHash}, update_time = NOW() WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 绑定微信 openid 的按列 CAS（2026-09-29 下沉收口，替代 bind-staff 里的全量 {@link #update(Staff)}）。
     * WHERE 带「空或同值」条件：服务层先判过「已绑定其他微信」就拒绝，但判与写之间可能有人抢先绑了
     * **别的** openid —— 那时本语句拿 0 行，调用方报同一条文案，而不是把别人的绑定覆盖掉。
     *
     * @return 1 = 绑定成功；0 = 已被其他微信绑定（不是错误条件缺失）
     */
    @Update("UPDATE staff SET openid = #{openid}, update_time = NOW() "
            + "WHERE id = #{id} AND (openid IS NULL OR openid = #{openid})")
    int bindOpenid(@Param("id") Long id, @Param("openid") String openid);

    @Delete("DELETE FROM staff WHERE id = #{id}")
    void delete(@Param("id") Long id);

    /** 只删核验过的员工行；不联动任何历史收益或工资单。返回 0 时调用方必须拒绝。 */
    @Delete("DELETE FROM staff WHERE id=#{id} AND station_id=#{stationId} "
            + "AND role=#{expectedRole} AND status <=> #{expectedStatus}")
    int deleteIf(@Param("id") Long id,
                 @Param("stationId") Long stationId,
                 @Param("expectedRole") String expectedRole,
                 @Param("expectedStatus") Integer expectedStatus);

    /** 某水站下所有在职员工 (站长+配送员) */
    @Select("SELECT * FROM staff WHERE station_id = #{stationId} AND status = 1")
    List<Staff> listByStationId(@Param("stationId") Long stationId);

    /** 某水站下指定角色的在职员工 */
    @Select("SELECT * FROM staff WHERE station_id = #{stationId} AND role = #{role} AND status = 1")
    List<Staff> listByStationIdAndRole(@Param("stationId") Long stationId, @Param("role") String role);

    @Select("SELECT * FROM staff WHERE name = #{name} AND status = 1 LIMIT 1")
    Staff findByName(@Param("name") String name);

    @Select("SELECT * FROM staff WHERE role = #{role} AND status = 1 LIMIT 1")
    Staff findByRole(@Param("role") String role);

    @Select("SELECT * FROM staff WHERE openid = #{openid} LIMIT 1")
    Staff findByOpenid(@Param("openid") String openid);

    @Select("SELECT * FROM staff WHERE phone = #{phone} LIMIT 1")
    Staff findByPhone(@Param("phone") String phone);

    /**
     * 绑定归属水站（**CAS：仅当该员工当前没有归属站**）。
     *
     * <p>场景：站长审批同意绑定申请。为什么必须带条件 —— 一个配送员可以对多个站各留一条
     * 待审批申请，两个站同时点「同意」时，双方都会先读到 {@code station_id IS NULL} 而放行；
     * 无条件的 {@code UPDATE ... WHERE id=?} 会让**后写者覆盖前写者**，
     * 于是审批记录显示两站都同意了，员工归属却只剩一个（2026-09-25 架构评审问题 8）。</p>
     *
     * @return 受影响行数；0 = 已被别人绑走 —— 调用方必须据此拒绝本次审批
     */
    @Update("UPDATE staff SET station_id = #{stationId}, update_time = NOW() WHERE id = #{id} AND station_id IS NULL")
    int updateStationIdIfUnbound(@Param("id") Long id, @Param("stationId") Long stationId);

    /**
     * 清空归属水站（**CAS：仅当当前归属确实是这一站**）。
     *
     * <p>场景：同意解绑申请 / 站长单方面解除。带 expected 站别是为了防"同意解绑"与
     * "员工已被调走/已被别站接管"并发时把**新的归属**一并抹掉 ——
     * 那会让一个正在给 B 站送货的配送员突然变成无归属，所有 requireStationId() 端点全废。</p>
     *
     * @return 受影响行数；0 = 归属已变（已解绑 / 已调站）—— 调用方必须据此拒绝
     */
    @Update("UPDATE staff SET station_id = NULL, update_time = NOW() WHERE id = #{id} AND station_id = #{expectedStationId}")
    int clearStationIdIf(@Param("id") Long id, @Param("expectedStationId") Long expectedStationId);

    /*
     * 已删除：void updateStationId(id, stationId)（2026-09-25，架构评审问题 8）。
     * 它是无条件写归属（无 expected、无受影响行数），三个调用点全在 DeliveryBindingController，
     * 现已换成上面两个 CAS 方法。判据同 AGENTS §6「所有状态改写必须 CAS 并检查受影响行数」——
     * 归属也算状态，无条件的等价于"最后写的人说了算"。
     */

    // ==================== 员工画像聚合 ====================

    /** 今日完成 */
    @Select("select count(*) from orders where delivery_staff_id = #{staffId} and status = 4 " +
            "and date(create_time) = curdate()")
    int countTodayCompleted(@Param("staffId") Long staffId);

    /** 本月完成 */
    @Select("select count(*) from orders where delivery_staff_id = #{staffId} and status = 4 " +
            "and create_time >= date_format(now(), '%Y-%m-01')")
    int countMonthCompleted(@Param("staffId") Long staffId);

    /** 累计完成 */
    @Select("select count(*) from orders where delivery_staff_id = #{staffId} and status = 4")
    int countTotalCompleted(@Param("staffId") Long staffId);

    /** 进行中（待配送 + 配送中） */
    @Select("select count(*) from orders where delivery_staff_id = #{staffId} and status in (1, 2)")
    int countDelivering(@Param("staffId") Long staffId);

    /** 已取消 */
    @Select("select count(*) from orders where delivery_staff_id = #{staffId} and status = 5")
    int countCancelled(@Param("staffId") Long staffId);

    /** 累计配送金额 */
    @Select("select coalesce(sum(total_amount),0) from orders where delivery_staff_id = #{staffId} and status = 4")
    java.math.BigDecimal sumTotalAmount(@Param("staffId") Long staffId);

    /** 本月配送金额 */
    @Select("select coalesce(sum(total_amount),0) from orders where delivery_staff_id = #{staffId} and status = 4 " +
            "and create_time >= date_format(now(), '%Y-%m-01')")
    java.math.BigDecimal sumMonthAmount(@Param("staffId") Long staffId);

    /** 退回站长次数（[AQ-015] 改由 order_transfer 统计，含已同意/已拒绝的历史记录） */
    @Select("select count(*) from order_transfer where from_staff_id = #{staffId} and sub_kind = 'RETURN_STATION'")
    int countReturns(@Param("staffId") Long staffId);

    /** 桶异常次数 */
    @Select("select count(*) from order_barrel_exception where delivery_staff_id = #{staffId}")
    int countExceptions(@Param("staffId") Long staffId);

    /**
     * 当前进行中订单。
     *
     * <p>⚠️ 站别两列与收件人快照是<b>给跨站画像抹除用的</b>（{@code util/CustomerProfileMask}）：
     * 本查询按 {@code delivery_staff_id} 过滤，跨站外派给本站配送员的单也在结果里，
     * 而 {@code c.name} 带出的是<b>归属站</b>的客户档案 —— 调用方必须对跨站行置 null。
     * <b>别名不能改</b>：MyBatis 的 {@code map-underscore-to-camel-case} 对 Map 返回值不生效，
     * 抹除逻辑按 {@code stationId} / {@code deliveryStationId} 这两个键取值，读不到就静默不抹。</p>
     */
    @Select("select o.id, o.status, o.total_amount as totalAmount, " +
            "o.address_snapshot as addressSnapshot, o.receiver_name as receiverName, " +
            "o.station_id as stationId, o.delivery_station_id as deliveryStationId, " +
            "c.name as customerName " +
            "from orders o left join customer c on o.customer_id = c.id " +
            "where o.delivery_staff_id = #{staffId} and o.status in (1, 2) " +
            "order by o.create_time desc limit 10")
    java.util.List<java.util.Map<String, Object>> listCurrentOrders(@Param("staffId") Long staffId);
}
