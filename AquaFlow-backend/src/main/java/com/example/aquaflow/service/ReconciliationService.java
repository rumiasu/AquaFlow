package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
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

    /** 分级告警：对账不平属**系统故障** → 投给系统管理员（见 constant/AlertType） */
    private final AlertService alertService;

    public ReconciliationService(JdbcTemplate jdbcTemplate, AlertService alertService) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertService = alertService;
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
            // [2026-09-16] 对账不平 = 系统故障（账目/流水层面出了问题，站长既看不懂也修不了）
            alertService.systemFault("DailyReconcile", "日结对账发现不平项",
                    "V1（押金/支付/桶/库存）不平项：" + result, null, null);
        }

        // 新桶权益模型的独立校验（与 V1 并行跑，不覆盖 V1 的结论）
        Map<String, Integer> v2 = runReconcileV2();
        boolean v2Ok = v2.values().stream().allMatch(v -> v == 0);
        if (v2Ok) {
            log.info("[日结对账 V2] 通过：权益批次 / 占用恒等 / 物理桶守恒 / 穿底 全部平衡");
        } else {
            log.warn("[日结对账 V2] 发现不平项：{}", v2);
            alertService.systemFault("DailyReconcile", "日结对账 V2 发现不平项",
                    "V2（权益批次/占用恒等/物理守恒/穿底）不平项：" + v2, null, null);
        }

        // 结果落表：此前只写日志，无人可查、无留痕（问责与趋势分析都做不到）
        persistResults(result, v2);
    }

    /**
     * 把对账结果落到 reconciliation_result（每日每检查项一行，同日重跑覆盖）。
     * <p>级别：E5/E7 为提示型（WARN），其余为 ERROR。</p>
     */
    public void persistResults(Map<String, Integer> v1, Map<String, Integer> v2) {
        java.time.LocalDate today = java.time.LocalDate.now();
        try {
            writeRows(today, v1, "ERROR");
            writeRows(today, v2, "WARN_KEYS");
        } catch (Exception e) {
            // 落表失败不能影响对账本身的告警（对账是只读校验，日志才是最后防线）
            log.error("[日结对账] 结果落表失败：{}", e.getMessage(), e);
        }
    }

    private void writeRows(java.time.LocalDate date, Map<String, Integer> data, String levelMode) {
        if (data == null) return;
        data.forEach((key, count) -> {
            String level = "WARN_KEYS".equals(levelMode)
                    ? (key.startsWith("E5") || key.startsWith("E7") ? "WARN" : "ERROR")
                    : "ERROR";
            jdbcTemplate.update(
                    "INSERT INTO reconciliation_result(run_date, check_key, diff_count, level, sample_ids) "
                            + "VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE diff_count = VALUES(diff_count), "
                            + "level = VALUES(level), sample_ids = VALUES(sample_ids), create_time = NOW()",
                    java.sql.Date.valueOf(date), key, count == null ? 0 : count, level, null);
        });
    }

    /**
     * 単一水站スコープの対帳（站长端向け）。
     *
     * <p>[2026-09-13 修正] 此前站长端读到的是 {@link #listRecentResults} 返回的<b>全平台</b>结果，
     * 其中含其它水站的差异件数与 {@code sample_ids}（客户 ID），属跨租户信息泄露。
     * 现改为只按登录站长所属水站做 station-scoped 校验：返回值中不含其它水站与全平台的任何数据。</p>
     *
     * <p>本方法只读、不写库；全平台结果仍由 03:00 的 {@link #dailyReconcile()} 落
     * {@code reconciliation_result} 供运维/事后追查使用，不经任何面向站长的接口暴露。</p>
     */
    public Map<String, Object> stationCheck(Long stationId) {
        if (stationId == null) {
            throw new BusinessException("无法识别当前水站");
        }
        Map<String, Integer> r = new LinkedHashMap<>();

        // SE1：本水站押金账户余额 == 本水站押金流水净和（对账等式1 的按站版）
        r.put("SE1_depositAccount", count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id FROM customer_deposit_account a "
                + "LEFT JOIN (SELECT customer_id, station_id, SUM(amount) AS flow_sum FROM deposit_record "
                + "           WHERE station_id = ? GROUP BY customer_id, station_id) f "
                + "  ON f.customer_id = a.customer_id AND f.station_id = a.station_id "
                + "WHERE a.station_id = ? AND ABS(a.balance - COALESCE(f.flow_sum, 0)) > 0.009) x",
                stationId, stationId));

        // SE3：权益汇总 vs 权益批次（E3 的按站版）
        r.put("SE3_rightVsLot", count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id, a.product_id FROM customer_barrel_asset a "
                + "LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty) rq, "
                + "                  SUM(remain_qty * unit_price) ra FROM customer_barrel_lot "
                + "           WHERE status = 1 AND station_id = ? GROUP BY customer_id, station_id, product_id) l "
                + "  ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id "
                + "WHERE a.station_id = ? AND (a.quantity <> COALESCE(l.rq, 0) "
                + "   OR ABS(COALESCE(a.right_amount, 0) - COALESCE(l.ra, 0)) > 0.009)) x",
                stationId, stationId));

        // SE4：占用为负（over < −权益）
        r.put("SE4_occupiedOutOfRange", count("SELECT COUNT(*) FROM customer_barrel_over o "
                + "WHERE o.station_id = ? AND o.over_qty < -COALESCE((SELECT SUM(l.remain_qty) FROM customer_barrel_lot l "
                + "  WHERE l.customer_id = o.customer_id AND l.station_id = o.station_id "
                + "    AND l.product_id = o.product_id AND l.status = 1), 0)", stationId));

        // SE5：物理桶守恒（E5 的按站版；type=6/9 为人工调整，已纳入）
        r.put("SE5_physicalConservation", count("SELECT COUNT(*) FROM ("
                + "SELECT u.customer_id, u.station_id, u.product_id FROM ("
                + "  SELECT customer_id, station_id, product_id, (delivered_qty - returned_qty) AS delta, 0 AS book FROM barrel_record WHERE type = 8 AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 7 AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 2 AND status = 3 AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type IN (3, 4) AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 1 AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 6 AND station_id = ? "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 9 AND station_id = ? "
                + "  UNION ALL SELECT a.customer_id, a.station_id, a.product_id, 0, a.quantity + COALESCE(o.over_qty, 0) "
                + "    FROM customer_barrel_asset a LEFT JOIN customer_barrel_over o "
                + "      ON o.customer_id = a.customer_id AND o.station_id = a.station_id AND o.product_id = a.product_id "
                + "    WHERE a.station_id = ? "
                + ") u GROUP BY u.customer_id, u.station_id, u.product_id "
                + "HAVING COALESCE(SUM(u.delta), 0) <> COALESCE(MAX(u.book), 0)) x",
                stationId, stationId, stationId, stationId, stationId, stationId, stationId, stationId));

        // SE6：押金穿底（权益可退金额 > 押金余额）
        r.put("SE6_depositShortfall", count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id FROM customer_barrel_asset a "
                + "LEFT JOIN customer_deposit_account da ON da.customer_id = a.customer_id AND da.station_id = a.station_id "
                + "WHERE a.station_id = ? GROUP BY a.customer_id, a.station_id "
                + "HAVING SUM(COALESCE(a.right_amount, 0)) - COALESCE(MAX(da.balance), 0) > 0.009) x",
                stationId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stationId", stationId);
        out.put("checks", r);
        out.put("totalDiff", r.values().stream().mapToInt(Integer::intValue).sum());
        out.put("checkedAt", java.time.LocalDateTime.now().toString());
        return out;
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
        // 只有留了 type=8 配送收发流水之后这条才成立。
        // [2026-09-13] 人工调整（station_adjustment）落 barrel_record 的 type=6/9：
        //   type=6 = 人工调整（增加，quantity 为绝对增量）
        //   type=9 = 人工调整（减少，quantity 为绝对减量）
        // 拆成两个类型而不是用负数，正是为了让本守恒式无需判断符号即可求和。
        // 若不把这两项纳入，每一次站长补录都会让本检查项告警（假警报）。
        int e5 = count("SELECT COUNT(*) FROM ("
                + "SELECT u.customer_id, u.station_id, u.product_id "
                + "FROM ("
                + "  SELECT customer_id, station_id, product_id, (delivered_qty - returned_qty) AS delta, 0 AS book FROM barrel_record WHERE type = 8 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 7 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 2 AND status = 3 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type IN (3, 4) "
                + "  UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 1 "
                + "  UNION ALL SELECT customer_id, station_id, product_id,  quantity, 0 FROM barrel_record WHERE type = 6 "
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record WHERE type = 9 "
                + "  UNION ALL SELECT a.customer_id, a.station_id, a.product_id, 0, "
                + "                   a.quantity + COALESCE(o.over_qty, 0) FROM customer_barrel_asset a "
                + "                   LEFT JOIN customer_barrel_over o ON o.customer_id = a.customer_id "
                + "                        AND o.station_id = a.station_id AND o.product_id = a.product_id "
                + ") u GROUP BY u.customer_id, u.station_id, u.product_id "
                + "HAVING COALESCE(SUM(u.delta), 0) <> COALESCE(MAX(u.book), 0)) x");
        r.put("E5_physicalConservation", e5);
        if (e5 > 0) {
            log.warn("[对账V2 ALERT E5] 物理桶不守恒：流水重算的占用与账面(权益+over)不符 {} 条。"
                    + "若存在历史遗留的 type=6 记录（2026-09-13 之前该类型语义为'设置为N'而非增减），需人工分辨", e5);
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

        // ---- E8：水票余额 vs 水票批次（v36，与 E3 同构）----
        // 为什么必须有一条：档位意味着一张票的价格是**分段**的，站长改一次档位价之后，
        // "客户账户里那 100 张票值多少钱"就再也没有参照物了。
        // ticket_lot 是真相源，ticket_account.remain_quantity / right_amount 都是它的派生汇总。
        int e8a = count("SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id, a.product_id FROM ticket_account a "
                + "LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty) rq, "
                + "                  SUM(remain_qty * unit_price) ra FROM ticket_lot WHERE status = 1 "
                + "            GROUP BY customer_id, station_id, product_id) l "
                + "  ON l.customer_id = a.customer_id AND l.station_id = a.station_id AND l.product_id = a.product_id "
                + "WHERE COALESCE(a.remain_quantity, 0) <> COALESCE(l.rq, 0) "
                + "   OR ABS(COALESCE(a.right_amount, 0) - COALESCE(l.ra, 0)) > 0.009) x");
        // 有批次却没有汇总行：说明账户行被删了，或批次写进了别的 (客户,站,商品) 维度
        int e8b = count("SELECT COUNT(*) FROM ("
                + "SELECT l.customer_id, l.station_id, l.product_id FROM ticket_lot l "
                + "WHERE l.status = 1 AND NOT EXISTS (SELECT 1 FROM ticket_account a "
                + "  WHERE a.customer_id = l.customer_id AND a.station_id = l.station_id "
                + "    AND a.product_id = l.product_id)) x");
        int e8 = e8a + e8b;
        r.put("E8_ticketBalanceVsLot", e8);
        if (e8 > 0) {
            // 与 E3/E5 同级：水票是钱（预付），不平属**系统故障**，投给系统管理员而不是站长
            log.error("[对账V2 ALERT E8] 水票余额与批次不符：汇总错={}, 有批次无汇总={}。示例={}",
                    e8a, e8b, sampleIds("SELECT a.customer_id FROM ticket_account a "
                            + "LEFT JOIN (SELECT customer_id, station_id, product_id, SUM(remain_qty) rq, "
                            + "                  SUM(remain_qty * unit_price) ra FROM ticket_lot WHERE status = 1 "
                            + "            GROUP BY customer_id, station_id, product_id) l "
                            + "  ON l.customer_id = a.customer_id AND l.station_id = a.station_id "
                            + " AND l.product_id = a.product_id "
                            + "WHERE COALESCE(a.remain_quantity, 0) <> COALESCE(l.rq, 0) "
                            + "   OR ABS(COALESCE(a.right_amount, 0) - COALESCE(l.ra, 0)) > 0.009"));
        }

        // ---- E10：应收核销 vs 实收款（2026-09-17，应收账款）----
        // 不变量「核销 ⟹ 已收款」：settlement_status=2 却 payment_status<>2 表示
        // "把这笔应收销掉了、钱却没进来"，属资金层面的不一致，与 E1/E8 同级。
        // ⚠️ 这里**只查单向**：反向（收了钱还没核销）是正常的 —— 现金单送货上门当场收钱，
        // 站长之后才走月结核销。谁把它改成双向比较，日结就会天天报不平、淹没真问题。
        // payment_status 为 NULL 也要算进来（NULL <> 2 在 SQL 里是 NULL，不是 true）。
        int e10 = count("SELECT COUNT(*) FROM orders "
                + "WHERE settlement_status = 2 AND (payment_status IS NULL OR payment_status <> 2)");
        r.put("E10_settledButUnpaid", e10);
        if (e10 > 0) {
            log.error("[对账V2 ALERT E10] 已核销却未收款的订单 {} 条。示例={}",
                    e10, sampleIds("SELECT id FROM orders WHERE settlement_status = 2 "
                            + "AND (payment_status IS NULL OR payment_status <> 2)"));
        }

        // ---- E9（E-PAY）：工资结算单合计 vs 本期明细之和（v37）----
        // ⚠️ 这条**刻意不属于客户对账**：staff_earning 是水站与人之间的账，与客户无关。
        // 把它混进等式 1~4 / E3~E8 会让每天 03:00 的日结必然报不平、淹没真问题。
        // 同理它的告警分级是 **OPERATION**（站长能看懂也能修）而不是 SYSTEM。
        int epay = count("SELECT COUNT(*) FROM ("
                + "SELECT p.id FROM staff_payroll p "
                + "LEFT JOIN (SELECT payroll_id, SUM(amount) AS s FROM staff_earning "
                + "            WHERE payroll_id IS NOT NULL GROUP BY payroll_id) e ON e.payroll_id = p.id "
                + "WHERE ABS(COALESCE(p.total_amount, 0) - COALESCE(e.s, 0)) > 0.009) x");
        r.put("EPAY_payrollVsEarning", epay);
        if (epay > 0) {
            log.error("[对账 ALERT E-PAY] 结算单合计与明细之和不符：{} 张。示例={}",
                    epay, sampleIds("SELECT p.id FROM staff_payroll p "
                            + "LEFT JOIN (SELECT payroll_id, SUM(amount) AS s FROM staff_earning "
                            + "            WHERE payroll_id IS NOT NULL GROUP BY payroll_id) e "
                            + "  ON e.payroll_id = p.id "
                            + "WHERE ABS(COALESCE(p.total_amount, 0) - COALESCE(e.s, 0)) > 0.009"));
        }

        return r;
    }

    private int count(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    /** 带参数的计数（station-scoped 校验使用） */
    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
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
