package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.TicketAccount;
import com.example.aquaflow.entity.TicketRecord;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.PaymentRecord;
import com.example.aquaflow.mapper.TicketAccountMapper;
import com.example.aquaflow.mapper.TicketRecordMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.constant.PaymentStatus;
import com.example.aquaflow.service.TicketAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TicketAccountServiceImpl implements TicketAccountService {

    private static final Logger log = LoggerFactory.getLogger(TicketAccountServiceImpl.class);

    @Autowired
    private TicketAccountMapper ticketAccountMapper;

    @Autowired
    private TicketRecordMapper ticketRecordMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private com.example.aquaflow.mapper.InventoryMapper inventoryMapper;

    @Override
    public List<TicketAccount> listByCustomerAndStation(Long customerId, Long stationId) {
        return ticketAccountMapper.listByCustomerAndStation(customerId, stationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addTicket(Long customerId, Long productId, Integer qty, Long stationId) {
        // [AQ-001] 水票按 (customer, product, station) 三维隔离，任缺一维隔离即失效。
        // 尤其 stationId 为 null 时，MySQL 唯一键中 NULL 互不相等，可插出多行 —— 水票串站 / 重复入账。
        // 数据库列已改为 NOT NULL 兜底，这里提前给出可读的报错。
        if (customerId == null) {
            throw new RuntimeException("客户ID不能为空");
        }
        if (productId == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        if (stationId == null) {
            throw new RuntimeException("水站ID不能为空，水票必须归属到具体水站");
        }
        if (qty == null || qty <= 0) {
            throw new RuntimeException("水票数量必须大于0");
        }
        // [AQ-051] 单次入账上限：防止误操作或脚本把水票余额刷成天文数字（原实现无任何上限）
        if (qty > 5000) {
            throw new RuntimeException("单次水票数量不能超过 5000");
        }

        // 水票按水站隔离
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(qty);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        } else {
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(null);
        record.setSource("购买");
        record.setTicketSource(1);
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
        // [AQ-051] 水票入账审计日志（谁给谁加了多少张）
        log.info("[AQ-051] 水票入账: customerId={}, productId={}, stationId={}, qty={}, operatorId={}",
                customerId, productId, stationId, qty, com.example.aquaflow.util.AuthContext.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId) {
        if (customerId == null || productId == null || stationId == null || qty == null || qty <= 0) {
            throw new BusinessException("退款水票参数不合法");
        }
        // 水票按水站隔离：先取（不存在则建），再回补
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            account = new TicketAccount();
            account.setCustomerId(customerId);
            account.setProductId(productId);
            account.setStationId(stationId);
            account.setRemainQuantity(qty);
            account.setUpdateTime(LocalDateTime.now());
            ticketAccountMapper.insert(account);
        } else {
            ticketAccountMapper.incrementQuantity(account.getId(), qty);
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(qty);
        record.setDecreaseQty(0);
        record.setOrderId(orderId);
        record.setSource("退款");
        record.setTicketSource(1);
        record.setCreateTime(LocalDateTime.now());
        ticketRecordMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consumeTicket(Long customerId, Long productId, Integer qty, Long orderId, Long stationId) {
        // 水票按水站隔离
        TicketAccount account = ticketAccountMapper.getByCustomerProductStation(customerId, productId, stationId);
        if (account == null) {
            throw new RuntimeException("当前水站水票余额不足");
        }
        int affected = ticketAccountMapper.decrementQuantity(account.getId(), qty);
        if (affected == 0) {
            throw new RuntimeException("水票余额不足");
        }

        TicketRecord record = new TicketRecord();
        record.setCustomerId(customerId);
        record.setProductId(productId);
        record.setStationId(stationId);
        record.setIncreaseQty(0);
        record.setDecreaseQty(qty);
        record.setOrderId(orderId);
        record.setSource("消费");
        record.setTicketSource(1);
        record.setCreateTime(LocalDateTime.now());
        // 唯一键 uk_ticket_consume(order_id, product_id) 兜底并发双扣：冲突即视为已扣，幂等跳过（AQ-019）
        try {
            ticketRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.warn("[TicketAccount] 水票消费记录已存在(并发幂等跳过): orderId={}, productId={}", orderId, productId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentRecord purchaseTicket(Long customerId, Long productId, Integer qty, Integer paymentMethod, Long stationId) {
        // #30: 先创建PENDING支付记录，再入账水票（支付确认后再真正入账）
        Product product = productMapper.getById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (qty == null || qty <= 0) {
            throw new BusinessException("购买数量必须大于0");
        }
        // 水票开关与票价以「站级库存」为准（与下单/试算走的 PriceUtil 同一口径）。
        // 注意 product.ticket_enabled 是商品级默认值，水站可对本站单独开启，
        // 因此必须查 inventory，不能只看 product。
        com.example.aquaflow.entity.Inventory inv =
                stationId != null ? inventoryMapper.getByStationAndProduct(stationId, productId) : null;
        if (inv == null || !Integer.valueOf(1).equals(inv.getTicketEnabled())) {
            throw new BusinessException("该商品在本水站未开启水票，暂不支持购买");
        }
        BigDecimal unitPrice = inv.getTicketPrice() != null && inv.getTicketPrice().compareTo(BigDecimal.ZERO) > 0
                ? inv.getTicketPrice()
                : (product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO);
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(qty));

        PaymentRecord record = new PaymentRecord();
        record.setCustomerId(customerId);
        record.setStationId(stationId);
        record.setAmount(totalAmount);
        record.setPaymentMethod(paymentMethod);
        record.setStatus(PaymentStatus.PENDING);
        record.setNote("线上购买水票");
        // 记录"买的是哪种水票、买几张"，支付确认后据此入账。
        // 此前这两个信息没有落库，导致支付成功也无从入账 —— 客户付了钱水票永远不到账。
        record.setTicketWaterTypeId(productId);
        record.setTicketQty(qty);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRecordMapper.insert(record);

        // 水票入账由 PaymentServiceImpl.confirmPayment 在支付确认成功后执行（乐观锁保证幂等）。
        // 真实环境微信支付到位后，这里应改为：统一下单 -> 等待支付回调 -> 回调中确认支付并入账。

        return record;
    }
}
