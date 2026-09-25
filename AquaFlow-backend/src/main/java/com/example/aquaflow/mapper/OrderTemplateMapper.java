package com.example.aquaflow.mapper;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.OrderTemplate;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderTemplateMapper {

    @Insert("insert into order_template(customer_id, station_id, name, special_note, enabled, is_default, create_time, update_time) " +
            "values(#{customerId}, #{stationId}, #{name}, #{specialNote}, #{enabled}, #{isDefault}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderTemplate template);

    /**
     * ⚠️ 无归属限定，只按 id 更新。**不要在新代码里直接用它**：
     * [2026-09-16] {@code OrderTemplateServiceImpl.save} 曾走它，于是顾客只要把别人的模板 id 传进来，
     * 就能覆盖别人的模板并顺带清空别人的模板明细（`itemMapper.deleteByTemplateId`）。
     * 需要更新请用 {@link #updateOwned}。
     */
    @Update("update order_template set name=#{name}, special_note=#{specialNote}, " +
            "enabled=#{enabled}, is_default=#{isDefault}, update_time=NOW() " +
            "where id=#{id}")
    void update(OrderTemplate template);

    /** [2026-09-16 AQ-036 补齐] 归属限定的更新：只改属于该客户的模板，返回受影响行数（0=不属于他/已删） */
    @Update("update order_template set name=#{name}, special_note=#{specialNote}, " +
            "enabled=#{enabled}, is_default=#{isDefault}, update_time=NOW() " +
            "where id=#{id} and customer_id=#{customerId}")
    int updateOwned(OrderTemplate template);

    @Select("select * from order_template where id = #{id}")
    OrderTemplate getById(@Param("id") Long id);

    @Select("select * from order_template where customer_id = #{customerId} and station_id = #{stationId} and is_default = 1 and enabled = 1 limit 1")
    OrderTemplate getDefault(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Select("select * from order_template where customer_id = #{customerId} and station_id = #{stationId} order by is_default desc, update_time desc")
    List<OrderTemplate> listByCustomerAndStation(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Update("update order_template set is_default = 0, update_time = NOW() where customer_id = #{customerId} and station_id = #{stationId} and is_default = 1")
    void clearDefault(@Param("customerId") Long customerId, @Param("stationId") Long stationId);

    @Update("update order_template set is_default = 1, update_time = NOW() where id = #{id}")
    void setDefault(@Param("id") Long id);

    @Update("update order_template set enabled = #{enabled}, update_time = NOW() where id = #{id}")
    void toggleEnabled(@Param("id") Long id, @Param("enabled") Integer enabled);

    @Delete("delete from order_template where id = #{id}")
    void delete(@Param("id") Long id);

    // ===== [AQ-036] 归属限定版本：只操作属于该客户的模板，返回受影响行数 =====

    /** 归属限定的启用/停用 */
    @Update("update order_template set enabled = #{enabled}, update_time = NOW() where id = #{id} and customer_id = #{customerId}")
    int toggleEnabledOwned(@Param("id") Long id, @Param("enabled") Integer enabled, @Param("customerId") Long customerId);

    /** 归属限定的删除 */
    @Delete("delete from order_template where id = #{id} and customer_id = #{customerId}")
    int deleteOwned(@Param("id") Long id, @Param("customerId") Long customerId);

    /** 归属限定的设为默认 */
    @Update("update order_template set is_default = 1, update_time = NOW() where id = #{id} and customer_id = #{customerId}")
    int setDefaultOwned(@Param("id") Long id, @Param("customerId") Long customerId);

    @Select("select ot.* from order_template ot " +
            "inner join orders o on o.customer_id = ot.customer_id " +
            "where ot.customer_id = #{customerId} and ot.station_id = #{stationId} and o.status = " + OrderStatus.COMPLETED + " " +
            "order by o.update_time desc limit 1")
    OrderTemplate getLastCompletedOrder(@Param("customerId") Long customerId, @Param("stationId") Long stationId);
}
