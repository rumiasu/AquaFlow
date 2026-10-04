package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.BarrelRightPurchase;
import com.example.aquaflow.entity.BarrelRightReservation;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 只写独立购买/分配凭据；桶数量与金额仍由 BarrelLedgerService 编排。 */
@Mapper
public interface BarrelBusinessMapper {
    @Select("select * from barrel_right_purchase where id=#{id}") BarrelRightPurchase purchaseById(Long id);
    @Update("update barrel_right_purchase set status='CANCELLED' where id=#{id} and status='PENDING'") int cancelPurchase(Long id);
    /** 切换期历史执行单没有新凭据，按原数量保守占用；只读、不改历史解释。 */
    @Select("select coalesce(sum(i.quantity),0) from orders o join order_item i on i.order_id=o.id where o.customer_id=#{customer} and o.station_id=#{station} and i.product_id=#{product} and o.status in (1,2) and o.id<>#{excluded} and not exists(select 1 from barrel_right_reservation r where r.owner_type='ORDER' and r.owner_id=o.id)")
    int legacyOrderBusyExcept(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product,@Param("excluded") Long excluded);
    default int legacyOrderBusy(Long c,Long s,Long p) { return legacyOrderBusyExcept(c,s,p,0L); }
    @Select("select coalesce(sum(b.quantity),0) from barrel_record b where b.customer_id=#{customer} and b.station_id=#{station} and b.product_id=#{product} and b.type=2 and b.status in (1,2) and b.id<>#{excluded} and not exists(select 1 from barrel_return_detail d where d.record_id=b.id)")
    int legacyReturnBusyExcept(@Param("customer") Long customer,@Param("station") Long station,@Param("product") Long product,@Param("excluded") Long excluded);
    default int legacyReturnBusy(Long c,Long s,Long p) { return legacyReturnBusyExcept(c,s,p,0L); }
    @Select("select * from barrel_right_reservation where customer_id=#{customerId} and station_id=#{stationId} and product_id=#{productId} and status='ACTIVE' order by id for update")
    List<BarrelRightReservation> activeForUpdate(@Param("customerId") Long customerId,@Param("stationId") Long stationId,@Param("productId") Long productId);
    @Select("select * from barrel_right_purchase where customer_id=#{customerId} and idempotency_key=#{key}")
    BarrelRightPurchase findPurchase(@Param("customerId") Long customerId, @Param("key") String key);

    /** 建款编号锁等待后须当前读原申请，不能复用外层事务先前的 RR 快照。 */
    @Select("select * from barrel_right_purchase where customer_id=#{customerId} and idempotency_key=#{key} for update")
    BarrelRightPurchase findPurchaseForUpdate(@Param("customerId") Long customerId, @Param("key") String key);

    @Select("select * from barrel_right_purchase where payment_id=#{paymentId} for update")
    BarrelRightPurchase purchaseByPayment(@Param("paymentId") Long paymentId);

    @Select("select * from barrel_right_purchase where lot_id=#{lotId}")
    BarrelRightPurchase purchaseByLot(@Param("lotId") Long lotId);

    @Select("select * from barrel_right_purchase where customer_id=#{customerId} and station_id=#{stationId} order by id desc")
    List<BarrelRightPurchase> purchases(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Insert("insert into barrel_right_purchase(customer_id,station_id,product_id,quantity,unit_price,amount,payment_id,idempotency_key,status,create_time) values(#{customerId},#{stationId},#{productId},#{quantity},#{unitPrice},#{amount},#{paymentId},#{idempotencyKey},'PENDING',now())")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertPurchase(BarrelRightPurchase purchase);

    @Update("update barrel_right_purchase set status='PAID',lot_id=#{lotId} where id=#{id} and status='PENDING'")
    int activatePurchase(@Param("id") Long id, @Param("lotId") Long lotId);

    @Select("select coalesce(sum(quantity-pending_qty),0) from barrel_right_reservation where customer_id=#{customerId} and station_id=#{stationId} and product_id=#{productId} and status='ACTIVE'")
    int reserved(@Param("customerId") Long customerId,@Param("stationId") Long stationId,@Param("productId") Long productId);

    @Select("select coalesce(sum(pickup_qty-pending_pickup_qty),0) from barrel_right_reservation where customer_id=#{customerId} and station_id=#{stationId} and product_id=#{productId} and status='ACTIVE'")
    int reservedPickup(@Param("customerId") Long customerId,@Param("stationId") Long stationId,@Param("productId") Long productId);

    @Insert("insert into barrel_right_reservation(customer_id,station_id,product_id,owner_type,owner_id,quantity,pickup_qty,pending_qty,pending_pickup_qty,status,create_time) values(#{customerId},#{stationId},#{productId},#{ownerType},#{ownerId},#{quantity},#{pickupQty},#{pendingQty},#{pendingPickupQty},'ACTIVE',now())")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insertReservation(BarrelRightReservation reservation);

    @Select("select * from barrel_right_reservation where owner_type=#{type} and owner_id=#{ownerId} order by product_id")
    List<BarrelRightReservation> reservations(@Param("type") String type,@Param("ownerId") Long ownerId);

    @Select("select * from barrel_right_reservation where owner_type=#{type} and owner_id=#{ownerId} order by product_id for update")
    List<BarrelRightReservation> reservationsForUpdate(@Param("type") String type,@Param("ownerId") Long ownerId);

    @Select("select count(*) from barrel_right_reservation where owner_type='ORDER' and owner_id=#{orderId}")
    int hasOrder(@Param("orderId") Long orderId);

    @Update("update barrel_right_reservation set pending_qty=0,pending_pickup_qty=0,pickup_qty=#{pickup} where id=#{id} and status='ACTIVE' and pending_qty=#{expected}")
    int fundReservation(@Param("id") Long id,@Param("expected") int expected,@Param("pickup") int pickup);

    @Update("update barrel_right_reservation set status=#{status} where owner_type=#{type} and owner_id=#{ownerId} and status='ACTIVE'")
    int release(@Param("type") String type,@Param("ownerId") Long ownerId,@Param("status") String status);
}
