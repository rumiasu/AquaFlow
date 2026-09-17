package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Station;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Station 表 Mapper。
 * <p>V1 模型中: station 不再保存 manager 字段，站长关系由 staff.role + staff.station_id 表达。</p>
 */
@Mapper
public interface StationMapper {

    @Insert("INSERT INTO station(name, phone, address, lat, lng, status, create_time, update_time) " +
            "VALUES(#{name}, #{phone}, #{address}, #{lat}, #{lng}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Station station);

    @Select("SELECT * FROM station")
    List<Station> listAll();

    @Select("SELECT * FROM station WHERE id = #{id}")
    Station getById(@Param("id") Long id);

    /**
     * 只更新**营业软状态**（营业状态 + 站长留言 + 更新时间）。
     *
     * <p>⚠️ 不要用通用的 {@link #update} 来做这件事：那条 SQL 会把 name/phone/address/status
     * 一起覆盖，站长只是改个"休息中"却把站名/地址写没了（本仓"整行覆盖"事故已发生过多次）。</p>
     *
     * @return 受影响行数（调用方需校验，避免"看着保存成功其实没改"）
     */
    @Update("UPDATE station SET operating_status=#{operatingStatus}, status_note=#{note}, " +
            "status_update_time=NOW(), update_time=NOW() WHERE id=#{stationId}")
    int updateOperatingStatus(@Param("stationId") Long stationId,
                             @Param("operatingStatus") Integer operatingStatus,
                             @Param("note") String note);

    // 注：原 offline_payment_enabled 列已于 2026-09-12 停止读写（站点总闸移除），
    // DROP 脚本见 sql/migration_v22_drop_station_offline_payment.sql
    @Update("UPDATE station SET name=#{name}, phone=#{phone}, address=#{address}, " +
            "status=#{status}, update_time=NOW() WHERE id=#{id}")
    void update(Station station);

    /**
     * 只更新**站点坐标**（站长在地图上选点），2026-09-17 新增（v34）。
     *
     * <p>⚠️ 与 {@link #updateOperatingStatus} 同理，<b>不要</b>用通用的 {@link #update} 来做这件事：
     * 那条 SQL 是整行覆盖（name/phone/address/status 全写一遍），站长只选个点却把站名/地址
     * 写没了的事故本仓已发生过多次。</p>
     *
     * <p>反向也成立：通用 {@code update} <b>刻意不带</b> {@code lat}/{@code lng} ——
     * 旧客户端编辑站点信息时不传坐标，若把坐标放进那条 SQL，站长改一次站名就会把坐标冲成 NULL，
     * 而坐标一没，配送范围校验就只能跳过（等于功能失效）。</p>
     *
     * @param lat 纬度；传 {@code null} 表示清除坐标（清除后范围校验会跳过，不会拒单）
     * @return 受影响行数（调用方必须校验：0 行 = 站点不存在或不属于该站长）
     */
    @Update("UPDATE station SET lat=#{lat}, lng=#{lng}, update_time=NOW() WHERE id=#{stationId}")
    int updateCoordinates(@Param("stationId") Long stationId,
                          @Param("lat") java.math.BigDecimal lat,
                          @Param("lng") java.math.BigDecimal lng);

    @Delete("DELETE FROM station WHERE id = #{id}")
    void delete(@Param("id") Long id);

    @Select("SELECT * FROM station WHERE status = 1")
    List<Station> listPublic();

    @Select("SELECT * FROM station WHERE id IN (SELECT station_id FROM staff WHERE id = #{creatorId} AND role = 'STATION_MANAGER')")
    List<Station> listByCreator(@Param("creatorId") Long creatorId);

    @Select("SELECT * FROM station WHERE id = #{id} AND id IN (SELECT station_id FROM staff WHERE id = #{creatorId} AND role = 'STATION_MANAGER')")
    Station getByIdAndCreator(@Param("id") Long id, @Param("creatorId") Long creatorId);
}
