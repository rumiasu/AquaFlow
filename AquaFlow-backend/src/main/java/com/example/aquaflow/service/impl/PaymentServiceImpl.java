package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.entity.CustomerBarrelInTransit;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.CustomerBarrelAsset;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.CustomerStationConfig;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.*;
import com.example.aquaflow.service.PaymentService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private CustomerBarrelAssetMapper customerBarrelAssetMapper;

    @Autowired
    private CustomerBarrelInTransitMapper customerBarrelInTransitMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private CustomerStationConfigMapper customerStationConfigMapper;

    @Override
    @Transactional
    public PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                        BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                        Long ticketProductId, Integer ticketQty, String note) {
        // 防重复支付：如果该订单已有已支付记录，直接返回
        if (orderId != null) {
            PaymentRecord existing = paymentRecordMapper.getByOrderId(orderId);
            if (existing != null && existing.getStatus() != null && existing.getStatus() == PaymentStatus.PAID) {
                return existing;
            }
        }

        // 获取订单信息
        com.example.aquaflow.entity.Orders order = null;
        Long orderStationId = null;
        if (orderId != null) {
            order = orderMapper.getById(orderId);
            if (order != null) {
                orderStationId = order.getDeliveryStationId() != null ? order.getDeliveryStationId() : order.getStationId();
            }
        }

        // 线下支付权限二次校验（防御性编程）
        if (paymentMethod != null && Integer.valueOf(3).equals(paymentMethod) && orderStationId != null) {
            if (!canUseOfflinePayment(customerId, orderStationId)) {
                throw new BusinessException("当前客户暂不支持线下支付");
            }
        }

        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setStationId(orderStationId);
        record.setAmount(amount);
        record.setPaymentMethod(paymentMethod);
        record.setNote(note);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());

        // 付款状态：微信/水票=已付款；货到付款=待确认
        // 测试阶段：所有支付方式默认直接成功（跳过微信实际支付）
        // TODO: 上线后恢复微信支付校验，改为 paymentMethod == 1 时调用微信统一下单接口
        if (paymentMethod != null && (Integer.valueOf(1).equals(paymentMethod) || Integer.valueOf(2).equals(paymentMethod))) {
            record.setStatus(PaymentStatus.PAID);
        } else {
            record.setStatus(PaymentStatus.PENDING);
        }

        paymentRecordMapper.insert(record);

        // 同步订单付款状态
        if (orderId != null) {
            orderMapper.updatePaymentStatus(orderId,
                    paymentMethod != null && (Integer.valueOf(1).equals(paymentMethod) || Integer.valueOf(2).equals(paymentMethod)) ? PaymentStatus.PAID : PaymentStatus.UNPAID);
        }

        return record;
    }

    @Override
    @Transactional
    public void confirmPayment(Long paymentId) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new RuntimeException("支付记录不存在");
        if (record.getStatus() != PaymentStatus.PENDING) throw new RuntimeException("该记录状态异常");
        // #29: 乐观锁 — 用原子更新确保只有PENDING状态才能改为PAID
        int affected = paymentRecordMapper.updateStatusIfPending(paymentId, PaymentStatus.PAID);
        if (affected == 0) {
            throw new RuntimeException("支付确认失败，状态已变更");
        }

        if (record.getOrderId() != null) {
            orderMapper.updatePaymentStatus(record.getOrderId(), PaymentStatus.PAID);
        }
    }

    @Override
    @Transactional
    public void confirmOrderCollection(Long orderId) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new RuntimeException("仅已配送待付款的订单可确认收款");
        }
        // #28: 使用equals而非==比较Integer
        boolean isOffline = order.getPaymentMethod() == null || !Integer.valueOf(3).equals(order.getPaymentMethod());
        if (isOffline) {
            List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : records) {
                if (r.getPaymentMethod() != null && !Integer.valueOf(3).equals(r.getPaymentMethod())
                        && r.getStatus() == PaymentStatus.PENDING) {
                    paymentRecordMapper.updateStatus(r.getId(), PaymentStatus.PAID);
                }
            }
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.PAID);
        }
        orderMapper.updateStatus(orderId, OrderStatus.COMPLETED);
    }

    @Override
    public void unconfirmOrderCollection(Long orderId) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new RuntimeException("仅已完成的订单可修正");
        }
        orderMapper.updateStatus(orderId, OrderStatus.DELIVERED);
    }

    @Override
    @Transactional
    public void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount) {
        // #27: 幂等性检查 — 如果该订单已有确认的支付记录，直接返回
        if (orderId != null) {
            List<PaymentRecord> existing = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : existing) {
                if (r.getStatus() == PaymentStatus.PAID && Integer.valueOf(3).equals(r.getPaymentMethod())) {
                    return; // 已确认过，幂等返回
                }
            }
        }
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setAmount(amount);
        record.setPaymentMethod(3); // 线下支付
        record.setStatus(PaymentStatus.PAID);
        record.setNote("货到付款确认");
        // 审计留痕：记录「谁、在哪个水站」收的款（历史 payment_record.operator_id 全为空，无法追溯）
        record.setOperatorId(AuthContext.getUserId());
        com.example.aquaflow.entity.Orders o = orderMapper.getById(orderId);
        Long st = (o != null && o.getStationId() != null) ? o.getStationId() : AuthContext.getStationId();
        record.setStationId(st);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);

        orderMapper.updatePaymentStatus(orderId, PaymentStatus.PAID);
    }

    @Override
    public void lockTicketPayment(Long orderId, Long customerId, Long productId, int qty, Integer orderStationId) {
        // #26: 直接调用原子扣减，检查返回值判断是否成功
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, orderStationId != null ? Long.valueOf(orderStationId) : null);
        if (account == null) {
            throw new RuntimeException("水票账户不存在");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new RuntimeException("水票余额不足");
        }
    }

    @Override
    public void deductTickets(Long orderId) {
        // 配送完成扣减水票 — 由订单状态变更触发
    }

    @Transactional
    public void refundOrder(Long orderId, String reason) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        Long customerId = order.getCustomerId();
        Long stationId = order.getDeliveryStationId() != null ? order.getDeliveryStationId() : order.getStationId();

        List<PaymentRecord> paidRecords = paymentRecordMapper.listByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == PaymentStatus.PAID)
                .toList();

        if (paidRecords.isEmpty()) {
            // 没有已支付记录，直接标记订单取消
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.UNPAID);
        } else {
            for (PaymentRecord r : paidRecords) {
                // 标记原支付记录为已退款
                paymentRecordMapper.updateStatus(r.getId(), PaymentStatus.REFUNDED);

                // 生成退款记录
                PaymentRecord refundRecord = new PaymentRecord();
                refundRecord.setOrderId(orderId);
                refundRecord.setCustomerId(r.getCustomerId());
                refundRecord.setStationId(r.getStationId());
                refundRecord.setAmount(r.getAmount().negate()); // 负金额表示退款
                refundRecord.setWaterAmount(r.getWaterAmount() != null ? r.getWaterAmount().negate() : BigDecimal.ZERO);
                refundRecord.setBarrelDeposit(r.getBarrelDeposit() != null ? r.getBarrelDeposit().negate() : BigDecimal.ZERO);
                refundRecord.setExcessBarrels(r.getExcessBarrels() != null ? -r.getExcessBarrels() : 0);
                refundRecord.setPaymentMethod(r.getPaymentMethod());
                refundRecord.setStatus(PaymentStatus.REFUNDED);
                refundRecord.setNote("退款：" + (reason != null ? reason : "订单取消"));
                refundRecord.setCreateTime(LocalDateTime.now());
                refundRecord.setUpdateTime(LocalDateTime.now());
                paymentRecordMapper.insert(refundRecord);

                // TODO: 微信支付退款 - 若 paymentMethod=1，调用微信退款 API
                // 这里暂时仅记录退款流水，实际微信退款需异步任务处理
            }

            // 更新订单支付状态为未支付
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.UNPAID);
        }

        // ===== 退桶押金 + 清理在途桶 =====
        // 仅当订单尚未配送完成(status < DELIVERED=4)时，押金桶还在在途状态，需退还押金并清理在途记录
        int orderStatus = order.getStatus() != null ? order.getStatus() : 0;
        if (orderStatus < OrderStatus.DELIVERED) {
            // 1. 退还客户押金账户余额
            BigDecimal depositAmount = order.getDepositAmount();
            if (depositAmount != null && depositAmount.compareTo(BigDecimal.ZERO) > 0) {
                int affected = customerDepositAccountMapper.decreaseBalance(customerId, stationId, depositAmount);
                if (affected > 0) {
                    DepositRecord dr = new DepositRecord();
                    dr.setCustomerId(customerId);
                    dr.setStationId(stationId);
                    dr.setType(DepositType.CANCEL_PREPAID); // 8 取消订单释放预收押金
                    dr.setAmount(depositAmount.negate()); // 负金额表示退出
                    dr.setNote("订单取消释放押金: " + (reason != null ? reason : ""));
                    dr.setOperatorId(null);
                    dr.setCreateTime(LocalDateTime.now());
                    depositRecordMapper.insert(dr);
                }
            }

            // 2. 清理该订单的在途桶记录（PENDING 状态）
            List<CustomerBarrelInTransit> inTransitList = customerBarrelInTransitMapper.listPendingByOrderId(orderId);
            if (inTransitList != null && !inTransitList.isEmpty()) {
                customerBarrelInTransitMapper.deleteByOrderId(orderId);
            }

            // 3. 回补库存：下单时已按归属站扣减库存，取消时原路加回，避免库存被反复下单-取消扣到 0
            List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
            if (items != null) {
                for (OrderItem item : items) {
                    if (item.getProductId() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                        inventoryMapper.increaseStock(order.getStationId(), item.getProductId(), item.getQuantity());
                    }
                }
            }
        }

        orderMapper.updateStatus(orderId, OrderStatus.CANCELLED);
    }

    @Override
    @Transactional
    public void refundPayment(Long paymentId, String note) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        if (record == null) throw new RuntimeException("支付记录不存在");
        // #32: 校验当前状态，只有PAID才能退款
        if (record.getStatus() != PaymentStatus.PAID) {
            throw new RuntimeException("仅已支付记录可退款，当前状态: " + record.getStatus());
        }
        paymentRecordMapper.updateStatus(paymentId, PaymentStatus.REFUNDED);
        if (record.getOrderId() != null) {
            orderMapper.updatePaymentStatus(record.getOrderId(), PaymentStatus.UNPAID);
        }
    }

    @Override
    public List<PaymentRecord> listByOrderId(Long orderId) {
        return paymentRecordMapper.listByOrderId(orderId);
    }

    @Override
    public List<PaymentRecord> listByCustomerId(Long customerId) {
        return paymentRecordMapper.listByCustomerId(customerId);
    }

    @Override
    public List<PaymentRecord> listAll(int limit) {
        return paymentRecordMapper.listByStationId(null);
    }

    @Override
    public List<PaymentRecord> listWithFilter(Integer status, Integer paymentMethod, int limit) {
        return paymentRecordMapper.listByStationId(null);
    }

    @Override
    public Map<String, Object> getStationConfig(Long stationId) {
        Map<String, Object> config = new HashMap<>();
        config.put("stationId", stationId);
        return config;
    }

    @Override
    public void updateStationConfig(Long stationId, Map<String, Object> config) {
        // 站点支付配置更新
    }

    @Override
    public Map<String, Object> quote(Long customerId, Long stationId, Integer paymentMethod, List<Map<String, Object>> items) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("allowOfflinePayment", canUseOfflinePayment(customerId, stationId));
        
        if (items == null || items.isEmpty() || stationId == null) {
            result.put("waterAmount", BigDecimal.ZERO);
            result.put("barrelDeposit", BigDecimal.ZERO);
            result.put("extraDeposit", BigDecimal.ZERO);
            result.put("extraDepositBuckets", 0);
            result.put("totalAmount", BigDecimal.ZERO);
            return result;
        }

        BigDecimal totalWaterAmount = BigDecimal.ZERO;
        BigDecimal totalNonBarrelDeposit = BigDecimal.ZERO;
        int totalExtraBuckets = 0;
        BigDecimal totalExtraDeposit = BigDecimal.ZERO;

        Map<Long, Integer> barrelByProduct = new java.util.HashMap<>();

        for (Map<String, Object> item : items) {
            Long productId = item.get("productId") != null ? Long.valueOf(item.get("productId").toString()) : null;
            Integer quantity = item.get("quantity") != null ? Integer.valueOf(item.get("quantity").toString()) : 1;
            if (productId == null || quantity <= 0) continue;

            Product product = productMapper.getById(productId);
            if (product == null) continue;

            Inventory inv = inventoryMapper.getByStationAndProduct(stationId, productId);
            if (inv == null || inv.getEnabled() == null || !Integer.valueOf(1).equals(inv.getEnabled())) continue;

            BigDecimal unitPrice;
            if (paymentMethod != null && Integer.valueOf(2).equals(paymentMethod) && product.getTicketPrice() != null && product.getTicketPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = product.getTicketPrice();
            } else {
                unitPrice = product.getPrice();
            }
            if (unitPrice == null) unitPrice = BigDecimal.ZERO;

            totalWaterAmount = totalWaterAmount.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));

            if (product.getCategory() != null && Integer.valueOf(1).equals(product.getCategory())) {
                barrelByProduct.merge(productId, quantity, Integer::sum);
            } else {
                BigDecimal itemDeposit = product.getDeposit() != null ? product.getDeposit() : BigDecimal.ZERO;
                totalNonBarrelDeposit = totalNonBarrelDeposit.add(itemDeposit.multiply(BigDecimal.valueOf(quantity)));
            }
        }

        // 桶装水押金：按商品独立计算，缺多少桶交多少押金
        if (!barrelByProduct.isEmpty()) {
            List<CustomerBarrelAsset> assets = customerBarrelAssetMapper.listByCustomerAndStation(customerId, stationId);
            Map<Long, Integer> heldByProduct = new java.util.HashMap<>();
            if (assets != null) {
                for (CustomerBarrelAsset a : assets) {
                    heldByProduct.merge(a.getProductId(), a.getQuantity() != null ? a.getQuantity() : 0, Integer::sum);
                }
            }

            for (Map.Entry<Long, Integer> e : barrelByProduct.entrySet()) {
                Long pid = e.getKey();
                int needed = e.getValue();
                int held = heldByProduct.getOrDefault(pid, 0);
                int shortage = Math.max(0, needed - held);
                if (shortage > 0) {
                    totalExtraBuckets += shortage;
                    Product p = productMapper.getById(pid);
                    BigDecimal deposit = (p != null && p.getDeposit() != null) ? p.getDeposit() : BigDecimal.ZERO;
                    totalExtraDeposit = totalExtraDeposit.add(deposit.multiply(BigDecimal.valueOf(shortage)));
                }
            }
        }

        BigDecimal totalAmount = totalWaterAmount.add(totalNonBarrelDeposit).add(totalExtraDeposit);

        result.put("waterAmount", totalWaterAmount);
        result.put("barrelDeposit", totalNonBarrelDeposit);
        result.put("extraDeposit", totalExtraDeposit);
        result.put("extraDepositBuckets", totalExtraBuckets);
        result.put("totalAmount", totalAmount);

        return result;
    }

    @Override
    public boolean canUseOfflinePayment(Long customerId, Long stationId) {
        if (customerId == null || stationId == null) {
            return false;
        }
        // 1. 水站总开关
        Station station = stationMapper.getById(stationId);
        if (station == null || station.getOfflinePaymentEnabled() == null || !Integer.valueOf(1).equals(station.getOfflinePaymentEnabled())) {
            return false;
        }
        // 2. 客户授权
        CustomerStationConfig config = customerStationConfigMapper.getByCustomerAndStation(customerId, stationId);
        return config != null && config.getOfflinePaymentEnabled() != null && Integer.valueOf(1).equals(config.getOfflinePaymentEnabled());
    }
}
