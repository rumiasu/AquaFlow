package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderTemplate;

import java.util.List;

public interface OrderTemplateService {

    OrderTemplate getQuickOrder(Long customerId, Long stationId);

    List<OrderTemplate> listByCustomerAndStation(Long customerId, Long stationId);

    OrderTemplate save(Long customerId, OrderTemplate template, Long stationId);

    void setDefault(Long customerId, Long templateId, Long stationId);

    void toggleEnabled(Long customerId, Long templateId, Integer enabled);

    OrderTemplate setFromOrder(Long customerId, Long orderId, Long stationId);

    void delete(Long customerId, Long templateId);
}
