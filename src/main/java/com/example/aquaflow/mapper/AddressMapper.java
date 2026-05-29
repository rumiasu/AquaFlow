package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Address;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AddressMapper {
    @Insert("insert into address(detail, tag, lat, lng, create_time, update_time) " +
            "values(#{detail}, #{tag}, #{lat}, #{lng}, #{createTime}, #{updateTime})")
    void insert(Address address);

    List<Address> list(String tag, String keyword);

    @Select("select * from address where id = #{id}")
    Address getById(Integer id);

    void update(Address address);
}
