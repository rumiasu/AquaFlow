package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketRecordMapper {

    @Insert("insert into ticket_record(customer_id, water_type_id, increase_qty, decrease_qty, order_id, source, create_time) " +
            "values(#{customerId}, #{waterTypeId}, #{increaseQty}, #{decreaseQty}, #{orderId}, #{source}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketRecord ticketRecord);

    /** 插入水票消耗记录（简化版） */
    @Insert("insert into ticket_record(customer_id, water_type_id, decrease_qty, order_id, source, create_time) " +
            "values(#{customerId}, #{waterTypeId}, #{decreaseQty}, #{orderId}, #{source}, NOW())")
    void insertWaterTicketRecord(@Param("customerId") Integer customerId,
                                 @Param("waterTypeId") Integer waterTypeId,
                                 @Param("increaseQty") Integer increaseQty,
                                 @Param("decreaseQty") Integer decreaseQty,
                                 @Param("orderId") Integer orderId,
                                 @Param("source") String source);

    @Select("select * from ticket_record where customer_id = #{customerId} order by create_time desc")
    List<TicketRecord> listByCustomerId(Integer customerId);

    @Select("select tr.*, w.name as waterTypeName, w.spec as waterTypeSpec " +
            "from ticket_record tr left join water_type w on tr.water_type_id = w.id " +
            "where tr.customer_id = #{customerId} order by tr.create_time desc")
    java.util.List<java.util.Map<String, Object>> listByCustomerIdWithDetail(@Param("customerId") Integer customerId);
}
