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

    /**
     * 本站全部公告（**含草稿与已下架**），供站长端管理列表使用。
     *
     * <p>[2026-09-18 修复] 原方法名 {@code listByStationId}，SQL 是
     * {@code where station_id = #{stationId} and status = 1} —— 于是站长端出现两个真实缺陷：
     * ① 「保存草稿」后提示成功，列表里却没有它（界面文案还写着"草稿只有你自己可见"）；
     * ② 点「下架」后该公告从列表消失，**再也点不回来**（无法重新上架）。
     * 两者都是"界面说做了、实际没做"的同族问题（AGENTS §8.15）。管理列表不该按发布状态过滤。</p>
     */
    @Select("select * from notice where station_id = #{stationId} order by create_time desc")
    List<Notice> listForStation(@Param("stationId") Long stationId);
}
