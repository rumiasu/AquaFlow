package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.DepositRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DepositRecordMapper {

    @Insert("insert into deposit_record(customer_id, type, amount, note, create_time) " +
            "values(#{customerId}, #{type}, #{amount}, #{note}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(DepositRecord depositRecord);

    @Select("select * from deposit_record where customer_id = #{customerId} order by create_time desc")
    List<DepositRecord> listByCustomerId(Integer customerId);
}
