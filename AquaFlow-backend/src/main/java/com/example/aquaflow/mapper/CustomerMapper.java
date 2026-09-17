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

    /**
     * 客户是否归属本站 —— <b>站长端"对这个客户动手"的归属判据</b>。
     *
     * <p>⚠️ <b>不要用 {@link #getStationCustomer} 代替本方法</b>。它的 SQL 带
     * {@code exists (select 1 from orders ...)}，那是「客户画像」的口径（有订单才有画像）；
     * 拿来当归属判据，<b>从没下过单的新客户恒定被判定为"不属于本站"</b>。
     * 这个坑本仓已踩过一次：{@code OrderController.getMyLatestStation} 的注释写着
     * 「旧实现只查订单，新客户恒定拿到 null」。2026-09-17 在用画像口径校验客户特权归属时
     * <b>又踩了一次</b> —— 免起送门槛的典型场景恰恰是"新客户第一单"，
     * 用画像口径会把该场景整个挡掉（用例 {@code CustomerPrivilegeIntegrationTest}）。</p>
     *
     * <p><b>归属的正确口径 = 两者取并集</b>：① 水站已把该客户纳入管辖
     * （{@code customer_station_config} 绑定行，站长代建/认领即产生）；② 该客户在本站下过单
     * （老客户可能没有绑定行）。缺任何一条都会漏判一类客户。</p>
     *
     * @return 1 = 归属本站；0 = 不归属本站或客户不存在。用 int 而不是 boolean：
     *         MyBatis 对 boolean 的映射依赖驱动，用 count 更稳
     */
    @Select("select count(*) from customer c where c.id = #{customerId} and (" +
            "exists (select 1 from customer_station_config csc where csc.customer_id = c.id and csc.station_id = #{stationId}) " +
            "or exists (select 1 from orders o where o.customer_id = c.id and o.station_id = #{stationId}))")
    int countCustomerOfStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 代客下单的客户选择器（站长端）：按姓名/电话关键字搜「本站客户」。
     *
     * <p>⚠️ <b>为什么不复用 {@link #listStationCustomers}（即 {@code GET /api/customers}）</b>：
     * 它的 SQL 是 <b>orders 驱动</b>（{@code join orders o ... where o.station_id = ?}），
     * <b>没下过单的客户一行都查不出来</b>。而站长刚在客户管理里新建的客户恰恰就是这种 ——
     * 于是"给新客户下第一单"这个最常见的代客下单场景，会在客户列表里找不到人，
     * 看起来像"客户没建成功"。归属口径因此与 {@link #countCustomerOfStation} 一致：
     * 绑定 <b>或</b> 本站订单，取并集。</p>
     *
     * <p>关键字过滤写成 {@code #{keyword} is null or ...} 而不是动态 {@code <if>}，
     * 与 {@code AddressMapper.list} 同款：少一处拼接就少一处出错的地方。</p>
     *
     * @param keyword 姓名或电话片段；{@code null}/空 = 不筛（返回最近建档的若干条）
     */
    @Select("select c.id, c.name, c.phone, c.customer_type as customerType from customer c "
            + "where (exists (select 1 from customer_station_config csc "
            + "                where csc.customer_id = c.id and csc.station_id = #{stationId}) "
            + "    or exists (select 1 from orders o where o.customer_id = c.id and o.station_id = #{stationId})) "
            + "and (#{keyword} is null or #{keyword} = '' "
            + "     or c.name like concat('%', #{keyword}, '%') "
            + "     or c.phone like concat('%', #{keyword}, '%')) "
            + "order by c.id desc limit 50")
    List<java.util.Map<String, Object>> listOrderCustomers(@Param("stationId") Long stationId,
                                                           @Param("keyword") String keyword);

    // [清理 2026-09-12] 删除 countAll()：全平台客户总数，零调用，且一旦被新页面顺手调用即跨站泄露。

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