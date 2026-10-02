package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketPurchaseFence;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 建款与结束必须先锁同一主键，不能用一次无锁空查询释放原编号。 */
@Mapper
public interface TicketPurchaseFenceMapper {
    @Insert("insert into ticket_purchase_fence(customer_id,idempotency_key) values(#{customerId},#{key}) "
            + "on duplicate key update customer_id=customer_id")
    int ensure(@Param("customerId") Long customerId, @Param("key") String key);

    @Select("select * from ticket_purchase_fence where customer_id=#{customerId} and idempotency_key=#{key} for update")
    TicketPurchaseFence lock(@Param("customerId") Long customerId, @Param("key") String key);

    @Update("update ticket_purchase_fence set closed_time=#{time} "
            + "where customer_id=#{customerId} and idempotency_key=#{key} and closed_time is null")
    int closeIfOpen(@Param("customerId") Long customerId, @Param("key") String key, @Param("time") LocalDateTime time);
}
