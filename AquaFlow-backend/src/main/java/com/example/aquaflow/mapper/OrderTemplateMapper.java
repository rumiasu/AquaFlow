package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderTemplate;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderTemplateMapper {

    @Insert("insert into order_template(customer_id, name, address_id, special_note, is_default, enabled, create_time, update_time) " +
            "values(#{customerId}, #{name}, #{addressId}, #{specialNote}, #{isDefault}, #{enabled}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderTemplate template);

    @Update("update order_template set name=#{name}, address_id=#{addressId}, special_note=#{specialNote}, " +
            "is_default=#{isDefault}, enabled=#{enabled}, update_time=#{updateTime} where id=#{id}")
    void update(OrderTemplate template);

    // 获取客户启用的默认模板（is_default=1）
    @Select("select * from order_template where customer_id = #{customerId} and is_default = 1 and enabled = 1 limit 1")
    OrderTemplate getDefault(@Param("customerId") Integer customerId);

    // 获取客户所有模板
    @Select("select * from order_template where customer_id = #{customerId} order by is_default desc, update_time desc")
    List<OrderTemplate> listByCustomerId(@Param("customerId") Integer customerId);

    // 获取客户最近一次完成的订单（作为默认模板备选）
    @Select("select o.id, o.water_type_id, o.quantity, o.address_id, o.special_note " +
            "from orders o where o.customer_id = #{customerId} and o.status = 3 " +
            "order by o.create_time desc limit 1")
    OrderTemplate getLastCompletedOrder(@Param("customerId") Integer customerId);

    // 设置默认模板（先全部取消默认，再设置当前为默认）
    @Update("update order_template set is_default = 0, update_time = now() where customer_id = #{customerId} and is_default = 1")
    void clearDefault(@Param("customerId") Integer customerId);

    @Update("update order_template set is_default = 1, update_time = now() where id = #{id}")
    void setDefault(@Param("id") Integer id);

    // 切换启用状态
    @Update("update order_template set enabled = #{enabled}, update_time = now() where id = #{id} and customer_id = #{customerId}")
    void toggleEnabled(@Param("id") Integer id, @Param("customerId") Integer customerId, @Param("enabled") Integer enabled);

    // 删除模板
    @Delete("delete from order_template where id = #{id} and customer_id = #{customerId}")
    void delete(@Param("id") Integer id, @Param("customerId") Integer customerId);
}
