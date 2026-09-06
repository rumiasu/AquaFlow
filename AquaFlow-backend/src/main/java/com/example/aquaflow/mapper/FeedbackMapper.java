package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Feedback;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FeedbackMapper {

    @Insert("insert into feedback(staff_id, customer_id, category, content, contact, create_time) " +
            "values(#{staffId}, #{customerId}, #{category}, #{content}, #{contact}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Feedback feedback);

    @Select("select * from feedback where id = #{id}")
    Feedback getById(@Param("id") Long id);

    @Select("select * from feedback where customer_id = #{customerId} order by create_time desc")
    List<Feedback> listByCustomerId(@Param("customerId") Long customerId);

    @Select("select * from feedback where staff_id = #{staffId} order by create_time desc")
    List<Feedback> listByStaffId(@Param("staffId") Long staffId);

    @Select("select * from feedback order by create_time desc")
    List<Feedback> listAll();

    @Select("select f.* from feedback f inner join customer c on f.customer_id = c.id " +
            "inner join customer_station_config csc on csc.customer_id = c.id and csc.station_id = #{stationId} " +
            "where f.customer_id is not null order by f.create_time desc")
    List<Feedback> listCustomerFeedbackByStation(@Param("stationId") Long stationId);

    @Select("select * from feedback where customer_id is not null order by create_time desc")
    List<Feedback> listCustomerFeedback();
}
