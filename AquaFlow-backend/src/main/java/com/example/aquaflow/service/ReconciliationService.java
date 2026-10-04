package com.example.aquaflow.service;

import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.util.BusinessTime;
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
    @org.springframework.beans.factory.annotation.Autowired private BarrelBusinessPolicy barrelPolicy;
    private String newReturnExclusion(String alias) { return barrelPolicy!=null && barrelPolicy.hasSchema()?" AND NOT EXISTS(select 1 from barrel_return_detail d where d.record_id="+alias+".id) ":" "; }
    private String newReceiptExclusions(String alias) { return barrelPolicy!=null && barrelPolicy.hasSchema()?" AND NOT EXISTS(select 1 from barrel_right_purchase bp where bp.payment_id="+alias+".id) AND NOT EXISTS(select 1 from barrel_return_detail bd where bd.fee_payment_id="+alias+".id) AND NOT EXISTS(select 1 from barrel_purchase_refund br where br.payment_id="+alias+".id) AND NOT EXISTS(select 1 from barrel_return_fee_refund bf where bf.refund_payment_id="+alias+".id) ":" "; }

    // 消费已退完而本次新押金仍被其他单占用，是合法部分退款；只豁免有组成凭据的原款。
    private String combinedConsumptionExclusion() {
        return barrelPolicy!=null && barrelPolicy.hasCombinedSchema()
                ? " AND NOT (o.payment_status=3 AND EXISTS(select 1 from order_barrel_purchase b join payment_record p on p.id=b.payment_id where b.order_id=o.id and p.status=2 and p.barrel_deposit>0 and (select coalesce(sum(c.water_amount+c.delivery_fee+c.floor_fee),0) from consumption_refund c where c.original_payment_id=p.id)=p.amount-p.barrel_deposit)) " : " ";
    }
    private final JdbcTemplate jdbcTemplate;

    /** 分级告警：对账不平属**系统故障** → 投给系统管理员（见 constant/AlertType） */
    private final AlertService alertService;

    /**
     * [F-16] 注入时钟：本类的"今天"决定对账结果落在哪一天（{@code reconciliation_result.run_date}）。
     * 原先是 {@code LocalDate.now()} 直连 —— 跨零点重跑时"同一天的结果"会分裂成两天，
     * 且测试没法把对账日期钉在指定某天。
     */
    private final BusinessTime businessTime;

    /**
     * V2 检查项里属于「站长台账」而不是「客户账」的键 —— 它们不平是**运营故障**
     * （站长看得懂、也能自己改结算单），因此**不能**混进面向系统管理员的 V2 汇总告警。
     * 见 {@link #alertStationLedgerImbalance(Map)}。
     */
    private static final java.util.Set<String> STATION_LEDGER_KEYS =
            java.util.Set.of("EPAY_payrollVsEarning");

    /** 一次日结最多投递几条站长台账告警；差异总数另写在正文与日志里，防一次刷爆站长的消息。 */
    private static final int ALERT_MAX_PER_RUN = 20;

    /** E-PAY 不平的结算单（合计 ≠ 本期明细之和），只取投递所需的两列。 */
    private static final String PAYROLL_IMBALANCE_SQL =
            "SELECT p.id AS id, p.station_id AS station_id FROM staff_payroll p "
                    + "LEFT JOIN (SELECT payroll_id, SUM(amount) AS s FROM staff_earning "
                    + "           WHERE payroll_id IS NOT NULL GROUP BY payroll_id) e ON e.payroll_id = p.id "
                    + "WHERE ABS(COALESCE(p.total_amount, 0) - COALESCE(e.s, 0)) > 0.009 "
                    + "ORDER BY p.id DESC LIMIT " + ALERT_MAX_PER_RUN;

    /**
     * 「该客户在该站有<b>已过应付日期、仍未结清</b>的账单」—— 押金穿底的**前提判据**（2026-09-21 新增）。
     *
     * <p>与 {@code PaymentServiceImpl.offlinePaymentBlockReason} 的「欠款即停」**完全同源**
     * （{@code payment_status = 1 AND status <> 5 AND due_date < CURDATE()}）—— 复用同一口径，不发明新规则。</p>
     *
     * <p>⚠️ 关联列用 {@code o.station_id}（订单**归属站**），与 {@code customer_barrel_asset.station_id}
     * （客户资产也认归属站，见 AGENTS.md §1.1）同口径。跨站外派单的欠款归结算站，
     * 本站的桶权益不会因为别站的欠款而报 —— **宁可少报，也不要把别站的欠款串到本站界面上**。</p>
     */
    private static final String HAS_OVERDUE_UNSETTLED_SQL =
            "EXISTS (SELECT 1 FROM orders o "
                    + " WHERE o.customer_id = a.customer_id AND o.station_id = a.station_id "
                    + "   AND o.payment_status = 1 AND o.status <> 5 "
                    + "   AND o.due_date IS NOT NULL AND o.due_date < CURDATE())";

    /**
     * 「押金穿底」的**唯一** SQL —— V1 的 {@code b3d}、V2 的 {@code E6}、站长端的 {@code SE6} 三处共用本方法。
     *
     * <p><b>为什么抽成一个方法</b>：它们原本是三段逐字重复的 SQL，靠注释写着"必须一起改"。
     * 改一处漏一处的后果是<b>「日结不报了，但站长界面天天报红」</b>—— 两边都以为自己是对的。
     * 抽成一处之后，分叉在物理上不可能发生。</p>
     *
     * <h3>[2026-09-21 修订] 为什么要加"已逾期未结账单"这个前提</h3>
     * <p>旧判据只看「权益可退金额 &gt; 押金余额」，于是**把正常的挂账时间差算成了穿底**：
     * 桶权益在<b>送达</b>那一刻就建好了（{@code BarrelLedgerService.applyDelivery} 写入押金快照），
     * 而押金要等<b>收到钱</b>才入账（{@code PaymentServiceImpl.applyDepositOnPaid}）。
     * 两者时点不同，于是"货已送到、钱还没收"这个窗口里必然 {@code 权益 > 0 且 押金 = 0}。</p>
     * <p>这曾是每天 03:00 的 SYSTEM 告警噪声源（与等式2 的 p2a/p2c、等式3 的 b3a 同形，
     * 见 AGENTS.md §1「对账等式不能把合法业务状态算成差异」）。新前提把
     * <b>"时间差"与"真穿底"</b>分开：钱在账期内是时间差，过了应付日期才是真风险。</p>
     *
     * <p>⚠️ <b>两个有意为之的取舍</b>（不是缺陷）：
     * <ol>
     *   <li><b>没有账期的客户（散户）永远不会被判为穿底</b> —— 他们 {@code due_date} 为 NULL、
     *       永远不"逾期"。其敞口由站长的「待收款」列表盯，不进对账差异。
     *       这是 2026-09-21 产品裁定（散户开货到付款的不多，不值得为它引入缺省账期）。</li>
     *   <li><b>订单已终结但权益仍在</b>的情形也<b>不</b>由本式兜底 —— 那属于"取消链没撤权益"，
     *       已由 {@code OrderStatus.isCancellable} 排除已送达(3) 从入口堵死，
     *       见 {@code PaymentServiceImpl.refundOrder} 的护栏注释。本式只管"欠着钱还拿着桶"。</li>
     * </ol>
     *
     * @param stationScoped true = 只看某一个站（站长端 SE6 用，需按顺序补一个 stationId 参数）
     */
    private static String depositShortfallSql(boolean stationScoped) {
        return "SELECT COUNT(*) FROM ("
                + "SELECT a.customer_id, a.station_id FROM customer_barrel_asset a "
                + "LEFT JOIN customer_deposit_account da ON da.customer_id = a.customer_id AND da.station_id = a.station_id "
                + (stationScoped ? "WHERE a.station_id = ? AND " : "WHERE ")
                + HAS_OVERDUE_UNSETTLED_SQL + " "
                + "GROUP BY a.customer_id, a.station_id "
                + "HAVING SUM(COALESCE(a.right_amount, 0)) - COALESCE(MAX(da.balance), 0) > 0.009) x";
    }

    public ReconciliationService(JdbcTemplate jdbcTemplate, AlertService alertService, BusinessTime businessTime) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertService = alertService;
        this.businessTime = businessTime;
    }

    /**
     * 每日 03:00 执行日结对账。
     *
     * <p>⚠️ [2026-09-27] 定时任务**自己跑挂了必须落一条 SYSTEM 告警**，只打日志等于没人知道。
     * 此前 {@code dailyReconcile()} 没有任何 try/catch：方法里有十几处 DB 调用，
     * 任何一处抛错（库重启、连接池耗尽、SQL 回归）都会让异常直接飞出定时任务 ——
     * 结果只有一行 Spring 的 error 日志、**{@code alert_log} 里一条都没有**，
     * 而运维唯一的入口就是查那张表（系统告警没有 HTTP 入口）。
     * 于是"日结没跑"这件事在系统里完全不可见，与"日结跑了且全平"长得一模一样。</p>
     *
     * <p>为什么不违反「不在 {@code @Transactional} 方法内 catch」那条判据：本方法**不是事务方法**，
     * 而是任务的入口；catch 之后异常不再上抛，正是这里想要的（任务不能把异常丢给调度器了事）。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyReconcile() {
        try {
            runDailyReconcile();
        } catch (Exception e) {
            log.error("[日结对账] 执行失败（已发系统告警）", e);
            alertService.systemFault("DailyReconcile", "日结对账任务执行失败",
                    "日结对账未完成，本次结果不可用：" + e.getClass().getSimpleName()
                            + " - " + e.getMessage(), null, null);
        }
    }

    /** 对账主体。与 {@link #dailyReconcile()} 拆开，纯粹为了给"任务自己挂了"留一个 catch。 */
    private void runDailyReconcile() {
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

        // ⚠️ V2 一张表里混了两种账，**投递方向相反**（见 constant/AlertType 的产品口径）：
        //   · 客户账（E3~E8 / E10）：不平 = 账目或流水层面出错 → 系统故障，投系统管理员；
        //   · 站长台账（E-PAY = 工资结算单 vs 本期明细）：不平 = 站长自己能修的运营故障。
        // [2026-09-18 修] 此前整张 V2 一把交给 systemFault：E-PAY 真出问题时平台管理员收到告警，
        // 而**唯一能改结算单的站长一条都收不到** —— 与本仓 §1.1 的分级口径正好相反。
        Map<String, Integer> v2Customer = new LinkedHashMap<>();
        Map<String, Integer> v2StationLedger = new LinkedHashMap<>();
        v2.forEach((k, v) -> (STATION_LEDGER_KEYS.contains(k) ? v2StationLedger : v2Customer).put(k, v));

        boolean v2Ok = v2Customer.values().stream().allMatch(v -> v == 0);
        if (v2Ok) {
            log.info("[日结对账 V2] 通过：权益批次 / 占用恒等 / 物理桶守恒 / 穿底 全部平衡");
        } else {
            log.warn("[日结对账 V2] 发现不平项：{}", v2Customer);
            alertService.systemFault("DailyReconcile", "日结对账 V2 发现不平项",
                    "V2（权益批次/占用恒等/物理守恒/穿底）不平项：" + v2Customer, null, null);
        }
        alertStationLedgerImbalance(v2StationLedger);

        // 结果落表：此前只写日志，无人可查、无留痕（问责与趋势分析都做不到）
        persistResults(result, v2);
    }

    /**
     * 把对账结果落到 reconciliation_result（每日每检查项一行，同日重跑覆盖）。
     *
     * <p>级别：E5/E7 为提示型（WARN），其余为 ERROR。</p>
     *
     * <p>⚠️ 这里的 {@code level} 是**严重度**（要不要马上看），与"这条告警投给谁"
     * （SYSTEM 系统管理员 / OPERATION 站长）是<b>两件独立的事</b> ——
     * 后者由 {@code constant/AlertType} + 投递时的调用点决定，见 {@link #dailyReconcile()}。
     * 例如 E-PAY 落表是 ERROR，但投递对象是站长。别把两者当成一个字段。</p>
     */
    public void persistResults(Map<String, Integer> v1, Map<String, Integer> v2) {
        // [F-16] 原先是 java.time.LocalDate.now() 直连：跨零点重跑会让"今天"的结果落到明天那一行，
        // 且测试无法把对账日期固定在指定某天（同一天重跑覆盖的前提也就没法验证）。
        java.time.LocalDate today = businessTime.today();
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
     * 站长台账不平 → **按水站**投 OPERATION 告警（2026-09-18）。
     *
     * <p>⚠️ {@code AlertService.stationFault} 的 {@code stationId} 是必填的（见 {@code constant/AlertType}），
     * 所以这里必须**一张不平的结算单投一条**，不能像 V1/V2 那样投一条全平台告警：
     * 站长只该看到本站的账，平台管理员也不该被拉进站长与配送员之间的账。</p>
     *
     * <p>⚠️ 条数按 {@link #ALERT_MAX_PER_RUN} 封顶：单价录错会让整批结算单同时不平，
     * 不封顶等于把站长的订阅消息刷爆。真实的差异总数写在告警正文与日志里。</p>
     */
    private void alertStationLedgerImbalance(Map<String, Integer> stationLedger) {
        if (stationLedger == null || stationLedger.isEmpty()) {
            return;
        }
        int total = stationLedger.getOrDefault("EPAY_payrollVsEarning", 0);
        if (total <= 0) {
            return;
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(PAYROLL_IMBALANCE_SQL)) {
            Object sid = row.get("station_id");
            if (sid == null) {
                // station_id 是 NOT NULL，真为空说明数据被手工改过 —— 投不出去也不能静默丢掉
                log.error("[对账 ALERT E-PAY] 结算单不平但无归属水站，无法投递站长：payrollId={}", row.get("id"));
                continue;
            }
            long payrollId = ((Number) row.get("id")).longValue();
            alertService.stationFault(((Number) sid).longValue(), "ERROR", "DailyReconcile",
                    "工资结算单与明细之和不符",
                    "结算单 #" + payrollId + " 的合计金额 ≠ 本期收益明细之和（本次共 " + total + " 张不平）。"
                            + "给配送员发钱之前先核对该单明细。",
                    "STAFF_PAYROLL", payrollId);
        }
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
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record b WHERE type = 2 AND status = 3 " + newReturnExclusion("b") + " AND station_id = ? "
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

        // SE6：押金穿底（权益可退金额 > 押金余额，**且该客户在本站有已逾期的未结账单**）
        // 判据与 V1 的 b3d、V2 的 E6 共用同一个方法 —— 三处不可能分叉，见 depositShortfallSql 的注释。
        r.put("SE6_depositShortfall", count(depositShortfallSql(true), stationId));

        // SE7~SE10：本站的库存预留凭据（2026-09-25 库存预留模型）—— E11~E14 的按站版。
        // 站长能看到本站的这四项，才有办法自己处置（先入库 / 先补凭据），不必等平台运维。
        // 判据与全平台版**同源**（同样的四种形状），只是多一个站过滤条件：
        //   SE7 = E11（本站 Σ活跃预留 > 在库实物）、SE8 = E12（本站履约的在途明细漏凭据）、
        //   SE9 = E13（凭据站别 ≠ 该单当前履约站，进/出本站两个方向都算）、SE10 = E14（预留量越界）
        r.put("SE7_reservedExceedsStock", count("SELECT COUNT(*) FROM ("
                + "SELECT r.station_id, r.product_id FROM inventory_reservation r "
                + "JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id "
                + "WHERE r.status = 1 AND r.station_id = ? GROUP BY r.station_id, r.product_id "
                + "HAVING SUM(r.reserved_qty) > MAX(i.quantity)) x", stationId));

        // 只看"本站正在履约"的单：判据同全平台版（coalesce 履约站，不是归属站）
        r.put("SE8_inflightItemsWithoutCredential", count(
                "SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.status IN (1, 2) AND COALESCE(o.delivery_station_id, o.station_id) = ? "
                + "AND NOT EXISTS (SELECT 1 FROM inventory_reservation r "
                + "  WHERE r.order_item_id = oi.id AND r.status = 1)", stationId));

        // 两个方向都算：货挂在别站而本站该履约（要拉回凭据）、货挂在本站而单已换到别站（要让位）
        r.put("SE9_credentialWrongStation", count(
                "SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id "
                + "WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id) "
                + "AND (r.station_id = ? OR COALESCE(o.delivery_station_id, o.station_id) = ?)",
                stationId, stationId));

        r.put("SE10_reservedOutOfRange", count(
                "SELECT COUNT(*) FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id "
                + "WHERE r.status = 1 AND r.station_id = ? "
                + "AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty)", stationId));

        // SE11/SE12：E15/E16 的按站版（二次收口 B2/B3）
        r.put("SE11_needSnapshotMismatch", count(
                "SELECT COUNT(*) FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id "
                + "WHERE r.status = 1 AND r.station_id = ? "
                + "AND (r.need_qty <> oi.quantity OR r.reserved_qty > oi.quantity)", stationId));

        // SE12：E16 的按站版（同样带 `reserved_qty > 0`：外派到"没配这个商品"的站是合法动作，
        // 那边的凭据 reserved=0 属缺货待补，不能算成差异）
        r.put("SE12_credentialWithoutInventoryRow", count(
                "SELECT COUNT(*) FROM inventory_reservation r WHERE r.status = 1 AND r.station_id = ? "
                + "AND r.reserved_qty > 0 "
                + "AND NOT EXISTS (SELECT 1 FROM inventory i "
                + "  WHERE i.station_id = r.station_id AND i.product_id = r.product_id)", stationId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stationId", stationId);
        out.put("checks", r);
        out.put("totalDiff", r.values().stream().mapToInt(Integer::intValue).sum());
        // [F-16] 原先是 java.time.LocalDateTime.now() 直连：跨零点前后两次调用会拿到相差一天的时间戳，
        // 站长端按它判断"这份读数是什么时候的"就会指向错误的那一天。
        out.put("checkedAt", businessTime.now().toString());
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
                + "AND EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 2)" + combinedConsumptionExclusion());
        // p2c 孤儿流水：**在线购票（无订单支付）本来就是 order_id IS NULL**，它不是孤儿。
        // ⚠️ 2026-09-17 修正：原来只判 order_id IS NULL，于是**任何买过水票的水站日结都会不平**
        // （v33 起在线购票必然产生无订单流水）→ 天天误报 SYSTEM 告警，真问题被淹没。
        // 真正的孤儿 = 既没有订单、也不是购票（ticket_qty 由 TicketAccountServiceImpl 落库）。
        int p2c = count("SELECT COUNT(*) FROM payment_record p LEFT JOIN orders o ON o.id = p.order_id "
                + "WHERE o.id IS NULL AND p.ticket_qty IS NULL "
                + "" + newReceiptExclusions("p") + "");
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
        // b3a：标了「已送达」却没有对应权益批次的配送中记录 —— 那才是真的丢权益。
        // ⚠️ 2026-09-17 修正：原来直接数 status='DELIVERED' 的行数，但 DELIVERED 是**合法终态**
        // （BarrelLedgerService 送达到账时把记录标为 DELIVERED 且**不物理删除**，供追溯；
        //  见 CustomerBarrelInTransit 的类注释）。于是**每一笔完成过的配送都会让等式3 不平**，
        // 日结天天报 SYSTEM 告警 —— 这与 E-PAY 那条注释里说的"淹没真问题"是同一个后果。
        int b3a = count("SELECT COUNT(*) FROM customer_barrel_in_transit t "
                + "WHERE t.status = 'DELIVERED' AND NOT EXISTS ("
                + "  SELECT 1 FROM customer_barrel_lot l WHERE l.customer_id = t.customer_id "
                + "    AND l.station_id = t.station_id AND l.product_id = t.product_id)");
        int b3b = count("SELECT COUNT(*) FROM customer_barrel_asset WHERE quantity < 0");
        int b3c = count("SELECT COUNT(*) FROM customer_barrel_over o "
                + "WHERE o.over_qty < -(SELECT COALESCE(SUM(l.remain_qty),0) FROM customer_barrel_lot l "
                + "                     WHERE l.customer_id = o.customer_id AND l.station_id = o.station_id "
                + "                       AND l.product_id = o.product_id AND l.status = 1)");
        // 押金口径改用 right_amount（= Σ lot.remain_qty × lot.unit_price，按买入时单价）。
        // 旧口径用【当前 product.deposit】折算，只要调过价就必然不平，属于假告警；
        // 新口径下不平说明"权益可退金额 > 押金账户余额"，是真实的退款穿底风险。
        //
        // [2026-09-21 已修] 本条原先把「现金单送达未收款」的时间差也算成差异（每天 03:00 报 SYSTEM 告警）。
        //   成因与取舍写在 depositShortfallSql 的注释里；判据现在多了"该客户在本站有已逾期的未结账单"这个前提。
        //   用户裁定（2026-09-21）：「货已送到、钱还没收是可以允许的」—— 在账期内就是时间差，过了账期才是真穿底。
        int b3d = count(depositShortfallSql(false));
        int eq3 = b3a + b3b + b3c + b3d;
        result.put("barrelState", eq3);
        if (eq3 > 0) {
            log.error("[日结对账 ALERT 等式3] 桶三态异常：已送达但无权益批次={}, 在手负数={}, over越界(over<-权益)={}, 权益金额超押金余额(穿底风险)={}",
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
                + "  UNION ALL SELECT customer_id, station_id, product_id, -quantity, 0 FROM barrel_record b WHERE type = 2 AND status = 3 " + newReturnExclusion("b") + " "
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

        // ---- E6：穿底（权益可退金额 > 押金账户余额，**且该客户在本站有已逾期的未结账单**）----
        // 与 V1 的 b3d、站长端的 SE6 共用同一个方法（depositShortfallSql），改口径只需改那一处。
        int e6 = count(depositShortfallSql(false));
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
        // ⚠️ [F-23 2026-09-30] 必须**排除「已退款(3)」**：先核销、后单笔退款是一条**合法**链路 ——
        // refundPayment 只把 orders.payment_status 置成 REFUNDED(3)、**不回退 settlement_status**
        // （那要动 PaymentServiceImpl，不在本次写权内），于是 E10 每 03:00 都为一张完全正常的单报一条
        // SYSTEM 告警，正是"淹没真故障"的形态。「已退款」不是"核销了却没收到钱"：钱到过账，之后按原
        // 路径退回去了。排除条件写成 NOT IN (2, 3)（而不是再叠一个 <> 3）是为了不把 IS NULL 那支弄丢 ——
        // NULL NOT IN (...) 求值为 NULL 而非 true。
        int e10 = count("SELECT COUNT(*) FROM orders "
                + "WHERE settlement_status = 2 AND (payment_status IS NULL OR payment_status NOT IN (2, 3))");
        r.put("E10_settledButUnpaid", e10);
        if (e10 > 0) {
            log.error("[对账V2 ALERT E10] 已核销却未收款（已排除已退款）的订单 {} 条。示例={}",
                    e10, sampleIds("SELECT id FROM orders WHERE settlement_status = 2 "
                            + "AND (payment_status IS NULL OR payment_status NOT IN (2, 3))"));
        }

        // ---- E11~E14：库存预留凭据（2026-09-25 库存预留模型，迁移 v63）----
        // 为什么"等式4（quantity == Σ inventory_record.delta）"还不够：预留**不动 quantity、不写流水**，
        // 所以实物账平完全可能与"已经卖出去的货"矛盾。这四条覆盖的正是等式4 看不见的那一面：
        //   E11 预留超实物：本站该商品 Σ活跃预留 > 在库实物（= 把不存在的货卖了，返工 R1 的形状）
        //   E12 在途单漏凭据：状态 1/2 的订单明细没有活跃凭据 ⇒ 完成配送时会被 shipForOrder 拒绝，
        //       站长只会看到"凭据不完整"，得先知道是哪几单（迁移漏行 / 手工改库的形状）
        //   E13 凭据挂错站：凭据站别 ≠ 该单当前履约站（换站没搬凭据的形状，跨站外派后取消补错站的那类）
        //   E14 预留量越界：reserved_qty 为负或超过订单需求量（补位的加数算错 / 明细被改过的形状）
        // 只读校验，与 V1/V2 其余条目同级：不平 = 系统故障，投系统管理员（站长既看不懂也修不了）。
        int e11 = count("SELECT COUNT(*) FROM ("
                + "SELECT r.station_id, r.product_id FROM inventory_reservation r "
                + "JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id "
                + "WHERE r.status = 1 GROUP BY r.station_id, r.product_id "
                + "HAVING SUM(r.reserved_qty) > MAX(i.quantity)) x");
        r.put("E11_reservedExceedsStock", e11);
        if (e11 > 0) {
            log.error("[对账V2 ALERT E11] 库存预留超过在库实物：{} 个(站,商品)。示例={}",
                    e11, sampleIds("SELECT CONCAT(r.station_id, '/', r.product_id) FROM inventory_reservation r "
                            + "JOIN inventory i ON i.station_id = r.station_id AND i.product_id = r.product_id "
                            + "WHERE r.status = 1 GROUP BY r.station_id, r.product_id "
                            + "HAVING SUM(r.reserved_qty) > MAX(i.quantity) LIMIT 20"));
        }

        int e12 = count("SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.status IN (1, 2) AND NOT EXISTS (SELECT 1 FROM inventory_reservation r "
                + "  WHERE r.order_item_id = oi.id AND r.status = 1)");
        r.put("E12_inflightItemsWithoutCredential", e12);
        if (e12 > 0) {
            log.error("[对账V2 ALERT E12] 在途订单明细缺少库存预留凭据：{} 条。示例={}",
                    e12, sampleIds("SELECT oi.id FROM order_item oi JOIN orders o ON o.id = oi.order_id "
                            + "WHERE o.status IN (1, 2) AND NOT EXISTS (SELECT 1 FROM inventory_reservation r "
                            + "  WHERE r.order_item_id = oi.id AND r.status = 1) LIMIT 20"));
        }

        // ⚠️ 站别取 orders 当前履约站 `coalesce(delivery_station_id, station_id)`，与
        // StationUtil.deliveryStation / InventoryReservationMapper 的判据同源；写死 o.station_id
        // 会把"外派到别站履约"的正常单全算成错站。
        int e13 = count("SELECT COUNT(*) FROM inventory_reservation r JOIN orders o ON o.id = r.order_id "
                + "WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id)");
        r.put("E13_credentialWrongStation", e13);
        if (e13 > 0) {
            log.error("[对账V2 ALERT E13] 库存预留凭据挂在非履约站：{} 条（换站时没搬凭据 / 被手工改过）。示例={}",
                    e13, sampleIds("SELECT r.order_id FROM inventory_reservation r JOIN orders o ON o.id = r.order_id "
                            + "WHERE r.status = 1 AND r.station_id <> COALESCE(o.delivery_station_id, o.station_id) LIMIT 20"));
        }

        int e14 = count("SELECT COUNT(*) FROM inventory_reservation r "
                + "WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty)");
        r.put("E14_reservedOutOfRange", e14);
        if (e14 > 0) {
            log.error("[对账V2 ALERT E14] 库存预留量越界（负数或超过需求量快照）：{} 条。示例={}",
                    e14, sampleIds("SELECT r.id FROM inventory_reservation r "
                            + "WHERE r.status = 1 AND (r.reserved_qty < 0 OR r.reserved_qty > r.need_qty) LIMIT 20"));
        }

        // ---- E15：需求量快照与真相源不一致（v65，二次收口 B2）----
        // need_qty 是"下单那一刻 order_item.quantity"的副本，补位**只读它** —— 副本一旦与真相源分叉，
        // 分配就会按错的量走。这条把它钉住：既查"快照 ≠ 明细量"，也查"明细量被改小后预留反而超过它"。
        int e15 = count("SELECT COUNT(*) FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id "
                + "WHERE r.status = 1 AND (r.need_qty <> oi.quantity OR r.reserved_qty > oi.quantity)");
        r.put("E15_needSnapshotMismatch", e15);
        if (e15 > 0) {
            log.error("[对账V2 ALERT E15] 预留凭据的需求量快照与订单明细不一致：{} 条。示例={}",
                    e15, sampleIds("SELECT r.id FROM inventory_reservation r JOIN order_item oi ON oi.id = r.order_item_id "
                            + "WHERE r.status = 1 AND (r.need_qty <> oi.quantity OR r.reserved_qty > oi.quantity) LIMIT 20"));
        }

        // ---- E16：活跃凭据所在的 (站,商品) 没有库存行（v65，二次收口 B3）----
        // ⚠️ E11 是 `join inventory` 按 (站,商品) 分组比较 —— 一份挂在"根本没有库存行"的站上的凭据
        // 会被内连接直接过滤掉，**永远不被 E11 看见**，于是它可以长期保留一个没有实物支撑的承诺。
        // 这类凭据正是"可分配量算错"的产物（迁移时原扣减站库存行缺失、或绕过服务写库）。
        int e16 = count("SELECT COUNT(*) FROM inventory_reservation r WHERE r.status = 1 "
                + "AND r.reserved_qty > 0 "
                + "AND NOT EXISTS (SELECT 1 FROM inventory i "
                + "  WHERE i.station_id = r.station_id AND i.product_id = r.product_id)");
        r.put("E16_credentialWithoutInventoryRow", e16);
        if (e16 > 0) {
            log.error("[对账V2 ALERT E16] 活跃预留凭据挂在没有库存行的 (站,商品) 上、且承诺了数量：{} 条。示例={}",
                    e16, sampleIds("SELECT r.id FROM inventory_reservation r WHERE r.status = 1 "
                            + "AND r.reserved_qty > 0 "
                            + "AND NOT EXISTS (SELECT 1 FROM inventory i "
                            + "  WHERE i.station_id = r.station_id AND i.product_id = r.product_id) LIMIT 20"));
        }

        // ---- E9（E-PAY）：工资结算单合计 vs 本期明细之和（v37）----
        // ⚠️ 这条**刻意不属于客户对账**：staff_earning 是水站与人之间的账，与客户无关。
        // 把它混进等式 1~4 / E3~E8 会让每天 03:00 的日结必然报不平、淹没真问题。
        // ⚠️ 它同样不属于「V2 汇总告警」：那个汇总投的是系统管理员（SYSTEM），而工资账
        // 站长能看懂也能自己修，属 OPERATION。键名登记在 STATION_LEDGER_KEYS，
        // 由 dailyReconcile → alertStationLedgerImbalance 按站投递 —— 改动键名要同步那两处。
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

        if(barrelPolicy!=null && barrelPolicy.hasSchema()) {
            r.put("E17_rightReservation",count("select count(*) from (select r.customer_id,r.station_id,r.product_id from barrel_right_reservation r left join customer_barrel_asset a on a.customer_id=r.customer_id and a.station_id=r.station_id and a.product_id=r.product_id where r.status='ACTIVE' group by r.customer_id,r.station_id,r.product_id having sum(r.quantity-r.pending_qty)>coalesce(max(a.quantity),0)) x"));
            r.put("E18_rightOwner",count("select count(*) from barrel_right_reservation r left join orders o on r.owner_type='ORDER' and o.id=r.owner_id left join barrel_return_detail d on r.owner_type='RETURN' and d.record_id=r.owner_id where r.status='ACTIVE' and (r.pending_qty<0 or r.pending_qty>r.quantity or r.pending_pickup_qty<0 or r.pending_pickup_qty>r.pickup_qty or r.quantity<=0 or r.pickup_qty<0 or r.pickup_qty>r.quantity or r.owner_type='ORDER' and (o.id is null or o.status not in (1,2)) or r.owner_type='RETURN' and (d.record_id is null or d.status not in ('APPLIED','APPROVED','RECEIVED')))"));
            r.put("E19_purchaseReceipt",count("select count(*) from barrel_right_purchase b left join payment_record p on p.id=b.payment_id left join customer_barrel_lot l on l.id=b.lot_id where b.status='PAID' and (p.id is null or p.status<>2 or p.amount<>b.amount or p.station_id<>b.station_id or l.id is null or l.qty<>b.quantity or l.station_id<>b.station_id)"));
            r.put("E20_returnHold",count("select count(*) from (select h.lot_id from barrel_return_lot_hold h join barrel_return_detail d on d.record_id=h.record_id left join customer_barrel_lot l on l.id=h.lot_id where d.status in ('APPLIED','APPROVED','RECEIVED') group by h.lot_id having sum(h.quantity)>coalesce(max(l.remain_qty),0)) x"));
        }
        if(barrelPolicy!=null && barrelPolicy.hasCombinedSchema()) {
            r.put("E21_orderBarrelReceipt",count("select count(*) from order_barrel_purchase b left join payment_record p on p.id=b.payment_id left join customer_barrel_lot l on l.id=b.lot_id where b.refunded_qty<0 or b.refunded_qty>b.quantity or b.refunded_amount<0 or b.refunded_amount>b.amount or b.amount<>b.unit_price*b.quantity or b.refunded_qty<>coalesce((select sum(f.quantity) from order_barrel_refund f where f.purchase_id=b.id),0) or b.refunded_amount<>coalesce((select sum(f.amount) from order_barrel_refund f where f.purchase_id=b.id),0) or b.status in ('PAID','REFUNDED') and (p.id is null or p.status not in (2,3) or p.order_id<>b.order_id or p.customer_id<>b.customer_id or p.barrel_deposit<>(select sum(x.amount) from order_barrel_purchase x where x.order_id=b.order_id) or l.id is null or l.qty<>b.quantity or l.station_id<>b.station_id or l.product_id<>b.product_id)"));
            r.put("E22_orderBarrelRefund",count("select count(*) from payment_record p where exists(select 1 from order_barrel_purchase b where b.payment_id=p.id) and (coalesce((select sum(c.water_amount+c.delivery_fee+c.floor_fee) from consumption_refund c where c.original_payment_id=p.id),0)+coalesce((select sum(r.amount) from order_barrel_refund r where r.original_payment_id=p.id),0)>p.amount)"));
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
