package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.BatchStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.BatchMapper;
import com.example.aquaflow.mapper.BatchOrderMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.BatchService;
import com.example.aquaflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        List<Integer> orderIds = batchOrderMapper.getOrderIdsByBatchId(id);
        for (Integer orderId : orderIds) {
            orderMapper.updateStatus(orderId, OrderStatus.PENDING);
        }
        batchOrderMapper.deleteByBatchId(id);
        batchMapper.delete(id);
    }

    @Override
    @Transactional
    public void finish(Integer id, List<Integer> finishedOrderIds, List<Integer> unfinishedOrderIds) {
        //•	允许单独调整某些订单完成状态
        if (finishedOrderIds != null && !finishedOrderIds.isEmpty()) {
            for (Integer finishedOrderId : finishedOrderIds) {
                orderMapper.updateStatus(finishedOrderId, OrderStatus.FINISHED);
            }
        }
        //传进来id和un,全是id是完成批次,也就是un非空则不完成,空了则完成
        if(unfinishedOrderIds == null || unfinishedOrderIds.isEmpty()){
            batchMapper.updateStatus(id, BatchStatus.FINISHED);
        }

    }

    @Override
    @Transactional
    public void start(Integer id) {
        batchMapper.updateStatus(id,BatchStatus.DELIVERING);
        List<Integer> orderIds = batchOrderMapper.getOrderIdsByBatchId(id);
        for (Integer orderId : orderIds){
            orderMapper.updateStatus(orderId,OrderStatus.DELIVERING);
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
        // 统计每种水类型需要的总量
        Map<Integer, Integer> needMap = new HashMap<>();

        for(Integer orderId : orderIds){
            Orders order = orderMapper.getById(orderId);
            if (order == null){
                throw new RuntimeException("订单不存在:"+orderId);
            }
            if (order.getStatus() != OrderStatus.PENDING){
                throw new RuntimeException("订单不是待组批状态:"+orderId);
            }

            needMap.merge(order.getWaterTypeId(), order.getQuantity(), Integer::sum);
        }

        // 校验库存
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            inventoryService.checkStock(entry.getKey(), entry.getValue());
        }

        int totalQty = 0;
        for (Integer orderId : orderIds) {
            Orders order = orderMapper.getById(orderId);
            totalQty += order.getQuantity();
        }

        // 创建批次
        Batch batch = new Batch();
        batch.setStatus(BatchStatus.PENDING);
        batch.setTotalQTY(totalQty);
        batch.setCreateTime(LocalDateTime.now());
        batch.setUpdateTime(LocalDateTime.now());
        batchMapper.insert(batch);

        // 扣减库存
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            inventoryMapper.decreaseStock(entry.getKey(), entry.getValue());
        }

        // 插入批次-订单关系，并标记订单为已组批
        for (Integer orderId : orderIds) {
            batchOrderMapper.insert(batch.getId(), orderId);
            orderMapper.updateStatus(orderId, OrderStatus.BATCHED);
        }

        return batch;
    }

}
