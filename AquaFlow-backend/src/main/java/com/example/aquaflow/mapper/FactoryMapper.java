package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Factory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FactoryMapper {

    @Insert("insert into factory(name, contact_person, contact_phone, address, status, create_time, update_time) " +
            "values(#{name}, #{contactPerson}, #{contactPhone}, #{address}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Factory factory);

    @Select("select * from factory")
    List<Factory> listAll();

    @Select("select * from factory where id = #{id}")
    Factory getById(Integer id);

    @Update("update factory set name=#{name}, contact_person=#{contactPerson}, contact_phone=#{contactPhone}, " +
            "address=#{address}, status=#{status}, update_time=NOW() where id=#{id}")
    void update(Factory factory);

    @Delete("delete from factory where id = #{id}")
    void delete(Integer id);
}
