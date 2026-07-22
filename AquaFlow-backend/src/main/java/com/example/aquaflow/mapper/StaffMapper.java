package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Staff;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StaffMapper {

    @Insert("insert into staff(name, phone, password, factory_id, station_id, role, status, create_time, update_time) " +
            "values(#{name}, #{phone}, #{password}, #{factoryId}, #{stationId}, #{role}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Staff staff);

    @Select("select * from staff where status = 1")
    List<Staff> listAll();

    @Select("select * from staff where id = #{id}")
    Staff getById(Integer id);

    @Update("update staff set name=#{name}, phone=#{phone}, password=#{password}, factory_id=#{factoryId}, station_id=#{stationId}, " +
            "role=#{role}, status=#{status}, update_time=NOW() where id=#{id}")
    void update(Staff staff);

    @Delete("delete from staff where id = #{id}")
    void delete(Integer id);

    @Select("select * from staff where station_id = #{stationId} and status = 1")
    List<Staff> listByStationId(@Param("stationId") Integer stationId);

    @Select("select * from staff where station_id = #{stationId} and role = #{role} and status = 1")
    List<Staff> listByStationIdAndRole(@Param("stationId") Integer stationId, @Param("role") String role);

    @Select("select * from staff where name = #{name} and status = 1 limit 1")
    Staff findByName(@Param("name") String name);
}
