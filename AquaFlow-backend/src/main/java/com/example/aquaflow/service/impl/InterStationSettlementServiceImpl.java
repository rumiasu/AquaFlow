package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.PayMethod;
import com.example.aquaflow.constant.SettleBasis;
import com.example.aquaflow.constant.SettleStatus;
import com.example.aquaflow.entity.InterStationSettlement;
import com.example.aquaflow.exception.BusinessException;
import com.example.aquaflow.mapper.InterStationSettlementMapper;
import com.example.aquaflow.service.InterStationSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 站间结算实现。口径与理由见 {@link InterStationSettlementService} 的类注释。
 *
 * <p><b>为什么"欠多少"是现算的、而不是落库的</b>：一条跨站单在生命周期里会被
 * 收款 / 换站（抢单、定向外派、召回、退回池、指定退回-同意）/ 取消 反复改写，
 * 要把金额与方向实时同步进表，就得在上述**每一个**写入点挂钩子 —— 漏一个的表现是
 * <b>静默少一笔应收</b>，而且没有任何对账等式会报出来（缺的事件等式两边同时缺，
 * 这是库存预留那一轮踩过的同形坑）。所以：<b>实时算 <code>=</code> 真相源；表只记人工动作</b>
 * （改价快照、结清事实、冲销）。</p>
 *
 * <p>代价（如实写在类注释里，别当没有）：未结清的金额**没有历史快照**，
 * 所以"三个月前它显示欠 168 而现在显示欠 12"这件事查不到 —— 只有**已结清**的那些单留了快照。
 * 若将来要求"每个时点的站间应收都能复现"，那要另做一张按时点快照的表，不在 v67 范围内。</p>
 */
@Service
@Slf4j
public class InterStationSettlementServiceImpl implements InterStationSettlementService {

    /** 单价快照的标度（与 `inter_station_settlement.unit_price decimal(10,4)` 一致）。 */
    private static final int UNIT_PRICE_SCALE = 4;

    private final InterStationSettlementMapper mapper;

    public InterStationSettlementServiceImpl(InterStationSettlementMapper mapper) {
        this.mapper = mapper;
    }

    // =====================================================================
    // 读：本站台账
    // =====================================================================

    @Override
    public Map<String, Object> ledgerOf(Long stationId) {
        List<Map<String, Object>> live = mapper.listLiveCrossStationOrders(stationId, null);
        // ⚠️ 「已结清但依据已消失」那批要单独取，且**站名也要一起取**：
        //    它们的订单已经不在 live 里了，只从 live 攒站名会让那些行显示成「水站#7」。
        List<InterStationSettlement> dangling = mapper.listSettledButVoid(stationId);
        Map<Long, String> names = loadStationNames(live);
        names.putAll(loadStationNamesOf(dangling));

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal receive = BigDecimal.ZERO;   // 别人欠本站（未结清）
        BigDecimal pay = BigDecimal.ZERO;       // 本站欠别人（未结清）
        BigDecimal settledReceive = BigDecimal.ZERO;
        BigDecimal settledPay = BigDecimal.ZERO;
        int unsettledCount = 0;

        for (Map<String, Object> row : live) {
            Map<String, Object> item = decorate(row, stationId, names);
            items.add(item);

            BigDecimal amount = dec(item.get("amount"));
            boolean settled = SettleStatus.SETTLED == intOf(item.get("status"));
            boolean reversed = SettleStatus.REVERSED == intOf(item.get("status"));
            if (reversed) {
                continue;   // 已冲销：不计入任何一侧（钱的方向已不成立）
            }
            boolean iReceive = "RECEIVE".equals(item.get("direction"));
            if (settled) {
                if (iReceive) {
                    settledReceive = settledReceive.add(amount);
                } else {
                    settledPay = settledPay.add(amount);
                }
            } else {
                unsettledCount++;
                if (iReceive) {
                    receive = receive.add(amount);
                } else {
                    pay = pay.add(amount);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stationId", stationId);
        data.put("items", items);
        data.put("unsettledCount", unsettledCount);
        // 「本站应被补」= 别人欠本站；「本站应付」= 本站欠别人。两者都用**正数**表达，方向由字段名承担
        // （负号只出现在 netAmount 上：正 = 净收，负 = 净付）。
        data.put("receivableAmount", receive);
        data.put("payableAmount", pay);
        data.put("netAmount", receive.subtract(pay));
        data.put("settledReceivableAmount", settledReceive);
        data.put("settledPayableAmount", settledPay);
        // 已登记结清、但订单后来被取消/退款的那批：**要能被看见并冲销**。
        // ⚠️ 它们不在 items 里 —— items 只列"还算数"的跨站单（live 的 WHERE 带 status != 5），
        // 所以不单独列出来的话，站长结过的那笔会凭空消失，冲销也就无从下手。
        data.put("danglingSettled", decorateDangling(stationId, dangling, names));
        // ⚠️ 这是**站长界面上的正文**：不许写 markdown 星号（会原样渲染出来），也不许出现开发词
        //    （AGENTS §6）。第一版写成 `**已收款、未取消**`，是在**活接口的真实响应**里被发现的
        //    （探针 `__live_ledger_shape.py`）—— 静态门禁与单元断言都查不到这种；
        //    用例现在把它锁住了（`scopeNote` 不许含星号与开发词）。
        data.put("scopeNote", "这里只算「已收款、未取消」的跨站单：钱收在归属站、营收算接单站的那部分差额。"
                + "押金与水票余额是客户在归属站的资产，不进这张表。");
        return data;
    }

    /** 「已结清但依据已消失」的那批（订单取消/退款）——给页面「需冲销」区用。 */
    private List<Map<String, Object>> decorateDangling(Long stationId, List<InterStationSettlement> rows,
                                                        Map<Long, String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InterStationSettlement row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", row.getOrderId());
            item.put("fromStationId", row.getFromStationId());
            item.put("fromStationName", names.getOrDefault(row.getFromStationId(),
                    "水站#" + row.getFromStationId()));
            item.put("toStationId", row.getToStationId());
            item.put("toStationName", names.getOrDefault(row.getToStationId(),
                    "水站#" + row.getToStationId()));
            item.put("direction", Objects.equals(row.getFromStationId(), stationId) ? "PAY" : "RECEIVE");
            item.put("directionText", Objects.equals(row.getFromStationId(), stationId) ? "本站欠别人" : "别人欠本站");
            item.put("amount", row.getAmount());
            item.put("basis", row.getBasis());
            item.put("basisText", SettleBasis.textOf(row.getBasis()));
            item.put("status", row.getStatus());
            item.put("statusText", SettleStatus.textOf(row.getStatus()));
            item.put("settledTime", row.getSettledTime());
            item.put("settleNote", row.getSettleNote());
            out.add(item);
        }
        return out;
    }

    // =====================================================================
    // 写：登记结清 / 改价 / 冲销
    // =====================================================================

    @Override
    public int unsettledCountOf(Long stationId) {
        // ⚠️ 有意**复用 ledgerOf**，不另写一个 count 查询：两处计数一旦分叉，
        //    站长就会看到"角标说 3、点进去 0 条"（PendingItem 目录的头号纪律）。
        //    代价是每次待办汇总多跑两条只读查询 —— 跨站单是低频数据，这个量级可以接受。
        Object v = ledgerOf(stationId).get("unsettledCount");
        return v instanceof Number n ? n.intValue() : 0;
    }

    @Override
    @Transactional
    public Map<String, Object> settle(Long stationId, Long orderId, String note, Long operatorId) {
        Map<String, Object> live = requireLive(orderId);
        Long payStation = longOf(live.get("payStationId"));
        Long receiveStation = longOf(live.get("settleStationId"));

        // 谁能登记：**付款方**（钱在它手上）。收款方单方面说"我收到了"没有意义 ——
        // 它是被动收钱的一方，登记权在出钱的一方（同"发钱的是站长不是平台"的既有口径）。
        if (!Objects.equals(payStation, stationId)) {
            throw new BusinessException("这笔钱在" + stationLabel(payStation) + "手上，应由该站登记结清；本站是收款方，无需登记");
        }

        InterStationSettlement existing = mapper.findByOrderId(orderId);
        Map<String, Object> snapshot = computeSnapshot(live, existing, null);

        if (existing == null) {
            InterStationSettlement row = buildRow(live, snapshot, operatorId);
            row.setStatus(SettleStatus.SETTLED);
            row.setSettledTime(LocalDateTime.now());
            row.setSettledBy(operatorId);
            row.setSettleNote(trimNote(note));
            mapper.insert(row);
            log.info("[站间结算] 登记结清(新建). order={}, {} -> {}, amount={}, basis={}",
                    orderId, payStation, receiveStation, row.getAmount(), row.getBasis());
            return resultOf(live, row, stationId);
        }

        if (SettleStatus.REVERSED == intOf(existing.getStatus())) {
            throw new BusinessException("这一笔已冲销（订单已取消或已退款），不能再登记结清");
        }
        if (SettleStatus.SETTLED == intOf(existing.getStatus())) {
            // 幂等：已结清再点一次直接成功（同 docs/design/35 §4 方案 A 对确认类端点的要求），
            // 但**不改**原来的结清时间与人 —— 那才是"什么时候算办完"的答案。
            return resultOf(live, existing, stationId);
        }
        if (mapper.settleIfPending(orderId, operatorId, trimNote(note)) == 0) {
            throw new BusinessException("该笔状态已被变更，请刷新后重试");
        }
        InterStationSettlement after = mapper.findByOrderId(orderId);
        log.info("[站间结算] 登记结清. order={}, {} -> {}, amount={}",
                orderId, payStation, receiveStation, after.getAmount());
        return resultOf(live, after, stationId);
    }

    @Override
    @Transactional
    public Map<String, Object> priceByListed(Long stationId, Long orderId, Long operatorId) {
        Map<String, Object> live = requireLive(orderId);
        Long ownerStation = longOf(live.get("ownerStationId"));
        Integer paymentMethod = intOf(live.get("paymentMethod"));

        if (!Objects.equals(ownerStation, stationId)) {
            throw new BusinessException("只有卖票站（本单归属站）能改成本单的站间计价方式 —— 差价由卖票站自己承担");
        }
        if (!Objects.equals(PayMethod.TICKET, paymentMethod)) {
            throw new BusinessException("只有水票支付的单才有「折算实付 / 挂牌价」两种口径；本单不是水票单");
        }

        InterStationSettlement existing = mapper.findByOrderId(orderId);
        if (existing != null && SettleStatus.REVERSED == intOf(existing.getStatus())) {
            throw new BusinessException("这一笔已冲销（订单已取消或已退款），不能再改价");
        }
        if (existing != null && SettleStatus.SETTLED == intOf(existing.getStatus())) {
            throw new BusinessException("这一笔已经结清，改价会让已记的凭据与实际不符；请先冲销再重新登记");
        }

        Map<String, Object> listed = computeSnapshot(live, null, SettleBasis.TICKET_LISTED);

        if (existing == null) {
            InterStationSettlement row = buildRow(live, listed, operatorId);
            row.setStatus(SettleStatus.PENDING);
            mapper.insert(row);
            log.info("[站间结算] 改按挂牌价(新建). order={}, station={}, amount={}",
                    orderId, stationId, row.getAmount());
            return resultOf(live, row, stationId);
        }
        if (mapper.repricingIfPending(orderId,
                SettleBasis.TICKET_LISTED, dec(listed.get("amount")), intOf(listed.get("ticketQty")),
                dec(listed.get("unitPrice")), dec(listed.get("feeAmount"))) == 0) {
            throw new BusinessException("该笔状态已被变更，请刷新后重试");
        }
        InterStationSettlement after = mapper.findByOrderId(orderId);
        log.info("[站间结算] 改按挂牌价. order={}, station={}, amount={}", orderId, stationId, after.getAmount());
        return resultOf(live, after, stationId);
    }

    @Override
    @Transactional
    public Map<String, Object> reverse(Long stationId, Long orderId) {
        if (orderId == null) {
            throw new BusinessException("缺少订单号");
        }
        // ⚠️ **这里不能先 requireLive(orderId)**：冲销要处理的情形正是"订单已经取消/退款"，
        //    而 live 的 WHERE 会把那种单排除掉 —— 于是这条唯一该走冲销的路会永远报
        //    「该订单不在站间结算台账里」，冲销成了点不动的按钮（实现时踩到，用例锁住了）。
        //    判据改成"台账里有没有这一行"，与订单当前状态无关。
        InterStationSettlement existing = mapper.findByOrderId(orderId);
        if (existing == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", orderId);
            data.put("changed", false);
            data.put("message", "这一笔还没有登记过（未结清的单不需要冲销）");
            return data;
        }
        // 判权：只有这笔的两个当事站能冲销（不信任请求参数，站别取自登录态）
        if (!Objects.equals(existing.getFromStationId(), stationId)
                && !Objects.equals(existing.getToStationId(), stationId)) {
            throw new BusinessException("本单与本站无关，不能冲销");
        }
        int n = mapper.reverse(orderId);
        log.info("[站间结算] 冲销. order={}, station={}, affected={}", orderId, stationId, n);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("changed", n > 0);
        data.put("status", SettleStatus.REVERSED);
        data.put("statusText", SettleStatus.textOf(SettleStatus.REVERSED));
        return data;
    }

    // =====================================================================
    // 内部：取数 / 算金额 / 组装
    // =====================================================================

    /**
     * 取某一单的跨站台账行；不在台账里（同站单 / 未收款 / 已取消）直接拒绝。
     *
     * <p>用 {@code listLive(orderId=…)} 而不是自己写一条 SQL —— 金额口径只有一处实现，
     * 两处必然分叉（本仓的"同一判断只实现一次"）。</p>
     */
    private Map<String, Object> requireLive(Long orderId) {
        if (orderId == null) {
            throw new BusinessException("缺少订单号");
        }
        List<Map<String, Object>> rows = mapper.listLiveCrossStationOrders(null, orderId);
        if (rows.isEmpty()) {
            throw new BusinessException("该订单不在站间结算台账里（同站履约、或还没收到钱、或已取消）");
        }
        return rows.get(0);
    }

    /**
     * 算这一单该结多少。**优先级：显式指定口径 &gt; 台账里已存的快照 &gt; 按支付方式取默认口径**。
     *
     * <p>三种口径的<b>票面/营收基数</b>：</p>
     * <ul>
     *   <li>{@link SettleBasis#REVENUE} = 水费 + 配送费 + 楼层费（**不含押金**）；</li>
     *   <li>{@link SettleBasis#TICKET_ACTUAL} = Σ 逐张 {@code ticket_record.unit_price × decrease_qty}；</li>
     *   <li>{@link SettleBasis#TICKET_LISTED} = {@code orders.water_amount}（挂牌价合计）。</li>
     * </ul>
     * <p>✅ [2026-09-29 拍板，原 {@code docs/design/31} §8.4 第 3 问] <b>票覆盖的配送费/楼层费一起结</b>：
     * 两种票口径的 {@code amount} = 上表基数 + {@code feeAmount}（= {@code delivery_fee + floor_fee}），
     * 否则接单站按票面折算只收到水钱、白送一趟配送（§3 例4 的差额）。
     * 三条护栏：① REVENUE 的费用<b>本来就在营收里</b>（此处 {@code feeAmount = 0}，不会重复计）；
     * ② {@code unitPrice} 仍只按<b>票面部分</b> ÷ 张数（费用不摊进单价，否则改口径会动到票的单价语义）；
     * ③ {@code feeAmount} 仍<b>单独记一列</b>（页面展示"这个数怎么来的"），存量快照原样用、不回溯改写。</p>
     *
     * @param forcedBasis 显式指定口径（改价时用；传 null = 不强制）。
     *                    ⚠️ <b>金额必须与口径一起算</b> —— 曾经写成"先按默认口径算金额、
     *                    再把 basis 改成挂牌价"，于是**改了名没改数**（金额仍是实付值），
     *                    而这是钱的事：站长点了"按挂牌价结"，账上却还是实付价，谁也看不出来。
     * @param row         台账里**已经存下的**那一行（有快照就必须用它，否则改价会被现算抹掉）。
     *                    ⚠️ 读路径传的是 {@link #persistedFrom(Map)} 从实时查询 left join 出来的行 ——
     *                    忘了传它，表现就是"改了挂牌价、再读一次又变回实付价"（2026-09-27 全量回归抓到的真缺陷）。
     */
    private Map<String, Object> computeSnapshot(Map<String, Object> live, InterStationSettlement row,
                                                Integer forcedBasis) {
        Map<String, Object> out = new LinkedHashMap<>();

        Integer paymentMethod = intOf(live.get("paymentMethod"));
        Integer basis;
        BigDecimal amount;
        Integer ticketQty;
        BigDecimal unitPrice;
        BigDecimal feeAmount;

        if (forcedBasis != null) {
            basis = forcedBasis;
            ticketQty = intOf(live.get("ticketQty"));
            BigDecimal baseAmount = amountOfBasis(live, basis);
            feeAmount = SettleBasis.isTicketBased(basis) ? dec(live.get("coveredFeeAmount")) : BigDecimal.ZERO;
            amount = baseAmount.add(feeAmount);
            unitPrice = unitPriceOf(baseAmount, ticketQty);
        } else if (row != null && row.getBasis() != null) {
            basis = row.getBasis();
            amount = dec(row.getAmount());
            ticketQty = row.getTicketQty() == null ? intOf(live.get("ticketQty")) : row.getTicketQty();
            unitPrice = row.getUnitPrice() == null ? unitPriceOf(amount, ticketQty) : row.getUnitPrice();
            feeAmount = row.getFeeAmount() == null ? BigDecimal.ZERO : row.getFeeAmount();
        } else {
            basis = SettleBasis.defaultFor(paymentMethod);
            ticketQty = intOf(live.get("ticketQty"));
            BigDecimal baseAmount = amountOfBasis(live, basis);
            feeAmount = SettleBasis.isTicketBased(basis) ? dec(live.get("coveredFeeAmount")) : BigDecimal.ZERO;
            amount = baseAmount.add(feeAmount);
            unitPrice = unitPriceOf(baseAmount, ticketQty);
        }

        out.put("basis", basis);
        out.put("amount", amount);
        out.put("ticketQty", ticketQty == null ? 0 : ticketQty);
        out.put("unitPrice", unitPrice);
        out.put("feeAmount", feeAmount);
        return out;
    }

    /** 三种口径各自从实时查询里取哪个数（口径与算式的唯一映射处）。 */
    private BigDecimal amountOfBasis(Map<String, Object> live, Integer basis) {
        switch (basis) {
            case SettleBasis.TICKET_ACTUAL: return dec(live.get("ticketActualAmount"));
            case SettleBasis.TICKET_LISTED: return dec(live.get("waterAmount"));
            default: return dec(live.get("revenueAmount"));
        }
    }

    /**
     * 把实时查询里 {@code left join} 出来的**台账行**还原成实体形态（没有行时返回 null）。
     *
     * <p>为什么要有这一步：读路径（{@link #decorate}）只拿得到 SQL 的 Map，
     * 而"这张单有没有被改过价 / 结清过"就写在那些列里。不还原成实体就没法复用
     * {@link #computeSnapshot} 的"有快照用快照"分支 —— 于是界面读出来永远是默认口径，
     * 站长点了「按挂牌价结」屏幕上却还是实付价（全量回归抓到的真缺陷）。</p>
     */
    private InterStationSettlement persistedFrom(Map<String, Object> live) {
        if (live == null || live.get("settleRowId") == null) {
            return null;
        }
        InterStationSettlement row = new InterStationSettlement();
        row.setOrderId(longOf(live.get("orderId")));
        row.setBasis(intOf(live.get("settleBasis")));
        row.setAmount(dec(live.get("settleAmount")));
        row.setTicketQty(intOf(live.get("settleTicketQty")));
        row.setUnitPrice(dec(live.get("settleUnitPrice")));
        row.setFeeAmount(dec(live.get("settleFeeAmount")));
        row.setStatus(intOf(live.get("settleStatus")));
        return row;
    }

    private BigDecimal unitPriceOf(BigDecimal amount, Integer ticketQty) {
        if (amount == null || ticketQty == null || ticketQty <= 0) {
            return null;
        }
        return amount.divide(BigDecimal.valueOf(ticketQty), UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private InterStationSettlement buildRow(Map<String, Object> live, Map<String, Object> snapshot, Long operatorId) {
        InterStationSettlement row = new InterStationSettlement();
        row.setOrderId(longOf(live.get("orderId")));
        row.setFromStationId(longOf(live.get("payStationId")));
        row.setToStationId(longOf(live.get("settleStationId")));
        row.setAmount(dec(snapshot.get("amount")));
        row.setBasis(intOf(snapshot.get("basis")));
        row.setTicketQty(intOf(snapshot.get("ticketQty")));
        row.setUnitPrice(dec(snapshot.get("unitPrice")));
        row.setFeeAmount(dec(snapshot.get("feeAmount")));
        row.setSnapshotTime(LocalDateTime.now());
        return row;
    }

    /** 把一行原始 SQL 结果组装成给前端看的样子（含中文文案与金额候选）。 */
    private Map<String, Object> decorate(Map<String, Object> live, Long stationId, Map<Long, String> names) {
        // ⚠️ 必须把 left join 出来的台账行传进去：传 null 会让"改过价/已结清"的快照被现算覆盖，
        //    界面读出来永远是默认口径（全量回归抓到过）。
        Map<String, Object> snapshot = computeSnapshot(live, persistedFrom(live), null);
        Long payStation = longOf(live.get("payStationId"));
        Long receiveStation = longOf(live.get("settleStationId"));
        boolean iReceive = !Objects.equals(payStation, stationId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", longOf(live.get("orderId")));
        out.put("ownerStationId", longOf(live.get("ownerStationId")));
        out.put("fromStationId", payStation);
        out.put("fromStationName", names.getOrDefault(payStation, "水站#" + payStation));
        out.put("toStationId", receiveStation);
        out.put("toStationName", names.getOrDefault(receiveStation, "水站#" + receiveStation));
        out.put("direction", iReceive ? "RECEIVE" : "PAY");
        out.put("directionText", iReceive ? "别人欠本站" : "本站欠别人");
        out.put("amount", snapshot.get("amount"));
        out.put("basis", snapshot.get("basis"));
        out.put("basisText", SettleBasis.textOf(intOf(snapshot.get("basis"))));
        out.put("ticketQty", snapshot.get("ticketQty"));
        out.put("unitPrice", snapshot.get("unitPrice"));
        out.put("feeAmount", snapshot.get("feeAmount"));
        out.put("paymentMethod", intOf(live.get("paymentMethod")));
        out.put("paymentMethodText", PayMethod.textOf(intOf(live.get("paymentMethod"))));
        // 三条口径的候选值都给出来 —— 站长看到"为什么是这个数"，柜台不用吵架
        out.put("revenueAmount", dec(live.get("revenueAmount")));
        out.put("waterAmount", dec(live.get("waterAmount")));
        out.put("ticketActualAmount", dec(live.get("ticketActualAmount")));

        Integer status = live.get("settleStatus") == null ? SettleStatus.PENDING : intOf(live.get("settleStatus"));
        out.put("status", status);
        out.put("statusText", SettleStatus.textOf(status));
        out.put("settledTime", live.get("settledTime"));
        out.put("settleNote", live.get("settleNote"));
        out.put("canChangePrice", Objects.equals(longOf(live.get("ownerStationId")), stationId)
                && Objects.equals(PayMethod.TICKET, intOf(live.get("paymentMethod")))
                && status != SettleStatus.SETTLED && status != SettleStatus.REVERSED);
        out.put("canSettle", Objects.equals(payStation, stationId) && status == SettleStatus.PENDING);
        return out;
    }

    /**
     * 写完之后**重新取一次** live 行再组装返回值。
     *
     * <p>⚠️ 不能拿写之前读到的那个 {@code live} 去装饰：它里面的
     * {@code settleStatus / settleAmount} 是**写之前**的快照，会返回"刚登记结清、状态还是待结清"
     * 这种自相矛盾的响应（前端据此渲染就会让站长重复点）。同一事务内重新查能看到自己刚写的行。</p>
     */
    private Map<String, Object> resultOf(Map<String, Object> liveIgnored, InterStationSettlement row, Long stationId) {
        List<Map<String, Object>> fresh = mapper.listLiveCrossStationOrders(null, row.getOrderId());
        if (fresh.isEmpty()) {
            // 极端情况：订单在本次操作期间被取消 —— 如实回报，不要伪造一个已结清的样子
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", row.getOrderId());
            data.put("amount", row.getAmount());
            data.put("basis", row.getBasis());
            data.put("basisText", SettleBasis.textOf(row.getBasis()));
            data.put("status", row.getStatus());
            data.put("statusText", SettleStatus.textOf(row.getStatus()));
            data.put("settledTime", row.getSettledTime());
            data.put("settleNote", row.getSettleNote());
            data.put("warning", "该订单已不在站间台账里（可能刚被取消），请刷新确认");
            return data;
        }
        return decorate(fresh.get(0), stationId, loadStationNames(fresh));
    }

    private Map<Long, String> loadStationNames(List<Map<String, Object>> live) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : live) {
            ids.add(longOf(row.get("payStationId")));
            ids.add(longOf(row.get("settleStationId")));
            ids.add(longOf(row.get("ownerStationId")));
        }
        return queryStationNames(ids);
    }

    /** 台账实体版的站名收集（「已结清但依据已消失」那批用）。 */
    private Map<Long, String> loadStationNamesOf(List<InterStationSettlement> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (InterStationSettlement row : rows) {
            ids.add(row.getFromStationId());
            ids.add(row.getToStationId());
        }
        return queryStationNames(ids);
    }

    private Map<Long, String> queryStationNames(Set<Long> ids) {
        ids.remove(null);
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (Map<String, Object> s : mapper.stationNames(new ArrayList<>(ids))) {
            names.put(longOf(s.get("id")), s.get("name") == null ? null : String.valueOf(s.get("name")));
        }
        return names;
    }

    private String stationLabel(Long stationId) {
        if (stationId == null) {
            return "别的站";
        }
        List<Map<String, Object>> rows = mapper.stationNames(List.of(stationId));
        return rows.isEmpty() || rows.get(0).get("name") == null
                ? ("水站#" + stationId) : String.valueOf(rows.get(0).get("name"));
    }

    /** 结清凭据说明落 `varchar(200)`：超长会 1406，当场截断并留日志，别让一次登记整个回滚。 */
    private String trimNote(String note) {
        if (note == null) {
            return null;
        }
        String n = note.trim();
        if (n.isEmpty()) {
            return null;
        }
        if (n.length() > 200) {
            log.warn("[站间结算] 结清说明超长已截断. len={}", n.length());
            return n.substring(0, 200);
        }
        return n;
    }

    // ===== Map 取值小工具：MyBatis 的 Map 返回里数值类型可能是 Integer/Long/BigDecimal，别强转 =====

    private static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(String.valueOf(o));
    }

    private static Integer intOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(String.valueOf(o));
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(o));
    }
}
