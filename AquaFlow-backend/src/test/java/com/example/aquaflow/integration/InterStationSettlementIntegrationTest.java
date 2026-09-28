package com.example.aquaflow.integration;

import com.example.aquaflow.constant.SettleBasis;
import com.example.aquaflow.constant.SettleStatus;
import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 站间结算台账（v67）—— 跨站单的「谁欠谁、欠多少、什么时候算办完」。
 *
 * <p>正本口径：{@code docs/design/31-站间结算算例-水票计价-决策件.md}（§6.2 算法 / §8 已拍板口径）。</p>
 *
 * <p>本用例守的四件事（每件都对应一条会真丢钱的口径）：</p>
 * <ol>
 *   <li><b>方向与金额</b>：钱收在归属站、营收算履约站 ⇒ 归属站欠履约站；
 *       两站净额<b>相加恒为 0</b>（可作断言的不变式）；</li>
 *   <li><b>票单默认折算实付</b>（§8.1）：逐张 {@code ticket_record.unit_price} 求和，
 *       <b>不是</b>挂牌价、也不是订单的 {@code water_amount}；</li>
 *   <li><b>只有卖票站能改按挂牌价</b>（§8.2），且改价<b>必须落快照</b>
 *       （否则下次实时算又变回实付价 —— "改了个寂寞"）；</li>
 *   <li><b>只有付款方能登记结清</b>，登记后 {@code settled_time/settled_by/settle_note} 齐全、
 *       重复登记幂等（不改原结清时间）、已冲销的不能再登记。</li>
 * </ol>
 *
 * <p>⚠️ 另守一条"别把押金算进去"：押金是客户在归属站的资产，不是营收
 * （{@code util/StationUtil}：营收 = 水费 + 配送费 + 楼层费）。</p>
 */
class InterStationSettlementIntegrationTest extends AbstractIntegrationTest {

    private static final String LEDGER = "/api/manager/inter-station-settlements";

    private long stationA;
    private long stationB;
    private long managerA;
    private long managerB;
    private long customerId;
    private long addressId;
    private long productId;

    private void base() {
        stationA = createStation("站间甲站");
        stationB = createStation("站间乙站");
        managerA = createStaff("甲站站长", "STATION_MANAGER", stationA, 1);
        managerB = createStaff("乙站站长", "STATION_MANAGER", stationB, 1);
        customerId = createCustomer("站间客户", "openid-inter-station");
        addressId = createAddress(customerId, "站间路 1 号");
        productId = createProduct("站间桶装水", 1, "12.00", "50.00", 1, "12.00");
    }

    /**
     * 造一张"钱收在归属站 A、履约与结算在 B"的跨站单。
     *
     * <p>⚠️ 显式写 {@code settle_station_id = 履约站}（不是靠 {@code coalesce} 兜底）：
     * 仓库判据是"正常写入必须落 settle_station_id，回退只是防御"（AGENTS §1.1），
     * 用例也照这条造数，否则测的是兜底路径而不是真实路径。</p>
     */
    private long crossOrder(int orderStatus, int paymentStatus, Integer paymentMethod,
                            String waterAmount, String depositAmount, String feeAmount,
                            String payRecordAmount, int payRecordStatus, long payStation) {
        long orderId = createOrderCrossStation(customerId, addressId, stationA, stationB, productId,
                orderStatus, paymentStatus, paymentMethod, waterAmount, depositAmount, "0.00");
        jdbc.update("update orders set settle_station_id = ?, delivery_fee = ?, floor_fee = ?, "
                        + "total_amount = ? where id = ?",
                stationB, new BigDecimal(feeAmount), BigDecimal.ZERO,
                new BigDecimal(waterAmount).add(new BigDecimal(depositAmount)).add(new BigDecimal(feeAmount)),
                orderId);
        createPaymentRecord(orderId, customerId, payStation, payRecordAmount, paymentMethod, payRecordStatus);
        return orderId;
    }

    private Api ledger(long staffId, long stationId) {
        return get(LEDGER, staffToken(staffId, "STATION_MANAGER", stationId));
    }

    private BigDecimal dec(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }

    private com.fasterxml.jackson.databind.JsonNode itemOf(Api api, long orderId) {
        for (com.fasterxml.jackson.databind.JsonNode it : api.data().path("items")) {
            if (it.path("orderId").asLong() == orderId) {
                return it;
            }
        }
        return null;
    }

    // =====================================================================

    @Test
    @DisplayName("非票跨站单：方向=归属站欠履约站，金额=本单营收（不含押金），两站净额相加为 0")
    void revenueBasisAndClosedInvariant() {
        base();
        // 水费 12 + 配送费 1 + 押金 50；钱（含押金）收在 A
        long orderId = crossOrder(1, 2, 1, "12.00", "50.00", "1.00", "63.00", 2, stationA);

        Api a = ledger(managerA, stationA);
        assertTrue(a.isSuccess(), a.message());
        com.fasterxml.jackson.databind.JsonNode ia = itemOf(a, orderId);
        assertNotNull(ia, "归属站应当在台账里看到这张跨站单");
        assertEquals("PAY", ia.path("direction").asText(), "钱在 A 手上、营收算 B ⇒ A 是付款方");
        assertEquals("本站欠别人", ia.path("directionText").asText());
        assertEquals(0, new BigDecimal("13.00").compareTo(dec(ia.path("amount"))),
                "站间应付 = 水费 + 配送费 + 楼层费 = 13.00（⚠️ 押金 50 不算营收，不能进来）");
        assertEquals(SettleBasis.REVENUE, ia.path("basis").asInt());
        assertEquals("本单营收照实结", ia.path("basisText").asText());
        assertEquals(stationB, ia.path("toStationId").asLong());
        assertEquals(0, new BigDecimal("13.00").compareTo(dec(a.data().path("payableAmount"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(dec(a.data().path("receivableAmount"))));

        Api b = ledger(managerB, stationB);
        com.fasterxml.jackson.databind.JsonNode ib = itemOf(b, orderId);
        assertNotNull(ib);
        assertEquals("RECEIVE", ib.path("direction").asText());
        assertEquals(0, new BigDecimal("13.00").compareTo(dec(b.data().path("receivableAmount"))));

        // ★ 不变式：两站净额相加恒为 0（应收 = 应付）
        assertEquals(0, dec(a.data().path("netAmount")).add(dec(b.data().path("netAmount")))
                .compareTo(BigDecimal.ZERO), "站间净额必须闭合到 0");
        assertEquals(0, new BigDecimal("-13.00").compareTo(dec(a.data().path("netAmount"))));
    }

    @Test
    @DisplayName("台账的口径说明是页面正文：不许带 markdown 星号，也不许出现开发词")
    void scopeNoteIsScreenCopy() {
        base();
        crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);

        String note = ledger(managerA, stationA).data().path("scopeNote").asText("");
        assertFalse(note.isEmpty(), "该页必须下发口径说明（站长要据此理解这张表在算什么）");
        // ⚠️ 这条是**在活接口的真实响应里发现**的：第一版写成了 `**已收款、未取消**`，
        //    markdown 星号会被小程序原样渲染成正文（同 dashboardReportCarriesScopeNote 的判据）。
        assertFalse(note.contains("*"), "画面文案不许带星号（会原样显示），实际=" + note);
        for (String devWord : new String[]{"接口", "后端", "字段", "落库", "端点"}) {
            assertFalse(note.contains(devWord), "画面文案不许出现开发词「" + devWord + "」，实际=" + note);
        }
    }

    @Test
    @DisplayName("同站单 / 未收款的跨站单 / 已取消的跨站单 都不进台账")
    void onlyPaidAndAliveCrossStationOrdersAppear() {
        base();
        // 1) 同站单：钱与营收都在 A
        long sameStation = createOrderFull(customerId, addressId, stationA, productId, 1, 2, 1,
                "12.00", "0.00", "12.00", false, 1);
        createPaymentRecord(sameStation, customerId, stationA, "12.00", 1, 2);

        // 2) 跨站但**没收到钱**（payment_status=1，流水还是待支付）—— "不能拿已送达当成钱已付"
        crossOrder(1, 1, 1, "12.00", "0.00", "0.00", "12.00", 1, stationA);

        // 3) 跨站且已收款，但订单已取消
        crossOrder(5, 3, 1, "12.00", "0.00", "0.00", "12.00", 3, stationA);

        Api a = ledger(managerA, stationA);
        assertTrue(a.isSuccess(), a.message());
        assertEquals(0, a.data().path("items").size(),
                "同站单 / 未收款 / 已取消 都不该出现在站间台账里");
        assertEquals(0, BigDecimal.ZERO.compareTo(dec(a.data().path("netAmount"))));
    }

    @Test
    @DisplayName("票单默认折算实付（§8.1）：逐张 ticket_record.unit_price 求和，不是挂牌价")
    void ticketOrderDefaultsToActualPaidValue() {
        base();
        // 挂牌 12.00 × 2 桶 = 水费 24.00；票是 10.00/张 买的（整批折扣），消耗 2 张 ⇒ 实付价值 20.00
        long orderId = crossOrder(1, 2, 3, "24.00", "0.00", "0.00", "24.00", 2, stationA);
        insert("INSERT INTO ticket_record(customer_id, order_id, product_id, station_id, source, "
                        + "decrease_qty, unit_price) VALUES (?,?,?,?,?,?,?)",
                customerId, orderId, productId, stationA, "消费", 2, new BigDecimal("10.00"));

        com.fasterxml.jackson.databind.JsonNode it = itemOf(ledger(managerA, stationA), orderId);
        assertNotNull(it);
        assertEquals(SettleBasis.TICKET_ACTUAL, it.path("basis").asInt(),
                "票单的默认口径必须是「折算实付」");
        assertEquals("水票折算实付", it.path("basisText").asText());
        assertEquals(0, new BigDecimal("20.00").compareTo(dec(it.path("amount"))),
                "10.00 × 2 张 = 20.00（按挂牌结会是 24.00 —— 差额 4.00 正是 §3 例2 的那个数）");
        assertEquals(2, it.path("ticketQty").asInt());
        assertEquals(0, new BigDecimal("10.0000").compareTo(dec(it.path("unitPrice"))));
        // 三条口径的候选值都下发，站长能看懂"为什么是这个数"
        assertEquals(0, new BigDecimal("24.00").compareTo(dec(it.path("waterAmount"))));
        assertEquals(0, new BigDecimal("20.00").compareTo(dec(it.path("ticketActualAmount"))));
    }

    @Test
    @DisplayName("卖票站可改按挂牌价（§8.2）：落快照、金额变挂牌价；履约站不能改")
    void onlyTicketSellerCanSwitchToListedPrice() {
        base();
        long orderId = crossOrder(1, 2, 3, "24.00", "0.00", "0.00", "24.00", 2, stationA);
        insert("INSERT INTO ticket_record(customer_id, order_id, product_id, station_id, source, "
                        + "decrease_qty, unit_price) VALUES (?,?,?,?,?,?,?)",
                customerId, orderId, productId, stationA, "消费", 2, new BigDecimal("10.00"));

        // 履约站（收款方）不能改 —— 差价由卖票站承担，改价权也只该在它手上
        Api byB = post("/api/manager/inter-station-settlements/" + orderId + "/price-by-listed",
                staffToken(managerB, "STATION_MANAGER", stationB), null);
        assertFalse(byB.isSuccess(), "收款方不该能改价");

        Api ok = post("/api/manager/inter-station-settlements/" + orderId + "/price-by-listed",
                staffToken(managerA, "STATION_MANAGER", stationA), null);
        assertTrue(ok.isSuccess(), ok.message());
        assertEquals(SettleBasis.TICKET_LISTED, ok.data().path("basis").asInt());
        assertEquals(0, new BigDecimal("24.00").compareTo(dec(ok.data().path("amount"))));

        // ★ 改价必须落快照，否则下次实时算又变回实付价
        assertEquals(1, intOf("select count(*) from inter_station_settlement where order_id = ?", orderId));
        assertEquals(SettleBasis.TICKET_LISTED,
                intOf("select basis from inter_station_settlement where order_id = ?", orderId));
        assertEquals(0, new BigDecimal("24.00").compareTo(
                decimalOf("select amount from inter_station_settlement where order_id = ?", orderId)));
        assertEquals(SettleStatus.PENDING,
                intOf("select status from inter_station_settlement where order_id = ?", orderId));

        // 再读一次仍是挂牌价（证明快照生效，而不是每次现算把改价抹掉）
        com.fasterxml.jackson.databind.JsonNode it = itemOf(ledger(managerA, stationA), orderId);
        assertEquals(SettleBasis.TICKET_LISTED, it.path("basis").asInt());
        assertEquals(0, new BigDecimal("24.00").compareTo(dec(it.path("amount"))));
    }

    @Test
    @DisplayName("登记结清：只有付款方能做、凭据齐全、重复登记幂等且不改原结清时间")
    void settleOnlyByPayerAndIdempotent() throws Exception {
        base();
        long orderId = crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);

        // 收款方登记 => 拒（钱不在它手上，"我收到了"没有意义）
        Api byReceiver = post("/api/manager/inter-station-settlements/" + orderId + "/settle",
                staffToken(managerB, "STATION_MANAGER", stationB), "{\"note\":\"我收到了\"}");
        assertFalse(byReceiver.isSuccess(), "收款方不该能登记结清");

        Api ok = post("/api/manager/inter-station-settlements/" + orderId + "/settle",
                staffToken(managerA, "STATION_MANAGER", stationA), "{\"note\":\"转账 20260927-001\"}");
        assertTrue(ok.isSuccess(), ok.message());
        assertEquals(SettleStatus.SETTLED, ok.data().path("status").asInt());
        assertEquals("已结清", ok.data().path("statusText").asText());
        assertFalse(ok.data().path("settledTime").isNull(), "必须记下「什么时候算办完」");
        assertEquals("转账 20260927-001", ok.data().path("settleNote").asText());

        assertEquals(SettleStatus.SETTLED,
                intOf("select status from inter_station_settlement where order_id = ?", orderId));
        assertEquals(managerA,
                longOf("select settled_by from inter_station_settlement where order_id = ?", orderId));
        assertNotNull(decimalOf("select amount from inter_station_settlement where order_id = ?", orderId));

        String firstPaidTime = ok.data().path("settledTime").asText();

        // 幂等：再点一次成功，且**结清时间不变**（那才是"办完"的答案）
        Api again = post("/api/manager/inter-station-settlements/" + orderId + "/settle",
                staffToken(managerA, "STATION_MANAGER", stationA), "{\"note\":\"又点了一次\"}");
        assertTrue(again.isSuccess(), "重复登记必须幂等，不能报错");
        assertEquals(firstPaidTime, again.data().path("settledTime").asText(),
                "重复登记不许改写「什么时候算办完」");
        assertEquals("转账 20260927-001", again.data().path("settleNote").asText());
        assertEquals(1, intOf("select count(*) from inter_station_settlement where order_id = ?", orderId),
                "一单一笔，不许插出第二行");

        // 结清后：未结清金额归零、已结清金额累加
        Api b = ledger(managerB, stationB);
        assertEquals(0, BigDecimal.ZERO.compareTo(dec(b.data().path("receivableAmount"))),
                "结清后不该再算作「本站应被补」");
        assertEquals(0, new BigDecimal("13.00").compareTo(dec(b.data().path("settledReceivableAmount"))));
        assertEquals(0, b.data().path("unsettledCount").asInt());
    }

    @Test
    @DisplayName("冲销：订单取消后那笔应付不再成立（改状态不删行），已冲销的不能再登记结清")
    void reverseKeepsTraceAndBlocksSettle() {
        base();
        long orderId = crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);
        String tokenA = staffToken(managerA, "STATION_MANAGER", stationA);

        assertTrue(post("/api/manager/inter-station-settlements/" + orderId + "/settle", tokenA, null)
                .isSuccess());

        Api rev = post("/api/manager/inter-station-settlements/" + orderId + "/reverse", tokenA, null);
        assertTrue(rev.isSuccess(), rev.message());
        assertEquals(Boolean.TRUE, rev.data().path("changed").asBoolean());

        assertEquals(SettleStatus.REVERSED,
                intOf("select status from inter_station_settlement where order_id = ?", orderId));
        assertEquals(1, intOf("select count(*) from inter_station_settlement where order_id = ?", orderId),
                "冲销是改状态，不许删行（轨迹要留得住）");

        // 已冲销：不能再登记结清
        Api settledAgain = post("/api/manager/inter-station-settlements/" + orderId + "/settle",
                tokenA, null);
        assertFalse(settledAgain.isSuccess(), "已冲销的应付不能再登记结清");

        // 已冲销的单：不计入任何一个方向
        Api a = ledger(managerA, stationA);
        assertEquals(0, BigDecimal.ZERO.compareTo(dec(a.data().path("payableAmount"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(dec(a.data().path("receivableAmount"))));
        com.fasterxml.jackson.databind.JsonNode it = itemOf(a, orderId);
        assertNotNull(it, "已冲销的单仍要能查到（否则站长无从核对）");
        assertEquals("已冲销", it.path("statusText").asText());
    }

    @Test
    @DisplayName("订单取消后不再进台账（已收款→已退款）")
    void cancelledOrderDisappears() {
        base();
        long orderId = crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);
        assertEquals(1, ledger(managerA, stationA).data().path("items").size());

        jdbc.update("update orders set status = 5, payment_status = 3 where id = ?", orderId);
        assertEquals(0, ledger(managerA, stationA).data().path("items").size(),
                "已取消的跨站单不该再出现在站间台账里");
    }

    @Test
    @DisplayName("结清之后订单被取消：那一笔必须能被看见并冲销（否则结过的钱凭空消失）")
    void settledRowSurvivesOrderCancellationUntilReversed() {
        base();
        long orderId = crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);
        String tokenA = staffToken(managerA, "STATION_MANAGER", stationA);
        assertTrue(post("/api/manager/inter-station-settlements/" + orderId + "/settle", tokenA, null)
                .isSuccess(), "前置：登记结清应成功");

        // 订单随后被取消/退款 ⇒ 它从正常台账里消失（live 的 WHERE 排除了这种单）
        jdbc.update("update orders set status = 5, payment_status = 3 where id = ?", orderId);
        Api a = ledger(managerA, stationA);
        assertEquals(0, a.data().path("items").size(), "已取消的单不该再出现在正常台账里");

        // ★ 但"我结过一笔、现在不该结了"必须看得见：不列出来，站长既不知道也不会去冲销
        assertEquals(1, a.data().path("danglingSettled").size(),
                "已结清但订单已取消的那笔要单独列出来等人冲销");
        com.fasterxml.jackson.databind.JsonNode d = a.data().path("danglingSettled").get(0);
        assertEquals(orderId, d.path("orderId").asLong());
        assertEquals(0, new BigDecimal("13.00").compareTo(dec(d.path("amount"))));
        assertEquals("站间甲站", d.path("fromStationName").asText(),
                "站名要跟着一起下发 —— 只从 live 攒站名会让这些行显示成「水站#3」");

        // 无关站不能冲销（判权在服务端，不看前端按钮）
        long otherStation = createStation("站间丙站");
        long otherManager = createStaff("丙站站长", "STATION_MANAGER", otherStation, 1);
        assertFalse(post("/api/manager/inter-station-settlements/" + orderId + "/reverse",
                staffToken(otherManager, "STATION_MANAGER", otherStation), null).isSuccess(),
                "与本站无关的单不能冲销");

        // ★ 冲销：此前这里必然报「该订单不在站间结算台账里」——
        //   错在"先 requireLive"，而 live 恰好把要冲销的那类单排除了（判据错位，用例锁住）
        Api rev = post("/api/manager/inter-station-settlements/" + orderId + "/reverse", tokenA, null);
        assertTrue(rev.isSuccess(), "订单已取消的那笔也要能冲销，实际=" + rev.message());
        assertEquals(Boolean.TRUE, rev.data().path("changed").asBoolean());
        assertEquals(SettleStatus.REVERSED,
                intOf("select status from inter_station_settlement where order_id = ?", orderId));
        assertEquals(0, ledger(managerA, stationA).data().path("danglingSettled").size(),
                "冲销之后不该再挂在「需冲销」里");
    }

    @Test
    @DisplayName("待办卡角标与台账页同源：站间未结清 = 台账 unsettledCount（防「角标说 3、点进去 0 条」）")
    void pendingBadgeMatchesLedger() {
        base();
        String tokenA = staffToken(managerA, "STATION_MANAGER", stationA);

        Api empty = get("/api/manager/pending-summary", tokenA);
        assertTrue(empty.isSuccess(), empty.message());
        assertEquals(0, summaryOf(empty, "interStationUnsettled", "count"),
                "没有跨站单时角标必须是 0（前端「零计数不进卡」的口径靠它）");

        crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);

        Api summary = get("/api/manager/pending-summary", tokenA);
        int badge = summaryOf(summary, "interStationUnsettled", "count");
        assertEquals(1, badge, "这一笔跨站单要被站长看见（否则那笔钱没人管）");
        // ★ 与落地页**同一个读数**：角标说几条，点进去就得是几条
        assertEquals(ledger(managerA, stationA).data().path("items").size(), badge,
                "角标必须等于台账页列出的条数 —— 本仓对这套待办的铁律是"
                        + "「每个计数都必须是某个已经在用的读数入口的调用结果」");
        // 级别：它**是钱**但两侧站长都不会干等（客户的水照常送）⇒ P1。
        // 红点只报 P0，所以这条不该把 tab 红点点亮（点亮了就是"红点说不清为什么亮"）。
        assertEquals("P1", levelOf(summary, "interStationUnsettled"),
                "站间未结清必须是 P1 —— 标成 P0 会让首页红点亮起一条与红点判据不符的项");
    }

    /** 从待办汇总里取某项的某个字段；找不到返回 -1（用例据此报"这一项根本没下发"）。 */
    private int summaryOf(Api summary, String key, String field) {
        for (com.fasterxml.jackson.databind.JsonNode n : summary.data().path("items")) {
            if (key.equals(n.path("key").asText())) {
                return n.path(field).asInt(-1);
            }
        }
        return -1;
    }

    private String levelOf(Api summary, String key) {
        for (com.fasterxml.jackson.databind.JsonNode n : summary.data().path("items")) {
            if (key.equals(n.path("key").asText())) {
                return n.path("level").asText("");
            }
        }
        return "";
    }

    @Test
    @DisplayName("权限：配送员与该站无关的站长都调不动；站点一律取登录态")
    void onlyManagerOfInvolvedStation() {
        base();
        long orderId = crossOrder(1, 2, 1, "12.00", "0.00", "1.00", "13.00", 2, stationA);
        long otherStation = createStation("无关站");
        long otherManager = createStaff("无关站站长", "STATION_MANAGER", otherStation, 1);

        Api delivery = get(LEDGER, staffToken(
                createStaff("配送员", "DELIVERY", stationA, 1), "DELIVERY", stationA));
        assertFalse(delivery.isSuccess(), "配送员不该能看站间台账");

        Api other = ledger(otherManager, otherStation);
        assertTrue(other.isSuccess(), other.message());
        assertEquals(0, other.data().path("items").size(), "与本站无关的单不该出现");

        // 无关站的站长拿着别人的 orderId 也不能改价 / 登记
        assertFalse(post("/api/manager/inter-station-settlements/" + orderId + "/price-by-listed",
                staffToken(otherManager, "STATION_MANAGER", otherStation), null).isSuccess());
        assertFalse(post("/api/manager/inter-station-settlements/" + orderId + "/settle",
                staffToken(otherManager, "STATION_MANAGER", otherStation), null).isSuccess());
    }
}
