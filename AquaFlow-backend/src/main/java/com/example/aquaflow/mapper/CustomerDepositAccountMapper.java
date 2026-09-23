package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerDepositAccount;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;

/**
 * 客户按水站隔离的押金余额。
 * <p>规则：A站押金不能直接用于B站订单。充值/扣减/退款均按站隔离。</p>
 */
@Mapper
public interface CustomerDepositAccountMapper {

    @Select("select * from customer_deposit_account where customer_id = #{customerId} and station_id = #{stationId}")
    CustomerDepositAccount getByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 原子增加押金余额 */
    @Insert("insert into customer_deposit_account(customer_id, station_id, balance, update_time) " +
            "values(#{customerId}, #{stationId}, #{amount}, now()) " +
            "on duplicate key update balance = balance + #{amount}, update_time = now()")
    void increaseBalance(@Param("customerId") Long customerId, @Param("stationId") Long stationId, @Param("amount") BigDecimal amount);

    /** 原子扣减押金余额（余额不足则 affected=0） */
    @Update("update customer_deposit_account set balance = balance - #{amount}, update_time = now() " +
            "where customer_id = #{customerId} and station_id = #{stationId} and balance >= #{amount}")
    int decreaseBalance(@Param("customerId") Long customerId, @Param("stationId") Long stationId, @Param("amount") BigDecimal amount);

    /** 查询押金余额 */
    @Select("select ifnull(balance, 0) from customer_deposit_account where customer_id = #{customerId} and station_id = #{stationId}")
    BigDecimal getBalance(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
