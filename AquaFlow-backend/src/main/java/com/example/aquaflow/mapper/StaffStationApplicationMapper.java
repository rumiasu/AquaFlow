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

    @Update("UPDATE staff_station_application SET status = #{status}, handle_staff_id = #{handleStaffId}, " +
            "handle_note = #{handleNote}, handle_time = NOW() WHERE id = #{id}")
    void handle(@Param("id") Long id, @Param("status") Integer status,
                @Param("handleStaffId") Long handleStaffId, @Param("handleNote") String handleNote);

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
