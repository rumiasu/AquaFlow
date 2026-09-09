package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.OrderBarrelException;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.CustomerDepositAccountMapper;
import com.example.aquaflow.mapper.DepositRecordMapper;
import com.example.aquaflow.service.BarrelAssetService;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.StationExceptionConfigService;
import com.example.aquaflow.service.TicketAccountService;
import com.example.aquaflow.util.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单桶异常处理服务实现
 */
@Service
@Slf4j
public class OrderBarrelExceptionServiceImpl implements OrderBarrelExceptionService {

    @Autowired
    private OrderBarrelExceptionMapper exceptionMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private BarrelAssetService barrelAssetService;

    @Autowired
    private StationExceptionConfigService stationConfigService;

    @Autowired
    private TicketAccountService ticketAccountService;

    @Autowired
    private CustomerDepositAccountMapper customerDepositAccountMapper;

    @Autowired
    private DepositRecordMapper depositRecordMapper;

    @Override
    @Transactional
    public OrderBarrelExceptionDTO recordReturn(Long orderId, ReturnInput input) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在: " + orderId);
        }

        int delivery = order.getDeliveryBucketQty() != null ? order.getDeliveryBucketQty() : 0;
        int actual = input.getActualReturn() != null ? input.getActualReturn() : 0;
        int diff = delivery - actual; // 正=少回

        if (diff == 0) {
            return null; // 无差异，无需记录异常
        }

        // 确定异常类别
        String category = diff > 0 ? "RETURN_SHORT" : "RETURN_OVER";
        String type = diff > 0 ? "SHORT_RETURN" : "OVER_RETURN";

        // 生成补偿建议
        CompensationSuggestion suggestion = generateSuggestion(category, Math.abs(diff), order.getStationId());

        // 创建异常记录
        OrderBarrelException ex = new OrderBarrelException();
        ex.setOrderId(orderId);
        ex.setCustomerId(order.getCustomerId());
        ex.setStationId(order.getStationId());
        ex.setDeliveryStaffId(AuthContext.getUserId());
        ex.setDeliveryQty(delivery);
        ex.setReturnQty(actual);
        ex.setDiscrepancy(diff);
        ex.setCategory(category);
        ex.setType(diff > 0 ? "SHORT_RETURN" : "OVER_RETURN");
        ex.setStaffAction(input.getStaffAction() != null ? input.getStaffAction() : "FULL");
        ex.setStaffNote(input.getStaffNote());
        ex.setWaterGiven(input.getWaterGiven() != null ? input.getWaterGiven() : 0);
        ex.setWaterOwed(input.getWaterOwed() != null ? input.getWaterOwed() : 0);
        ex.setSuggestedTicketQty(suggestion.getTicketQty());
        ex.setSuggestedCashAmount(suggestion.getCashAmount());
        ex.setStatus("STAFF_RECORDED");
        ex.setCreatedAt(LocalDateTime.now());

        exceptionMapper.insert(ex);

        // 更新订单异常标记
        order.setExceptionFlag(true);
        order.setExceptionCategory(category);
        order.setExceptionCount(order.getExceptionCount() != null ? order.getExceptionCount() + 1 : 1);
        order.setBarrelExceptionId(ex.getId());
        order.setReturnBucketQty(actual);
        order.setBarrelDiscrepancy(diff);
        orderMapper.update(order);

        // 推送站长通知（异步）
        // notificationService.pushExceptionCreated(toDTO(ex));

        log.info("[OrderBarrelException] 配送员录入回桶异常: orderId={}, exceptionId={}, diff={}", orderId, ex.getId(), diff);
        return toDTO(ex);
    }

    @Override
    @Transactional
    public OrderBarrelExceptionDTO recordException(Long orderId, ExceptionInput input) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在: " + orderId);
        }

        OrderBarrelException ex = new OrderBarrelException();
        ex.setOrderId(orderId);
        ex.setCustomerId(order.getCustomerId());
        ex.setStationId(order.getStationId());
        ex.setDeliveryStaffId(AuthContext.getUserId());
        ex.setDeliveryQty(input.getExpectedValue() != null ? input.getExpectedValue() : 0);
        ex.setReturnQty(input.getActualValue() != null ? input.getActualValue() : 0);
        ex.setDiscrepancy((input.getExpectedValue() != null ? input.getExpectedValue() : 0) - 
                          (input.getActualValue() != null ? input.getActualValue() : 0));
        ex.setCategory(input.getCategory());
        ex.setType(input.getType());
        ex.setStaffAction(input.getStaffAction() != null ? input.getStaffAction() : "FULL");
        ex.setStaffNote(input.getStaffNote());
        ex.setWaterGiven(input.getWaterGiven() != null ? input.getWaterGiven() : 0);
        ex.setWaterOwed(input.getWaterOwed() != null ? input.getWaterOwed() : 0);
        ex.setStatus("STAFF_RECORDED");
        ex.setCreatedAt(LocalDateTime.now());

        exceptionMapper.insert(ex);

        // 更新订单标记
        order.setExceptionFlag(true);
        order.setExceptionCategory(input.getCategory());
        order.setExceptionCount(order.getExceptionCount() != null ? order.getExceptionCount() + 1 : 1);
        order.setBarrelExceptionId(ex.getId());
        orderMapper.update(order);

        log.info("[OrderBarrelException] 配送员录入异常: orderId={}, exceptionId={}, category={}", orderId, ex.getId(), input.getCategory());
        return toDTO(ex);
    }

    @Override
    @Transactional
    public void handleException(Long exceptionId, HandleInput input) {
        OrderBarrelException ex = exceptionMapper.getById(exceptionId);
        if (ex == null) {
            throw new RuntimeException("异常记录不存在: " + exceptionId);
        }

        String action = input.getAction() != null ? input.getAction() : "IGNORE";

        // 记录站长决策
        ex.setManagerAction(action);
        ex.setRefundTicketQty(input.getRefundTicketQty());
        ex.setRefundCashAmount(input.getRefundCashAmount());
        ex.setAdjustAssetQty(input.getAdjustAssetQty());
        ex.setAdjustProductId(input.getAdjustProductId());
        ex.setManagerNote(input.getManagerNote());
        // #39: 修复APPROVE/MODIFY的状态映射 — 这两个action应设为MANAGER_APPROVED
        ex.setStatus("IGNORE".equals(action) ? "IGNORED" : "MANAGER_APPROVED");
        ex.setDecidedAt(LocalDateTime.now());

        exceptionMapper.updateDecision(ex.getId(), action,
                input.getRefundTicketQty(), input.getRefundCashAmount(),
                input.getAdjustAssetQty(), input.getAdjustProductId(),
                input.getManagerNote(), ex.getStatus());

        // 如果是APPROVE或MODIFY，直接执行补偿（此时状态已是MANAGER_APPROVED）
        if ("APPROVE".equals(action) || "MODIFY".equals(action)) {
            executeCompensation(ex.getId());
        }

        log.info("[OrderBarrelException] 站长处理异常: exceptionId={}, action={}", exceptionId, action);
    }

    @Override
    @Transactional
    public void executeCompensation(Long exceptionId) {
        OrderBarrelException ex = exceptionMapper.getById(exceptionId);
        if (ex == null) {
            throw new RuntimeException("异常记录不存在: " + exceptionId);
        }

        if (!"MANAGER_APPROVED".equals(ex.getStatus()) && !"EXECUTING".equals(ex.getStatus())) {
            throw new RuntimeException("异常状态不允许执行: " + ex.getStatus());
        }

        ex.setStatus("EXECUTING");
        exceptionMapper.updateStatus(ex.getId(), "EXECUTING");

        try {
            String action = ex.getManagerAction();
            Long customerId = ex.getCustomerId();
            Long stationId = ex.getStationId();

            // 执行具体补偿动作
            if ("REFUND_TICKET".equals(action) && ex.getRefundTicketQty() != null && ex.getRefundTicketQty() > 0) {
                // 退水票：增加客户水票账户余额
                // 水票按商品维度，此处若未指定 productId 则无法入账，记录日志跳过
                if (ex.getAdjustProductId() != null) {
                    ticketAccountService.addTicket(customerId, ex.getAdjustProductId(), ex.getRefundTicketQty(), stationId);
                    log.info("[OrderBarrelException] 执行退水票: exceptionId={}, productId={}, qty={}",
                            ex.getId(), ex.getAdjustProductId(), ex.getRefundTicketQty());
                } else {
                    log.warn("[OrderBarrelException] 退水票失败: 未指定 productId, exceptionId={}", ex.getId());
                }
            } else if ("REFUND_CASH".equals(action) && ex.getRefundCashAmount() != null && ex.getRefundCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                // 退现金：退还到客户押金账户（站长以现金/余额形式补偿客户）
                int affected = customerDepositAccountMapper.decreaseBalance(customerId, stationId, ex.getRefundCashAmount());
                if (affected > 0) {
                    DepositRecord dr = new DepositRecord();
                    dr.setCustomerId(customerId);
                    dr.setStationId(stationId);
                    dr.setType(DepositType.EXCEPTION_COMPENSATION); // 7 异常补偿退押金
                    dr.setAmount(ex.getRefundCashAmount().negate());
                    dr.setNote("桶异常补偿退现金: exceptionId=" + ex.getId());
                    dr.setOperatorId(AuthContext.getUserId());
                    dr.setCreateTime(LocalDateTime.now());
                    depositRecordMapper.insert(dr);
                    log.info("[OrderBarrelException] 执行退现金: exceptionId={}, amount={}", ex.getId(), ex.getRefundCashAmount());
                }
            } else if ("WAIVE_DEPOSIT".equals(action) && ex.getRefundCashAmount() != null && ex.getRefundCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                // 减免押金：站长减免客户部分押金（从押金账户退还）
                int affected = customerDepositAccountMapper.decreaseBalance(customerId, stationId, ex.getRefundCashAmount());
                if (affected > 0) {
                    DepositRecord dr = new DepositRecord();
                    dr.setCustomerId(customerId);
                    dr.setStationId(stationId);
                    dr.setType(DepositType.EXCEPTION_COMPENSATION); // 7 异常补偿退押金
                    dr.setAmount(ex.getRefundCashAmount().negate());
                    dr.setNote("桶异常减免押金: exceptionId=" + ex.getId());
                    dr.setOperatorId(AuthContext.getUserId());
                    dr.setCreateTime(LocalDateTime.now());
                    depositRecordMapper.insert(dr);
                    log.info("[OrderBarrelException] 执行减免押金: exceptionId={}, amount={}", ex.getId(), ex.getRefundCashAmount());
                }
            } else if ("ADJUST_ASSET".equals(action) && ex.getAdjustAssetQty() != null && ex.getAdjustAssetQty() != 0) {
                // 调整桶资产
                if (ex.getAdjustProductId() == null) {
                    throw new RuntimeException("调整桶资产必须指定产品ID");
                }
                List<BarrelAssetService.BarrelReturnItem> items = new ArrayList<>();
                BarrelAssetService.BarrelReturnItem item = new BarrelAssetService.BarrelReturnItem();
                item.setProductId(ex.getAdjustProductId());
                item.setQty(Math.abs(ex.getAdjustAssetQty()));
                item.setRefundAmount(BigDecimal.ZERO); // 纯资产调整不涉及押金
                if (ex.getAdjustAssetQty() > 0) {
                    // 增加资产 - 需要 purchase 逻辑
                    List<BarrelAssetService.BarrelPurchaseItem> purchaseItems = new ArrayList<>();
                    BarrelAssetService.BarrelPurchaseItem pItem = new BarrelAssetService.BarrelPurchaseItem();
                    pItem.setProductId(ex.getAdjustProductId());
                    pItem.setQty(ex.getAdjustAssetQty());
                    pItem.setDepositAmount(BigDecimal.ZERO);
                    purchaseItems.add(pItem);
                    barrelAssetService.purchaseBarrels(ex.getCustomerId(), ex.getStationId(), purchaseItems, AuthContext.getUserId());
                } else {
                    // 减少资产
                    items.add(new BarrelAssetService.BarrelReturnItem(ex.getAdjustProductId(), Math.abs(ex.getAdjustAssetQty()), BigDecimal.ZERO));
                    barrelAssetService.returnBarrels(ex.getCustomerId(), ex.getStationId(), items, AuthContext.getUserId());
                }
                log.info("[OrderBarrelException] 执行调整桶资产: exceptionId={}, qty={}", ex.getId(), ex.getAdjustAssetQty());
            }
            // IGNORE/RESCHEDULE 仅记录不执行金额操作

            ex.setStatus("EXECUTED");
            ex.setExecutedAt(LocalDateTime.now());
            exceptionMapper.updateStatus(ex.getId(), "EXECUTED");

            // 推送客户通知
            // notificationService.pushCompensationExecuted(toDTO(ex));

            log.info("[OrderBarrelException] 补偿执行完成: exceptionId={}", ex.getId());
        } catch (Exception e) {
            log.error("[OrderBarrelException] 补偿执行失败: exceptionId={}", ex.getId(), e);
            ex.setStatus("MANAGER_APPROVED"); // 回滚到待执行状态
            exceptionMapper.updateStatus(ex.getId(), "MANAGER_APPROVED");
            throw e;
        }
    }

    @Override
    public Page<OrderBarrelExceptionDTO> listExceptions(Long stationId, ExceptionQuery query) {
        int offset = (query.getPage() - 1) * query.getSize();
        List<OrderBarrelException> list = exceptionMapper.listByStation(
                stationId, query.getStatus(), query.getCategory(), query.getStaffId(), offset, query.getSize());
        long total = exceptionMapper.countByStation(stationId, query.getStatus(), query.getCategory(), query.getStaffId());

        List<OrderBarrelExceptionDTO> records = list.stream().map(this::toDTO).collect(Collectors.toList());
        return new Page<>(records, total, query.getPage(), query.getSize());
    }

    @Override
    public OrderBarrelExceptionDTO getById(Long id) {
        OrderBarrelException ex = exceptionMapper.getById(id);
        return ex != null ? toDTO(ex) : null;
    }

    @Override
    public List<OrderBarrelExceptionDTO> getByOrderId(Long orderId) {
        return exceptionMapper.listByOrderId(orderId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public ExceptionStatsDTO getStats(Long stationId, LocalDate startDate, LocalDate endDate) {
        ExceptionStatsDTO stats = new ExceptionStatsDTO();

        // 类型分布
        List<Map<String, Object>> catList = exceptionMapper.countByCategory(stationId, startDate, endDate);
        Map<String, Long> byCategory = new HashMap<>();
        long totalCount = 0;
        for (Map<String, Object> row : catList) {
            String cat = (String) row.get("category");
            Long cnt = ((Number) row.get("cnt")).longValue();
            byCategory.put(cat, cnt);
            totalCount += cnt;
        }

        // 补偿汇总（空表时 sumCompensation 返回 null，需判空）
        Map<String, Object> sum = exceptionMapper.sumCompensation(stationId, startDate, endDate);
        long totalTickets = 0;
        BigDecimal totalCash = BigDecimal.ZERO;
        if (sum != null) {
            totalTickets = sum.get("total_tickets") != null ? ((Number) sum.get("total_tickets")).longValue() : 0;
            totalCash = sum.get("total_cash") != null ? (BigDecimal) sum.get("total_cash") : BigDecimal.ZERO;
        }

        stats.setTotalCount(totalCount);
        stats.setByCategory(byCategory);
        stats.setTotalRefundTickets(totalTickets);
        stats.setTotalRefundCash(totalCash);

        return stats;
    }

    // ==================== 补偿建议生成 ====================

    private CompensationSuggestion generateSuggestion(String category, int diffQty, Long stationId) {
        CompensationSuggestion suggestion = new CompensationSuggestion();
        
        // 获取站点配置
        Map<String, Object> config = stationConfigService.getConfig(stationId);
        List<String> priority = (List<String>) config.getOrDefault("compensationPriority", 
                List.of("REFUND_TICKET", "REFUND_CASH", "WAIVE_DEPOSIT"));
        
        int absDiff = Math.abs(diffQty);
        int remaining = absDiff;
        
        for (String type : priority) {
            if (remaining <= 0) break;
            
            if ("REFUND_TICKET".equals(type)) {
                suggestion.setTicketQty(Math.min(remaining, remaining)); // 简化：每桶1张水票
                remaining -= suggestion.getTicketQty();
            } else if ("REFUND_CASH".equals(type) && remaining > 0) {
                suggestion.setCashAmount(BigDecimal.valueOf(remaining * 30)); // 假设每桶押金30元
                remaining = 0;
            }
        }
        
        return suggestion;
    }

    // ==================== 内部类 ====================

    private static class CompensationSuggestion {
        private int ticketQty = 0;
        private BigDecimal cashAmount = BigDecimal.ZERO;

        public int getTicketQty() { return ticketQty; }
        public void setTicketQty(int ticketQty) { this.ticketQty = ticketQty; }
        public BigDecimal getCashAmount() { return cashAmount; }
        public void setCashAmount(BigDecimal cashAmount) { this.cashAmount = cashAmount; }
    }

    private OrderBarrelExceptionDTO toDTO(OrderBarrelException ex) {
        OrderBarrelExceptionDTO dto = new OrderBarrelExceptionDTO();
        dto.setId(ex.getId());
        dto.setOrderId(ex.getOrderId());
        dto.setCustomerId(ex.getCustomerId());
        dto.setStationId(ex.getStationId());
        dto.setDeliveryStaffId(ex.getDeliveryStaffId());
        dto.setDeliveryQty(ex.getDeliveryQty());
        dto.setReturnQty(ex.getReturnQty());
        dto.setDiscrepancy(ex.getDiscrepancy());
        dto.setCategory(ex.getCategory());
        dto.setType(ex.getType());
        dto.setStaffAction(ex.getStaffAction());
        dto.setStaffNote(ex.getStaffNote());
        dto.setWaterGiven(ex.getWaterGiven());
        dto.setWaterOwed(ex.getWaterOwed());
        dto.setManagerAction(ex.getManagerAction());
        dto.setRefundTicketQty(ex.getRefundTicketQty());
        dto.setRefundCashAmount(ex.getRefundCashAmount());
        dto.setAdjustAssetQty(ex.getAdjustAssetQty());
        dto.setAdjustProductId(ex.getAdjustProductId());
        dto.setManagerNote(ex.getManagerNote());
        dto.setSuggestedTicketQty(ex.getSuggestedTicketQty());
        dto.setSuggestedCashAmount(ex.getSuggestedCashAmount());
        dto.setStatus(ex.getStatus());
        dto.setCreatedAt(ex.getCreatedAt());
        dto.setDecidedAt(ex.getDecidedAt());
        dto.setExecutedAt(ex.getExecutedAt());
        return dto;
    }
}