package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderTransfer;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 订单转单 Mapper [AQ-015]。
 * <p>承载转单状态的结构化读写，替代原先依赖 special_note 文本标记的 LIKE 判定。</p>
 */
@Mapper
public interface OrderTransferMapper {

    @Insert("insert into order_transfer(order_id, kind, sub_kind, from_staff_id, to_staff_id, from_station_id, to_station_id, status, reason, operator_id, create_time, update_time) " +
            "values(#{orderId}, #{kind}, #{subKind}, #{fromStaffId}, #{toStaffId}, #{fromStationId}, #{toStationId}, #{status}, #{reason}, #{operatorId}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderTransfer record);

    /** 查询订单最新一条待决策转单 */
    @Select("select * from order_transfer where order_id = #{orderId} and status = 'PENDING' order by id desc limit 1")
    OrderTransfer findPendingByOrder(@Param("orderId") Long orderId);

    /**
     * 查询订单最新一条**指定类型**的待决策转单（撤回与决策都要按 kind 定位，
     * 否则会误判成"另一类转单被撤了"）。
     *
     * <p>[2026-09-18 补] 撤回（{@code cancelTransfer}）需要先拿到这条记录才能校验
     * "调用者是不是发起人"（{@code from_staff_id}）。补这个方法之前，全仓**没有任何**
     * 按 {@code from_staff_id} 过滤/取值的 SQL，于是那条校验根本无法实现 ——
     * 结果是同站任意员工都能撤掉别人的转单。</p>
     */
    @Select("select * from order_transfer where order_id = #{orderId} and kind = #{kind} "
            + "and status = 'PENDING' order by id desc limit 1")
    OrderTransfer findPendingByOrderAndKind(@Param("orderId") Long orderId, @Param("kind") String kind);

    /** 订单是否存在待决策转单 */
    @Select("select count(*) from order_transfer where order_id = #{orderId} and status = 'PENDING'")
    int countPendingByOrder(@Param("orderId") Long orderId);

    /** 订单待决策转单的类型（无则返回 null），供订单实体判定 transferKind */
    @Select("select kind from order_transfer where order_id = #{orderId} and status = 'PENDING' order by id desc limit 1")
    String findPendingKindByOrder(@Param("orderId") Long orderId);

    /** 按ID置状态 */
    @Update("update order_transfer set status = #{status}, update_time = NOW() where id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 把某订单所有待决策转单置为指定状态（决策点用）。
     * 返回受影响行数；0 表示无待决策转单（已被处理或不存在）。
     */
    @Update("update order_transfer set status = #{status}, operator_id = #{operatorId}, update_time = NOW() " +
            "where order_id = #{orderId} and status = 'PENDING'")
    int resolvePending(@Param("orderId") Long orderId, @Param("status") String status, @Param("operatorId") Long operatorId);

    /**
     * 按类型把某订单待决策转单置为指定状态（区分 STAFF / DIRECTED，避免误消另一类）。
     */
    @Update("update order_transfer set status = #{status}, operator_id = #{operatorId}, update_time = NOW() " +
            "where order_id = #{orderId} and kind = #{kind} and status = 'PENDING'")
    int resolvePendingByKind(@Param("orderId") Long orderId, @Param("kind") String kind,
                             @Param("status") String status, @Param("operatorId") Long operatorId);

    /** 按订单查询全部转单记录（倒序，审计展示用） */
    @Select("select * from order_transfer where order_id = #{orderId} order by id desc")
    List<OrderTransfer> listByOrder(@Param("orderId") Long orderId);
}
