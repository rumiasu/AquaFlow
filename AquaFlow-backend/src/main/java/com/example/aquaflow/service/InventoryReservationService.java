package com.example.aquaflow.service;

/**
 * 库存预留凭据服务 —— 「这份货是为哪张单留在哪个站」的唯一写入口。
 *
 * <p>规格见 {@code docs/design/28-库存预留与履约凭据.md}；要解决的问题见该文档 §1（跨站外派后取消
 * 会把货补错站、缺货下单那部分永不落账）。</p>
 *
 * <h3>两个量，四个时点</h3>
 * <pre>
 *   下单     → reserveForItem    ：占"可用量"（= 在库实物 − 本站已预留），**不动实物**
 *   入库/盘点 → {@link InventoryService#backfillReservations}：新增的货按 FIFO 补给等货的单
 *   完成配送 → shipForOrder      ：出库（实物 −），锚定**当时履约站**；预留不足则拒绝
 *   换站     → transferForOrder  ：旧站凭据释放、新站按可用量重建（货跟着履约站走）
 *   取消/拒单 → releaseForOrder   ：释放（实物本来就没动，所以**不是**回补库存）
 * </pre>
 *
 * <p>⚠️ 与旧实现的根本差别：旧代码"下单就 <b>减</b> `inventory.quantity`、取消就 <b>加</b> 回去"，
 * 于是"扣在哪一站"从未被记录；本服务把这件事变成一条可核对的凭据。</p>
 */
public interface InventoryReservationService {

    /**
     * 可用量 = 在库实物 − 本站该商品已预留量（&ge;0）。
     * <p>缺货判断与商城"暂时没货"都读它；<b>站长端「库存数量」仍读实物</b>（他盘点的是实物）。</p>
     */
    int availableQty(Long stationId, Long productId);

    /**
     * 为一条订单明细建预留（下单时调用）。
     *
     * <p>锁 `inventory` 行取当前值：并发下单必须串行地算可用量，否则两个客户会各自把同一批实物承诺出去。
     * 预留量 = {@code min(可用量, 需求量)}，差额就是"缺货待补"（产品允许缺货预订，见 `needConfirm`）。</p>
     *
     * @return 实际预留量（可能小于需求量）；同时把该值写进 {@code order_item.deducted_qty}
     */
    int reserveForItem(Long orderId, Long orderItemId, Long stationId, Long productId, int requestedQty);

    /** 该单"缺货待补"总量（Σ(订单量 − 已预留量)）；0 = 货已全部落到实物上。 */
    int shortageOfOrder(Long orderId);

    /**
     * 完成配送出库：把该单各项预留**一次性出库**（`inventory.quantity` 减少 + 写 {@code CONSUME} 流水）。
     *
     * <p>硬门槛（本方法自己判，调用方不要绕）：预留不足（有缺货待补）或本站实物不足时**抛
     * BusinessException** —— 绝不允许"少扣一点先把单结了"：那正是问题 4b 的病根
     * （7 桶永远不落账，账实永久漂移，而对账等式抓不到）。</p>
     *
     * <p>兼容：v63 迁移之前的存量单没有凭据（它们的货在下单时就已经扣过实物），此时本方法直接返回，
     * 不会二次扣减。</p>
     */
    void shipForOrder(Long orderId);

    /**
     * 换站：把该单的活跃凭据从当前站搬到 {@code toStationId}（放池/定向外派/抢单/召回都走它）。
     *
     * <p>做法是"旧站那条置为已释放 + 新站插一条新的"（保留两条当审计轨迹，唯一键只约束**活跃**凭据）。
     * 新站的预留量按**新站可用量**重算：不足就是"到新站后仍缺货待补"，不阻断接单
     * （保留"缺货可预订"），但完成配送时会被 {@link #shipForOrder} 拦下。</p>
     */
    void transferForOrder(Long orderId, Long toStationId);

    /**
     * 释放该单全部活跃凭据（取消 / 拒单 / 超时扫单）。
     * <p>⚠️ **不写库存回补**：实物从没被扣过 —— 这是新模型与旧实现最容易改错的一处。</p>
     *
     * @return 受影响行数（0 = 本单没有活跃凭据，通常是重复取消）
     */
    int releaseForOrder(Long orderId);
}
