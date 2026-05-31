package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.Inventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface InventoryMapper {

    List<Inventory> list();

    @Insert("insert into inventory (water_type_id, quantity) values (#{waterTypeId},#{quantity})")
    void save(Inventory inventory);

    Inventory getByWaterTypeId(Integer waterTypeId);

    void insert(Inventory newInventory);

    void update(Inventory inventory);
}
