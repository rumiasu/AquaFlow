package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderBarrelPurchase;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

/** 随单押金购买及退款凭据；不直接修改桶账。 */
@Mapper
public interface OrderBarrelPurchaseMapper {
    @Select("select * from order_barrel_purchase where order_id=#{id} order by product_id for update")
    List<OrderBarrelPurchase> lockOrder(Long id);
    @Select("select * from order_barrel_purchase where order_id=#{id} order by product_id")
    List<OrderBarrelPurchase> listOrder(Long id);
    @Select("select count(*) from order_barrel_purchase where order_id=#{id}")
    int hasOrder(Long id);
    @Select("select * from order_barrel_purchase where lot_id=#{id}")
    OrderBarrelPurchase byLot(Long id);
    @Insert("insert into order_barrel_purchase(order_id,customer_id,station_id,product_id,quantity,unit_price,amount) values(#{orderId},#{customerId},#{stationId},#{productId},#{quantity},#{unitPrice},#{amount})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(OrderBarrelPurchase row);
    @Update("update order_barrel_purchase set status='PAID',payment_id=#{payment},lot_id=#{lot} where id=#{id} and status='PENDING'")
    int activate(@Param("id") Long id,@Param("payment") Long payment,@Param("lot") Long lot);
    @Update("update order_barrel_purchase set status='CANCELLED' where order_id=#{id} and status='PENDING'")
    int cancelPending(Long id);
    @Update("update order_barrel_purchase set status=case when refunded_qty+#{quantity}=quantity then 'REFUNDED' else 'PAID' end, refunded_qty=refunded_qty+#{quantity},refunded_amount=refunded_amount+#{amount} where id=#{id} and status='PAID' and refunded_qty+#{quantity}<=quantity and refunded_amount+#{amount}<=amount")
    int refund(@Param("id") Long id,@Param("quantity") int quantity,@Param("amount") BigDecimal amount);
    @Insert("insert into order_barrel_refund(purchase_id,original_payment_id,refund_payment_id,return_record_id,quantity,amount,reason) values(#{purchase},#{original},#{refund},#{record},#{quantity},#{amount},#{reason})")
    int refundProof(@Param("purchase") Long purchase,@Param("original") Long original,@Param("refund") Long refund,@Param("record") Long record,@Param("quantity") int quantity,@Param("amount") BigDecimal amount,@Param("reason") String reason);
    @Select("select coalesce(sum(amount),0) from order_barrel_refund where original_payment_id=#{id}")
    BigDecimal refunded(Long id);
    @Select("select id as receiptId,amount from order_barrel_refund where original_payment_id=#{id}")
    List<java.util.Map<String,Object>> refundRows(Long id);
}
