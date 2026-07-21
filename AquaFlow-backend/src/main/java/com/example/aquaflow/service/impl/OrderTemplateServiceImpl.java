package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.OrderTemplate;
import com.example.aquaflow.entity.OrderTemplateItem;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.OrderTemplateItemMapper;
import com.example.aquaflow.mapper.OrderTemplateMapper;
import com.example.aquaflow.service.OrderTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderTemplateServiceImpl implements OrderTemplateService {

    @Autowired
    private OrderTemplateMapper templateMapper;

    @Autowired
    private OrderTemplateItemMapper itemMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public OrderTemplate getQuickOrder(Integer customerId) {
        // 优先返回默认模板
        OrderTemplate tpl = templateMapper.getDefault(customerId);
        if (tpl != null) {
            tpl.setItems(itemMapper.listByTemplateId(tpl.getId()));
            return tpl;
        }
        // 其次返回最近启用的模板
        List<OrderTemplate> all = templateMapper.listByCustomerId(customerId);
        for (OrderTemplate t : all) {
            if (t.getEnabled() != null && t.getEnabled() == 1) {
                t.setItems(itemMapper.listByTemplateId(t.getId()));
                return t;
            }
        }
        // 最后返回最近一次完成的订单
        return templateMapper.getLastCompletedOrder(customerId);
    }

    @Override
    public List<OrderTemplate> listByCustomerId(Integer customerId) {
        List<OrderTemplate> list = templateMapper.listByCustomerId(customerId);
        for (OrderTemplate tpl : list) {
            tpl.setItems(itemMapper.listByTemplateId(tpl.getId()));
        }
        return list;
    }

    @Override
    @Transactional
    public OrderTemplate save(Integer customerId, OrderTemplate template) {
        template.setCustomerId(customerId);
        template.setUpdateTime(LocalDateTime.now());

        if (template.getId() != null) {
            // 更新模板主体
            templateMapper.update(template);
            // 删除旧明细，插入新明细
            itemMapper.deleteByTemplateId(template.getId());
        } else {
            // 新建模板
            if (template.getEnabled() == null) template.setEnabled(1);
            if (template.getIsDefault() == null) template.setIsDefault(0);
            template.setCreateTime(LocalDateTime.now());
            templateMapper.insert(template);
        }

        // 插入明细
        if (template.getItems() != null) {
            for (OrderTemplateItem item : template.getItems()) {
                item.setTemplateId(template.getId());
                itemMapper.insert(item);
            }
        }

        // 如果设为默认，取消其他默认
        if (template.getIsDefault() != null && template.getIsDefault() == 1) {
            templateMapper.clearDefault(customerId);
            templateMapper.setDefault(template.getId());
        }

        return template;
    }

    @Override
    public void setDefault(Integer customerId, Integer templateId) {
        templateMapper.clearDefault(customerId);
        templateMapper.setDefault(templateId);
    }

    @Override
    public void toggleEnabled(Integer customerId, Integer templateId, Integer enabled) {
        templateMapper.toggleEnabled(templateId, customerId, enabled);
    }

    @Override
    @Transactional
    public OrderTemplate setFromOrder(Integer customerId, Integer orderId) {
        Orders order = orderMapper.getById(orderId);
        if (order == null || !order.getCustomerId().equals(customerId)) {
            throw new RuntimeException("订单不存在");
        }

        // 创建模板
        OrderTemplate template = new OrderTemplate();
        template.setCustomerId(customerId);
        template.setName(null);
        template.setAddressId(order.getAddressId());
        template.setSpecialNote(order.getSpecialNote());
        template.setEnabled(1);
        template.setIsDefault(0);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        templateMapper.insert(template);

        // 创建明细
        OrderTemplateItem item = new OrderTemplateItem();
        item.setTemplateId(template.getId());
        item.setWaterTypeId(order.getWaterTypeId());
        item.setQuantity(order.getQuantity());
        itemMapper.insert(item);

        template.setItems(List.of(item));
        return template;
    }

    @Override
    @Transactional
    public void delete(Integer customerId, Integer templateId) {
        itemMapper.deleteByTemplateId(templateId);
        templateMapper.delete(templateId, customerId);
    }
}
