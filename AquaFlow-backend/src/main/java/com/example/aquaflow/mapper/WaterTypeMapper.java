package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.WaterType;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface WaterTypeMapper {
    @Insert("insert into water_type(name, spec, note, create_time, update_time) " +
            "values(#{name}, #{spec}, #{note}, #{createTime}, #{updateTime})")
    void insert(WaterType waterType);

    @Update("update water_type set name=#{name}, spec=#{spec}, note=#{note}, price=#{price}, update_time=#{updateTime} where id=#{id}")
    void update(WaterType waterType);

    @Delete("delete from water_type where id = #{id}")
    void deleteById(@Param("id") Integer id);

    List<WaterType> list();

    @Select("select * from water_type where id = #{id}")
    WaterType getById(Integer id);

    List<WaterType> listByKeyword(@Param("keyword") String keyword);

    // 用户已购买过的水类型（去重）
    @Select("select distinct w.id, w.name, w.spec, w.note, " +
            "sum(o.quantity) as totalOrdered " +
            "from orders o " +
            "left join water_type w on o.water_type_id = w.id " +
            "where o.customer_id = #{customerId} and o.status in (2, 3, 4) " +
            "group by w.id order by totalOrdered desc")
    List<Map<String, Object>> listMyTypes(@Param("customerId") Integer customerId);
}
