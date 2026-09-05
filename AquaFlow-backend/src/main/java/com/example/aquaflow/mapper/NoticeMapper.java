package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Notice;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface NoticeMapper {

    @Insert("insert into notice(station_id, title, content, type, status, publisher_id, create_time, update_time) " +
            "values(#{stationId}, #{title}, #{content}, #{type}, #{status}, #{publisherId}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Notice notice);

    @Update("update notice set title=#{title}, content=#{content}, type=#{type}, status=#{status}, update_time=NOW() " +
            "where id=#{id}")
    void update(Notice notice);

    @Delete("delete from notice where id=#{id}")
    void delete(@Param("id") Long id);

    @Select("select * from notice where id = #{id}")
    Notice getById(@Param("id") Long id);

    @Select("select * from notice where status = 1 order by create_time desc")
    List<Notice> listPublished();

    @Select("select * from notice where station_id = #{stationId} and status = 1 order by create_time desc")
    List<Notice> listByStationId(@Param("stationId") Long stationId);

    @Select("select * from notice order by create_time desc")
    List<Notice> listAll();
}
