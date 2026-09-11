package com.example.aquaflow.service;

import com.example.aquaflow.entity.OrderBarrelException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 订单桶异常处理服务。
 * <p>统一处理订单层面的桶相关异常：录入、审批、补偿建议、执行。
 * 不直接操作资产/押金，通过 BarrelAssetService / PaymentService / TicketService 执行。
 */
public interface OrderBarrelExceptionService {

    // ==================== DTO 定义 (必须在方法签名之前定义) ====================

    /**
     * 分页结果
     */
    class Page<T> {
        private List<T> records;
        private long total;
        private int page;
        private int size;

        public Page() {}
        public Page(List<T> records, long total, int page, int size) {
            this.records = records;
            this.total = total;
            this.page = page;
            this.size = size;
        }
        public List<T> getRecords() { return records; }
        public void setRecords(List<T> records) { this.records = records; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }

    /**
     * 查询参数
     */
    class ExceptionQuery {
        private String status;
        private String category;
        private Long staffId;
        private Long customerId;
        private int page = 1;
        private int size = 20;

        public ExceptionQuery() {}
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public Long getStaffId() { return staffId; }
        public void setStaffId(Long staffId) { this.staffId = staffId; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }

    /**
     * 统计DTO
     */
    class ExceptionStatsDTO {
        private long totalCount;
        private Map<String, Long> byCategory;
        private long totalRefundTickets;
        private java.math.BigDecimal totalRefundCash;
        private double avgHandleHours;
        private List<StaffStatDTO> staffStats;

        public ExceptionStatsDTO() {}
        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }
        public Map<String, Long> getByCategory() { return byCategory; }
        public void setByCategory(Map<String, Long> byCategory) { this.byCategory = byCategory; }
        public long getTotalRefundTickets() { return totalRefundTickets; }
        public void setTotalRefundTickets(long totalRefundTickets) { this.totalRefundTickets = totalRefundTickets; }
        public java.math.BigDecimal getTotalRefundCash() { return totalRefundCash; }
        public void setTotalRefundCash(java.math.BigDecimal totalRefundCash) { this.totalRefundCash = totalRefundCash; }
        public double getAvgHandleHours() { return avgHandleHours; }
        public void setAvgHandleHours(double avgHandleHours) { this.avgHandleHours = avgHandleHours; }
        public List<StaffStatDTO> getStaffStats() { return staffStats; }
        public void setStaffStats(List<StaffStatDTO> staffStats) { this.staffStats = staffStats; }
    }

    class StaffStatDTO {
        private Long staffId;
        private String staffName;
        private long exceptionCount;
        private double avgHandleHours;

        public StaffStatDTO() {}
        public Long getStaffId() { return staffId; }
        public void setStaffId(Long staffId) { this.staffId = staffId; }
        public String getStaffName() { return staffName; }
        public void setStaffName(String staffName) { this.staffName = staffName; }
        public long getExceptionCount() { return exceptionCount; }
        public void setExceptionCount(long exceptionCount) { this.exceptionCount = exceptionCount; }
        public double getAvgHandleHours() { return avgHandleHours; }
        public void setAvgHandleHours(double avgHandleHours) { this.avgHandleHours = avgHandleHours; }
    }

    // ==================== 方法签名 ====================

    /**
     * 配送员录入回桶实收 → 生成异常记录
     * 不动资产/押金，仅记录差异、生成补偿建议、推送站长通知
     */
    OrderBarrelExceptionDTO recordReturn(Long orderId, ReturnInput input);

    /**
     * 通用异常录入（非回桶类：缺水、客户拒收、损坏等）
     */
    OrderBarrelExceptionDTO recordException(Long orderId, ExceptionInput input);

    /**
     * 站长审批处理
     */
    void handleException(Long exceptionId, HandleInput input);

    /**
     * 执行已审批的补偿动作
     * 内部调用 BarrelAssetService / PaymentService / TicketService
     */
    void executeCompensation(Long exceptionId);

    /**
     * 站长端分页查询
     */
    Page<OrderBarrelExceptionDTO> listExceptions(Long stationId, ExceptionQuery query);

    /**
     * 根据ID获取异常详情
     */
    OrderBarrelExceptionDTO getById(Long id);

    /**
     * 获取订单关联的所有异常
     */
    List<OrderBarrelExceptionDTO> getByOrderId(Long orderId);

    /**
     * 异常统计看板
     */
    ExceptionStatsDTO getStats(Long stationId, java.time.LocalDate startDate, java.time.LocalDate endDate);

    // ==================== DTO 定义 ====================

    /**
     * 配送员录入回桶输入
     */
    class ReturnInput {
        private Integer actualReturn;
        private String staffAction; // FULL/PARTIAL/REFUSE/OWE
        private Integer waterGiven;
        private Integer waterOwed;
        private String staffNote;

        public ReturnInput() {}
        public Integer getActualReturn() { return actualReturn; }
        public void setActualReturn(Integer actualReturn) { this.actualReturn = actualReturn; }
        public String getStaffAction() { return staffAction; }
        public void setStaffAction(String staffAction) { this.staffAction = staffAction; }
        public Integer getWaterGiven() { return waterGiven; }
        public void setWaterGiven(Integer waterGiven) { this.waterGiven = waterGiven; }
        public Integer getWaterOwed() { return waterOwed; }
        public void setWaterOwed(Integer waterOwed) { this.waterOwed = waterOwed; }
        public String getStaffNote() { return staffNote; }
        public void setStaffNote(String staffNote) { this.staffNote = staffNote; }
    }

    /**
     * 通用异常录入输入
     */
    class ExceptionInput {
        private String category; // RETURN_SHORT/RETURN_OVER/RETURN_REFUSE/RETURN_DAMAGE/STATION_SHORTAGE/CUSTOMER_REFUSE/OTHER
        private String type;
        private Integer expectedValue;
        private Integer actualValue;
        private String staffAction;
        private Integer waterGiven;
        private Integer waterOwed;
        private String staffNote;

        public ExceptionInput() {}
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Integer getExpectedValue() { return expectedValue; }
        public void setExpectedValue(Integer expectedValue) { this.expectedValue = expectedValue; }
        public Integer getActualValue() { return actualValue; }
        public void setActualValue(Integer actualValue) { this.actualValue = actualValue; }
        public String getStaffAction() { return staffAction; }
        public void setStaffAction(String staffAction) { this.staffAction = staffAction; }
        public Integer getWaterGiven() { return waterGiven; }
        public void setWaterGiven(Integer waterGiven) { this.waterGiven = waterGiven; }
        public Integer getWaterOwed() { return waterOwed; }
        public void setWaterOwed(Integer waterOwed) { this.waterOwed = waterOwed; }
        public String getStaffNote() { return staffNote; }
        public void setStaffNote(String staffNote) { this.staffNote = staffNote; }
    }

    /**
     * 站长处理输入
     */
    class HandleInput {
        private String action; // APPROVE/MODIFY/IGNORE/ESCALATE
        private Integer refundTicketQty;
        private java.math.BigDecimal refundCashAmount;
        private Integer adjustAssetQty;
        private Long adjustProductId;
        private String managerNote;

        public HandleInput() {}
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public Integer getRefundTicketQty() { return refundTicketQty; }
        public void setRefundTicketQty(Integer refundTicketQty) { this.refundTicketQty = refundTicketQty; }
        public java.math.BigDecimal getRefundCashAmount() { return refundCashAmount; }
        public void setRefundCashAmount(java.math.BigDecimal refundCashAmount) { this.refundCashAmount = refundCashAmount; }
        public Integer getAdjustAssetQty() { return adjustAssetQty; }
        public void setAdjustAssetQty(Integer adjustAssetQty) { this.adjustAssetQty = adjustAssetQty; }
        public Long getAdjustProductId() { return adjustProductId; }
        public void setAdjustProductId(Long adjustProductId) { this.adjustProductId = adjustProductId; }
        public String getManagerNote() { return managerNote; }
        public void setManagerNote(String managerNote) { this.managerNote = managerNote; }
    }

    /**
     * 异常DTO
     */
    class OrderBarrelExceptionDTO {
        private Long id;
        private Long orderId;
        private Long customerId;
        private Long stationId;
        private Long deliveryStaffId;
        private Integer deliveryQty;
        private Integer returnQty;
        private Integer discrepancy;
        private String category;
        private String type;
        private String staffAction;
        private String staffNote;
        private Integer waterGiven;
        private Integer waterOwed;
        private String managerAction;
        private Integer refundTicketQty;
        private java.math.BigDecimal refundCashAmount;
        private Integer adjustAssetQty;
        private Long adjustProductId;
        private String managerNote;
        private Integer suggestedTicketQty;
        private java.math.BigDecimal suggestedCashAmount;
        private String status;
        /** 状态中文文案（后端下发，前端禁止自建映射表） */
        private String statusText;
        /** 异常类别中文文案（后端下发） */
        private String categoryText;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime decidedAt;
        private java.time.LocalDateTime executedAt;

        public OrderBarrelExceptionDTO() {}
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public Long getStationId() { return stationId; }
        public void setStationId(Long stationId) { this.stationId = stationId; }
        public Long getDeliveryStaffId() { return deliveryStaffId; }
        public void setDeliveryStaffId(Long deliveryStaffId) { this.deliveryStaffId = deliveryStaffId; }
        public Integer getDeliveryQty() { return deliveryQty; }
        public void setDeliveryQty(Integer deliveryQty) { this.deliveryQty = deliveryQty; }
        public Integer getReturnQty() { return returnQty; }
        public void setReturnQty(Integer returnQty) { this.returnQty = returnQty; }
        public Integer getDiscrepancy() { return discrepancy; }
        public void setDiscrepancy(Integer discrepancy) { this.discrepancy = discrepancy; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getStaffAction() { return staffAction; }
        public void setStaffAction(String staffAction) { this.staffAction = staffAction; }
        public String getStaffNote() { return staffNote; }
        public void setStaffNote(String staffNote) { this.staffNote = staffNote; }
        public Integer getWaterGiven() { return waterGiven; }
        public void setWaterGiven(Integer waterGiven) { this.waterGiven = waterGiven; }
        public Integer getWaterOwed() { return waterOwed; }
        public void setWaterOwed(Integer waterOwed) { this.waterOwed = waterOwed; }
        public String getManagerAction() { return managerAction; }
        public void setManagerAction(String managerAction) { this.managerAction = managerAction; }
        public Integer getRefundTicketQty() { return refundTicketQty; }
        public void setRefundTicketQty(Integer refundTicketQty) { this.refundTicketQty = refundTicketQty; }
        public java.math.BigDecimal getRefundCashAmount() { return refundCashAmount; }
        public void setRefundCashAmount(java.math.BigDecimal refundCashAmount) { this.refundCashAmount = refundCashAmount; }
        public Integer getAdjustAssetQty() { return adjustAssetQty; }
        public void setAdjustAssetQty(Integer adjustAssetQty) { this.adjustAssetQty = adjustAssetQty; }
        public Long getAdjustProductId() { return adjustProductId; }
        public void setAdjustProductId(Long adjustProductId) { this.adjustProductId = adjustProductId; }
        public String getManagerNote() { return managerNote; }
        public void setManagerNote(String managerNote) { this.managerNote = managerNote; }
        public Integer getSuggestedTicketQty() { return suggestedTicketQty; }
        public void setSuggestedTicketQty(Integer suggestedTicketQty) { this.suggestedTicketQty = suggestedTicketQty; }
        public java.math.BigDecimal getSuggestedCashAmount() { return suggestedCashAmount; }
        public void setSuggestedCashAmount(java.math.BigDecimal suggestedCashAmount) { this.suggestedCashAmount = suggestedCashAmount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }
        public String getCategoryText() { return categoryText; }
        public void setCategoryText(String categoryText) { this.categoryText = categoryText; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
        public java.time.LocalDateTime getDecidedAt() { return decidedAt; }
        public void setDecidedAt(java.time.LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
        public java.time.LocalDateTime getExecutedAt() { return executedAt; }
        public void setExecutedAt(java.time.LocalDateTime executedAt) { this.executedAt = executedAt; }
    }

    /**
     * 客户视角：按客户 + 水站分页查询自己的桶异常记录。
     * <p>客户侧此前没有这个能力，前端 exception 页调用 /api/customer/exceptions 恒 500。</p>
     */
    Page<OrderBarrelExceptionDTO> listByCustomer(Long customerId, Long stationId, int page, int size);
}