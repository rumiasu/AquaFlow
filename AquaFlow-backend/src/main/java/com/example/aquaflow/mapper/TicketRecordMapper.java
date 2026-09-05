package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface TicketRecordMapper {

    @Insert("insert into ticket_record(customer_id, product_id, station_id, increase_qty, decrease_qty, order_id, source, ticket_source, create_time) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{increaseQty}, #{decreaseQty}, #{orderId}, #{source}, #{ticketSource}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketRecord ticketRecord);

    @Select("select * from ticket_record where id = #{id}")
    TicketRecord getById(@Param("id") Long id);

    @Select("select * from ticket_record where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId} order by create_time desc")
    List<TicketRecord> listByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId, @Param("stationId") Long stationId);

    @Select("select * from ticket_record where customer_id = #{customerId} order by create_time desc")
    List<TicketRecord> listByCustomerId(@Param("customerId") Long customerId);

    @Select("select tr.*, p.name as productName, p.spec as productSpec " +
            "from ticket_record tr " +
            "left join product p on tr.product_id = p.id " +
            "where tr.customer_id = #{customerId} " +
            "order by tr.create_time desc")
    List<Map<String, Object>> listByCustomerIdWithDetail(@Param("customerId") Long customerId);
}
