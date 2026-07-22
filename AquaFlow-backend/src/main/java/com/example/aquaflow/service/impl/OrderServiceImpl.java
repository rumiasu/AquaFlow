package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.BatchStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.Batch;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.BatchMapper;
import com.example.aquaflow.mapper.BatchOrderMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private BatchOrderMapper batchOrderMapper;

    @Autowired
    private BatchMapper batchMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private com.example.aquaflow.mapper.TicketAccountMapper ticketAccountMapper;

    @Override
    @Transactional
    public void updateStatus(Integer id, Integer status) {
        Orders order = orderMapper.getById(id);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 状态机校验
        if (!OrderStatus.isValidTransition(order.getStatus(), status)) {
            throw new RuntimeException("不允许从状态" + order.getStatus() + "转换到" + status);
        }

        // 取消订单时的善后处理
        if (status == OrderStatus.CANCELLED) {
            if (order.getStatus() == OrderStatus.BATCHED) {
                cancelBatchedOrder(order);
            }
            // 取消时自动退款
            autoRefundOnCancel(order);
        }

        orderMapper.updateStatus(id, status);
    }

    /**
     * 取消已组批的订单：从批次中移除并恢复库存
     */
    private void cancelBatchedOrder(Orders order) {
        Integer batchId = batchOrderMapper.getBatchIdByOrderId(order.getId());
        if (batchId == null) return;

        Batch batch = batchMapper.getById(batchId);
        if (batch == null) return;

        // 只有待配送状态的批次才允许移除订单
        if (batch.getStatus() != BatchStatus.PENDING) {
            throw new RuntimeException("批次已出发，无法取消订单");
        }

        // 恢复库存
        inventoryMapper.increaseStock(order.getStationId(), order.getWaterTypeId(), order.getQuantity());

        // 从批次中移除此订单
        batchOrderMapper.deleteByOrderId(order.getId());

        // 更新批次的总数量
        List<Integer> remainingOrderIds = batchOrderMapper.getOrderIdsByBatchId(batchId);
        if (remainingOrderIds.isEmpty()) {
            // 批次没有订单了，直接删除批次
            batchMapper.delete(batchId);
        } else {
            // 重新计算批次总数量
            List<Orders> remainingOrders = batchOrderMapper.getOrdersByBatchId(batchId);
            int newTotal = 0;
            for (Orders o : remainingOrders) {
                newTotal += o.getQuantity();
            }
            batchMapper.updateTotalQTY(batchId, newTotal);
        }
    }

    /**
     * 取消订单时自动退款：查找该订单所有已付款记录并退款
     */
    private void autoRefundOnCancel(Orders order) {
        List<PaymentRecord> records = paymentRecordMapper.listByOrderId(Long.valueOf(order.getId()));
        for (PaymentRecord record : records) {
            if (record.getStatus() == PaymentStatus.PAID) {
                paymentRecordMapper.updateStatus(record.getId(), PaymentStatus.REFUNDED);
                // 水票退款：恢复余额
                if (record.getPaymentMethod() == 3 && record.getTicketWaterTypeId() != null) {
                    com.example.aquaflow.entity.TicketAccount account =
                            ticketAccountMapper.getByCustomerAndWaterType(
                                    record.getCustomerId().intValue(),
                                    record.getTicketWaterTypeId().intValue());
                    if (account != null) {
                        ticketAccountMapper.incrementQuantity(account.getId(), record.getTicketQty());
                    }
                }
            }
        }
        // 更新订单付款状态为未付款
        orderMapper.updatePaymentStatus(order.getId(), PaymentStatus.UNPAID);
    }

    @Override
    public Orders getById(Integer id) {
        return orderMapper.getById(id);
    }

    @Override
    public List<Orders> list(Integer stationId, Integer customerId, Integer status, String tag, String createTimeStart, String createTimeEnd) {
        return orderMapper.list(stationId, customerId, status, tag, createTimeStart, createTimeEnd);
    }

    @Override
    public void save(Orders orders) {
        orders.setStatus(OrderStatus.PENDING);
        orders.setCreateTime(LocalDateTime.now());
        orders.setUpdateTime(LocalDateTime.now());

        // 如果前端未传stationId，从客户信息中获取
        if (orders.getStationId() == null && orders.getCustomerId() != null) {
            com.example.aquaflow.entity.Customer customer = customerMapper.getById(orders.getCustomerId());
            if (customer != null) {
                orders.setStationId(customer.getStationId());
            }
        }

        // 保存地址快照
        if (orders.getAddressId() != null) {
            Address addr = addressMapper.getById(orders.getAddressId());
            if (addr != null) {
                orders.setReceiverName(addr.getName());
                orders.setReceiverPhone(addr.getPhone());
                orders.setAddressSnapshot(addr.getDetail());
            }
        }

        orderMapper.save(orders);
    }
}
