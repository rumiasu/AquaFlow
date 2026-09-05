package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.FileInfo;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FileInfoMapper {

    @Insert("insert into file_info(file_name, file_size, file_type, mime_type, object_name, category, uploader_id, uploader_name, create_time, update_time) " +
            "values(#{fileName}, #{fileSize}, #{fileType}, #{mimeType}, #{objectName}, #{category}, #{uploaderId}, #{uploaderName}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FileInfo fileInfo);

    @Select("select * from file_info where id = #{id}")
    FileInfo getById(@Param("id") Long id);

    @Select("select * from file_info order by create_time desc")
    List<FileInfo> listAll();

    @Select("select * from file_info where category = #{category} order by create_time desc")
    List<FileInfo> listByCategory(@Param("category") String category);

    @Delete("delete from file_info where id = #{id}")
    void deleteById(@Param("id") Long id);
}
