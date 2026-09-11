package com.example.aquaflow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日结对账定时任务（对应审计第 7 节 4 条守恒等式）。
 *
 * <p>把 {@code scripts/daily_reconcile.sql} 的 4 条等式固化为每日凌晨自动执行的服务：
 * 任一等式不平即记 error 日志（带示例异常主键），便于接入监控/告警后自动发现账乱。
 * 全部平衡则记 info。无需人工跑 SQL。</p>
 *
 * <p>不动任何业务数据，只读校验。</p>
 */
@Slf4j
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 每日 03:00 执行日结对账。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyReconcile() {
        log.info("[日结对账] 开始执行");
        Map<String, Integer> result = runReconcile();
        boolean allOk = result.values().stream().allMatch(v -> v == 0);
        if (allOk) {
            log.info("[日结对账] 通过：押金 / 支付 / 桶 / 库存 全部平衡");
        } else {
            log.error("[日结对账] 发现不平项，请人工介入：{}", result);
        }

        // 新桶权益模型的独立校验（与 V1 并行跑，不覆盖 V1 的结论）
        Map<String, Integer> v2 = runReconcileV2();
        boolean v2Ok = v2.values().stream().allMatch(v -> v == 0);
        if (v2Ok) {
            log.info("[日结对账 V2] 通过：权益批次 / 占用恒等 / 物理桶守恒 / 穿底 全部平衡");
        } else {
            log.warn("[日结对账 V2] 发现不平项：{}", v2);
        }
    }

    /**
     * 执行 4 条等式校验，返回各等式的不平条数。供定时任务与（未来的）手动触发接口复用。
     */
    public Map<String, Integer> runReconcile() {
        Map<String, Integer> result = new LinkedHashMap<>();

        // 等式1：押金账户余额 vs 押金流水净和
        int eq1 = count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id FROM customer_deposit_account a "
                + "LEFT JOIN (SELECT customer_id, station_id, SUM(amount) AS flow_sum FROM deposit_record GROUP BY customer_id, station_id) r "
                + "ON r.customer_id = a.customer_id AND r.station_id = a.station_id "
                + "WHERE ABS(a.balance - COALESCE(r.flow_sum, 0)) > 0.009) x");
        result.put("depositAccount", eq1);
        if (eq1 > 0) {
            log.error("[日结对账 ALERT 等式1] 押金账户余额与流水不符：{} 个账户。示例 customer_id={}",
                    eq1, sampleIds("SELECT a.customer_id FROM customer_deposit_account a "
                            + "LEFT JOIN (SELECT customer_id, station_id, SUM(amount) AS flow_sum FROM deposit_record GROUP BY customer_id, station_id) r "
                            + "ON r.customer_id = a.customer_id AND r.station_id = a.station_id "
                            + "WHERE ABS(a.balance - COALESCE(r.flow_sum, 0)) > 0.009 LIMIT 20"));
        }

        // 等式2：订单支付状态 vs 支付流水
        int p2a = count("SELECT COUNT(*) FROM orders o WHERE o.payment_status = 2 "
                + "AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2)");
        int p2b = count("SELECT COUNT(*) FROM orders o WHERE o.payment_status <> 2 "
                + "AND EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2)");
        int p2c = count("SELECT COUNT(*) FROM payment_record p LEFT JOIN orders o ON o.id = p.order_id WHERE o.id IS NULL");
        int p2d = count("SELECT COUNT(*) FROM orders o WHERE o.payment_status = 3 "
                + "AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 3)");
        int eq2 = p2a + p2b + p2c + p2d;
        result.put("paymentStatus", eq2);
        if (eq2 > 0) {
            log.error("[日结对账 ALERT 等式2] 订单支付状态与流水不符：已付无凭证={}, 有凭证未置已付={}, 孤儿流水={}, 已退无退款流水={}",
                    p2a, p2b, p2c, p2d);
        }

        // 等式3：桶三态守恒
        // 注意 b3c 的语义已随桶权益模型改变：
        //   旧：customer_owed_barrel.owed_qty < 0 视为脏数据（该表只增不减，负值确实异常）
        //   新：customer_barrel_over.over_qty < 0 = 顾客多还桶 / 水站暂存，是【业务方确认的合法状态】，
        //       不报警。真正要拦的是越界：over < −权益（意味着占用为负，物理上不可能）。
        int b3a = count("SELECT COUNT(*) FROM customer_barrel_in_transit WHERE status = 'DELIVERED'");
        int b3b = count("SELECT COUNT(*) FROM customer_barrel_asset WHERE quantity < 0");
        int b3c = count("SELECT COUNT(*) FROM customer_barrel_over o "
                + "WHERE o.over_qty < -(SELECT COALESCE(SUM(l.remain_qty),0) FROM customer_barrel_lot l "
                + "                     WHERE l.customer_id = o.customer_id AND l.station_id = o.station_id "
                + "                       AND l.product_id = o.product_id AND l.status = 1)");
        // 押金口径改用 right_amount（= Σ lot.remain_qty × lot.unit_price，按买入时单价）。
        // 旧口径用【当前 product.deposit】折算，只要调过价就必然不平，属于假告警；
        // 新口径下不平说明"权益可退金额 > 押金账户余额"，是真实的退款穿底风险。
        int b3d = count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id FROM customer_barrel_asset a "
                + "LEFT JOIN customer_deposit_account da ON da.customer_id = a.customer_id AND da.station_id = a.station_id "
                + "GROUP BY a.customer_id, a.station_id "
                + "HAVING SUM(COALESCE(a.right_amount, 0)) - COALESCE(MAX(da.balance), 0) > 0.009) x");
        int eq3 = b3a + b3b + b3c + b3d;
        result.put("barrelState", eq3);
        if (eq3 > 0) {
            log.error("[日结对账 ALERT 等式3] 桶三态异常：配送中残留DELIVERED={}, 在手负数={}, over越界(over<-权益)={}, 权益金额超押金余额(穿底风险)={}",
                    b3a, b3b, b3c, b3d);
        }

        // 等式4：库存数量 vs 库存流水累计
        int eq4 = count("SELECT COUNT(*) FROM inventory i "
                + "LEFT JOIN (SELECT station_id, product_id, SUM(delta) AS flow_sum FROM inventory_record GROUP BY station_id, product_id) r "
                + "ON r.station_id = i.station_id AND r.product_id = i.product_id "
                + "WHERE (i.quantity - COALESCE(r.flow_sum, 0)) <> 0");
        result.put("inventory", eq4);
        if (eq4 > 0) {
            log.error("[日对账 ALERT 等式4] 库存与流水累计不符：{} 条", eq4);
        }

        return result;
    }

    // =========================================================================
    // 对账 V2：按「桶权益模型」重新设计的独立等式（E3 ~ E7）
    //
    // V1 的等式 3 是旧模型改出来的补丁，能跑但语义拧巴。V2 从模型公理出发重新写：
    //   权益 Right = Σ lot.remain_qty      占用 = 权益 + over
    // 与 V1 并行运行，结论互不覆盖。
    // =========================================================================

    /**
     * 桶权益模型对账。返回各等式的不平条数。
     *
     * <ul>
     *   <li>E3 权益汇总与批次一致：asset.quantity == Σ lot.remain_qty 且 right_amount == Σ remain×unit_price</li>
     *   <li>E4 占用恒等与越界：占用 = 权益 + over 且占用 ≥ 0（over < −权益 意味着桶数为负，物理不可能）</li>
     *   <li>E5 物理桶守恒：占用（账面）== 用流水重算的占用</li>
     *   <li>E6 穿底：押金账户余额 ≥ 权益可退金额</li>
     *   <li>E7 存桶滞留：over &lt; 0 且 30 天无配送拉回 —— <b>只告警不拦截</b></li>
     * </ul>
     */
    public Map<String, Integer> runReconcileV2() {
        Map<String, Integer> r = new LinkedHashMap<>();

        // ---- E3：权益汇总 vs 押金条批次 ----
        int e3a = count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id, a.product_id FROM customer_barrel_asset a "
                + "LEFT JOIN (SELECT customer_id, station_id, product_id, "
                + "                  SUM(remain_qty) rq, SUM(remain_qty * unit_price) ra "
                + "           FROM customer_barrel_lot WHERE status = 1 "
                + "           GROUP BY customer_id, station_id, product_id) l "
                + "  ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id "
                + "WHERE a.quantity <> COALESCE(l.rq, 0) "
                + "   OR ABS(COALESCE(a.right_amount, 0) - COALESCE(l.ra, 0)) > 0.009) x");
        // 反向：有批次却没有权益汇总行
        int e3b = count("SELECT COUNT(*) FROM ("
                + "SELECT l.customer_id, l.station_id, l.product_id FROM customer_barrel_lot l "
                + "WHERE l.status = 1 AND NOT EXISTS (SELECT 1 FROM customer_barrel_asset a "
                + "  WHERE a.customer_id = l.customer_id AND a.station_id = l.station_id AND a.product_id = l.product_id) "
                + "GROUP BY l.customer_id, l.station_id, l.product_id) x");
        int e3 = e3a + e3b;
        r.put("E3_rightVsLot", e3);
        if (e3 > 0) {
            log.error("[对账V2 ALERT E3] 权益汇总与押金条批次不符：汇总表错={}, 有批次无汇总={}。示例={}",
                    e3a, e3b, sampleIds("SELECT a.customer_id FROM customer_barrel_asset a "
                            + "LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty) rq "
                            + "           FROM customer_barrel_lot WHERE status = 1 GROUP BY 1,2,3) l "
                            + "  ON l.customer_id=a.customer_id AND l.station_id=a.station_id AND l.product_id=a.product_id "
                            + "WHERE a.quantity <> COALESCE(l.rq,0) LIMIT 20"));
        }

        // ---- E4：占用恒等 + 越界 ----
        // 占用 = 权益 + over 天然成立（占用是派生值），真正要拦的是占用为负：
        // over < −权益 意味着"顾客手上有负数个桶"，物理上不可能，说明某一侧算错了。
        int e4 = count("SELECT COUNT(*) FROM customer_barrel_over o "
                + "WHERE o.over_qty < -(SELECT COALESCE(SUM(l.remain_qty), 0) FROM customer_barrel_lot l "
                + "                     WHERE l.customer_id = o.customer_id AND l.station_id = o.station_id "
                + "                       AND l.product_id = o.product_id AND l.status = 1)");
        r.put("E4_occupiedOutOfRange", e4);
        if (e4 > 0) {
            log.error("[对账V2 ALERT E4] 占用为负（over < −权益）：{} 条。这意味着某一侧的桶数算错了，物理上不可能", e4);
        }

        // ---- E5：物理桶守恒（用流水重算占用，与账面交叉验证）----
        // 只有留了 type=8 配送收发流水之后这条才成立；历史数据若含 type=6 人工调整
        // （语义是"设置为 N"而非增减），可能导致误报，需要人工分辨。
        int e5 = count("SELECT COUNT(*) FROM ("
                + "SELECT u.customer_id, u.station_id, u.product_id "
                + "FROM ("
                + "  SELECT customer_id, station_id, product_id, (delivered_qty - returned_qty) AS delta, 0 AS book FROM barrel_record WHERE type = 8 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 7 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 2 AND status = 3 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type IN (3, 4) "
                + "  UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 1 "
                + "  UNION ALL SELECT a.customer_id, a.station_id, a.product_id, 0, "
                + "                   a.quantity + COALESCE(o.over_qty, 0) FROM customer_barrel_asset a "
                + "                   LEFT JOIN customer_barrel_over o ON o.customer_id = a.customer_id "
                + "                        AND o.station_id = a.station_id AND o.product_id = a.product_id "
                + ") u GROUP BY u.customer_id, u.station_id, u.product_id "
                + "HAVING COALESCE(SUM(u.delta), 0) <> COALESCE(MAX(u.book), 0)) x");
        r.put("E5_physicalConservation", e5);
        if (e5 > 0) {
            log.warn("[对账V2 ALERT E5] 物理桶不守恒：流水重算的占用与账面(权益+over)不符 {} 条。"
                    + "若存在 type=6 人工调整记录，需先人工分辨（该类型是'设置为N'而非增减，无法纳入守恒）", e5);
        }

        // ---- E6：穿底（权益可退金额 > 押金账户余额）----
        int e6 = count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id FROM customer_barrel_asset a "
                + "LEFT JOIN customer_deposit_account da ON da.customer_id = a.customer_id AND da.station_id = a.station_id "
                + "GROUP BY a.customer_id, a.station_id "
                + "HAVING SUM(COALESCE(a.right_amount, 0)) - COALESCE(MAX(da.balance), 0) > 0.009) x");
        r.put("E6_depositShortfall", e6);
        if (e6 > 0) {
            log.error("[对账V2 ALERT E6] 押金穿底：权益可退金额 > 押金账户余额，共 {} 个账户。"
                    + "这些客户一旦退桶就会退失败（或绕过校验后真的退穿）", e6);
        }

        // ---- E7：存桶滞留（over<0 且 30 天无配送拉回）—— 只告警，不拦截 ----
        // over<0 本身是合法状态，不该报警；但"多还的桶在水站放了 30 天没人管"
        // 值得人工看一眼：可能是顾客忘了，也可能是水站没登记。
        int e7 = count("SELECT COUNT(*) FROM customer_barrel_over o "
                + "WHERE o.over_qty < 0 AND NOT EXISTS ("
                + "  SELECT 1 FROM barrel_record r WHERE r.customer_id = o.customer_id "
                + "    AND r.station_id = o.station_id AND r.product_id = o.product_id "
                + "    AND r.type = 8 AND r.create_time > DATE_SUB(NOW(), INTERVAL 30 DAY))");
        r.put("E7_storageStale", e7);
        if (e7 > 0) {
            log.warn("[对账V2 提示 E7] 存桶滞留：over<0 且 30 天无配送记录 {} 条（顾客多还的桶寄在水站，建议人工确认）", e7);
        }

        return r;
    }

    private int count(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private String sampleIds(String sql) {
        try {
            List<Long> ids = jdbcTemplate.queryForList(sql, Long.class);
            return ids.isEmpty() ? "无" : ids.toString();
        } catch (Exception e) {
            return "示例查询异常:" + e.getMessage();
        }
    }
}
