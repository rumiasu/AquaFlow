package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** Own-account aggregate reads only; never reuse a truncated display/history list. */
@Mapper
public interface AccountClosureCheckMapper {
    @Select("select count(*) from customer where id=#{customerId}")
    int exists(@Param("customerId") Long customerId);

    @SelectProvider(type=AccountClosureCheckSql.class,method="facts")
    List<Map<String,Object>> facts(@Param("customerId") Long customerId);
}
