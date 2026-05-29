package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Customer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CustomerMapper {
    List<Customer> list();

    @Insert("insert into customer(name, phone, note, create_time, update_time) "+
            "value (#{name},#{phone},#{note},#{createTime},#{updateTime})")
    void insert(Customer customer);

    @Select("select * from customer where id=#{id}")
    Customer getById(Integer id);

    void update(Customer customer);
}
