package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.WaterType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WaterTypeMapper {
    @Insert("insert into water_type(name, spec, note, create_time, update_time) " +
            "values(#{name}, #{spec}, #{note}, #{createTime}, #{updateTime})")
    void insert(WaterType waterType);

    List<WaterType> list();

    @Select("select * from water_type where id = #{id}")
    WaterType getById(Integer id);
}
