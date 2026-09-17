package com.example.aquaflow.service.impl;

import com.example.aquaflow.entity.OrderBarrelException;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.DepositRecord;
import com.example.aquaflow.constant.DepositType;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.OrderBarrelExceptionMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.service.BarrelAssetService;
import com.example.aquaflow.service.DepositRecordService;
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

    /** 押金唯一入口（账户 + 流水成对写）；不再直连 mapper 改余额 */
    @Autowired
    private DepositRecordService depositRecordService;

    /** 分级告警：运营故障投给站长、系统故障投给系统管理员（见 constant/AlertType） */
    @Autowired
    private com.example.aquaflow.service.AlertService alertService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderBarrelExceptionDTO recordReturn(Long orderId, ReturnInput input) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在: " + orderId);
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

        // 更新订单异常标记（专用列更新 + 计数 DB 侧自增，不再整行写回）
        orderMapper.markBarrelException(orderId, category, ex.getId(), actual, diff);

        // 通知站长（[2026-09-16] 此前这行是**注释掉的**，等于"异常产生了但站长不知道"；
        // 现在改成落库+日志的分级告警，站长端可查 /api/manager/alerts，微信订阅消息待接入）。
        alertService.stationFault(order.getStationId(), "WARN", "OrderBarrelException",
                "新桶异常待处置：" + category + " 差 " + diff + " 桶",
                "订单 " + orderId + " 回桶异常（应收 " + delivery + "、实收 " + actual
                        + "），请及时处置。异常单号=" + ex.getId(),
                "ORDER_BARREL_EXCEPTION", ex.getId());

        log.info("[OrderBarrelException] 配送员录入回桶异常: orderId={}, exceptionId={}, diff={}", orderId, ex.getId(), diff);
        return toDTO(ex);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderBarrelExceptionDTO recordException(Long orderId, ExceptionInput input) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在: " + orderId);
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

        // 更新订单标记（专用列更新，不再整行写回：整行写回会覆盖并发的 status/payment_status）
        orderMapper.markBarrelException(orderId, input.getCategory(), ex.getId(), null, null);

        log.info("[OrderBarrelException] 配送员录入异常: orderId={}, exceptionId={}, category={}", orderId, ex.getId(), input.getCategory());
        return toDTO(ex);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleException(Long exceptionId, HandleInput input) {
        OrderBarrelException ex = exceptionMapper.getById(exceptionId);
        if (ex == null) {
            throw new BusinessException("异常记录不存在: " + exceptionId);
        }

        // AQ-010: 终态守卫 — 已处理(MANAGER_APPROVED/EXECUTED/IGNORED)的异常不可重复处理，防止重复刷补偿
        // [2026-09-13] 守卫改为 CAS：读后写会让并发两次 handleException 都通过校验，
        // 于是同一条异常被重复补偿（重复退票 + 重复加押金）。affected=0 即代表已被别人处理。
        if (!"STAFF_RECORDED".equals(ex.getStatus())) {
            throw new BusinessException("异常已处理或状态无效，不可重复处理: " + ex.getStatus());
        }

        String action = input.getAction() != null ? input.getAction() : "IGNORE";

        // 记录站长决策（CAS：仅 STAFF_RECORDED → 决策态可写）
        String nextStatus = "IGNORE".equals(action) ? "IGNORED" : "MANAGER_APPROVED";
        int decided = exceptionMapper.updateDecisionIf(ex.getId(), "STAFF_RECORDED", action,
                input.getRefundTicketQty(), input.getRefundCashAmount(),
                input.getAdjustAssetQty(), input.getAdjustProductId(),
                input.getManagerNote(), nextStatus);
        if (decided == 0) {
            throw new BusinessException("该异常已被处理，请刷新后重试");
        }

        // 如果是APPROVE或MODIFY，直接执行补偿（此时状态已是MANAGER_APPROVED）
        if ("APPROVE".equals(action) || "MODIFY".equals(action)) {
            try {
                executeCompensation(ex.getId());
            } catch (RuntimeException e) {
                // [2026-09-16] 补偿执行失败属于**系统故障**（对账/账目出了问题，站长修不了），
                // 投给系统管理员；然后照旧抛出去让事务回滚，异常单退回可重试。
                // 告警走独立事务（AlertService 内部 REQUIRES_NEW），不会被这次回滚带走。
                alertService.systemFault("OrderBarrelException",
                        "桶异常补偿执行失败：exceptionId=" + ex.getId(),
                        "action=" + action + "，原因=" + e.getMessage(),
                        "ORDER_BARREL_EXCEPTION", ex.getId());
                throw e;
            }
        } else if ("IGNORE".equals(action)) {
            alertService.stationFault(ex.getStationId(), "INFO", "OrderBarrelException",
                    "桶异常已忽略：exceptionId=" + ex.getId(),
                    "站长选择忽略，未做任何补偿。备注=" + input.getManagerNote(),
                    "ORDER_BARREL_EXCEPTION", ex.getId());
        }

        log.info("[OrderBarrelException] 站长处理异常: exceptionId={}, action={}", exceptionId, action);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeCompensation(Long exceptionId) {
        OrderBarrelException ex = exceptionMapper.getById(exceptionId);
        if (ex == null) {
            throw new BusinessException("异常记录不存在: " + exceptionId);
        }

        // AQ-010: 终态守卫 — 已执行(EXECUTED)的异常拒绝重复执行（配合 handleException 的守卫，杜绝重复刷补偿）
        // [2026-09-13] 改为 CAS：把「读到的状态」当作 expected，affected=0 即已被并发推进，直接拒绝。
        if (!"MANAGER_APPROVED".equals(ex.getStatus()) && !"EXECUTING".equals(ex.getStatus())) {
            throw new BusinessException("异常状态不允许执行: " + ex.getStatus());
        }
        int claimed = exceptionMapper.updateStatusIf(ex.getId(), ex.getStatus(), "EXECUTING");
        if (claimed == 0) {
            throw new BusinessException("该异常已由其他操作执行或状态已变更，请刷新后重试");
        }
        ex.setStatus("EXECUTING");

        {
            Long customerId = ex.getCustomerId();
            Long stationId = ex.getStationId();

            // AQ-010: 基于站长配置的补偿参数执行，而非依赖 managerAction 文本枚举。
            // 前端统一下发 APPROVE/MODIFY，旧代码按 REFUND_TICKET/REFUND_CASH 等枚举匹配导致补偿永不执行。
            boolean compensated = false;

            // 1. 退水票：增加客户水票账户余额
            if (ex.getRefundTicketQty() != null && ex.getRefundTicketQty() > 0) {
                if (ex.getAdjustProductId() != null) {
                    ticketAccountService.addTicket(customerId, ex.getAdjustProductId(), ex.getRefundTicketQty(), stationId);
                    compensated = true;
                    log.info("[OrderBarrelException] 执行退水票: exceptionId={}, productId={}, qty={}",
                            ex.getId(), ex.getAdjustProductId(), ex.getRefundTicketQty());
                } else {
                    // [2026-09-16 修复] 原来这里只 log.warn，然后照样把异常标成 EXECUTED ——
                    // 站长以为票退了，客户账户上一张都没多（与"静默丢字段"同一类坑）。
                    // 补偿要求了却做不到，必须失败回滚、让他补全重试。
                    throw new BusinessException("退水票必须指定商品（adjustProductId 不能为空）");
                }
            }

            // 2. 退现金 / 减免押金：退还到客户押金账户（余额增加）
            //    [2026-09-13 修复] 旧实现用 type=7「异常补偿退押金」+ 直写 increaseBalance，
            //    但 7 在 DepositType.isDecrease 里是【扣减】——同一个类型出现两种相反方向。
            //    现改为：走押金唯一入口（账户+流水成对写），类型用 9「人工补录押金（增加）」。
            if (ex.getRefundCashAmount() != null && ex.getRefundCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                DepositRecord dr = new DepositRecord();
                dr.setCustomerId(customerId);
                dr.setStationId(stationId);
                dr.setType(DepositType.MANUAL_GRANT); // 9 人工补录押金（余额增加）
                dr.setAmount(ex.getRefundCashAmount()); // 正值：方向由 type 决定
                dr.setNote("桶异常补偿退现金: exceptionId=" + ex.getId());
                dr.setOperatorId(AuthContext.getUserId());
                depositRecordService.add(dr, stationId);
                compensated = true;
                log.info("[OrderBarrelException] 执行退现金: exceptionId={}, amount={}", ex.getId(), ex.getRefundCashAmount());
            }

            // 3. 调整桶资产
            if (ex.getAdjustAssetQty() != null && ex.getAdjustAssetQty() != 0) {
                if (ex.getAdjustProductId() == null) {
                    throw new BusinessException("调整桶资产必须指定产品ID");
                }
                List<BarrelAssetService.BarrelReturnItem> items = new ArrayList<>();
                BarrelAssetService.BarrelReturnItem item = new BarrelAssetService.BarrelReturnItem();
                item.setProductId(ex.getAdjustProductId());
                item.setQty(Math.abs(ex.getAdjustAssetQty()));
                item.setRefundAmount(BigDecimal.ZERO); // 纯资产调整不涉及押金
                if (ex.getAdjustAssetQty() > 0) {
                    // 增加资产
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
                compensated = true;
                log.info("[OrderBarrelException] 执行调整桶资产: exceptionId={}, qty={}", ex.getId(), ex.getAdjustAssetQty());
            }

            // 收尾：EXECUTING → EXECUTED（CAS，affected=0 说明状态被并发改动，必须失败而不是静默继续）
            if (exceptionMapper.updateStatusIf(ex.getId(), "EXECUTING", "EXECUTED") == 0) {
                throw new BusinessException("异常执行状态已变更，本次补偿已回滚，请刷新后重试");
            }
            ex.setStatus("EXECUTED");
            ex.setExecutedAt(LocalDateTime.now());

            log.info("[OrderBarrelException] 补偿执行完成: exceptionId={}, compensated={}", ex.getId(), compensated);

            // [2026-09-16] 补偿落账成功 → 运营告警给站长（"钱/票已经动过了"是需要站内留痕与对账的动作）
            alertService.stationFault(ex.getStationId(), "INFO", "OrderBarrelException",
                    "桶异常补偿已执行：exceptionId=" + ex.getId(),
                    "退水票=" + (ex.getRefundTicketQty() == null ? 0 : ex.getRefundTicketQty())
                            + " 张，退现金=¥" + (ex.getRefundCashAmount() == null ? "0" : ex.getRefundCashAmount())
                            + "，调整桶资产=" + (ex.getAdjustAssetQty() == null ? 0 : ex.getAdjustAssetQty()),
                    "ORDER_BARREL_EXCEPTION", ex.getId());
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
    public Page<OrderBarrelExceptionDTO> listByCustomer(Long customerId, Long stationId, int page, int size) {
        if (customerId == null) {
            return new Page<>(java.util.Collections.emptyList(), 0, page, size);
        }
        int safeSize = size > 0 ? size : 20;
        int safePage = page > 0 ? page : 1;

        // 客户自己的异常记录量很小，直接全量取出后在内存分页；
        // stationId 为空表示"全站汇总"，用于客户未选水站时仍能看到自己的记录。
        List<OrderBarrelException> all = stationId != null
                ? exceptionMapper.listByCustomerAndStation(customerId, stationId)
                : exceptionMapper.listByCustomer(customerId);

        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<OrderBarrelExceptionDTO> records = all.subList(from, to).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return new Page<>(records, all.size(), safePage, safeSize);
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
        // 中文文案由后端统一计算下发，前端不再自建 status/category 映射表
        dto.setStatusText(ex.getStatusText());
        dto.setCategoryText(ex.getCategoryText());
        dto.setCreatedAt(ex.getCreatedAt());
        dto.setDecidedAt(ex.getDecidedAt());
        dto.setExecutedAt(ex.getExecutedAt());
        return dto;
    }
}