package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.PaymentRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PaymentRecordMapper {

    @Insert("insert into payment_record(order_id, customer_id, station_id, amount, payment_method, status, transaction_no, operator_id, note, create_time, update_time, water_amount, barrel_deposit, excess_barrels) " +
            "values(#{orderId}, #{customerId}, #{stationId}, #{amount}, #{paymentMethod}, #{status}, #{transactionNo}, #{operatorId}, #{note}, #{createTime}, #{updateTime}, #{waterAmount}, #{barrelDeposit}, #{excessBarrels})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PaymentRecord record);

    @Select("select * from payment_record where id = #{id}")
    PaymentRecord getById(@Param("id") Long id);

    @Select("select * from payment_record where order_id = #{orderId} order by create_time")
    List<PaymentRecord> listByOrderId(@Param("orderId") Long orderId);

    @Select("select * from payment_record where order_id = #{orderId} order by create_time desc limit 1")
    PaymentRecord getByOrderId(@Param("orderId") Long orderId);

    @Select("select * from payment_record where customer_id = #{customerId} order by create_time desc")
    List<PaymentRecord> listByCustomerId(@Param("customerId") Long customerId);

    @Select("select * from payment_record where station_id = #{stationId} order by create_time desc")
    List<PaymentRecord> listByStationId(@Param("stationId") Long stationId);

    @Update("update payment_record set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 乐观锁更新：仅当当前状态为PENDING时才更新 */
    @Update("update payment_record set status = #{status}, update_time = NOW() where id = #{id} and status = 0")
    int updateStatusIfPending(@Param("id") Long id, @Param("status") Integer status);

    @Select("select * from payment_record where station_id = #{stationId} " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{paymentMethod} is null or payment_method = #{paymentMethod}) " +
            "order by create_time desc limit #{limit}")
    List<PaymentRecord> listByStation(@Param("stationId") Long stationId,
                                      @Param("status") Integer status,
                                      @Param("paymentMethod") Integer paymentMethod,
                                      @Param("limit") int limit);

    @Select("select * from payment_record where station_id = #{stationId} order by create_time desc limit #{limit}")
    List<PaymentRecord> listAllByStation(@Param("stationId") Long stationId, @Param("limit") int limit);
}
