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

    // [2026-09-30 F-32/F-19] 已删除无期望态的 updateStatus(id, status)：全仓零调用。
    // 原调用点 BarrelLedgerService.applyDelivery 已改走下面的 updateStatusIf 并检查受影响行数
    // —— 旧版 SQL 没有 status 条件、返回 void，调用方连行数都拿不到，"静默成功"会掩盖重复入账。
    // 要改状态一律用 updateStatusIf（AGENTS §8.20：拿不到行数就别返回 success）。

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

    // [2026-09-30 F-32/F-19] 已删除 updateStatusByOrderId(orderId, status)：全仓零调用。
    // 它是「按 related_order_id 把多行一次改成同一状态」的整批改写，且无期望态、返回 void ——
    // 与台账 F-42（applyDelivery 顺序重放会把"本单新购权益"漏算）是同一类风险形状：
    // 批量状态改写没有"这行本来是什么状态"的概念，重放时会再改一遍。
    // 按订单操作请用 linkPendingToOrder / deleteByOrderId；改单行状态用 updateStatusIf。

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