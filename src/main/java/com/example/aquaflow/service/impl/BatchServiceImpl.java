package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.mapper.BatchMapper;
import com.example.aquaflow.mapper.BatchOrderMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.BatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.crypto.Data;
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

    @Override
    public void delete(Integer id) {
        Batch batch = batchMapper.getById(id);
        if (batch.getStatus()==1){
            batchMapper.delete(id);
        }
    }

    @Override
    @Transactional
    public void finish(Integer id, List<Integer> finishedOrderIds, List<Integer> unfinishedOrderIds) {
        //•	允许单独调整某些订单完成状态
        if (finishedOrderIds != null && !finishedOrderIds.isEmpty()) {
            for (Integer finishedOrderId : finishedOrderIds) {
                orderMapper.updateStatus(finishedOrderId, 3);
            }
        }

    }

    @Override
    @Transactional
    public void start(Integer id) {
        batchMapper.updateStatus(id,2);
        List<Integer> orderIds = batchOrderMapper.getOrderIdsByBatchId(id);
        for (Integer orderId : orderIds){
            orderMapper.updateStatus(orderId,2);
        }
    }

    @Override
    public Batch getById(Integer id) {
        return batchMapper.getById(id);
    }

    @Override
    public List<Batch> list(Integer status, Data createTimeStart, Data createTimeEnd) {
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
            if (order.getStatus() != 1){
                throw new RuntimeException("订单不是待配送状态:"+orderId);
            }

            needMap.merge(order.getWaterTypeId(), order.getQuantity(), Integer::sum);
        }

        // 校验库存
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            Integer waterTypeId = entry.getKey();
            Integer needQuantity = entry.getValue();

            Inventory inventory = inventoryMapper.getByWaterTypeId(waterTypeId);
            if (inventory == null || inventory.getQuantity() < needQuantity) {
                throw new RuntimeException("水类型" + waterTypeId + "库存不足，需要:" + needQuantity + ", 当前:" + (inventory == null ? 0 : inventory.getQuantity()));
            }
        }

        int totalQty = 0;
        for (Integer orderId : orderIds) {
            Orders order = orderMapper.getById(orderId);
            totalQty += order.getQuantity();
        }

        // 创建批次
        Batch batch = new Batch();
        batch.setStatus(1);
        batch.setTotalQTY(totalQty);
        batch.setCreateTime(LocalDateTime.now());
        batch.setUpdateTime(LocalDateTime.now());
        batchMapper.insert(batch);

        // 扣减库存
        for (Map.Entry<Integer, Integer> entry : needMap.entrySet()) {
            inventoryMapper.decreaseStock(entry.getKey(), entry.getValue());
        }


        // 插入批次-订单关系
        for (Integer orderId : orderIds) {
            batchOrderMapper.insert(batch.getId(), orderId);
        }

        return batch;
    }

}
