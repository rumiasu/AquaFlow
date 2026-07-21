package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerStationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CustomerStationRecordMapper {

    @Insert("insert into customer_station_record(customer_id, from_station_id, to_station_id, reason, create_time) " +
            "values(#{customerId}, #{fromStationId}, #{toStationId}, #{reason}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerStationRecord customerStationRecord);

    @Select("select * from customer_station_record where customer_id = #{customerId} order by create_time desc")
    List<CustomerStationRecord> listByCustomerId(Integer customerId);
}
