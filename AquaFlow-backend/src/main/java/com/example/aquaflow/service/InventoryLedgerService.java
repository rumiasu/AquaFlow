package com.example.aquaflow.service;

/**
 * 库存**流水**的唯一写入口（`inventory_record`）。
 *
 * <p>为什么要单独一个 bean（2026-09-25 返工）：库存实物的两条写路径——人工入库/盘点
 * （{@code InventoryServiceImpl}）与订单出库/补位（{@code InventoryReservationServiceImpl}）——
 * 互有调用需求（入库后要补预留、出库要写流水）。Spring Boot 2.6+ 默认**禁止循环引用**
 * （{@code spring.main.allow-circular-references=false}），两个服务直接互相注入会在启动期报错。
 * 把"写流水"这件最小的事提出来，依赖方向就成了单向：
 * <pre>
 *   InventoryServiceImpl ──▶ InventoryReservationService ──▶ InventoryLedgerService ──▶ InventoryRecordMapper
 * </pre>
 *
 * <p>规则不变（[AQ-029]）：**库存增减必须与流水成对出现**；预留与释放**不写**本表
 * （它们不动 {@code inventory.quantity}，写了会破坏 V1-4 等式「quantity == Σ delta」）。
 */
public interface InventoryLedgerService {

    /**
     * 写一条库存流水（delta 为 0 时静默跳过）。
     *
     * @param delta 变动量（正=入/回补，负=出/扣减）
     * @param type  见 {@link com.example.aquaflow.constant.InventoryChangeType}
     */
    void recordChange(Long stationId, Long productId, Integer delta, String type,
                      Long refId, Long operatorId, String note);
}
