package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.FileInfo;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 文件登记（COS 对象表）。
 *
 * <p>⚠️ <b>列表查询一律带水站条件</b>（v45，2026-09-18）：原 `listAll()` / `listByCategory`
 * 没有任何水站过滤，而本表此前也没有 `station_id` 列 —— 任何站长 token 都能列出**全部水站**的
 * 文件名与 COS 临时 URL（跨租户泄露，AGENTS §8.23 登记）。那两个方法已随之删除：
 * **不要为了省一个条件把它们加回来**，需要"看全部"的只有开发者，用 SQL 直接查库。</p>
 *
 * <p>可见口径：本站的（{@code station_id = 本站}）<b>或</b> 平台级的（{@code station_id IS NULL}）。</p>
 */
@Mapper
public interface FileInfoMapper {

    @Insert("insert into file_info(file_name, file_size, file_type, mime_type, object_name, category, station_id, uploader_id, uploader_name, create_time, update_time) " +
            "values(#{fileName}, #{fileSize}, #{fileType}, #{mimeType}, #{objectName}, #{category}, #{stationId}, #{uploaderId}, #{uploaderName}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FileInfo fileInfo);

    @Select("select * from file_info where id = #{id}")
    FileInfo getById(@Param("id") Long id);

    /** 本站可见的全部文件（本站 + 平台级），按时间倒序 */
    @Select("select * from file_info where (station_id = #{stationId} or station_id is null) order by create_time desc")
    List<FileInfo> listVisible(@Param("stationId") Long stationId);

    /** 本站可见的某分类文件（本站 + 平台级） */
    @Select("select * from file_info where (station_id = #{stationId} or station_id is null) and category = #{category} "
            + "order by create_time desc")
    List<FileInfo> listVisibleByCategory(@Param("stationId") Long stationId, @Param("category") String category);

    @Delete("delete from file_info where id = #{id}")
    void deleteById(@Param("id") Long id);
}
