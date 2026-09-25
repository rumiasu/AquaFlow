package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketAccount;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface TicketAccountMapper {

    @Insert("insert into ticket_account(customer_id, product_id, station_id, remain_quantity, update_time) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{remainQuantity}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketAccount ticketAccount);

    @Select("select * from ticket_account where id = #{id}")
    TicketAccount getById(@Param("id") Long id);

    @Select("select * from ticket_account where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId}")
    TicketAccount getByCustomerProductStation(@Param("customerId") Long customerId,
                                              @Param("productId") Long productId,
                                              @Param("stationId") Long stationId);

    @Update("update ticket_account set remain_quantity = remain_quantity - #{qty}, update_time = NOW() where id = #{id} and remain_quantity >= #{qty}")
    int decrementQuantity(@Param("id") Long id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = remain_quantity + #{qty}, update_time = NOW() where id = #{id}")
    void incrementQuantity(@Param("id") Long id, @Param("qty") Integer qty);

    @Update("update ticket_account set remain_quantity = #{remainQuantity}, update_time = NOW() where id = #{id}")
    void updateQuantity(@Param("id") Long id, @Param("remainQuantity") Integer remainQuantity);

    /**
     * 同步「剩余水票的金额价值」（v36）。
     *
     * <p>它是**派生列**，真相源是 {@code ticket_lot}：值必须等于
     * {@code Σ lot.remain_qty × lot.unit_price}。任何改动批次的路径（购票入账、消耗、退款回补、
     * 站长加票）都必须在同一事务里调它，否则对账 E8 立刻报不平。</p>
     *
     * <p>⚠️ 只写正数、不在这里做业务判断 —— 金额方向与大小的判断属于账务逻辑，不属于 mapper。</p>
     */
    @Update("update ticket_account set right_amount = #{rightAmount}, update_time = NOW() where id = #{id}")
    void setRightAmount(@Param("id") Long id, @Param("rightAmount") java.math.BigDecimal rightAmount);

    @Select("select * from ticket_account where customer_id = #{customerId} and station_id = #{stationId}")
    List<TicketAccount> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 客户在本站的水票明细（含商品名/规格）。
     * <p>注意：这里的 Map 直接回给小程序，键名即字段名。此前用 {@code ta.*} 会带出
     * {@code remain_quantity} 等下划线列名，而两端约定的是驼峰 {@code remainQuantity}，
     * 结果水票页「剩余张数」恒为空、合计张数恒为 0 —— 客户买了票却看不到票。
     * 因此这里逐个显式起驼峰别名，不要改回 {@code ta.*}。</p>
     */
    @Select("select ta.id as id, " +
            "ta.customer_id as customerId, " +
            "ta.product_id as productId, " +
            "ta.station_id as stationId, " +
            "ta.remain_quantity as remainQuantity, " +
            "ta.right_amount as rightAmount, " +
            "ta.update_time as updateTime, " +
            "p.name as productName, " +
            "p.spec as productSpec, " +
            "p.price as price, " +
            "p.ticket_price as ticketPrice, " +
            "coalesce(nullif(i.ticket_price, 0), nullif(p.ticket_price, 0), nullif(i.sale_price, 0), p.price) as effectiveTicketPrice " +
            "from ticket_account ta " +
            "left join product p on ta.product_id = p.id " +
            "left join inventory i on i.product_id = ta.product_id and i.station_id = ta.station_id " +
            "where ta.customer_id = #{customerId} and ta.station_id = #{stationId} " +
            "order by ta.update_time desc")
    List<Map<String, Object>> listByCustomerAndStationWithDetail(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
