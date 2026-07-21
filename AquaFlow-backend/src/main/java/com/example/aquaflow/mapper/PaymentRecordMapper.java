package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.PaymentRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PaymentRecordMapper {

    @Insert("INSERT INTO payment_record(order_id, customer_id, amount, water_amount, barrel_deposit, excess_barrels, payment_method, ticket_water_type_id, ticket_qty, status, note, create_time, update_time) " +
            "VALUES(#{orderId}, #{customerId}, #{amount}, #{waterAmount}, #{barrelDeposit}, #{excessBarrels}, #{paymentMethod}, #{ticketWaterTypeId}, #{ticketQty}, #{status}, #{note}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PaymentRecord record);

    @Select("SELECT * FROM payment_record WHERE id = #{id}")
    PaymentRecord getById(@Param("id") Long id);

    @Select("SELECT * FROM payment_record WHERE order_id = #{orderId} ORDER BY create_time")
    List<PaymentRecord> listByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM payment_record WHERE customer_id = #{customerId} ORDER BY create_time DESC")
    List<PaymentRecord> listByCustomerId(@Param("customerId") Long customerId);

    @Select("SELECT * FROM payment_record ORDER BY create_time DESC LIMIT #{limit}")
    List<PaymentRecord> listAll(@Param("limit") int limit);

    @Update("UPDATE payment_record SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
