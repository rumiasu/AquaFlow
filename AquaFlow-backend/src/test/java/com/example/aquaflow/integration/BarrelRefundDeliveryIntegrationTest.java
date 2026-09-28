package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退押金「实际交付」（v66，产品拍板 2026-09-27 · 正本 {@code docs/design/35} §7）。
 *
 * <p>三条口径各自有可失败的判据，本类逐条把它们钉住：</p>
 * <ol>
 *   <li><b>§7.2 核销与交付同一次点击</b>：第 3 步走通 ⇒ {@code status=3} <b>且</b>
 *       {@code refund_paid_time} / {@code refund_paid_by} 有值。
 *       「{@code status=3} 而交付时间为空」= 违规数据，站长端计数能筛出来；
 *       <b>反向</b>：把 {@code finishRefund} 改回只写 status ⇒ 本类立刻红。</li>
 *   <li><b>§7.3 通道白名单 + 不许假装已退</b>：{@code ONLINE} 在微信退款通道未接入时明确拒绝，
 *       且 <b>status 不变、deposit_record 一条不写</b>；非法取值（99 / "abc"）同样拒。</li>
 *   <li><b>§7.4 第二道风险闸</b>：申请时没风险、审批前欠上款的客户，
 *       退押金必须被 {@code doRefund} 拦下（此前只拦申请那一道）。
 *       <b>反向</b>：摘掉 {@code doRefund} 里那段 ⇒ 本类红。</li>
 * </ol>
 *
 * <p><b>为什么每条都要能说出"改回去会不会红"</b>：这三条都是"加了一道闸"的改动，
 * 闸门本身不会让既有用例变红 —— 只有专门断言"拒绝发生"的用例才会。
 * 断言里同时查<b>钱与状态都没动</b>，而不是只看 code（AGENTS §8.1：业务错误也是 HTTP 200）。</p>
 */
@DisplayName("退押金实际交付 v66：核销即交付 + 通道白名单 + 审批时风险闸")
class BarrelRefundDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final int CASH = 2;

    private long station;
    private long product;
    private long customer;
    private long mgr;
    private long deliveryStaff;

    private void seed() {
        station = createStation("S1");
        product = createProduct("桶装水18.9L", 1, "20.00", "30.00", 1, "18.00");
        createInventoryFull(station, product, 10, 1, "18.00");
        customer = createCustomer("Alice", "openid-alice");
        mgr = createStaff("M1", "STATION_MANAGER", station, 1);
        deliveryStaff = createStaff("D1", "DELIVERY", station, 1);
    }

    private String mgrToken() {
        return staffToken(mgr, "STATION_MANAGER", station);
    }

    /** n 个可退桶权益（押金 30/个已入账），单价 30 —— 退 n 个应退 30n 元。 */
    private void giveRefundableBarrels(String lotNo, int n, String unitPrice) {
        createBarrelLot(lotNo, customer, station, product, unitPrice, n, n);
        java.math.BigDecimal total = new java.math.BigDecimal(unitPrice).multiply(java.math.BigDecimal.valueOf(n));
        createBarrelAsset(customer, station, product, n, total.toPlainString());
        createDepositBalance(customer, station, total.toPlainString());
    }

    private Api applyReturn(int qty) {
        return post("/api/barrels/return", customerToken(customer),
                "{\"stationId\":" + station + ",\"productId\":" + product + ",\"quantity\":" + qty + "}");
    }

    private Api approve(long recordId, String body) {
        return put("/api/barrels/records/" + recordId + "/status", mgrToken(), body);
    }

    private Api confirmPaid(long recordId, String body) {
        return put("/api/barrels/records/" + recordId + "/refund-paid", mgrToken(),
                body == null ? "{}" : body);
    }

    private Api undelivered() {
        return get("/api/barrels/refund-undelivered", mgrToken());
    }

    private int right() {
        return intOf("SELECT IFNULL(SUM(remain_qty),0) FROM customer_barrel_lot "
                + "WHERE customer_id=? AND station_id=? AND product_id=? AND status=1", customer, station, product);
    }

    private java.math.BigDecimal depositBalance() {
        return decimalOf("SELECT IFNULL(MAX(balance),0) FROM customer_deposit_account "
                + "WHERE customer_id=? AND station_id=?", customer, station);
    }

    private int depositRecords() {
        return intOf("SELECT COUNT(*) FROM deposit_record WHERE customer_id=? AND station_id=?", customer, station);
    }

    private LocalDateTime paidTime(long recordId) {
        return jdbc.queryForObject("SELECT refund_paid_time FROM barrel_record WHERE id=?",
                LocalDateTime.class, recordId);
    }

    /** 造一条「已核销未交付」的历史单（模拟升级 v66 之前退过的押金：那时系统没记过交付）。 */
    private long insertLegacyRefundedRecord(int type, String refund) {
        return insert("INSERT INTO barrel_record(customer_id, station_id, product_id, type, quantity, status, "
                        + "deposit_refund, create_time) VALUES (?,?,?,?,1,3,?,NOW())",
                customer, station, product, type, new java.math.BigDecimal(refund));
    }

    /** 让该客户在本站欠上一笔**逾期**的现金单 ⇒ 风险等级升为 ALERT（>15 天则 FREEZE）。 */
    private long overdueCashOrder(int overdueDays) {
        long address = createAddress(customer, "测试地址-" + overdueDays);
        long order = createOrderFull(customer, address, station, product, 3, 1, CASH,
                "20.00", "0.00", "20.00", false, 0);
        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL ? DAY) WHERE id=?",
                overdueDays, order);
        return order;
    }

    /* =====================================================================
     * 1. §7.2 正常走通：status=3 与交付时间/交付人一起落库
     * ===================================================================== */

    @Test
    @DisplayName("§7.2 第 3 步 = 退押金并当面交付：status=3 且 refund_paid_time / refund_paid_by 同时有值")
    void refundWritesDeliveryInSameClick() {
        seed();
        giveRefundableBarrels("LOT-1", 1, "30.00");

        Api apply = applyReturn(1);
        assertTrue(apply.isSuccess(), "申请应成功，实际=" + apply);
        long recordId = apply.data().path("recordId").asLong();

        // DEF-7 不跳步：没收桶就退钱必须被拒（这条闸门本次不动，一并钉住）
        Api jump = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertFalse(jump.isSuccess(), "桶还没确认收到就退押金必须被拒，实际=" + jump);
        assertEquals(1, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "被拒后应停在待处理(1)");
        assertNull(paidTime(recordId), "被拒后不得留下交付时间");

        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        Api refund = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertTrue(refund.isSuccess(), "现金当面交付应成功，实际=" + refund);

        assertEquals(3, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "应置已退押金(3)");
        assertNotNull(paidTime(recordId),
                "**核销与交付必须同一次完成**：status=3 而 refund_paid_time 为空是违规数据（§7.2）");
        assertEquals(mgr, longOf("SELECT refund_paid_by FROM barrel_record WHERE id=?", recordId),
                "不传 refundPaidBy 时交付人 = 操作人（站长自己交的）");

        // 钱：账户清零 + 负数流水仍在**第 3 步**写（§7.2 末段：不许挪到交付那一步）
        assertEquals(0, depositBalance().compareTo(java.math.BigDecimal.ZERO), "押金应退回为 0");
        assertEquals(1, depositRecords(), "应在第 3 步写一条押金负数流水");
        assertEquals(0, decimalOf("SELECT amount FROM deposit_record WHERE customer_id=?", customer)
                .compareTo(new java.math.BigDecimal("-30.00")), "流水应是 -30.00（扣减类落负数的口径）");
        assertEquals(0, right(), "权益应被核销为 0");

        // 刚走完这一步的单**不该**出现在「已核销未交付」里
        assertEquals(0, undelivered().data().path("count").asInt(),
                "正常走完第 3 步后不该有任何未交付记录，实际=" + undelivered());
    }

    /* =====================================================================
     * 2. §7.3 ONLINE：通道未接入 ⇒ 明确拒绝，且什么都没发生
     * ===================================================================== */

    @Test
    @DisplayName("§7.3 refundChannel=ONLINE：微信退款通道未接入 ⇒ 明确拒绝，不改状态、不写流水")
    void onlineChannelIsRejectedWithoutSideEffects() {
        seed();
        giveRefundableBarrels("LOT-2", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        Api refund = approve(recordId, "{\"status\":3,\"refundChannel\":\"ONLINE\"}");

        assertFalse(refund.isSuccess(), "线上通道未接入时必须明确拒绝（不许假装已退），实际=" + refund);
        assertTrue(refund.message().contains("微信退款通道未接入"),
                "拒绝原因必须点明通道未接入，实际=" + refund.message());

        // 拒绝 = 什么都没发生：状态、权益、押金账户、流水、核销明细、交付时间
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId),
                "被拒后应停在「已确认收到」(2)，等站长改用现金交付");
        assertNull(paidTime(recordId), "被拒后不得写交付时间");
        assertEquals(1, right(), "被拒后权益不得被核销");
        assertEquals(0, depositBalance().compareTo(new java.math.BigDecimal("30.00")), "被拒后押金不得减少");
        assertEquals(0, depositRecords(), "被拒后不得写押金流水（拒绝发生在写库之前）");
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record_lot WHERE record_id=?", recordId),
                "被拒后不得留下批次核销明细");
    }

    /* =====================================================================
     * 3. §7.3 通道白名单：99 / "abc" 一律拒
     * ===================================================================== */

    @Test
    @DisplayName("§7.3 非法 refundChannel（99 / \"abc\"）被白名单拒绝，且文案不把未知值说成已知值")
    void illegalRefundChannelIsRejected() {
        seed();
        giveRefundableBarrels("LOT-3", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        Api numeric = approve(recordId, "{\"status\":3,\"refundChannel\":99}");
        assertFalse(numeric.isSuccess(), "数值型非法通道必须被拒，实际=" + numeric);
        assertTrue(numeric.message().contains("退款方式不合法"),
                "拒绝原因要点明是退款方式的问题，实际=" + numeric.message());
        assertTrue(numeric.message().contains("CASH"),
                "要告诉站长合法取值（否则他不知道怎么改），实际=" + numeric.message());

        Api text = approve(recordId, "{\"status\":3,\"refundChannel\":\"abc\"}");
        assertFalse(text.isSuccess(), "字符串型非法通道必须被拒，实际=" + text);
        assertTrue(text.message().contains("abc"),
                "文案要原样带出非法值（不许把它说成某个已知通道），实际=" + text.message());

        // 两次被拒都必须是"什么都没发生"
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "状态不得被改");
        assertNull(paidTime(recordId), "不得写交付时间");
        assertEquals(1, right(), "权益不得被核销");
        assertEquals(0, depositRecords(), "不得写押金流水");
    }

    /* =====================================================================
     * 4. §7.2 「已核销未交付」计数可查、交付确认后减一
     * ===================================================================== */

    @Test
    @DisplayName("§7.2 已核销未交付：只读计数可查（含历史存量单），补登记交付后计数减一")
    void undeliveredCountIsQueryableAndDropsAfterConfirm() {
        seed();

        // 违规单 = status=3 且未登记交付的退桶记录（升级前退过的历史单就是这个形状）
        long legacy = insertLegacyRefundedRecord(2, "30.00");
        // ⚠️ type=7(纯还桶)/type=8(配送收发) 也把 status 写成 3，那是处理标记、不是"已退押金"，
        //    绝不能被算成"没给钱就核销" —— 这两行就是那条判据的反例
        insertLegacyRefundedRecord(7, "0.00");
        insertLegacyRefundedRecord(8, "0.00");

        Api before = undelivered();
        assertTrue(before.isSuccess(), "站长查未交付计数应成功，实际=" + before);
        assertEquals(1, before.data().path("count").asInt(),
                "只应数出 type=2 的那一条（type=7/8 的 status=3 不是已退押金），实际=" + before.data());
        assertEquals(0, before.data().path("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("30.00")), "金额合计应只含违规单，实际=" + before.data());
        assertEquals(1, before.data().path("records").size(), "明细应给出待补登记的单");
        assertNull(paidTime(legacy), "造数前提：这条历史单没有交付时间");

        Api confirm = confirmPaid(legacy, null);
        assertTrue(confirm.isSuccess(), "补登记交付应成功，实际=" + confirm);
        assertFalse(confirm.data().path("alreadyPaid").asBoolean(), "首次确认不是幂等命中");
        assertNotNull(paidTime(legacy), "补登记后应有交付时间");
        assertEquals(mgr, longOf("SELECT refund_paid_by FROM barrel_record WHERE id=?", legacy),
                "补登记也要记下是谁交的钱");

        assertEquals(0, undelivered().data().path("count").asInt(), "补登记后计数应减一（1 → 0）");
        // 补登记只补事实：金额与状态一个都不许动
        assertEquals(3, intOf("SELECT status FROM barrel_record WHERE id=?", legacy), "补登记不得改状态");
        assertEquals(0, decimalOf("SELECT deposit_refund FROM barrel_record WHERE id=?", legacy)
                .compareTo(new java.math.BigDecimal("30.00")), "补登记不得改金额");
    }

    /* =====================================================================
     * 5. §7.1 交付确认幂等：重复调用成功且不改原交付时间
     * ===================================================================== */

    @Test
    @DisplayName("§7.1 PUT /{id}/refund-paid 幂等：重复调用返回成功且**不改**原交付时间")
    void refundPaidIsIdempotentAndKeepsOriginalTime() {
        seed();

        // (a) 历史单：第一次写入，第二次是幂等命中
        long legacy = insertLegacyRefundedRecord(2, "30.00");
        assertTrue(confirmPaid(legacy, null).isSuccess(), "首次补登记应成功");
        LocalDateTime first = paidTime(legacy);
        assertNotNull(first);

        Api again = confirmPaid(legacy, null);
        assertTrue(again.isSuccess(), "重复确认必须仍返回成功（超时重试不该看到报错），实际=" + again);
        assertTrue(again.data().path("alreadyPaid").asBoolean(), "应告知这次是幂等命中");
        assertEquals(first, paidTime(legacy), "**原交付时间是事实，重复调用不得改写它**");

        // (b) 正常第 3 步已经写好交付，再点一次同样幂等
        giveRefundableBarrels("LOT-5", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");
        assertTrue(approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}").isSuccess(), "退押金应成功");
        LocalDateTime done = paidTime(recordId);

        Api repeat = confirmPaid(recordId, null);
        assertTrue(repeat.isSuccess(), "已交付的单再确认应回成功，实际=" + repeat);
        assertTrue(repeat.data().path("alreadyPaid").asBoolean(), "应告知本次没有写入");
        assertEquals(done, paidTime(recordId), "重复确认不得把交付时间刷新成「现在」");
    }

    @Test
    @DisplayName("§7.1 未核销的单不能登记交付（否则就是把'交付'当'核销'用）")
    void cannotConfirmDeliveryBeforeRefund() {
        seed();
        giveRefundableBarrels("LOT-5b", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();

        Api res = confirmPaid(recordId, null);

        assertFalse(res.isSuccess(), "还没退押金的单不该能登记交付，实际=" + res);
        assertEquals(1, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "状态不得被改");
        assertNull(paidTime(recordId), "不得写交付时间");
    }

    /* =====================================================================
     * 6. §7.4 审批时的第二道风险闸
     * ===================================================================== */

    @Test
    @DisplayName("§7.4 申请后客户欠上逾期款（ALERT/FREEZE）：审批退押金被第二道闸拦下，钱一分不动")
    void riskIsRecheckedWhenApprovingRefund() {
        seed();
        giveRefundableBarrels("LOT-6", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        // 申请之后、审批之前欠上款（申请那一刻没有风险，所以只有 doRefund 这道闸能拦）
        long order = overdueCashOrder(3);

        Api alert = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertFalse(alert.isSuccess(), "预警客户审批退押金必须被拦，实际=" + alert);
        assertTrue(alert.message().contains("欠款"),
                "拒绝原因要指向**欠款**（只说'押金余额不足'站长看不出真实原因），实际=" + alert.message());
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "被拦后应停在(2)");
        assertNull(paidTime(recordId), "被拦后不得留下交付时间");
        assertEquals(1, right(), "被拦后权益不得被核销");
        assertEquals(0, depositBalance().compareTo(new java.math.BigDecimal("30.00")), "被拦后押金不得减少");
        assertEquals(0, depositRecords(), "被拦后不得写押金流水");
        assertEquals(0, intOf("SELECT COUNT(*) FROM barrel_record_lot WHERE record_id=?", recordId),
                "被拦后不得留下批次核销明细（风险闸在写账之前）");

        // 逾期超过阈值 → 冻结：同样是拦
        jdbc.update("UPDATE orders SET due_date = DATE_SUB(CURDATE(), INTERVAL 20 DAY) WHERE id=?", order);
        Api freeze = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertFalse(freeze.isSuccess(), "冻结客户审批退押金必须被拦，实际=" + freeze);
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "被拦后应仍停在(2)");

        // 结清欠款 → 风险自动回到正常、退押金放行（等级是现算的，不需要谁去点解冻）
        jdbc.update("UPDATE orders SET payment_status = 2 WHERE id=?", order);
        Api ok = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertTrue(ok.isSuccess(), "欠款结清后退押金应放行，实际=" + ok);
        assertEquals(3, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "应置已退押金(3)");
        assertNotNull(paidTime(recordId), "放行时同样要登记交付");
    }

    @Test
    @DisplayName("§7.4 风险闸只在**确实要退钱**时生效：应退 0 元的退桶不被它挡住")
    void riskGateOnlyAppliesWhenMoneyActuallyLeaves() {
        seed();
        // 单价 0 的历史批次（迁移推断出 0 元的情形）：核销出来 refund = 0，不是"往外掏钱"
        giveRefundableBarrels("LOT-7", 1, "0.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");
        overdueCashOrder(3);   // 审批前欠上款

        Api res = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\"}");

        assertTrue(res.isSuccess(), "退款额为 0 时不该被风险闸挡住（与 previewReturn 同判据），实际=" + res);
        assertEquals(3, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "应置已退押金(3)");
        assertNotNull(paidTime(recordId), "没有金额要交付，但交付事实仍然要记");
        assertEquals(0, depositRecords(), "退款额为 0 时不写押金流水（旧口径如此，本次不动）");
        assertEquals(0, right(), "权益应被核销为 0");
    }

    /* =====================================================================
     * 7. 跨站：B 站站长不能碰 A 站的退桶记录
     * ===================================================================== */

    @Test
    @DisplayName("跨站防线：B 站站长既不能退 A 站的押金，也不能给 A 站的单补登记交付")
    void crossStationManagerIsRejected() {
        seed();
        long otherStation = createStation("S2");
        long otherMgr = createStaff("M2", "STATION_MANAGER", otherStation, 1);
        String otherToken = staffToken(otherMgr, "STATION_MANAGER", otherStation);

        giveRefundableBarrels("LOT-8", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "本站确认收到空桶应成功");
        long legacy = insertLegacyRefundedRecord(2, "30.00");

        Api refund = put("/api/barrels/records/" + recordId + "/status", otherToken,
                "{\"status\":3,\"refundChannel\":\"CASH\"}");
        assertFalse(refund.isSuccess(), "他站站长不得退本站押金，实际=" + refund);

        Api paid = put("/api/barrels/records/" + legacy + "/refund-paid", otherToken, "{}");
        assertFalse(paid.isSuccess(), "他站站长不得给本站的单登记交付，实际=" + paid);

        // 两次都必须是"什么都没发生"
        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "状态不得被改");
        assertNull(paidTime(recordId), "不得写交付时间");
        assertNull(paidTime(legacy), "他站的补登记不得生效");
        assertEquals(1, right(), "权益不得被核销");
        assertEquals(0, depositRecords(), "不得写押金流水");
    }

    /* =====================================================================
     * 8. §7.1 「核销的人」与「交钱的人」可以是两个
     * ===================================================================== */

    @Test
    @DisplayName("§7.1 交付人可以不是核销人：站长核销、配送员代交时 refund_paid_by = 那个配送员")
    void refundPaidByCanBeAnotherStaffOfSameStation() {
        seed();
        giveRefundableBarrels("LOT-9", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        Api refund = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\",\"refundPaidBy\":" + deliveryStaff + "}");
        assertTrue(refund.isSuccess(), "指定本站配送员代交应成功，实际=" + refund);

        assertEquals(deliveryStaff, longOf("SELECT refund_paid_by FROM barrel_record WHERE id=?", recordId),
                "交付人必须是传进来的那个配送员（这正是**不复用 operator_id** 的理由）");
        assertEquals(mgr, longOf("SELECT confirmed_by FROM barrel_record WHERE id=?", recordId),
                "核销/确认收桶的人仍然是站长 —— 两个概念不共用一个量");
    }

    @Test
    @DisplayName("§7.1 交付人必须是本站员工：传他站站长/不存在的人一律拒，且不落库")
    void refundPaidByMustBelongToStation() {
        seed();
        long otherStation = createStation("S2");
        long otherMgr = createStaff("M2", "STATION_MANAGER", otherStation, 1);

        giveRefundableBarrels("LOT-10", 1, "30.00");
        long recordId = applyReturn(1).data().path("recordId").asLong();
        assertTrue(approve(recordId, "{\"status\":2}").isSuccess(), "确认收到空桶应成功");

        Api other = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\",\"refundPaidBy\":" + otherMgr + "}");
        assertFalse(other.isSuccess(), "交付人不得是别站员工，实际=" + other);
        assertTrue(other.message().contains("本站员工"), "拒绝原因要说明原因，实际=" + other.message());

        Api ghost = approve(recordId, "{\"status\":3,\"refundChannel\":\"CASH\",\"refundPaidBy\":99999999}");
        assertFalse(ghost.isSuccess(), "不存在的交付人必须被拒，实际=" + ghost);

        assertEquals(2, intOf("SELECT status FROM barrel_record WHERE id=?", recordId), "被拒后状态不得被改");
        assertEquals(1, right(), "被拒后权益不得被核销");
        assertEquals(0, depositRecords(), "被拒后不得写押金流水");
    }
}
