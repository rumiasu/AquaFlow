package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.TicketRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface TicketRecordMapper {

    @Insert("insert into ticket_record(customer_id, product_id, station_id, increase_qty, decrease_qty, order_id, source, ticket_source, unit_price, ticket_lot_id, create_time, adjustment_id) " +
            "values(#{customerId}, #{productId}, #{stationId}, #{increaseQty}, #{decreaseQty}, #{orderId}, #{source}, #{ticketSource}, #{unitPrice}, #{ticketLotId}, #{createTime}, #{adjustmentId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(TicketRecord ticketRecord);

    @Select("select * from ticket_record where id = #{id}")
    TicketRecord getById(@Param("id") Long id);

    /**
     * 取某订单某商品的**消费**流水（v36）。
     *
     * <p>用途：订单取消要回补水票时，必须按**当时消耗的批次单价**还原，
     * 而不是按退款时的当前价 —— 否则站长在这中间调过一次价，客户拿回的票就凭空变了值。</p>
     *
     * <p>⚠️ v54 曾让它再读一列 {@code account_product_id}（"这笔扣自哪个账户"）用于把票退回原账户；
     * 2026-09-20 产品澄清「按统一折扣买的票只能抵那款水」之后，账户恒等于 {@code product_id}，
     * 该列已由 v59 撤回，本查询回到只服务于"按当时单价还原"这一个用途。</p>
     */
    @Select("select * from ticket_record where order_id = #{orderId} and product_id = #{productId} "
            + "and decrease_qty > 0 order by id desc limit 1")
    TicketRecord getConsumeRecord(@Param("orderId") Long orderId, @Param("productId") Long productId);

    @Select("select * from ticket_record where customer_id = #{customerId} and product_id = #{productId} and station_id = #{stationId} order by create_time desc")
    List<TicketRecord> listByCustomerAndProduct(@Param("customerId") Long customerId, @Param("productId") Long productId, @Param("stationId") Long stationId);

    /**
     * 统计某订单某商品已产生的水票消费流水数量。
     * 用于水票扣减幂等：同一订单重复触发扣减时直接跳过，避免重复扣票。
     */
    @Select("select count(*) from ticket_record where order_id = #{orderId} and product_id = #{productId} " +
            "and decrease_qty > 0")
    int countConsumeByOrderAndProduct(@Param("orderId") Long orderId, @Param("productId") Long productId);

    /**
     * 统计某订单某商品已产生的水票**退款回补**流水数量（2026-09-18 补）。
     *
     * <p>用途：退款回补的幂等闸门。水票回补一旦发生就不该再来一次，两条退款路径
     * （订单取消链 {@code refundOrder} / 站长手工退款 {@code refundPayment}）都必须在
     * 调 {@code refundTicket} 之前先问这一句。</p>
     *
     * <p><b>为什么不能只靠 {@code uk_ticket_consume(order_id, product_id, source)} 兜底</b>：
     * 那条唯一键确实能挡住第二次回补（回补流水撞键 → 整个事务回滚，票不会多），
     * 但它是**数据库异常**，会被兜底处理器转成 {@code code=500}「系统错误」，
     * 让"这张单已经退过了"这种正常业务判断看起来像后端崩了 —— 与 AGENTS.md §8.17 同类。</p>
     */
    @Select("select count(*) from ticket_record where order_id = #{orderId} and product_id = #{productId} "
            + "and increase_qty > 0 and source = '退款'")
    int countRefundByOrderAndProduct(@Param("orderId") Long orderId, @Param("productId") Long productId);

    @Select("select * from ticket_record where customer_id = #{customerId} order by create_time desc")
    List<TicketRecord> listByCustomerId(@Param("customerId") Long customerId);

    /** 按客户+水站拉取水票流水（实体版，供站长端资产明细使用；资产按水站隔离，station_id 不可省） */
    @Select("select * from ticket_record where customer_id = #{customerId} and station_id = #{stationId} order by create_time desc")
    List<TicketRecord> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    /**
     * 水票流水的公共字段列表。
     * <p>必须逐个起驼峰别名：Map 的键名会直接透给小程序，而两端约定的是
     * {@code increaseQty/decreaseQty/createTime}。此前用 {@code tr.*} 带出下划线列名，
     * 导致"消费记录"标签页的名称、时间、增减数量全部渲染为空。</p>
     */
    String RECORD_DETAIL_COLUMNS =
            "select tr.id as id, " +
            "tr.customer_id as customerId, " +
            "tr.product_id as productId, " +
            "tr.station_id as stationId, " +
            "tr.order_id as orderId, " +
            "tr.increase_qty as increaseQty, " +
            "tr.decrease_qty as decreaseQty, " +
            "tr.source as source, " +
            "tr.ticket_source as ticketSource, " +
            "tr.create_time as createTime, " +
            "p.name as productName, " +
            "p.spec as productSpec " +
            "from ticket_record tr " +
            "left join product p on tr.product_id = p.id ";

    @Select(RECORD_DETAIL_COLUMNS +
            "where tr.customer_id = #{customerId} " +
            "order by tr.create_time desc")
    List<Map<String, Object>> listByCustomerIdWithDetail(@Param("customerId") Long customerId);

    /** [AQ-023] 按客户+水站过滤，杜绝登录站长跨站查询他站客户水票流水 */
    @Select(RECORD_DETAIL_COLUMNS +
            "where tr.customer_id = #{customerId} and tr.station_id = #{stationId} " +
            "order by tr.create_time desc")
    List<Map<String, Object>> listByCustomerAndStationWithDetail(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
