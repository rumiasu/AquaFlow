package com.example.aquaflow.mapper;

import com.example.aquaflow.entity.OrderTemplateItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderTemplateItemMapper {

    @Insert("insert into order_template_item(template_id, product_id, quantity) " +
            "values(#{templateId}, #{productId}, #{quantity})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(OrderTemplateItem item);

    @Select("select * from order_template_item where template_id = #{templateId}")
    List<OrderTemplateItem> listByTemplateId(@Param("templateId") Long templateId);

    @Delete("delete from order_template_item where template_id = #{templateId}")
    void deleteByTemplateId(@Param("templateId") Long templateId);
}
