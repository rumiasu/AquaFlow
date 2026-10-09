package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

/** 退还流程凭据，不直接写客户桶账。 */
@Mapper
public interface BarrelReturnDetailMapper {
    String ARRANGEMENT_FIELDS = "a.initial_pickup_mode,a.initial_companion_order_id,a.version as arrangement_version,a.requires_confirmation as arrangement_requires_confirmation,a.confirmed_version as customer_confirmed_version";
    @Select("select d.*,p.status as fee_payment_status," + ARRANGEMENT_FIELDS + " from barrel_return_detail d left join payment_record p on p.id=d.fee_payment_id left join barrel_return_arrangement a on a.record_id=d.record_id where d.record_id=#{recordId}")
    BarrelReturnDetail get(@Param("recordId") Long recordId);
    @Select("select * from barrel_return_detail where fee_payment_id=#{payment}") BarrelReturnDetail byFeePayment(Long payment);
    @Insert("insert into barrel_return_fee_refund(original_payment_id,refund_payment_id,record_id,amount,operator_id,note,create_time) values(#{original},#{refund},#{record},#{amount},#{operator},#{note},now())")
    int feeRefund(@Param("original") Long original,@Param("refund") Long refund,@Param("record") Long record,@Param("amount") BigDecimal amount,@Param("operator") Long operator,@Param("note") String note);

    @Select("select d.*, " + ARRANGEMENT_FIELDS + " from barrel_return_detail d left join barrel_return_arrangement a on a.record_id=d.record_id where d.record_id=#{recordId} for update")
    BarrelReturnDetail lock(@Param("recordId") Long recordId);

    @Select("select d.*, " + ARRANGEMENT_FIELDS + " from barrel_return_detail d join barrel_record r on r.id=d.record_id left join barrel_return_arrangement a on a.record_id=d.record_id where r.customer_id=#{customerId} and d.idempotency_key=#{key}")
    BarrelReturnDetail byIntent(@Param("customerId") Long customerId,@Param("key") String key);

    @Insert("insert into barrel_return_detail(record_id,customer_id,station_id,idempotency_key,pickup_mode,companion_order_id,required_barrels,received_barrels,pickup_fee,status,note) values(#{recordId},#{customerId},#{stationId},#{idempotencyKey},#{pickupMode},#{companionOrderId},#{requiredBarrels},0,0,'APPLIED',#{note})")
    int insert(BarrelReturnDetail detail);

    @Update("update barrel_return_detail set status='APPROVED',pickup_fee=#{fee},fee_payment_id=#{paymentId},approved_time=now(),note=#{note} where record_id=#{id} and status='APPLIED'")
    int approve(@Param("id") Long id,@Param("fee") BigDecimal fee,@Param("paymentId") Long paymentId,@Param("note") String note);

    /** 原子写入两张表；受影响行数可为1或2，0表示状态或版本CAS未命中。 */
    @Update("update barrel_return_detail d join barrel_return_arrangement a on a.record_id=d.record_id set d.customer_confirmed_time=now(),a.confirmed_version=a.version where d.record_id=#{id} and d.status='APPROVED' and a.version=#{version} and (d.customer_confirmed_time is null or a.confirmed_version is null or a.confirmed_version<>a.version)")
    int confirmCustomer(@Param("id") Long id,@Param("version") int version);

    // 2026-10-08：免费原安排不强迫再点确认；收费仍须本人授权，不补写确认时间。
    @Update("update barrel_return_detail d join barrel_return_arrangement a on a.record_id=d.record_id set d.status='RECEIVED',d.received_barrels=d.required_barrels,d.received_time=now(),d.refund_due_time=date_add(now(),interval 24 hour) where d.record_id=#{id} and d.status='APPROVED' and ((d.pickup_fee=0 and a.requires_confirmation=0) or (d.customer_confirmed_time is not null and a.confirmed_version=a.version))")
    int receive(@Param("id") Long id);

    @Update("update barrel_return_detail set pickup_mode=#{mode},companion_order_id=#{order},pickup_fee=0,fee_payment_id=null,status='APPLIED',approved_time=null,customer_confirmed_time=null where record_id=#{id} and status in ('APPLIED','APPROVED')")
    int changeArrangement(@Param("id") Long id,@Param("mode") String mode,@Param("order") Long order);

    @Update("update barrel_return_detail set status=#{status} where record_id=#{id} and status=#{expected}")
    int transition(@Param("id") Long id,@Param("expected") String expected,@Param("status") String status);

    @Select("select coalesce(sum(h.quantity),0) from barrel_return_lot_hold h join barrel_return_detail d on d.record_id=h.record_id where h.lot_id=#{lotId} and d.status in ('APPLIED','APPROVED','RECEIVED') for update")
    int heldLot(@Param("lotId") Long lotId);

    /** 只读试算与写路径统计同一活跃占用，不通过 FOR UPDATE 抢占实际办理的行锁。 */
    @Select("select coalesce(sum(h.quantity),0) from barrel_return_lot_hold h join barrel_return_detail d on d.record_id=h.record_id where h.lot_id=#{lotId} and d.status in ('APPLIED','APPROVED','RECEIVED')")
    int heldLotReadOnly(@Param("lotId") Long lotId);


    @Insert("insert into barrel_return_lot_hold(record_id,lot_id,quantity,unit_price,amount) values(#{recordId},#{lotId},#{quantity},#{unitPrice},#{amount})")
    int holdLot(@Param("recordId") Long recordId,@Param("lotId") Long lotId,@Param("quantity") int quantity,@Param("unitPrice") BigDecimal unitPrice,@Param("amount") BigDecimal amount);

    @Select("select lot_id as lotId,quantity as qty,unit_price as unitPrice,amount from barrel_return_lot_hold where record_id=#{recordId} order by lot_id")
    List<Map<String,Object>> heldLots(@Param("recordId") Long recordId);

    @Insert("insert into barrel_purchase_refund(payment_id,purchase_id,return_record_id,amount) values(#{paymentId},#{purchaseId},#{recordId},#{amount})")
    int refundProof(@Param("paymentId") Long paymentId,@Param("purchaseId") Long purchaseId,@Param("recordId") Long recordId,@Param("amount") BigDecimal amount);
}
