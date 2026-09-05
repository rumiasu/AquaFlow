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

    @Insert("INSERT INTO station(name, phone, address, status, create_time, update_time) " +
            "VALUES(#{name}, #{phone}, #{address}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Station station);

    @Select("SELECT * FROM station")
    List<Station> listAll();

    @Select("SELECT * FROM station WHERE id = #{id}")
    Station getById(@Param("id") Long id);

    @Update("UPDATE station SET name=#{name}, phone=#{phone}, address=#{address}, " +
            "status=#{status}, offline_payment_enabled=#{offlinePaymentEnabled}, update_time=NOW() WHERE id=#{id}")
    void update(Station station);

    @Delete("DELETE FROM station WHERE id = #{id}")
    void delete(@Param("id") Long id);

    @Select("SELECT * FROM station WHERE status = 1")
    List<Station> listPublic();

    @Select("SELECT * FROM station WHERE id IN (SELECT station_id FROM staff WHERE id = #{creatorId} AND role = 'STATION_MANAGER')")
    List<Station> listByCreator(@Param("creatorId") Long creatorId);

    @Select("SELECT * FROM station WHERE id = #{id} AND id IN (SELECT station_id FROM staff WHERE id = #{creatorId} AND role = 'STATION_MANAGER')")
    Station getByIdAndCreator(@Param("id") Long id, @Param("creatorId") Long creatorId);
}
