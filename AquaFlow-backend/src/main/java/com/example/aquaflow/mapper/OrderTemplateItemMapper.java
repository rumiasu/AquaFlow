package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderTemplateItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderTemplateItemMapper {

    @Insert("insert into order_template_item(template_id, water_type_id, quantity) " +
            "values(#{templateId}, #{waterTypeId}, #{quantity})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderTemplateItem item);

    @Select("select i.*, w.name as waterTypeName, w.spec as waterTypeSpec, w.price as waterTypePrice " +
            "from order_template_item i left join water_type w on i.water_type_id = w.id " +
            "where i.template_id = #{templateId}")
    List<OrderTemplateItem> listByTemplateId(@Param("templateId") Integer templateId);

    @Delete("delete from order_template_item where template_id = #{templateId}")
    void deleteByTemplateId(@Param("templateId") Integer templateId);
}
