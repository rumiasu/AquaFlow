package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelLot;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 桶权益批次（押金条）数据访问 —— 金额唯一真相源。
 */
@Mapper
public interface CustomerBarrelLotMapper {

    @Insert("insert into customer_barrel_lot" +
            "(lot_no, customer_id, station_id, product_id, unit_price, qty, remain_qty," +
            " source_type, price_source, related_order_id, deposit_record_id," +
            " status, is_migrated, operator_id, note, create_time, update_time) " +
            "values(#{lotNo}, #{customerId}, #{stationId}, #{productId}, #{unitPrice}, #{qty}, #{remainQty}," +
            " #{sourceType}, #{priceSource}, #{relatedOrderId}, #{depositRecordId}," +
            " #{status}, #{isMigrated}, #{operatorId}, #{note}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CustomerBarrelLot lot);

    /**
     * 生成正式押金条凭证号（依赖自增 id，故插入后回填）。
     * 先插 TMP- 占位再改名，是为了避免自增与业务号两套序列打架。
     */
    @Update("update customer_barrel_lot set lot_no = #{lotNo}, update_time = NOW() where id = #{id}")
    int setLotNo(@Param("id") Long id, @Param("lotNo") String lotNo);

    @Select("select * from customer_barrel_lot where id = #{id}")
    CustomerBarrelLot getById(@Param("id") Long id);

    /**
     * 可核销批次，FIFO（先买的先终止）。
     * 不用 LIFO：那会优先退掉高价批次，损害水站。
     */
    @Select("select * from customer_barrel_lot " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId} " +
            "  and status = 1 and remain_qty > 0 " +
            "order by create_time asc, id asc")
    List<CustomerBarrelLot> listAvailable(@Param("customerId") Long customerId,
                                          @Param("stationId") Long stationId,
                                          @Param("productId") Long productId);

    /** 同上使用行锁，供写路径（退桶核销）使用 */
    @Select("select * from customer_barrel_lot " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId} " +
            "  and status = 1 and remain_qty > 0 " +
            "order by create_time asc, id asc for update")
    List<CustomerBarrelLot> listAvailableForUpdate(@Param("customerId") Long customerId,
                                                   @Param("stationId") Long stationId,
                                                   @Param("productId") Long productId);

    @Select("select * from customer_barrel_lot " +
            "where customer_id = #{customerId} and station_id = #{stationId} " +
            "order by product_id, create_time asc, id asc")
    List<CustomerBarrelLot> listByCustomerAndStation(@Param("customerId") Long customerId,
                                                     @Param("stationId") Long stationId);

    /**
     * 核销指定数量。remain_qty 不足时 affected=0（调用方必须校验并回滚），
     * 这是防止并发超退的关键——不要用「先查后减」。
     */
    @Update("update customer_barrel_lot set remain_qty = remain_qty - #{qty}, update_time = NOW() " +
            "where id = #{id} and status = 1 and remain_qty >= #{qty}")
    int consume(@Param("id") Long id, @Param("qty") int qty);

    @Update("update customer_barrel_lot set remain_qty = remain_qty + #{qty}, update_time = NOW() " +
            "where id = #{id}")
    int restore(@Param("id") Long id, @Param("qty") int qty);

    @Update("update customer_barrel_lot set status = 2, update_time = NOW() " +
            "where id = #{id} and remain_qty <= 0")
    int markExhausted(@Param("id") Long id);

    @Select("select coalesce(sum(remain_qty), 0) from customer_barrel_lot " +
            "where customer_id = #{customerId} and station_id = #{stationId} and product_id = #{productId} and status = 1")
    int sumRemain(@Param("customerId") Long customerId,
                  @Param("stationId") Long stationId,
                  @Param("productId") Long productId);

    /** 应退桶款 = Σ remain_qty × unit_price */
    @Select("select coalesce(sum(remain_qty * unit_price), 0) from customer_barrel_lot " +
            "where customer_id = #{customerId} and station_id = #{stationId} and status = 1")
    BigDecimal sumRightAmount(@Param("customerId") Long customerId,
                              @Param("stationId") Long stationId);
}
