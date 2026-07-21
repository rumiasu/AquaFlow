package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Station;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface StationMapper {

    @Insert("insert into station(name, manager, phone, address, factory_id, status, create_time, update_time) " +
            "values(#{name}, #{manager}, #{phone}, #{address}, #{factoryId}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Station station);

    @Select("select * from station")
    List<Station> listAll();

    @Select("select * from station where id = #{id}")
    Station getById(Integer id);

    @Update("update station set name=#{name}, manager=#{manager}, phone=#{phone}, address=#{address}, " +
            "factory_id=#{factoryId}, status=#{status}, update_time=NOW() where id=#{id}")
    void update(Station station);

    @Delete("delete from station where id = #{id}")
    void delete(Integer id);
}
