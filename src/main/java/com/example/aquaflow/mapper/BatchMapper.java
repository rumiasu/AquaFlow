package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Batch;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface BatchMapper {

    @Insert("insert into batch (status, total_qty, create_time, update_time) values " +
            "(#{status},#{totalQTY},#{createTime},#{updateTime})")
    void insert(Batch batch);


    List<Batch> list(Integer status, String createTimeStart, String createTimeEnd);

    @Select("select * from batch where id=#{id}")
    Batch getById(Integer id);

    @Update("update batch set status=#{status},update_time=now() where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    @Delete("delete from batch where id=#{id}")
    void delete(Integer id);
}
