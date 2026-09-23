package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.PaymentRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PaymentRecordMapper {

    /**
     * 新增支付流水。
     *
     * <p>⚠️ {@code delivery_fee} / {@code floor_fee} 用 {@code IFNULL(..., 0.00)} 兜住，
     * <b>不要改回裸 {@code #{deliveryFee}}</b>：这两列是 v34 加的 {@code NOT NULL DEFAULT 0.00}，
     * 而实体字段默认是 {@code null} —— 两条写路径（{@code PaymentServiceImpl.createPayment}
     * 与 {@code TicketAccountServiceImpl.purchaseTicket}）都不设这两个字段，
     * 裸写会把 {@code NULL} 显式塞进 NOT NULL 列，MySQL 直接报
     * 「Column 'delivery_fee' cannot be null」，**整条支付链路全挂**
     * （实测：一次全量测试 29 个用例失败，覆盖购票、现金收款、退款、押金、取消回滚）。
     * 注意 SQL 里的 {@code DEFAULT 0.00} 只在「列没出现在 INSERT 里」时生效，
     * 显式传 NULL 时它救不了你。</p>
     */
    @Insert("insert into payment_record(order_id, idempotency_key, customer_id, station_id, amount, payment_method, status, transaction_no, operator_id, note, create_time, update_time, water_amount, barrel_deposit, delivery_fee, floor_fee, excess_barrels, ticket_water_type_id, ticket_qty, ticket_package_id) " +
            "values(#{orderId}, #{idempotencyKey}, #{customerId}, #{stationId}, #{amount}, #{paymentMethod}, #{status}, #{transactionNo}, #{operatorId}, #{note}, #{createTime}, #{updateTime}, #{waterAmount}, #{barrelDeposit}, IFNULL(#{deliveryFee}, 0.00), IFNULL(#{floorFee}, 0.00), #{excessBarrels}, #{ticketWaterTypeId}, #{ticketQty}, #{ticketPackageId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PaymentRecord record);

    /**
     * 按客户端幂等键查已有流水（{@code uk_payment_idempotency(customer_id, idempotency_key)}）。
     *
     * <p><b>必须带 customerId 查询</b>：单靠 token 查会让客户端编造/复用别人的 token 时
     * 拿回别人的支付记录。{@code idempotencyKey} 为 null 时本查询恒返回空
     * （SQL 中 {@code NULL = NULL} 为 unknown），这正是订单支付想要的行为。</p>
     *
     * <p>用途：{@code TicketAccountServiceImpl.purchaseTicket} 的串行重放命中路径
     * ——连点两次「买票」时返回同一笔流水，而不是新建第二笔。</p>
     */
    @Select("select * from payment_record where customer_id = #{customerId} and idempotency_key = #{idempotencyKey} order by id desc limit 1")
    PaymentRecord getByCustomerAndIdempotencyKey(@Param("customerId") Long customerId,
                                                 @Param("idempotencyKey") String idempotencyKey);

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
     * <p>⚠️ <b>[2026-09-23 改名] 原名 {@code updateStatusIf}，参数顺序是 (id, <b>新</b>, <b>期望</b>)</b>
     * —— 而 {@code OrderMapper.updateStatusIf} / {@code OrderBarrelExceptionMapper.updateStatusIf}
     * <b>同名却是 (id, 期望, 新)</b>，两派并存、没有任何编译期保护。
     * 本仓在这一点上<b>静默失败过 3 次</b>（见 {@code StaffPayrollMapper} 的 javadoc）。
     * 现把"新在前"的两个改名为 {@code updateStatusTo}：<b>名字里带 To，就说明第二个参数是目标状态</b>，
     * 顺序不再靠记忆。新写 CAS 请沿用：{@code updateStatusIf} = (期望, 新)，
     * {@code updateStatusTo} = (新, 期望)。</p>
     *
     * @return 1=更新成功，0=状态已变更（并发下被别人改过）
     */
    @Update("update payment_record set status = #{status}, update_time = NOW() " +
            "where id = #{id} and status = #{expectStatus}")
    int updateStatusTo(@Param("id") Long id,
                       @Param("status") Integer status,
                       @Param("expectStatus") Integer expectStatus);

    /**
     * 把这张订单<b>尚未确认</b>的待收款流水改挂到新的结算站（v47，2026-09-18）。
     *
     * <p>只在订单的<b>结算站发生变化</b>时调用：抢单 / 定向外派 / 退回池 / 召回 / 指定退回-同意。
     * 流水的站别是在"发起收款"那一刻按当时的站写死的（{@code PaymentServiceImpl.createPayment}），
     * 订单随后换了站它不会自己跟着走 —— 结果是「履约站收了钱、凭据却挂在归属站」，
     * 正是 v47 要消灭的那种钱货分家。</p>
     *
     * <p>⚠️ 只搬 {@code status = 1}（{@link com.example.aquaflow.constant.PaymentStatus#PENDING}）
     * 的行：已收 / 已退的历史凭据不能改站，那是已经发生过的钱，改了等于伪造账；
     * 而新收的那笔（{@code recordCashCollection}）本来就按结算站写。</p>
     *
     * @return 受影响行数（0 = 这张单没有待收款流水，属正常）
     */
    @Update("update payment_record set station_id = #{stationId}, update_time = NOW() " +
            "where order_id = #{orderId} and status = 1")
    int movePendingToStation(@Param("orderId") Long orderId, @Param("stationId") Long stationId);

    /**
     * 把这张订单已有的<b>待收款</b>流水就地确认成已付（并改挂到结算站）。
     *
     * <p>⚠️ <b>收款路径必须走本方法，不能再插一条 PAID</b>：{@code active_order_id} 生成列把
     * {@code status ∈ (1,2)} 都算活跃，配合 {@code uk_payment_active_order} 是"一单一条活跃流水"。
     * 客户下单后点过「去支付」（现金单会先落一条 PENDING）时，配送员送达再点「已收款」若走插入，
     * 必然撞唯一键 → {@code DuplicateKeyException} → 整个送达事务回滚，
     * 前端拿到 {@code code=1「数据已存在，请勿重复提交」}，订单永远停在配送中（2026-09-18 实测复现）。</p>
     *
     * <p>{@code note} / {@code operatorId} 覆盖写：这条凭据此刻表达的是"谁在什么时候收的这笔钱"，
     * 比原来那条"客户发起的收款意图"更接近事实。</p>
     *
     * @return 受影响行数（0 = 这张单没有待收款流水，调用方按"新插一条"处理）
     */
    @Update("update payment_record set status = 2, station_id = #{stationId}, " +
            "note = #{note}, operator_id = #{operatorId}, update_time = NOW() " +
            "where order_id = #{orderId} and status = 1")
    int confirmPendingToPaid(@Param("orderId") Long orderId,
                             @Param("stationId") Long stationId,
                             @Param("note") String note,
                             @Param("operatorId") Long operatorId);

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
