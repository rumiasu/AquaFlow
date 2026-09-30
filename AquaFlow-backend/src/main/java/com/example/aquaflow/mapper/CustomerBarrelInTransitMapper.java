package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.CustomerBarrelInTransit;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CustomerBarrelInTransitMapper {

    // [DEF-2] 必须落 unit_price：这是下单当时算出的桶权益单价快照，
    // 配送完成时 BarrelLedgerService.resolveUnitPrice 首选它来建押金条(customer_barrel_lot.unit_price)。
    // 旧实现漏了这一列，快照恒为 NULL → 回退到 order_item.deposit（桶装水被刻意记 0）
    // → 押金条单价 0 → 退桶退款退 ¥0，顾客已付押金退不出去。
    @Insert("insert into customer_barrel_in_transit(customer_id, station_id, product_id, qty, unit_price, related_order_id, status, created_at, updated_at) " +
            "values(#{customerId}, #{stationId}, #{productId}, #{qty}, #{unitPrice}, #{relatedOrderId}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(CustomerBarrelInTransit inTransit);

    @Select("select * from customer_barrel_in_transit where id = #{id}")
    CustomerBarrelInTransit getById(@Param("id") Long id);

    @Select("select * from customer_barrel_in_transit where customer_id = #{customerId} and station_id = #{stationId} order by created_at desc")
    List<CustomerBarrelInTransit> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from customer_barrel_in_transit where related_order_id = #{orderId}")
    List<CustomerBarrelInTransit> listByOrderId(@Param("orderId") Long orderId);

    @Select("select * from customer_barrel_in_transit where related_order_id = #{orderId} and status = 'PENDING'")
    List<CustomerBarrelInTransit> listPendingByOrderId(@Param("orderId") Long orderId);

    /**
     * 无期望态的无守卫写入（历史遗留原语）。
     *
     * <p>⚠️ [F-32] <b>"配送中 → 已送达"的状态流转一律走 {@link #updateStatusIf}</b>：
     * 本方法 SQL 里没有 {@code status} 条件、返回 {@code void}（调用方连受影响行数都拿不到），
     * 在「同一行被并发或重复推进」时照样返回成功 —— 而调用点（{@code BarrelLedgerService.applyDelivery}）
     * 在改状态之前就已经把权益/押金条记过一遍了，重复入账会被这次"静默成功"掩盖。
     * 本仓另有 3 次 CAS 参数写反 ⇒ 恒命中 0 行 ⇒ 不报错、静默没改的事故（AGENTS.md §1.1）。</p>
     */
    @Update("update customer_barrel_in_transit set status = #{status}, updated_at = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * CAS：只有该行<b>仍是</b> {@code expectedStatus} 时才改写成 {@code newStatus}，返回受影响行数。
     *
     * <p>参数顺序与 {@code OrderMapper.updateStatusIf} / {@code OrderBarrelExceptionMapper.updateStatusIf}
     * 一致：<b>第二个参数是「期望状态」</b>（带 {@code If} 的第二个是期望、带 {@code To} 的第二个是目标，
     * 本仓曾把两者写反 —— 传反恒命中 0 行、不报错、静默没改，见 AGENTS.md §1.1）。
     * 调用方必须检查返回值：0 行不许当成功（AGENTS.md §8.20）。</p>
     */
    @Update("update customer_barrel_in_transit set status = #{newStatus}, updated_at = NOW() "
            + "where id = #{id} and status = #{expectedStatus}")
    int updateStatusIf(@Param("id") Long id, @Param("expectedStatus") String expectedStatus,
                       @Param("newStatus") String newStatus);

    @Update("update customer_barrel_in_transit set status = #{status}, updated_at = NOW() where related_order_id = #{orderId}")
    void updateStatusByOrderId(@Param("orderId") Long orderId, @Param("status") String status);

    @Delete("delete from customer_barrel_in_transit where related_order_id = #{orderId}")
    void deleteByOrderId(@Param("orderId") Long orderId);

    @Update("<script>" +
            "update customer_barrel_in_transit set related_order_id = #{orderId}, updated_at = NOW() " +
            "where id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " and status = 'PENDING' and related_order_id IS NULL" +
            "</script>")
    void linkPendingToOrder(@Param("orderId") Long orderId,
                            @Param("ids") List<Long> ids);
}