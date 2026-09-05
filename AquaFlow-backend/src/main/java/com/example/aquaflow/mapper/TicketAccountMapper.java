package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketAccount;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface TicketAccountMapper {

    @Insert("insert into ticket_account(customer_id, product_id, station_id, remain_quantity, update_time) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{remainQuantity}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketAccount ticketAccount);

    @Select("select * from ticket_account where id = #{id}")
    TicketAccount getById(@Param("id") Long id);

    @Select("select * from ticket_account where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId}")
    TicketAccount getByCustomerProductStation(@Param("customerId") Long customerId,
                                              @Param("productId") Long productId,
                                              @Param("stationId") Long stationId);

    @Update("update ticket_account set remain_quantity = remain_quantity - #{qty}, update_time = NOW() where id = #{id} and remain_quantity >= #{qty}")
    int decrementQuantity(@Param("id") Long id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = remain_quantity + #{qty}, update_time = NOW() where id = #{id}")
    void incrementQuantity(@Param("id") Long id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = #{remainQuantity}, update_time = NOW() where id = #{id}")
    void updateQuantity(@Param("id") Long id, @Param("remainQuantity") Integer remainQuantity);

    @Select("select * from ticket_account where customer_id = #{customerId} and station_id = #{stationId}")
    List<TicketAccount> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select ta.*, p.name as productName, p.spec as productSpec " +
            "from ticket_account ta " +
            "left join product p on ta.product_id = p.id " +
            "where ta.customer_id = #{customerId} and ta.station_id = #{stationId} " +
            "order by ta.update_time desc")
    List<Map<String, Object>> listByCustomerAndStationWithDetail(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
