package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderTemplate;

import java.util.List;

public interface OrderTemplateService {

    /** 获取用户的快速下单数据（优先级：默认模板 > 最近完成订单 > null） */
    OrderTemplate getQuickOrder(Integer customerId);

    /** 获取客户所有模板（含明细） */
    List<OrderTemplate> listByCustomerId(Integer customerId);

    /** 保存/更新模板（含明细） */
    OrderTemplate save(Integer customerId, OrderTemplate template);

    /** 设为默认模板 */
    void setDefault(Integer customerId, Integer templateId);

    /** 启用/关闭模板 */
    void toggleEnabled(Integer customerId, Integer templateId, Integer enabled);

    /** 从订单创建模板 */
    OrderTemplate setFromOrder(Integer customerId, Integer orderId);

    /** 删除模板 */
    void delete(Integer customerId, Integer templateId);
}
