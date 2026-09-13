package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.PaymentRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PaymentRecordMapper {

    @Insert("insert into payment_record(order_id, customer_id, station_id, amount, payment_method, status, transaction_no, operator_id, note, create_time, update_time, water_amount, barrel_deposit, excess_barrels, ticket_water_type_id, ticket_qty) " +
            "values(#{orderId}, #{customerId}, #{stationId}, #{amount}, #{paymentMethod}, #{status}, #{transactionNo}, #{operatorId}, #{note}, #{createTime}, #{updateTime}, #{waterAmount}, #{barrelDeposit}, #{excessBarrels}, #{ticketWaterTypeId}, #{ticketQty})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PaymentRecord record);

    @Select("select * from payment_record where id = #{id}")
    PaymentRecord getById(@Param("id") Long id);

    @Select("select * from payment_record where order_id = #{orderId} order by create_time")
    List<PaymentRecord> listByOrderId(@Param("orderId") Long orderId);

    @Select("select * from payment_record where order_id = #{orderId} order by create_time desc limit 1")
    PaymentRecord getByOrderId(@Param("orderId") Long orderId);

    /**
     * 统计订单在指定状态下的支付流水条数。
     * [AQ-002][AQ-007] 用于完成配送前的支付前置校验：
     * 订单能不能置「已付款」，唯一凭据是存在 status=PAID 的流水，而不是配送员说了算。
     */
    @Select("select count(*) from payment_record where order_id = #{orderId} and status = #{status}")
    int countByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") Integer status);

    @Select("select * from payment_record where customer_id = #{customerId} order by create_time desc")
    List<PaymentRecord> listByCustomerId(@Param("customerId") Long customerId);

    /** [AQ-023] 按客户+水站过滤，杜绝登录站长跨站查询他站客户支付流水 */
    @Select("select * from payment_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<PaymentRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from payment_record where station_id = #{stationId} order by create_time desc")
    List<PaymentRecord> listByStationId(@Param("stationId") Long stationId);

    @Update("update payment_record set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 乐观锁更新（CAS）：仅当当前状态为 expectStatus 时才更新为目标 status，返回受影响行数。
     *
     * [AQ-003] 此前这里是 {@code updateStatusIfPending}，SQL 硬编码 {@code and status = 0}，
     * 但 PaymentStatus.PENDING = 1，导致支付确认永远只影响 0 行 —— 支付主链路是断的。
     * 改为把期望状态作为参数传入，由调用方显式给出，避免再次写死常量。
     *
     * @return 1=更新成功，0=状态已变更（并发下被别人改过）
     */
    @Update("update payment_record set status = #{status}, update_time = NOW() " +
            "where id = #{id} and status = #{expectStatus}")
    int updateStatusIf(@Param("id") Long id,
                       @Param("status") Integer status,
                       @Param("expectStatus") Integer expectStatus);

    @Select("select * from payment_record where station_id = #{stationId} " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{paymentMethod} is null or payment_method = #{paymentMethod}) " +
            "order by create_time desc limit #{limit}")
    List<PaymentRecord> listByStation(@Param("stationId") Long stationId,
                                      @Param("status") Integer status,
                                      @Param("paymentMethod") Integer paymentMethod,
                                      @Param("limit") int limit);

    @Select("select * from payment_record where station_id = #{stationId} order by create_time desc limit #{limit}")
    List<PaymentRecord> listAllByStation(@Param("stationId") Long stationId, @Param("limit") int limit);

    /**
     * 站长待确认收款列表（含客户名与所购商品名）。
     *
     * <p>覆盖两类待确认收款，它们此前<b>都没有任何界面入口</b>：</p>
     * <ol>
     *   <li><b>订单待收款</b>（{@code order_id} 非空）：现金/微信下单后生成的 PENDING 流水；</li>
     *   <li><b>线上买水票</b>（{@code order_id} 为空）：{@code POST /api/tickets/purchase} 只写 PENDING 流水，
     *       而微信支付渠道未接入，钱只能靠站长核对到账后手工确认 —— 没有这个列表，
     *       顾客付了钱、水票永远不入账（实测库里积压过多笔此类流水）。</li>
     * </ol>
     *
     * <p>返回 Map 而非 {@code PaymentRecord}：customerName / productName 是关联列，
     * 不该往实体里加非数据库字段。列表只取展示与确认所需字段，不回传整行。</p>
     */
    @Select("select p.id, p.order_id as orderId, p.customer_id as customerId, "
            + "c.name as customerName, c.phone as customerPhone, "
            + "p.station_id as stationId, p.amount, p.payment_method as paymentMethod, "
            + "p.ticket_water_type_id as ticketProductId, p.ticket_qty as ticketQty, "
            + "p.note, p.create_time as createTime, "
            + "(select oi.product_name_snapshot from order_item oi where oi.order_id = p.order_id order by oi.id limit 1) as productName "
            + "from payment_record p "
            + "left join customer c on c.id = p.customer_id "
            + "where p.station_id = #{stationId} and p.status = 1 "
            + "order by p.create_time asc limit #{limit}")
    List<java.util.Map<String, Object>> listPendingByStation(@Param("stationId") Long stationId,
                                                             @Param("limit") int limit);
}
