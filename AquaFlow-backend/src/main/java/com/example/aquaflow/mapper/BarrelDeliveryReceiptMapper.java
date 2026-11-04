package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** 配送实收来源和明确分配凭据；不写实物桶账，不推断历史流水来源。 */
@Mapper
public interface BarrelDeliveryReceiptMapper {
    String SOURCE = "select r.id as recordId,r.related_order_id as orderId,r.customer_id as customerId,r.station_id as stationId,r.product_id as productId,r.returned_qty as returnedQty,s.input_kind as inputKind from barrel_record r left join barrel_delivery_receipt s on s.record_id=r.id where r.related_order_id=#{orderId} and r.customer_id=#{customerId} and r.station_id=#{stationId} and r.product_id=#{productId} and r.type=8 and r.status=3 order by r.id";

    @Insert("insert into barrel_delivery_receipt(record_id,input_kind) values(#{recordId},#{inputKind})")
    int insertSource(@Param("recordId") Long recordId,@Param("inputKind") String inputKind);

    @Select(SOURCE)
    List<Map<String,Object>> sources(@Param("orderId") Long orderId,@Param("customerId") Long customerId,@Param("stationId") Long stationId,@Param("productId") Long productId);

    @Select(SOURCE+" for update")
    List<Map<String,Object>> sourcesForUpdate(@Param("orderId") Long orderId,@Param("customerId") Long customerId,@Param("stationId") Long stationId,@Param("productId") Long productId);

    @Select("select delivery_record_id as deliveryReceiptId,quantity from barrel_return_delivery_allocation where return_record_id=#{recordId}")
    Map<String,Object> allocation(@Param("recordId") Long recordId);

    @Select("select quantity from barrel_return_delivery_allocation where delivery_record_id=#{receiptId} order by return_record_id for update")
    List<Integer> allocatedForUpdate(@Param("receiptId") Long receiptId);

    @Select("select coalesce(sum(quantity),0) from barrel_return_delivery_allocation where delivery_record_id=#{receiptId}")
    int allocated(@Param("receiptId") Long receiptId);

    @Insert("insert into barrel_return_delivery_allocation(return_record_id,delivery_record_id,quantity,operator_id) values(#{recordId},#{receiptId},#{quantity},#{operatorId})")
    int allocate(@Param("recordId") Long recordId,@Param("receiptId") Long receiptId,@Param("quantity") int quantity,@Param("operatorId") Long operatorId);
}
