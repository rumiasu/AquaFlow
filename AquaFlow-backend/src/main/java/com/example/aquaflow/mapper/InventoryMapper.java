package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Inventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface InventoryMapper {

    List<Inventory> list();

    @Insert("insert into inventory (water_type_id, quantity) values (#{waterTypeId},#{quantity})")
    void save(Inventory inventory);

    Inventory getByWaterTypeId(Integer waterTypeId);

    void insert(Inventory newInventory);

    void update(Inventory inventory);

    @Update("update inventory set quantity = quantity - #{quantity}, update_time = now() " +
            "where water_type_id = #{waterTypeId}")
    void decreaseStock(@Param("waterTypeId") Integer waterTypeId, @Param("quantity") Integer quantity);

    @Select("select ifnull(sum(quantity), 0) from inventory")
    int sumQuantity();

}
