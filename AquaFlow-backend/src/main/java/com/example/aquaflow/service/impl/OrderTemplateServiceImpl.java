package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.entity.OrderTemplate;
import com.example.aquaflow.entity.OrderTemplateItem;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.OrderItemMapper;
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

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Override
    public OrderTemplate getQuickOrder(Long customerId, Long stationId) {
        // H02: 模板按水站隔离
        OrderTemplate tpl = templateMapper.getDefault(customerId, stationId);
        if (tpl != null) {
            tpl.setItems(itemMapper.listByTemplateId(tpl.getId()));
            return tpl;
        }
        List<OrderTemplate> all = templateMapper.listByCustomerAndStation(customerId, stationId);
        for (OrderTemplate t : all) {
            if (t.getEnabled() != null && Integer.valueOf(1).equals(t.getEnabled())) {
                t.setItems(itemMapper.listByTemplateId(t.getId()));
                return t;
            }
        }
        return templateMapper.getLastCompletedOrder(customerId, stationId);
    }

    @Override
    public List<OrderTemplate> listByCustomerAndStation(Long customerId, Long stationId) {
        List<OrderTemplate> list = templateMapper.listByCustomerAndStation(customerId, stationId);
        for (OrderTemplate tpl : list) {
            tpl.setItems(itemMapper.listByTemplateId(tpl.getId()));
        }
        return list;
    }

    @Override
    @Transactional
    public OrderTemplate save(Long customerId, OrderTemplate template, Long stationId) {
        template.setCustomerId(customerId);
        template.setStationId(stationId);
        template.setUpdateTime(LocalDateTime.now());

        if (template.getId() != null) {
            // [2026-09-16 修复] 原来这里直接 templateMapper.update(template)（只按 id 过滤，无归属校验），
            // 于是顾客只要把**别人的模板 id** 传进来，就能覆盖别人的模板名/备注，并顺带
            // itemMapper.deleteByTemplateId 清空别人的模板明细 —— 与 [AQ-036] 已修的
            // setDefault / toggleEnabled / delete 是同一个洞，当时漏了这一处。
            // 现在走归属限定的 updateOwned，并按仓库约定检查受影响行数（0 = 不是他的 / 已删）。
            int affected = templateMapper.updateOwned(template);
            if (affected == 0) {
                throw new BusinessException("常用订单不存在，可能已被删除");
            }
            itemMapper.deleteByTemplateId(template.getId());
        } else {
            if (template.getEnabled() == null) template.setEnabled(1);
            if (template.getIsDefault() == null) template.setIsDefault(0);
            template.setCreateTime(LocalDateTime.now());
            templateMapper.insert(template);
        }

        if (template.getItems() != null) {
            for (OrderTemplateItem item : template.getItems()) {
                item.setTemplateId(template.getId());
                itemMapper.insert(item);
            }
        }

        if (template.getIsDefault() != null && Integer.valueOf(1).equals(template.getIsDefault())) {
            templateMapper.clearDefault(customerId, stationId);
            templateMapper.setDefault(template.getId());
        }

        return template;
    }

    @Override
    public void setDefault(Long customerId, Long templateId, Long stationId) {
        // [AQ-036] 旧实现 setDefault 只按 id 更新，customerId 仅用于 clearDefault → 顾客可把他人模板设为默认。
        // 先校验归属，再清空旧默认、设为新默认。
        OrderTemplate t = templateMapper.getById(templateId);
        if (t == null || t.getCustomerId() == null || !t.getCustomerId().equals(customerId)) {
            throw new BusinessException("常用订单不存在，可能已被删除");
        }
        templateMapper.clearDefault(customerId, stationId);
        templateMapper.setDefault(templateId);
    }

    @Override
    public void toggleEnabled(Long customerId, Long templateId, Integer enabled) {
        // [AQ-036] 归属校验下沉到 SQL：只更新属于该客户的模板
        int affected = templateMapper.toggleEnabledOwned(templateId, enabled, customerId);
        if (affected <= 0) {
            throw new BusinessException("常用订单不存在，可能已被删除");
        }
    }

    @Override
    @Transactional
    public OrderTemplate setFromOrder(Long customerId, Long orderId, Long stationId) {
        Orders order = orderMapper.getById(orderId);
        if (order == null || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("订单不存在");
        }

        OrderTemplate template = new OrderTemplate();
        template.setCustomerId(customerId);
        template.setStationId(stationId);
        template.setName(null);
        template.setSpecialNote(order.getSpecialNote());
        template.setEnabled(1);
        template.setIsDefault(0);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        templateMapper.insert(template);

        java.util.List<OrderTemplateItem> templateItems = new java.util.ArrayList<>();
        java.util.List<OrderItem> orderItems = orderItemMapper.listByOrderId(orderId);
        for (OrderItem oi : orderItems) {
            OrderTemplateItem item = new OrderTemplateItem();
            item.setTemplateId(template.getId());
            item.setProductId(oi.getProductId());
            item.setQuantity(oi.getQuantity());
            itemMapper.insert(item);
            templateItems.add(item);
        }

        template.setItems(templateItems);
        return template;
    }

    @Override
    @Transactional
    public void delete(Long customerId, Long templateId) {
        // [AQ-036] 旧实现直接按 id 删除（含明细）→ 顾客可删他人模板。先做归属限定的删除。
        int affected = templateMapper.deleteOwned(templateId, customerId);
        if (affected <= 0) {
            throw new BusinessException("常用订单不存在，可能已被删除");
        }
        itemMapper.deleteByTemplateId(templateId);
    }
}
