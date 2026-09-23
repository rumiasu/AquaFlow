package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerNotification;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerNotificationMapper {

    @Insert("insert into customer_notification(customer_id, type, title, content, related_order_id, is_read, create_time) " +
            "values(#{customerId}, #{type}, #{title}, #{content}, #{relatedOrderId}, #{isRead}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerNotification notification);

    @Select("select * from customer_notification where customer_id = #{customerId} order by create_time desc limit #{limit}")
    List<CustomerNotification> listByCustomerId(@Param("customerId") Long customerId, @Param("limit") int limit);

    @Select("select * from customer_notification where customer_id = #{customerId} and is_read = 0 order by create_time desc")
    List<CustomerNotification> listUnreadByCustomerId(@Param("customerId") Long customerId);

    @Update("update customer_notification set is_read = 1 where id = #{id}")
    void markRead(@Param("id") Long id);

    /**
     * [AQ-035] 按 id + 归属客户更新已读，返回受影响行数。
     * 旧实现只按 id 更新，且 controller 查到归属后丢弃结果 → 顾客可把任意他人通知标记已读。
     */
    @Update("update customer_notification set is_read = 1 where id = #{id} and customer_id = #{customerId}")
    int markReadOwned(@Param("id") Long id, @Param("customerId") Long customerId);

    @Update("update customer_notification set is_read = 1 where customer_id = #{customerId}")
    void markAllRead(@Param("customerId") Long customerId);

    @Select("select count(*) from customer_notification where customer_id = #{customerId} and is_read = 0")
    int countUnread(@Param("customerId") Long customerId);
}
