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
}
