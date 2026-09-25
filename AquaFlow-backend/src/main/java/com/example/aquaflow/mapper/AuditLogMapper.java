package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.AuditLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    @Insert("insert into audit_log(user_id, username, role, module, action, target, detail, ip, create_time) " +
            "values(#{userId}, #{username}, #{role}, #{module}, #{action}, #{target}, #{detail}, #{ip}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AuditLog auditLog);

    @Select("select * from audit_log order by create_time desc limit #{limit}")
    List<AuditLog> listRecent(@Param("limit") int limit);

    @Select("select * from audit_log where user_id = #{userId} order by create_time desc limit #{limit}")
    List<AuditLog> listByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("select * from audit_log where module = #{module} order by create_time desc limit #{limit}")
    List<AuditLog> listByModule(@Param("module") String module, @Param("limit") int limit);
}
