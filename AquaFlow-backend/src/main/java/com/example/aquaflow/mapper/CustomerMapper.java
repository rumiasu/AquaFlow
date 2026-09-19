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

    /**
     * 只改客户类型（1 个人 / 2 企业）—— 企业身份审核通过时用（v50）。
     *
     * <p>⚠️ 必须用这个窄方法，不要拿 {@link #update(Customer)} 顶上：那一条是**整行覆盖写**，
     * 传一个只填了 id/type 的对象上去会把 openid、姓名、电话、备注一并清空。</p>
     */
    @Update("update customer set customer_type = #{customerType}, update_time = NOW() where id = #{id}")
    int updateCustomerType(@Param("id") Long id, @Param("customerType") Integer customerType);

    @Select("select * from customer")
    List<Customer> list();

    @Select("select * from customer where openid = #{openid}")
    Customer findByOpenid(@Param("openid") String openid);

    @Select("select * from customer where phone = #{phone} limit 1")
    Customer findByPhone(@Param("phone") String phone);

    /**
     * ⚠️ <b>不要用它做站长端搜索</b>（历史方法，当前全仓零调用，保留仅为不误删他人引用）。
     *
     * <p>两个硬伤恰好都是新口径要解决的问题：① <b>不按水站过滤</b>（{@code select * from customer}
     * 扫全平台客户，一旦被新页面顺手调用即跨站泄露）；② 只做 {@code name/phone} 的强子串匹配，
     * <b>不搜地址</b> —— 而站长认人主要靠地址。</p>
     *
     * <p>站长端搜索统一走 {@link #listSearchCandidates}（本站候选集）+
     * {@code util/CustomerSearchMatcher}（归一化与相关性打分），两个入口共用同一实现。</p>
     */
    @Select("select * from customer where name like concat('%', #{keyword}, '%') or phone like concat('%', #{keyword}, '%')")
    List<Customer> search(@Param("keyword") String keyword);

    @Select("select distinct c.* from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    List<Customer> listByStationId(@Param("stationId") Long stationId);

    /**
     * 站长端客户搜索的<b>候选集</b>（不是结果集）：本站客户 + 其地址文本，交给
     * {@code util/CustomerSearchMatcher} 归一化并打分排序。
     *
     * <p><b>为什么 SQL 里不带关键字过滤</b>：站长习惯把「阳光小区8栋1单元301」打成「阳光81301」，
     * 任何 {@code like '%关键字%'} 的粗筛都会把<b>本该命中的候选在进入打分之前就滤掉</b>
     * （SQL 粗筛只能宽、不能窄）。候选上限与超限策略见
     * {@code CustomerSearchMatcher.MAX_CANDIDATES}。</p>
     *
     * <p><b>归属口径 = 绑定 ∪ 本站订单</b>（与 {@link #countCustomerOfStation} 同源）：
     * 只查绑定行会漏掉"只下过单、没有绑定行"的老客户；只查订单又查不到"刚建档还没下单"的新客户，
     * 而后者恰恰是代客下单最常见的场景。这也是本方法<b>不能</b>复用
     * {@link #listStationCustomers}（orders 驱动，无订单一行都查不出）的原因。</p>
     *
     * <p><b>地址文本</b>同时覆盖两处来源：客户档案地址（{@code address} 按 customer_id 全量取，
     * 默认地址排在前面）与本站订单的地址快照（{@code orders.address_snapshot}）——
     * 客户改过/删过地址后，仍能按当初实际送货的地址搜到人。</p>
     *
     * <p>⚠️ 两条实现约束：① {@code group by c.id} 靠 MySQL 对主键的函数依赖带出其它 {@code c.*} 列，
     * 与 {@link #listStationCustomers} 同款；② {@code separator '\n'} 在 Java 源码里写作
     * {@code '\\n'}，用于把同一客户的多个地址分行（展示只取第一行）。</p>
     *
     * @param limit 候选上限；调用方传 {@code CustomerSearchMatcher.MAX_CANDIDATES}
     */
    @Select("select c.id, c.name, c.phone, c.customer_type as customerType, "
            + "group_concat(concat_ws('', ifnull(a.province,''), ifnull(a.city,''), ifnull(a.district,''), ifnull(a.detail,'')) "
            + "order by a.is_default desc, a.id desc separator '\\n') as addressText, "
            + "(select group_concat(o.address_snapshot separator ' ') from orders o "
            + " where o.customer_id = c.id and o.station_id = #{stationId} and o.address_snapshot is not null) as orderAddressText "
            + "from customer c "
            + "left join address a on a.customer_id = c.id "
            + "where (exists (select 1 from customer_station_config csc where csc.customer_id = c.id and csc.station_id = #{stationId}) "
            + "    or exists (select 1 from orders o2 where o2.customer_id = c.id and o2.station_id = #{stationId})) "
            + "group by c.id "
            + "order by c.id desc "
            + "limit #{limit}")
    List<Map<String, Object>> listSearchCandidates(@Param("stationId") Long stationId, @Param("limit") int limit);

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

    // [2026-09-18 客户地址搜索] 删除 listOrderCustomers（代客下单选择器的关键字 LIKE 查询）：
    //   它与 searchByStation 是同一件事的两份实现（都是"本站客户 + name/phone 强子串"），
    //   而"两处搜索各写一套"正是本仓计价双轨事故的同形风险。现在两个入口
    //   （GET /api/customers 客户列表、GET /api/manager/order-assist/customers 选择器）
    //   共用 CustomerMapper.listSearchCandidates + util/CustomerSearchMatcher。
    //   原注释里那条判据仍然有效，已随实现搬进 listSearchCandidates 的 javadoc：
    //   "归属 = 绑定 ∪ 本站订单；不能复用 orders 驱动的 listStationCustomers，否则新客户查不出来"。

    // [清理 2026-09-12] 删除 countAll()：全平台客户总数，零调用，且一旦被新页面顺手调用即跨站泄露。

    @Select("select count(distinct c.id) from customer c " +
            "join orders o on c.id = o.customer_id " +
            "where o.station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    // ==================== 客户画像聚合 ====================
    //
    // ⚠️ 站别口径在这一段是**混着两种**的，改之前先看清是哪一个（v47，2026-09-18）：
    //   · 「钱」类聚合（完成单数 / 累计消费 / 本月消费 / 最近下单 / 常买商品 / 最近订单）用
    //     **结算站** `coalesce(settle_station_id, delivery_station_id, station_id)` ——
    //     跨站外派单的水费 + 配送费 + 楼层费归实际配送站，所以它算在实际配送站的客户画像里。
    //     读侧的三级 coalesce 是**防御**：正常路径必须写 settle_station_id（见 v47 迁移文件头）。
    //   · 「资产」类聚合（水票余额 / 欠桶 / 桶异常）**仍按归属站 station_id**，一个字没改 ——
    //     那是"客户买在哪个站的资产"，与营收归谁是两件事（AGENTS §1.1）。别顺手"统一"掉。

    /** 常用地址（默认地址优先） */
    @Select("select concat_ws('', ifnull(a.province,''), ifnull(a.city,''), ifnull(a.district,''), ifnull(a.detail,'')) " +
            "from address a where a.customer_id = #{customerId} " +
            "order by a.is_default desc, a.id desc limit 1")
    String getDefaultAddress(@Param("customerId") Long customerId);

    /** 本站已完成订单数 */
    @Select("select count(*) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId}")
    int countCompletedOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本站累计消费金额 */
    @Select("select coalesce(sum(o.total_amount),0) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId}")
    BigDecimal sumConsumption(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本月完成订单数 */
    @Select("select count(*) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.create_time >= date_format(now(), '%Y-%m-01')")
    int countMonthOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 本月消费金额 */
    @Select("select coalesce(sum(o.total_amount),0) from orders o where o.customer_id = #{customerId} and o.status = 4 " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} " +
            "and o.create_time >= date_format(now(), '%Y-%m-01')")
    BigDecimal sumMonthConsumption(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 最近下单时间 */
    @Select("select max(o.create_time) from orders o where o.customer_id = #{customerId} " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId}")
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
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} " +
            "group by ifnull(p.name, oi.product_name_snapshot) " +
            "order by qty desc limit 3")
    List<Map<String, Object>> listFavoriteProducts(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /** 最近 5 笔订单 */
    @Select("select o.id, o.status, o.total_amount as totalAmount, o.create_time as createTime, " +
            "o.receiver_name as receiverName " +
            "from orders o where o.customer_id = #{customerId} " +
            "and coalesce(o.settle_station_id, o.delivery_station_id, o.station_id) = #{stationId} " +
            "order by o.create_time desc limit 5")
    List<Map<String, Object>> listRecentOrders(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}