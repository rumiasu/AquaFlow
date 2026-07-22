package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.BatchStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.BatchMapper;
import com.example.aquaflow.mapper.BatchOrderMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.BatchService;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BatchServiceImpl implements BatchService {

    @Autowired
    private BatchMapper batchMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private BatchOrderMapper batchOrderMapper;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private CustomerMapper customerMapper;

    @Override
    @Transactional
    public void delete(Integer id) {
        Batch batch = batchMapper.getById(id);
        if(batch == null){
            throw new RuntimeException("批次不存在");
        }
        if(batch.getStatus()!=BatchStatus.PENDING){
            throw new RuntimeException("仅待配送批次允许删除");
        }

        // 获取批次关联的订单详情（含水类型和数量），用于恢复库存
        List<Orders> orders = batchOrderMapper.getOrdersByBatchId(id);

        // 恢复库存：按水类型聚合数量后加回（需要stationId定位库存行）
        Map<Integer, Integer> restoreMap = new HashMap<>();
        for (Orders order : orders) {
            restoreMap.merge(order.getWaterTypeId(), order.getQuantity(), Integer::sum);
        }
        // 从关联订单中获取stationId（同一批次的订单属于同一水站）
        Integer stationId = orders.isEmpty() ? null : orders.get(0).getStationId();
        if (stationId == null) {
            throw new RuntimeException("批次订单缺少水站信息，无法恢复库存");
        }
        for (Map.Entry<Integer, Integer> entry : restoreMap.entrySet()) {
            inventoryMapper.increaseStock(stationId, entry.getKey(), entry.getValue());
        }

        // 重置订单状态为待组批
        for (Orders order : orders) {
            orderMapper.updateStatus(order.getId(), OrderStatus.PENDING);
        }
        batchOrderMapper.deleteByBatchId(id);
        batchMapper.delete(id);
    }

    @Override
    @Transactional
    public void finish(Integer id, List<Integer> finishedOrderIds, List<Integer> unfinishedOrderIds) {
        Set<Integer> finishedCustomerIds = new HashSet<>();
        if (finishedOrderIds != null && !finishedOrderIds.isEmpty()) {
            for (Integer finishedOrderId : finishedOrderIds) {
                orderMapper.updateStatus(finishedOrderId, OrderStatus.FINISHED);
                // 收集完成订单的客户ID，用于刷新统计
                Orders order = orderMapper.getById(finishedOrderId);
                if (order != null && order.getCustomerId() != null) {
                    finishedCustomerIds.add(order.getCustomerId());
                }
            }
        }
        if(unfinishedOrderIds == null || unfinishedOrderIds.isEmpty()){
            batchMapper.updateStatus(id, BatchStatus.FINISHED);
        }
        // 刷新客户统计
        for (Integer customerId : finishedCustomerIds) {
            customerMapper.refreshStats(customerId);
        }
    }

    @Override
    @Transactional
    public void finishAll(Integer id) {
        List<Integer> orderIds = batchOrderMapper.getOrderIdsByBatchId(id);
        Set<Integer> finishedCustomerIds = new HashSet<>();
        for (Integer orderId : orderIds) {
            Orders order = orderMapper.getById(orderId);
            if (order != null && order.getCustomerId() != null) {
                finishedCustomerIds.add(order.getCustomerId());
            }
            orderMapper.updateStatus(orderId, OrderStatus.FINISHED);
        }
        batchMapper.updateStatus(id, BatchStatus.FINISHED);
        // 刷新客户统计
        for (Integer customerId : finishedCustomerIds) {
            customerMapper.refreshStats(customerId);
        }
    }

    @Override
    @Transactional
    public void start(Integer id) {
        Batch batch = batchMapper.getById(id);
        if (batch == null) {
            throw new RuntimeException("批次不存在");
        }
        if (batch.getStatus() != BatchStatus.PENDING) {
            throw new RuntimeException("仅待配送批次允许出发");
        }
        batchMapper.updateStatus(id, BatchStatus.DELIVERING);
        List<Integer> orderIds = batchOrderMapper.getOrderIdsByBatchId(id);
        for (Integer orderId : orderIds){
            orderMapper.updateStatus(orderId, OrderStatus.DELIVERING);
        }
    }

    @Override
    public Batch getById(Integer id) {
        Batch batch = batchMapper.getById(id);
        if(batch == null){
            throw new RuntimeException("批次不存在");
        }
        // 查询批次包含的订单列表
        List<Orders> orders = batchOrderMapper.getOrdersByBatchId(id);
        batch.setOrders(orders);
        return batch;
    }

    @Override
    public List<Batch> list(Integer status, String createTimeStart, String createTimeEnd) {
        return batchMapper.list(status,createTimeStart,createTimeEnd);
    }

    @Override
    @Transactional
    public Batch create(List<Integer> orderIds) {
        // 一次性读取所有订单，避免重复查询
        List<Orders> ordersList = new java.util.ArrayList<>();
        Map<Integer, Integer> needMap = new HashMap<>();
        int totalQty = 0;
        Integer stationId = null;

        for(Integer orderId : orderIds){
            Orders order = orderMapper.getById(orderId);
            if (order == null){
                throw new RuntimeException("订单不存在:"+orderId);
            }
            if (order.getStatus() != OrderStatus.PENDING){
                throw new RuntimeException("订单不是待组批状态:"+orderId);
            }
            ordersList.add(order);
            needMap.merge(order.getWaterTypeId(), order.getQuantity(), Integer::sum);
            totalQty += order.getQuantity();
            // 取第一个订单的stationId作为批次的水站
            if (stationId == null) {
                stationId = order.getStationId();
            }
        }

        if (stationId == null) {
            throw new RuntimeException("订单缺少水站信息，无法组批");
        }

        // 校验库存（按水站维度）
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            inventoryService.checkStock(stationId, entry.getKey(), entry.getValue());
        }

        // 创建批次（设置stationId）
        Batch batch = new Batch();
        batch.setStatus(BatchStatus.PENDING);
        batch.setTotalQTY(totalQty);
        batch.setStationId(stationId);
        batch.setCreateTime(LocalDateTime.now());
        batch.setUpdateTime(LocalDateTime.now());
        batchMapper.insert(batch);

        // 扣减库存（按水站维度）
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            inventoryMapper.decreaseStock(stationId, entry.getKey(), entry.getValue());
        }

        // 插入批次-订单关系，并标记订单为已组批
        for (Orders order : ordersList) {
            batchOrderMapper.insert(batch.getId(), order.getId());
            orderMapper.updateStatus(order.getId(), OrderStatus.BATCHED);
        }

        return batch;
    }

    @Override
    public List<Batch> listByDeliveryPersonId(Integer deliveryPersonId) {
        List<Batch> batches = batchMapper.listByDeliveryPersonId(deliveryPersonId);
        // 填充每个批次的订单详情
        for (Batch batch : batches) {
            List<Orders> orders = batchOrderMapper.getOrdersByBatchId(batch.getId());
            batch.setOrders(orders);
        }
        return batches;
    }

    @Override
    public void assignDeliveryPerson(Integer batchId, Integer deliveryPersonId) {
        Batch batch = batchMapper.getById(batchId);
        if (batch == null) {
            throw new RuntimeException("批次不存在");
        }
        batchMapper.assignDeliveryPerson(batchId, deliveryPersonId);
    }

}
