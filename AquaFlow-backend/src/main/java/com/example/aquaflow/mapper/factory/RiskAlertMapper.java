package com.example.aquaflow.mapper.factory;

import com.example.aquaflow.entity.RiskAlert;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RiskAlertMapper {

    @Insert("insert into risk_alert(station_id, alert_type, alert_level, title, content, suggestion, status, create_time, update_time) " +
            "values(#{stationId}, #{alertType}, #{alertLevel}, #{title}, #{content}, #{suggestion}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(RiskAlert alert);

    @Select("select ra.*, s.name as stationName from risk_alert ra " +
            "left join station s on ra.station_id = s.id " +
            "order by ra.alert_level desc, ra.create_time desc")
    List<RiskAlert> listAll();

    @Select("select ra.*, s.name as stationName from risk_alert ra " +
            "left join station s on ra.station_id = s.id " +
            "where ra.status = #{status} " +
            "order by ra.alert_level desc, ra.create_time desc")
    List<RiskAlert> listByStatus(@Param("status") Integer status);

    @Select("select ra.*, s.name as stationName from risk_alert ra " +
            "left join station s on ra.station_id = s.id " +
            "where ra.id = #{id}")
    RiskAlert getById(@Param("id") Integer id);

    @Update("update risk_alert set status = 2, update_time = now() where id = #{id}")
    void markRead(@Param("id") Integer id);

    @Update("update risk_alert set status = 3, handle_note = #{handleNote}, update_time = now() where id = #{id}")
    void handle(@Param("id") Integer id, @Param("handleNote") String handleNote);

    @Select("select alert_level as alertLevel, count(*) as count from risk_alert where status != 3 group by alert_level")
    List<java.util.Map<String, Object>> countByLevel();

    @Select("select ra.*, s.name as stationName from risk_alert ra " +
            "left join station s on ra.station_id = s.id " +
            "where ra.status != 3 " +
            "order by ra.create_time desc limit 10")
    List<RiskAlert> recentAlerts();

    @Select("select count(*) from risk_alert where station_id = #{stationId} and alert_type = #{alertType} and status != 3 and create_time >= date_sub(now(), interval #{days} day)")
    int countRecentByType(@Param("stationId") Integer stationId, @Param("alertType") String alertType, @Param("days") Integer days);
}
