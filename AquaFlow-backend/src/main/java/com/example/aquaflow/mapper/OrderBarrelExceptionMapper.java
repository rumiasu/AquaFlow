package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderBarrelException;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderBarrelExceptionMapper {

    @Insert("insert into order_barrel_exception(" +
            "order_id, customer_id, station_id, delivery_staff_id, " +
            "delivery_qty, return_qty, discrepancy, category, type, " +
            "staff_action, staff_note, water_given, water_owed, " +
            "manager_action, refund_ticket_qty, refund_cash_amount, " +
            "adjust_asset_qty, adjust_product_id, manager_note, " +
            "suggested_ticket_qty, suggested_cash_amount, status, created_at) " +
            "values(" +
            "#{orderId}, #{customerId}, #{stationId}, #{deliveryStaffId}, " +
            "#{deliveryQty}, #{returnQty}, #{discrepancy}, #{category}, #{type}, " +
            "#{staffAction}, #{staffNote}, #{waterGiven}, #{waterOwed}, " +
            "#{managerAction}, #{refundTicketQty}, #{refundCashAmount}, " +
            "#{adjustAssetQty}, #{adjustProductId}, #{managerNote}, " +
            "#{suggestedTicketQty}, #{suggestedCashAmount}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderBarrelException exception);

    @Select("select * from order_barrel_exception where id = #{id}")
    OrderBarrelException getById(@Param("id") Long id);

    @Select("select * from order_barrel_exception where order_id = #{orderId} order by created_at desc")
    List<OrderBarrelException> listByOrderId(@Param("orderId") Long orderId);

    @Select("select * from order_barrel_exception " +
            "where station_id = #{stationId} " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{category} is null or category = #{category}) " +
            "and (#{staffId} is null or delivery_staff_id = #{staffId}) " +
            "order by created_at desc " +
            "limit #{offset}, #{size}")
    List<OrderBarrelException> listByStation(
            @Param("stationId") Long stationId,
            @Param("status") String status,
            @Param("category") String category,
            @Param("staffId") Long staffId,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select("select count(*) from order_barrel_exception " +
            "where station_id = #{stationId} " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{category} is null or category = #{category}) " +
            "and (#{staffId} is null or delivery_staff_id = #{staffId})")
    int countByStation(
            @Param("stationId") Long stationId,
            @Param("status") String status,
            @Param("category") String category,
            @Param("staffId") Long staffId);

    @Select("select * from order_barrel_exception " +
            "where customer_id = #{customerId} and station_id = #{stationId} " +
            "order by created_at desc")
    List<OrderBarrelException> listByCustomerAndStation(
            @Param("customerId") Long customerId,
            @Param("stationId") Long stationId);

    /** 客户全部异常记录（不限定水站，用于客户未选水站时的"我的异常"列表） */
    @Select("select * from order_barrel_exception " +
            "where customer_id = #{customerId} " +
            "order by created_at desc")
    List<OrderBarrelException> listByCustomer(@Param("customerId") Long customerId);

    @Update("update order_barrel_exception set " +
            "manager_action = #{managerAction}, " +
            "refund_ticket_qty = #{refundTicketQty}, " +
            "refund_cash_amount = #{refundCashAmount}, " +
            "adjust_asset_qty = #{adjustAssetQty}, " +
            "adjust_product_id = #{adjustProductId}, " +
            "manager_note = #{managerNote}, " +
            "status = #{status}, " +
            "decided_at = NOW() " +
            "where id = #{id}")
    void updateDecision(@Param("id") Long id,
                        @Param("managerAction") String managerAction,
                        @Param("refundTicketQty") Integer refundTicketQty,
                        @Param("refundCashAmount") java.math.BigDecimal refundCashAmount,
                        @Param("adjustAssetQty") Integer adjustAssetQty,
                        @Param("adjustProductId") Long adjustProductId,
                        @Param("managerNote") String managerNote,
                        @Param("status") String status);

    @Update("update order_barrel_exception set " +
            "status = #{status}, " +
            "executed_at = NOW() " +
            "where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    @Select("select category, count(*) as cnt from order_barrel_exception " +
            "where station_id = #{stationId} " +
            "and created_at >= #{startDate} and created_at <= #{endDate} " +
            "group by category")
    List<java.util.Map<String, Object>> countByCategory(
            @Param("stationId") Long stationId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);

    @Select("select sum(refund_ticket_qty) as total_tickets, " +
            "sum(refund_cash_amount) as total_cash " +
            "from order_barrel_exception " +
            "where station_id = #{stationId} " +
            "and status = 'EXECUTED' " +
            "and created_at >= #{startDate} and created_at <= #{endDate}")
    java.util.Map<String, Object> sumCompensation(
            @Param("stationId") Long stationId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate);
}