package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.AuditLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    /** 持久业务凭据不可清理；本站行锁与READ_COMMITTED事务保证可见性，不锁整个审计扫描范围。 */
    @Select("select detail from audit_log where user_id=#{actor} and module='INVENTORY_INBOUND' " +
            "and action='RECEIPT' and target=#{target} order by id limit 2")
    List<String> inboundReceipts(@Param("actor") Long actor, @Param("target") String target);

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
