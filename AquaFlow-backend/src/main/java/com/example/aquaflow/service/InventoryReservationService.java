package com.example.aquaflow.service;

/**
 * 库存预留凭据服务 —— 「这份货是为哪张单留在哪个站」的**唯一**写入口与**唯一**分配入口。
 *
 * <p>规格正本：{@code docs/design/28-库存预留与履约凭据.md}（§11 是 2026-09-25 返工修订，效力高于前文）。
 * 要解决的问题：跨站外派后取消把货补错站（实测 A=8、B=12）、缺货下单那部分永不落账。</p>
 *
 * <h3>四个时点</h3>
 * <pre>
 *   下单       → reserveForItem        ：预留（占可用量，**不动实物**）
 *   入库/盘点增 → backfillReservations  ：把新到的货按**业务需求时间**补给等待需求
 *   完成配送   → shipForOrder          ：出库（实物 −）；**先做完整性检查**（覆盖/站别/数量）
 *   换站       → transferForOrder      ：旧站释放 + 新站重建 ⇒ 旧站补位
 *   取消/拒单   → releaseForOrder       ：释放 ⇒ **同样触发补位**（首版漏了这半句，见 R4）
 * </pre>
 *
 * <h3>锁与当前读（返工 R1 + 二次收口 B1）</h3>
 * <ul>
 *   <li><b>统一锁序（全仓唯一，没有例外）</b>：① 用普通读**发现**可能要动的 (站,商品)（只用来决定锁谁）
 *       → ② 按 {@code (station_id, product_id)} **升序**锁 {@code inventory} 行
 *       → ③ 用 {@code ...ForUpdate} **当前读**凭据并**重新验证**资源集合（变了就抛可读冲突）
 *       → ④ 写凭据（CAS + 检查行数）与 {@code order_item} 镜像。
 *       加上调用方已持有的锁，完整序是 <b>{@code orders} → {@code inventory}(升序) → 凭据 → {@code order_item}</b>。
 *       首版"补位/预留先库存、出库/换站/释放先凭据"是两套相反的顺序，会与并发入库构成锁环（二次验收 B1）。</li>
 *   <li><b>两件套</b>：任何分配决策都要 ① 先 {@code SELECT ... FROM inventory ... FOR UPDATE} 锁住
 *       (站,商品) 的库存行（串行化同一商品的并发分配），② 再用 {@code ...ForUpdate} 的**当前读**
 *       读活跃凭据（REPEATABLE READ 下普通 SELECT 会读事务开始时的快照，只锁库存行救不了它 —— AGENTS §8.2）。</li>
 *   <li><b>需求量快照</b>：补位只读凭据行上的 {@code need_qty} / {@code need_time}（v65），
 *       **不 join** `orders`/`order_item` —— 否则"当前读看到新凭据、普通读看不到刚提交的新明细"，
 *       新等待单会被当成 need=0 跳过（二次验收 B2）。真相源仍是 `order_item.quantity` / `orders.create_time`，
 *       一致性由对账 E15 校验。</li>
 *   <li>本服务**只**通过 {@link InventoryLedgerService} 写 {@code inventory_record}（预留/释放不写流水）。</li>
 * </ul>
 *
 * <p>⚠️ 与旧实现的根本差别：旧代码"下单就减 quantity、取消就加回去"，"扣在哪一站"从未被记录。</p>
 */
public interface InventoryReservationService {
    /** 实盘亏损命令专用；按原需求时间保留较早预留，受影响需求继续等货。 */
    java.util.List<java.util.Map<String,Object>> reduceForPhysicalLoss(Long stationId,Long productId,int targetQuantity);

    /**
     * 可用量 = 在库实物 − 本站该商品**活跃**预留之和（&ge;0）。**仅供参考展示**；
     * 分配决策一律走本服务内部的两件套，不要在外面先算再用（会有 TOCTOU）。
     */
    int availableQty(Long stationId, Long productId);

    /**
     * 为一条订单明细建预留（下单时调用）。
     * <p>预留量 = {@code min(可用量, 需求量)}；差额即"缺货待补"（产品允许缺货预订）。
     * **缺货明细也必须留下凭据**（`reserved_qty = 0`），否则补位找不到这条需求、
     * 完成配送的覆盖检查也会漏（返工 R2 的第二半）。</p>
     *
     * @return 实际预留量；同时把该值写进 {@code order_item.deducted_qty}（该列的唯一语义 = 活跃凭据的预留量镜像）
     */
    int reserveForItem(Long orderId, Long orderItemId, Long stationId, Long productId, int requestedQty);

    /** 该单"缺货待补"总量（Σ(订单量 − 已预留量)）；0 = 货已全部落到实物上。 */
    int shortageOfOrder(Long orderId);

    /**
     * 该单的**备货情况**（配送端「已备齐 / 还缺哪些商品」的只读投影，契约工作包 C4）。
     *
     * <p>返回 {@code ready} / {@code shortageTotal} / {@code itemsWithoutCredential} /
     * {@code items:[{productId, productName, needQty, reservedQty, shortage}]}。
     * 口径：只读凭据上的 {@code need_qty} 快照（与 {@link #shortageOfOrder} 同一套判据），
     * 另加"整条明细没有凭据"那半边 —— <b>两条都满足才算 {@code ready}</b>。</p>
     *
     * <p>⚠️ 这是**展示**：真正拦住"少扣一点先把单结了"的是 {@link #shipForOrder}，
     * 它在出库前会再查一遍（提示可能过期，写动作必须再校验）。</p>
     */
    java.util.Map<String, Object> prepInfoOfOrder(Long orderId);

    /**
     * 补位：把本站该商品的可用量按**业务需求时间**（`orders.create_time`，换站新建凭据也会保留原单时间）
     * 补给等待需求。**这是全仓唯一的分配算法实现** —— 入库/盘点增加、释放、换站旧站都调它。
     * <p>不写 `inventory_record`（预留不动实物）；补到可用量用完为止。</p>
     */
    void backfillReservations(Long stationId, Long productId);

    /**
     * 盘点**下调**前的不变量检查：目标实物量不得低于本站该商品**已预留总量**。
     *
     * <p>为什么必须是硬拒绝（返工 V06）：把实物从 10 盘到 3、而其中 8 已被订单预留，
     * 差额 Δ(实物 − 预留) = −5 —— 等于"已经卖出去的货凭空消失"，但站长看到的只是"保存成功"。
     * 真实盘亏本轮不做专用命令（契约 §4），所以这里明确拒绝并列出占用中的订单。</p>
     *
     * <p>调用方（{@code InventoryServiceImpl.setStock}）已持有 inventory 行锁；本方法内部再取一次
     * 库存行锁 + 活跃凭据的**当前读**，顺序与其余命令一致（先库存后凭据），自持锁重入无副作用。</p>
     *
     * @throws com.example.aquaflow.exception.BusinessException 目标量低于已预留量
     */
    void assertStockNotBelowReserved(Long stationId, Long productId, int targetQuantity);

    /**
     * 完成配送出库：把该单各项预留一次性出库（`quantity` 减少 + 写 `CONSUME` 流水）。
     *
     * <p><b>硬门槛（本方法自己判，调用方不要绕）</b>：① 凭据必须覆盖该单**每一条**明细；
     * ② 每张凭据的站别必须等于 {@code expectedStationId}（该单当前履约站）；
     * ③ `reserved_qty` 必须等于该明细的需求量（缺货待补为 0）且在 `[0, quantity]` 内；
     * ④ 本站实物足够。任一不满足 ⇒ 抛 {@code BusinessException}，绝不"少扣一点先把单结了"。</p>
     */
    void shipForOrder(Long orderId, Long expectedStationId);

    /**
     * 换站：把该单活跃凭据从当前站搬到 {@code toStationId}（放池/定向外派/抢单/召回/指定退回同意都走它）。
     * <p>旧站凭据置为已释放、新站插一条新凭据（保留两条当审计轨迹），
     * 且**旧站释放出来的货要按 FIFO 补给它自己的等待需求**。</p>
     */
    void transferForOrder(Long orderId, Long toStationId);

    /**
     * 释放该单全部活跃凭据（取消 / 拒单 / 超时扫单）并**触发受影响 (站,商品) 的补位**。
     * <p>⚠️ 实物从没被扣过，所以这里**不写库存回补**（首版写的那两行会凭空造库存）。</p>
     *
     * @return 释放的凭据行数（0 = 本单没有活跃凭据，通常是重复取消）
     */
    int releaseForOrder(Long orderId);
}
