package com.example.aquaflow.mapper;

import com.example.aquaflow.dto.ProductWithInventoryVO;
import com.example.aquaflow.entity.Product;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProductMapper {

    @Insert("insert into product(name, category, brand, spec, image_object_name, description, price, deposit, max_per_order, status, sort, create_time, update_time) " +
            "values(#{name}, #{category}, #{brand}, #{spec}, #{imageObjectName}, #{description}, #{price}, #{deposit}, #{maxPerOrder}, #{status}, #{sort}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Product product);

    @Update("update product set name=#{name}, category=#{category}, brand=#{brand}, spec=#{spec}, image_object_name=#{imageObjectName}, " +
            "description=#{description}, price=#{price}, deposit=#{deposit}, max_per_order=#{maxPerOrder}, status=#{status}, sort=#{sort}, update_time=#{updateTime} where id=#{id}")
    void update(Product product);

    /**
     * 软删除(停用/下架): 不物理删除, 保留历史订单的商品引用.
     * status: 0 下架 1 正常 2 停售
     */
    @Update("update product set status = 0, update_time = NOW() where id = #{id}")
    void deleteById(@Param("id") Long id);

    @Update("update product set status = #{status}, update_time = NOW() where id = #{id}")
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    @Select("select * from product order by sort asc, id asc")
    List<Product> list();

    @Select("select * from product where id = #{id}")
    Product getById(@Param("id") Long id);

    List<Product> listByKeyword(@Param("keyword") String keyword);

    @Select("select * from product where category = #{category} and status = 1 order by sort asc, id asc")
    List<Product> listByCategory(@Param("category") Integer category);

    @Select("select * from product where status = 1 order by sort asc, id asc")
    List<Product> listOnSale();

    @Select("select distinct p.* from product p " +
            "join inventory i on i.product_id = p.id " +
            "where i.station_id = #{stationId} and p.status = 1")
    List<Product> listByStationId(@Param("stationId") Long stationId);

    // ===== 管理端: 商品 + 库存联合查询 =====

    @Select("select p.id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, p.status, p.sort, p.create_time, p.update_time, " +
            "i.id as inventory_id, i.quantity, i.enabled, i.ticket_enabled, i.ticket_price, i.priority_display " +
            "from product p " +
            "left join inventory i on i.product_id = p.id and i.station_id = #{stationId} " +
            "order by i.priority_display desc, p.sort asc, p.id asc")
    List<ProductWithInventoryVO> listWithInventory(@Param("stationId") Long stationId);

    @Select("select p.id, p.name, p.category, p.brand, p.spec, p.image_object_name, p.description, " +
            "p.price, p.deposit, p.max_per_order, p.status, p.sort, p.create_time, p.update_time, " +
            "i.id as inventory_id, i.quantity, i.enabled, i.ticket_enabled, i.ticket_price, i.priority_display " +
            "from product p " +
            "left join inventory i on i.product_id = p.id and i.station_id = #{stationId} " +
            "where p.id = #{id}")
    ProductWithInventoryVO getByIdWithInventory(@Param("id") Long id, @Param("stationId") Long stationId);
}