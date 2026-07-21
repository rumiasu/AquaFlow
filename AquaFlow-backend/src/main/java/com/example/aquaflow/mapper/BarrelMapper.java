package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRecord;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface BarrelMapper {

    @Insert("insert into barrel_record(customer_id, quantity, status, deposit_refund, note, create_time) " +
            "values(#{customerId}, #{quantity}, #{status}, #{depositRefund}, #{note}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(BarrelRecord record);

    @Select("select * from barrel_record where customer_id = #{customerId} order by create_time desc")
    List<BarrelRecord> listByCustomerId(@Param("customerId") Integer customerId);

    @Select("select * from barrel_record order by create_time desc")
    List<BarrelRecord> listAll();

    @Select("select * from barrel_record where id = #{id}")
    BarrelRecord getById(@Param("id") Integer id);

    @Update("update barrel_record set status = #{status}, handle_note = #{handleNote}, handle_time = now() where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status, @Param("handleNote") String handleNote);

    // 统计客户送出总桶数（已完成订单）
    @Select("select ifnull(sum(delivery_bucket_qty), 0) from orders where customer_id = #{customerId} and status = 3")
    int sumDeliveryBuckets(@Param("customerId") Integer customerId);

    // 统计客户已回收总桶数（已完成订单）
    @Select("select ifnull(sum(return_bucket_qty), 0) from orders where customer_id = #{customerId} and status = 3")
    int sumReturnBuckets(@Param("customerId") Integer customerId);

    // 统计客户待退桶数（退桶申请中）
    @Select("select ifnull(sum(quantity), 0) from barrel_record where customer_id = #{customerId} and status = 1")
    int sumPendingReturns(@Param("customerId") Integer customerId);

    // 统计客户已退桶数（已确认+已退押金）
    @Select("select ifnull(sum(quantity), 0) from barrel_record where customer_id = #{customerId} and status in (2, 3)")
    int sumConfirmedReturns(@Param("customerId") Integer customerId);

    // 客户押金余额
    @Select("select ifnull(deposit_balance, 0) from customer where id = #{customerId}")
    BigDecimal getDepositBalance(@Param("customerId") Integer customerId);

    // 更新客户押金余额
    @Update("update customer set deposit_balance = #{balance} where id = #{id}")
    void updateDepositBalance(@Param("id") Integer id, @Param("balance") BigDecimal balance);

    // 最近退桶记录
    @Select("select * from barrel_record where customer_id = #{customerId} order by create_time desc limit #{limit}")
    List<BarrelRecord> recentRecords(@Param("customerId") Integer customerId, @Param("limit") int limit);

    // 客户最近一次订单（用于默认下单参数）
    @Select("select o.id, o.water_type_id, o.quantity, o.special_note, w.name as waterTypeName " +
            "from orders o left join water_type w on o.water_type_id = w.id " +
            "where o.customer_id = #{customerId} order by o.create_time desc limit 1")
    Map<String, Object> getLastOrder(@Param("customerId") Integer customerId);

    // 水类型单价
    @Select("select id, name, spec, note from water_type where id = #{id}")
    Map<String, Object> getWaterTypeById(@Param("id") Integer id);

    // 按水类型统计持有桶数明细
    @Select("select w.id as waterTypeId, w.name as waterTypeName, w.spec as waterTypeSpec, " +
            "ifnull(sum(o.delivery_bucket_qty), 0) as deliveryQty, " +
            "ifnull(sum(o.return_bucket_qty), 0) as returnQty, " +
            "ifnull(sum(o.delivery_bucket_qty - o.return_bucket_qty), 0) as holdingQty " +
            "from orders o left join water_type w on o.water_type_id = w.id " +
            "where o.customer_id = #{customerId} and o.delivery_bucket_qty > 0 " +
            "group by w.id, w.name, w.spec " +
            "having holdingQty > 0")
    List<Map<String, Object>> getBarrelSummaryByType(@Param("customerId") Integer customerId);
}
