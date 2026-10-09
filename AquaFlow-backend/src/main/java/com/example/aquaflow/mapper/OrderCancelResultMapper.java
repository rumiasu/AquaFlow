package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;

/** 审批状态仍认order_transfer；这里只追加处理说明，不造售后或资金状态。 */
@Mapper
public interface OrderCancelResultMapper {
    @Insert("insert into order_cancel_result(request_id,result_note,automatic,operator_id,create_time) values(#{id},#{note},#{automatic},#{operator},now())")
    int insert(@Param("id") Long id,@Param("note") String note,@Param("automatic") boolean automatic,@Param("operator") Long operator);
    @Select("select result_note as resultNote,automatic from order_cancel_result where request_id=#{id}")
    Map<String,Object> get(Long id);
}
