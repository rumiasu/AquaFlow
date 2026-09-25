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

    @Delete("DELETE FROM staff WHERE id = #{id}")
    void delete(@Param("id") Long id);

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
     * 仅更新 staff.station_id。
     * 场景:
     * <ul>
     *   <li>站长审批同意绑定: station_id 设为目标站</li>
     *   <li>站长审批同意解绑: station_id 置 NULL</li>
     *   <li>站长单方面解除配送员: station_id 置 NULL (同时写 audit_log module=STAFF_BINDING action=FORCE_UNBIND)</li>
     * </ul>
     */
    @Update("UPDATE staff SET station_id = #{stationId}, update_time = NOW() WHERE id = #{id}")
    void updateStationId(@Param("id") Long id, @Param("stationId") Long stationId);

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
