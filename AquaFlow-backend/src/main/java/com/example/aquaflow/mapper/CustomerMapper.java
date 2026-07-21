package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Customer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("select count(*) from customer")
    int countAll();

    @Select("select * from customer where name like concat('%', #{keyword}, '%') or phone like concat('%', #{keyword}, '%')")
    List<Customer> search(@Param("keyword") String keyword);

    @Select("select * from customer where openid = #{openid}")
    Customer findByOpenid(@Param("openid") String openid);

    @Insert("insert into customer(name, phone, openid, create_time, update_time) " +
            "values(#{name}, #{phone}, #{openid}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertWithOpenid(Customer customer);

    @Update("update customer set openid = #{openid}, update_time = now() where id = #{id}")
    void updateOpenid(@Param("id") Integer id, @Param("openid") String openid);
}
