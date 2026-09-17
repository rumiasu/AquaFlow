package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.AlertLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AlertLogMapper {

    @Insert("insert into alert_log(alert_type, level, source, station_id, staff_id, title, content, "
            + "related_type, related_id, notify_status, create_time) "
            + "values(#{alertType}, #{level}, #{source}, #{stationId}, #{staffId}, #{title}, #{content}, "
            + "#{relatedType}, #{relatedId}, #{notifyStatus}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AlertLog log);

    /**
     * 站长端只读本站的**运营**告警。
     *
     * <p>⚠️ 必须带 {@code alert_type='OPERATION'} 条件：系统故障告警是发给开发者的，
     * 里面可能带平台级细节（对账不平的具体表/金额），不能漏给站长看（跨租户 + 越权知情）。</p>
     */
    @Select("select * from alert_log where alert_type='OPERATION' and station_id = #{stationId} "
            + "order by id desc limit #{limit}")
    List<AlertLog> listStationAlerts(@Param("stationId") Long stationId, @Param("limit") int limit);

    @Select("select count(*) from alert_log where alert_type = #{alertType}")
    int countByType(@Param("alertType") String alertType);

    /** 外部渠道投递结果回写（PUSHED / FAILED） */
    @org.apache.ibatis.annotations.Update("update alert_log set notify_status = #{status} where id = #{id}")
    int updateNotifyStatus(@Param("id") Long id, @Param("status") String status);
}
