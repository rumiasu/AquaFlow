package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Customer;
import com.example.aquaflow.vo.CustomerStationVO;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CustomerMapper {

    @Insert("insert into customer(openid, name, phone, customer_type, note, first_order_time, last_delivery_time, create_time, update_time) " +
            "values(#{openid}, #{name}, #{phone}, #{customerType}, #{note}, #{firstOrderTime}, #{lastDeliveryTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Customer customer);

    @Select("select * from customer where id = #{id}")
    Customer getById(@Param("id") Long id);

    @Update("update customer set openid=#{openid}, name=#{name}, phone=#{phone}, customer_type=#{customerType}, " +
            "note=#{note}, first_order_time=#{firstOrderTime}, " +
            "last_delivery_time=#{lastDeliveryTime}, update_time=NOW() where id=#{id}")
    void update(Customer customer);

    @Delete("delete from customer where id = #{id}")
    void delete(@Param("id") Long id);

    @Select("select * from customer")
    List<Customer> list();

    @Select("select * from customer where openid = #{openid}")
    Customer findByOpenid(@Param("openid") String openid);

    @Select("select * from customer where phone = #{phone} limit 1")
    Customer findByPhone(@Param("phone") String phone);

    @Select("select * from customer where name like concat('%', #{keyword}, '%') or phone like concat('%', #{keyword}, '%')")
    List<Customer> search(@Param("keyword") String keyword);

    @Select("select distinct c.* from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    List<Customer> listByStationId(@Param("stationId") Long stationId);

    @Select("select distinct c.* from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId} " +
            "and (c.name like concat('%', #{keyword}, '%') or c.phone like concat('%', #{keyword}, '%'))")
    List<Customer> searchByStation(@Param("stationId") Long stationId, @Param("keyword") String keyword);

    /**
     * 站长客户视图：本站有订单的客户，LEFT JOIN 客户×水站配置带出货到付款权限。
     * 派生字段（驼峰别名）由 CustomerStationVO 接收。
     */
    @Select("select c.id, c.name, c.phone, c.customer_type as customerType, c.note, " +
            // [AQ-054] customer.deposit_balance 为废弃列（只读不写恒 0），展示改用真实来源 deposit_account
            "coalesce((select da.balance from customer_deposit_account da where da.customer_id = c.id and da.station_id = #{stationId}), 0) as depositBalance, c.total_orders as totalOrders, " +
            "c.total_consumption as totalConsumption, c.tags, " +
            "c.first_order_time as firstOrderTime, c.last_delivery_time as lastDeliveryTime, " +
            "c.create_time as createTime, " +
            "coalesce(csc.offline_payment_enabled, 0) as offlinePaymentEnabled " +
            "from customer c " +
            "join orders o on c.id = o.customer_id " +
            "left join customer_station_config csc on csc.customer_id = c.id and csc.station_id = #{stationId} " +
            "where o.station_id = #{stationId} " +
            "group by c.id")
    List<CustomerStationVO> listStationCustomers(@Param("stationId") Long stationId);

    /**
     * 单个客户的站长视图（含本站货到付款权限）。无订单/无配置关系时返回 null。
     */
    @Select("select c.id, c.name, c.phone, c.customer_type as customerType, c.note, " +
            // [AQ-054] customer.deposit_balance 为废弃列（只读不写恒 0），展示改用真实来源 deposit_account
            "coalesce((select da.balance from customer_deposit_account da where da.customer_id = c.id and da.station_id = #{stationId}), 0) as depositBalance, c.total_orders as totalOrders, " +
            "c.total_consumption as totalConsumption, c.tags, " +
            "c.first_order_time as firstOrderTime, c.last_delivery_time as lastDeliveryTime, " +
            "c.create_time as createTime, " +
            "coalesce(csc.offline_payment_enabled, 0) as offlinePaymentEnabled " +
            "from customer c " +
            "left join customer_station_config csc on csc.customer_id = c.id and csc.station_id = #{stationId} " +
            "where c.id = #{customerId} " +
            "and exists (select 1 from orders o where o.customer_id = c.id and o.station_id = #{stationId})")
    CustomerStationVO getStationCustomer(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select count(*) from customer")
    int countAll();

    @Select("select count(distinct c.id) from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    // ==================== 客户画像聚合 ====================

    /** 常用地址（默认地址优先） */
    @Select("select concat_ws('', ifnull(a.province,''), ifnull(a.city,''), ifnull(a.district,''), ifnull(a.detail,'')) " +
            "from address a where a.customer_id = #{customerId} " +
            "order by a.is_default desc, a.id desc limit 1")
    String getDefaultAddress(@Param("customerId") Long customerId);

    /** 本站已完成订单数 */
    @Select("select count(*) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId}")
    int countCompletedOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本站累计消费金额 */
    @Select("select coalesce(sum(o.total_amount),0) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId}")
    BigDecimal sumConsumption(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本月完成订单数 */
    @Select("select count(*) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.create_time >= date_format(now(), '%Y-%m-01')")
    int countMonthOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本月消费金额 */
    @Select("select coalesce(sum(o.total_amount),0) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.create_time >= date_format(now(), '%Y-%m-01')")
    BigDecimal sumMonthConsumption(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 最近下单时间 */
    @Select("select max(o.create_time) from orders o where o.customer_id = #{customerId} " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId}")
    LocalDateTime getLastOrderTime(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本站水票余额 */
    @Select("select coalesce(sum(remain_quantity),0) from ticket_account " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    Integer getTicketBalance(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 本站欠桶数：按商品统计 Σ max(0, over_qty)。
     * 欠桶已改为按商品记录在 customer_barrel_over；over 可为负（多还桶/水站暂存，合法状态），
     * 负值不能抵销其他商品的欠桶，所以必须先 greatest(over_qty, 0) 再求和。
     */
    @Select("select coalesce(sum(greatest(over_qty, 0)),0) from customer_barrel_over " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    Integer getOwedBarrels(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 桶异常次数 */
    @Select("select count(*) from order_barrel_exception " +
            "where customer_id = #{customerId} and station_id = #{stationId}")
    int countExceptions(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 常用商品 TOP3 */
    @Select("select ifnull(p.name, oi.product_name_snapshot) as name, sum(oi.quantity) as qty " +
            "from order_item oi join orders o on oi.order_id = o.id " +
            "left join product p on oi.product_id = p.id " +
            "where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId} " +
            "group by ifnull(p.name, oi.product_name_snapshot) " +
            "order by qty desc limit 3")
    List<Map<String, Object>> listFavoriteProducts(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 最近 5 笔订单 */
    @Select("select o.id, o.status, o.total_amount as totalAmount, o.create_time as createTime, " +
            "o.receiver_name as receiverName " +
            "from orders o where o.customer_id = #{customerId} " +
            "and coalesce(o.delivery_station_id, o.station_id) = #{stationId} " +
            "order by o.create_time desc limit 5")
    List<Map<String, Object>> listRecentOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}