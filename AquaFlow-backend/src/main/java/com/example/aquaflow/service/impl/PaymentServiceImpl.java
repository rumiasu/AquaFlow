package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.constant.InventoryChangeType;
import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.entity.Orders;
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
import com.example.aquaflow.service.InventoryService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import com.example.aquaflow.util.PriceUtil;
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

    /** 水票账户：用于水票支付的原子扣减与在线购票入账 */
    @Autowired
    private TicketAccountService ticketAccountService;

    /** [AQ-029] 库存流水：库存回补时写流水 */
    @Autowired
    private InventoryService inventoryService;

    /** 订单归属站（优先履约站） */
    private static Long stationOf(Orders o) {
        if (o == null) return null;
        return o.getDeliveryStationId() != null ? o.getDeliveryStationId() : o.getStationId();
    }

    /**
     * [AQ-043] 订单「归属站」= orders.station_id。
     * 钱与票据（收款流水 / 预收押金 / 水票扣减）一律记在归属站，与欠桶 adjustOwed 的口径一致；
     * 跨站外派时若用履约站会与实物账错位（钱记履约站、欠桶记归属站）。
     */
    private static Long ownerStation(Orders o) {
        if (o == null) return null;
        return o.getStationId() != null ? o.getStationId() : stationOf(o);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRecord createPayment(Long orderId, Long customerId, BigDecimal amount, BigDecimal waterAmount,
                                        BigDecimal barrelDeposit, Integer excessBarrels, Integer paymentMethod,
                                        Long ticketWaterTypeId, Integer ticketQty, String note) {
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

        // 货到付款（现金）权限二次校验：需要水站开启且客户已授权
        // 注意：旧代码这里判断的是 3（老语义"线下支付"），与 PayMethod 的 2=现金冲突，已按 PayMethod 统一
        if (Integer.valueOf(PayMethod.CASH).equals(paymentMethod) && orderStationId != null) {
            if (!canUseOfflinePayment(customerId, orderStationId)) {
                throw new BusinessException("当前客户暂不支持货到付款");
            }
        }

        // ===== 金额一律以服务端重算为准，客户端传入值全部丢弃 =====
        // 否则任何人都可以 POST {orderId, paymentMethod, amount:0.01} 让自己的订单变成已支付
        if (order != null) {
            amount = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
            waterAmount = order.getWaterAmount() != null ? order.getWaterAmount() : BigDecimal.ZERO;
            barrelDeposit = order.getDepositAmount() != null ? order.getDepositAmount() : BigDecimal.ZERO;
        }

        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setStationId(orderStationId);
        record.setAmount(amount);
        record.setWaterAmount(waterAmount);
        record.setBarrelDeposit(barrelDeposit);
        record.setExcessBarrels(excessBarrels);
        record.setTicketWaterTypeId(ticketWaterTypeId);
        record.setTicketQty(ticketQty);
        record.setPaymentMethod(paymentMethod);
        record.setNote(note);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());

        // ===== 支付状态判定：绝不能由客户端参数直接决定"已支付" =====
        // 水票(3)：先按订单项原子扣减水票，扣减成功才算已支付
        // 微信(1)：PENDING，PAID 只能由微信支付异步回调写入（TODO: 接入统一下单 + 回调验签）
        // 现金(2)：PENDING，货到付款，由站长确认收款后写入
        // 历史实现：paymentMethod==1 或 2 直接置 PAID（"测试阶段"注释），等于任何人都可零元购
        Integer status;
        if (Integer.valueOf(PayMethod.TICKET).equals(paymentMethod)) {
            deductTickets(orderId);
            status = PaymentStatus.PAID;
        } else {
            status = PaymentStatus.PENDING;
        }
        record.setStatus(status);

        paymentRecordMapper.insert(record);

        // 同步订单付款状态
        if (orderId != null) {
            orderMapper.updatePaymentStatus(orderId,
                    Integer.valueOf(PaymentStatus.PAID).equals(status) ? PaymentStatus.PAID : PaymentStatus.UNPAID);
        }

        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPayment(Long paymentId) {
        PaymentRecord record = paymentRecordMapper.getById(paymentId);
        // 以下均为"业务前置条件不满足"，属于可预期的用户侧错误，用 BusinessException（code=1）
        // 而不是 RuntimeException —— 后者会被兜底处理器转成 code=500「系统错误」，
        // 让"重复点击确认"这类正常场景看起来像后端崩了。
        if (record == null) throw new BusinessException("支付记录不存在");
        if (record.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(record.getStatus() == PaymentStatus.PAID
                    ? "该笔支付已确认，请勿重复操作"
                    : "该笔支付当前状态不可确认");
        }
        // #29: 乐观锁 — 用原子更新确保只有PENDING状态才能改为PAID
        // [AQ-003] 期望状态必须显式传 PaymentStatus.PENDING(=1)；
        // 旧实现在 SQL 里硬编码 status=0，与 PENDING 取值不符，导致此处恒为 0 行、支付永远确认失败。
        int affected = paymentRecordMapper.updateStatusIf(paymentId, PaymentStatus.PAID, PaymentStatus.PENDING);
        if (affected == 0) {
            throw new BusinessException("支付确认失败，状态已变更，请刷新后重试");
        }

        // 水票支付：确认收款时补齐扣减（deductTickets 内部幂等，已扣过会跳过）
        if (Integer.valueOf(PayMethod.TICKET).equals(record.getPaymentMethod()) && record.getOrderId() != null) {
            deductTickets(record.getOrderId());
        }

        // 在线购买水票：支付确认后入账。
        // 此前这里是一个 TODO —— 客户在线买水票付了钱，水票却永远不到账。
        // 上面的乐观锁保证同一笔支付只会确认成功一次，因此入账天然幂等。
        if (record.getTicketWaterTypeId() != null && record.getTicketQty() != null && record.getTicketQty() > 0) {
            ticketAccountService.addTicket(record.getCustomerId(), record.getTicketWaterTypeId(),
                    record.getTicketQty(), record.getStationId());
        }

        if (record.getOrderId() != null) {
            orderMapper.updatePaymentStatus(record.getOrderId(), PaymentStatus.PAID);
            // [AQ-009] 支付成功时才入账预收桶押金（此前在下单时即入账，那时客户一分未付）
            applyDepositOnPaid(record.getOrderId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmOrderCollection(Long orderId) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new RuntimeException("仅已配送待付款的订单可确认收款");
        }
        // 货到付款（现金）：现场收款后把待支付流水置为已支付
        // 旧代码用 3 判断"线下支付"，与 PayMethod（3=水票）语义冲突，已统一为 CASH=2
        boolean isCashOnDelivery = Integer.valueOf(PayMethod.CASH).equals(order.getPaymentMethod());
        if (isCashOnDelivery) {
            List<PaymentRecord> records = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : records) {
                if (r.getStatus() != null && r.getStatus() == PaymentStatus.PENDING) {
                    paymentRecordMapper.updateStatus(r.getId(), PaymentStatus.PAID);
                }
            }
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.PAID);
            // [AQ-009] 收款成功时入账预收桶押金
            applyDepositOnPaid(orderId);
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
    public boolean hasPaidRecord(Long orderId) {
        if (orderId == null) {
            return false;
        }
        // [AQ-002][AQ-007] 订单能否视为「已付款」，唯一凭据是存在 PAID 流水。
        return paymentRecordMapper.countByOrderIdAndStatus(orderId, PaymentStatus.PAID) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordCashCollection(Long orderId) {
        if (orderId == null) {
            return;
        }
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // 幂等：已有 PAID 流水则跳过，不重复记账（#27）
        if (hasPaidRecord(orderId)) {
            return;
        }
        // [AQ-002] 现金（货到付款）由配送员现场收款，是合法收款动作；
        // 但必须补写一条 PAID 支付流水，否则「订单已付款」与支付流水对不上，日结无凭证。
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(order.getCustomerId());
        record.setStationId(ownerStation(order));
        record.setAmount(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        record.setWaterAmount(order.getWaterAmount());
        record.setBarrelDeposit(order.getDepositAmount());
        record.setPaymentMethod(PayMethod.CASH);
        record.setStatus(PaymentStatus.PAID);
        record.setNote("配送员现场收款（货到付款）");
        record.setOperatorId(AuthContext.getUserId());
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDepositOnPaid(Long orderId) {
        if (orderId == null) {
            return;
        }
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            return;
        }
        BigDecimal dep = order.getDepositAmount();
        if (dep == null || dep.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long stationId = ownerStation(order);
        // [AQ-009] 幂等：同一订单仅入账一次（按 related_order_id + PREPAID 去重），
        // 可被线上确认 / 现金收款 / 水票扣减等多处"置已付款"入口安全重复调用。
        if (depositRecordMapper.countByOrderAndType(orderId, DepositType.PREPAID) > 0) {
            return;
        }
        customerDepositAccountMapper.increaseBalance(order.getCustomerId(), stationId, dep);

        DepositRecord dr = new DepositRecord();
        dr.setCustomerId(order.getCustomerId());
        dr.setStationId(stationId);
        dr.setType(DepositType.PREPAID);
        dr.setAmount(dep);
        dr.setRelatedOrderId(orderId);
        dr.setNote("订单支付成功预收桶押金");
        dr.setOperatorId(AuthContext.getUserId());
        dr.setCreateTime(LocalDateTime.now());
        depositRecordMapper.insert(dr);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmCashPayment(Long orderId, Long customerId, BigDecimal amount) {
        // #27: 幂等性检查 — 如果该订单已有确认的支付记录，直接返回
        if (orderId != null) {
            List<PaymentRecord> existing = paymentRecordMapper.listByOrderId(orderId);
            for (PaymentRecord r : existing) {
                if (r.getStatus() == PaymentStatus.PAID && Integer.valueOf(PayMethod.CASH).equals(r.getPaymentMethod())) {
                    return; // 已确认过，幂等返回
                }
            }
        }

        com.example.aquaflow.entity.Orders o = orderMapper.getById(orderId);
        // 收款金额以订单为准，忽略客户端传入值（否则可伪造实收金额）
        if (o != null && o.getTotalAmount() != null) {
            amount = o.getTotalAmount();
        }

        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setCustomerId(customerId);
        record.setAmount(amount);
        record.setPaymentMethod(PayMethod.CASH); // 现金（货到付款）
        record.setStatus(PaymentStatus.PAID);
        record.setNote("货到付款确认");
        // 审计留痕：记录「谁、在哪个水站」收的款（历史 payment_record.operator_id 全为空，无法追溯）
        record.setOperatorId(AuthContext.getUserId());
        Long st = (o != null && o.getStationId() != null) ? o.getStationId() : AuthContext.getStationId();
        record.setStationId(st);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);

        orderMapper.updatePaymentStatus(orderId, PaymentStatus.PAID);
        // [AQ-009] 现场收款（货到付款）成功时入账预收桶押金
        applyDepositOnPaid(orderId);
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

    /**
     * 按订单项扣减水票（水票支付的核心链路）。
     * <p>
     * 此前这是一个空方法，且全项目 0 处调用 —— 客户用"水票支付"下单，水票余额一分不减，可以无限白嫖。
     * 现在在「支付确认成功」与「水票支付创建时」两个入口调用，内部按订单项逐个原子扣减。
     * </p>
     * 扣减幂等：以 ticket_record 中该订单已有的消费流水为依据，已扣过的商品跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductTickets(Long orderId) {
        if (orderId == null) {
            return;
        }
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在，无法扣减水票");
        }
        Long stationId = ownerStation(order);
        List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (OrderItem item : items) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            if (ticketRecordMapper.countConsumeByOrderAndProduct(orderId, item.getProductId()) > 0) {
                continue; // 已扣过，幂等跳过
            }
            // consumeTicket 内部使用 remain_quantity >= qty 的原子 SQL，扣减失败（余额不足）直接抛异常
            ticketAccountService.consumeTicket(order.getCustomerId(), item.getProductId(),
                    item.getQuantity(), orderId, stationId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Long orderId, String reason) {
        com.example.aquaflow.entity.Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        Long customerId = order.getCustomerId();
        Long stationId = order.getDeliveryStationId() != null ? order.getDeliveryStationId() : order.getStationId();

        // AQ-008: 退还订单已消费的水票（按实际扣减记录回补，避免重复还/漏还）
        List<OrderItem> refundItems = orderItemMapper.listByOrderId(orderId);
        if (refundItems != null) {
            for (OrderItem item : refundItems) {
                if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                    continue;
                }
                // 仅当该订单该商品确曾消耗水票时才归还
                if (ticketRecordMapper.countConsumeByOrderAndProduct(orderId, item.getProductId()) > 0) {
                    ticketAccountService.refundTicket(customerId, item.getProductId(), item.getQuantity(), orderId, stationId);
                }
            }
        }

        List<PaymentRecord> paidRecords = paymentRecordMapper.listByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == PaymentStatus.PAID)
                .toList();

        if (paidRecords.isEmpty()) {
            // 没有任何已支付记录 = 这笔订单客户根本没付过钱。
            // 旧实现一律写 REFUNDED，于是从未付款的订单取消后显示"已退款"，
            // 与实际资金流水对不上。正确语义是"已取消"。
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.CANCELLED);
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

            // AQ-022: 退款后支付状态应为 REFUNDED 而非 UNPAID，反映"已退款"而非"从未付款"
            orderMapper.updatePaymentStatus(orderId, PaymentStatus.REFUNDED);
        }

        // ===== 退桶押金 + 清理配送中桶 =====
        // 仅当订单尚未配送完成(status < DELIVERED=4)时，押金桶还在配送中状态，需退还押金并清理配送中记录
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

            // 2. 清理该订单的配送中桶记录（PENDING 状态）
            List<CustomerBarrelInTransit> inTransitList = customerBarrelInTransitMapper.listPendingByOrderId(orderId);
            if (inTransitList != null && !inTransitList.isEmpty()) {
                customerBarrelInTransitMapper.deleteByOrderId(orderId);
            }

            // 3. 回补库存：按下单时"实际扣减量"回补，而不是订单数量。
            //    下单时库存不足只扣了现有库存（deducted_qty < quantity），若按 quantity 回补会凭空多出库存，
            //    反复"下单-取消"即可刷出无限库存。
            List<OrderItem> items = orderItemMapper.listByOrderId(orderId);
            if (items != null) {
                for (OrderItem item : items) {
                    if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                        continue;
                    }
                    int restoreQty = item.getDeductedQty() != null
                            ? Math.min(item.getDeductedQty(), item.getQuantity())
                            : item.getQuantity();
                    if (restoreQty > 0) {
                        inventoryMapper.increaseStock(order.getStationId(), item.getProductId(), restoreQty);
                        // [AQ-029] 退款回补库存写流水
                        inventoryService.recordChange(order.getStationId(), item.getProductId(), restoreQty,
                                InventoryChangeType.REFUND_RESTORE, orderId, AuthContext.getUserId(), "退款回补");
                    }
                }
            }
        }

        orderMapper.updateStatus(orderId, OrderStatus.CANCELLED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
    public List<PaymentRecord> listAll(Long stationId, int limit) {
        // [AQ-052] 旧实现丢弃入参、恒调 listByStationId(null)，一旦 SQL 被"顺手优化"成动态条件
        // 就会变成全平台流水泄露。改为必须按站查询。
        return paymentRecordMapper.listAllByStation(stationId, limit);
    }

    @Override
    public List<PaymentRecord> listWithFilter(Long stationId, Integer status, Integer paymentMethod, int limit) {
        return paymentRecordMapper.listByStation(stationId, status, paymentMethod, limit);
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

        boolean allowOffline = canUseOfflinePayment(customerId, stationId);
        result.put("allowOfflinePayment", allowOffline);
        // 可用支付方式由后端下发（含文案与默认选中项），前端禁止自带 1/2/3 映射表，
        // 否则再次出现"前端 2=水票、后端 2=现金"这类错位。
        result.put("methods", PayMethod.availableMethods(allowOffline));
        result.put("defaultMethod", PayMethod.defaultMethod(allowOffline));

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

            // 单价统一走 PriceUtil：报价与下单共用同一算法
            // 旧实现用 paymentMethod==2 判断水票价，而 PayMethod 定义 2=现金、3=水票，是错位的
            // [AQ-031] 传入站级库存，水票价以 inventory.ticket_price 为准，与下单侧保持一致
            BigDecimal unitPrice = PriceUtil.calcUnitPrice(product, inv, paymentMethod);

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
