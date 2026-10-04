package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.OrderStatus;
import com.example.aquaflow.entity.Address;
import com.example.aquaflow.entity.OrderItem;
import com.example.aquaflow.entity.OrderTransfer;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.AddressMapper;
import com.example.aquaflow.mapper.CustomerMapper;
import com.example.aquaflow.mapper.OrderItemMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.service.BarrelService;
import com.example.aquaflow.service.CustomerRiskService;
import com.example.aquaflow.service.DeliveryConsoleService;
import com.example.aquaflow.service.InventoryReservationService;
import com.example.aquaflow.service.OrderWorkflowService;
import com.example.aquaflow.util.CustomerProfileMask;
import com.example.aquaflow.util.StationUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工端配送控制台只读投影的实现（F-18：由 {@code DeliveryController} 原样搬出）。
 *
 * <p>搬动原则是<b>纯搬不改行为</b>：方法体、判据与文案逐字保持，只是换了宿主。
 * 唯一的结构性变化是原先散在控制器里的私有助手（{@code maskCrossStationProfiles} /
 * {@code attachCustomerRisk} / {@code feeInfoOf} / {@code loadStationNames} /
 * {@code checkProductMatch} / {@code staleDispatchHint} / {@code decorateReturnPlan}）
 * 现在是本类的私有方法。</p>
 *
 * <p>⚠️ <b>跨租户可见面的红线在这里</b>：{@code listPoolOrders} 与 {@code listDirectedIncoming}
 * 下发给别站站长的只有「钱货去向」文案与订单快照金额，<b>不带</b>归属站成本 / 库存 / 联系方式，
 * <b>不带</b>客户画像（{@code customerName} / {@code customerPhone}）。新增字段必须逐个过一遍
 * "这是不是 A 站的敏感信息"（AGENTS §1.1）。</p>
 */
@Service
public class DeliveryConsoleServiceImpl implements DeliveryConsoleService {
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.BarrelBusinessPolicy barrelPolicy;
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.BarrelLedgerService barrelLedger;
    @org.springframework.beans.factory.annotation.Autowired private com.example.aquaflow.service.DispatchAgreementService dispatchAgreements;
    @Autowired private com.example.aquaflow.service.OrderBarrelPurchaseService orderBarrelPurchases;

    /** 见 {@link DeliveryConsoleService} 类注释：时间源照搬原状，未纳入 F-16 的 BusinessTime 改造。 */
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductMapper productMapper;

    /** 抢单池 / 指定外派（别站指定本店）要下发「定价来源站名」，站名从这里取（见 loadStationNames 的批量做法）。 */
    @Autowired
    private StationMapper stationMapper;

    /**
     * 「外派久未接单」的阈值分钟数（见 {@link #staleDispatchHint}）。
     *
     * <p>[2026-09-29 已拍板，原 {@code docs/design/31} §8.4 第 1 问] 产品定为<b>分钟级三档
     * 10 / 30 / 60（默认 60）</b>，按 {@code aquaflow.dispatch.stale-minutes} 配置。
     * 原 {@code stale-hours:4}（4 小时）作废 —— 桶装水是半天时效，外派 4 小时才提示等于当天已经送不到了。
     * ≤0 = 本条不启用（同"0 关掉"的既有约定，而不是"0 分钟后就提示"—— 那会让每张刚外派的单立刻变提示）。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${aquaflow.dispatch.stale-minutes:60}")
    private int dispatchStaleMinutes;

    @Autowired
    private OrderWorkflowService orderWorkflowService;

    @Autowired
    private AddressMapper addressMapper;

    /**
     * 给站长端订单列表打**客户信用标记**（黄=有挂账 / 红=逾期或超额度）。
     *
     * <p>它只读、只算，不写任何业务表 —— 所以不违反"Controller 不得注入 Mapper"的契约。
     * 判据只有一份实现（{@link CustomerRiskService#summarizeStation}），本类不再抄一遍。</p>
     */
    @Autowired
    private CustomerRiskService customerRiskService;

    /**
     * 只读投影：订单详情里的「备货情况」（已备齐 / 还缺哪些商品，契约工作包 C4）。
     * <p>它只读、只算、不写任何业务表 —— 与上面的 customerRiskService 同理；
     * 分配与出库仍然只走服务层。</p>
     */
    @Autowired
    private InventoryReservationService inventoryReservationService;

    /**
     * 只读投影：完成配送页的「核对回桶」各行该默认填几（[2026-09-26]）。
     *
     * <p>默认值属于**口径**（本单新买押金的桶不参与回收、旧桶才默认回），只能由后端算，
     * 实现与判据在 {@link BarrelService#returnPlanOfOrder}。</p>
     */
    @Autowired
    private BarrelService barrelService;

    // 注：本类的前身（DeliveryController）曾直接注入 OrderTransferMapper（转单状态）与
    // BarrelLedgerService（桶权益总账）直写那两张表，已按「控制器不得触碰业务表」的契约移入
    // OrderWorkflowService。新增写操作请调 orderWorkflowService，**不要在这里加 Mapper 写方法** ——
    // 那会绕开状态机与账本写入口。

    /* ==================== 配送员自助面 ==================== */

    @Override
    public List<Orders> listAssignedToMe(Long staffId) {
        // 配送员待接单：分配给我但 status 仍为 1 的订单
        return maskCrossStationProfiles(orderMapper.listAssignedToStaff(staffId));
    }

    @Override
    public List<Orders> listDelivering(Long staffId) {
        return maskCrossStationProfiles(
                orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING));
    }

    @Override
    public List<Orders> listCompletedToday(Long staffId) {
        return maskCrossStationProfiles(orderMapper.listByDeliveryStaffIdAndDate(
                staffId, OrderStatus.COMPLETED, java.time.LocalDate.now()));
    }

    @Override
    public List<Orders> listBarrelRecords(Long staffId) {
        return maskCrossStationProfiles(orderMapper.listBarrelRecords(staffId));
    }

    @Override
    public List<Orders> listHistory(Long staffId) {
        return maskCrossStationProfiles(
                orderMapper.listHistoryByDeliveryStaffId(staffId, OrderStatus.COMPLETED));
    }

    @Override
    public List<Orders> listIncomingTransfers(Long staffId) {
        // 与待接单同一口径：转给我的单可能是跨站履约单（配送员之间转手也能转到它）
        return maskCrossStationProfiles(orderMapper.listIncomingTransfers(staffId));
    }

    @Override
    public Map<String, Object> todayStats(Long staffId, Long stationId) {
        Map<String, Object> stats = new HashMap<>();
        var completed = orderMapper.listByDeliveryStaffIdAndDate(
                staffId, OrderStatus.COMPLETED, java.time.LocalDate.now());
        stats.put("completedCount", completed.size());
        var delivering = orderMapper.listByDeliveryStaffId(staffId, OrderStatus.DELIVERING);
        stats.put("deliveringCount", delivering.size());
        int totalReturn = completed.stream()
                .mapToInt(o -> o.getReturnBucketQty() != null ? o.getReturnBucketQty() : 0)
                .sum();
        stats.put("returnBarrels", totalReturn);
        // 待收款订单数：以前端读 stats.unpaidOrders，但后端从未下发该字段，看板恒显示 0。
        // 改为后端按 payment_status / payment_method 真实统计（水票视同已付，不计入）。
        // ⚠️ 页面侧目前没有消费方（「我的」那三个数是按角色取的），但**不能删**：
        // `OrderSettleStationIntegrationTest.unpaidCount()` 拿它当"待收款口径"的探针（4 处断言）。
        stats.put("unpaidOrders", stationId == null ? 0 : orderMapper.countUncollected(stationId));
        // [2026-09-19 删除] pendingCount（本站 status=1 的单数）：
        // 唯一消费方是「我的」页的「待配送」格，该格已在统计卡按角色分叉时撤掉（配送页本身就是那个页签）。
        // 它每次都要跑一遍 listStationPending 只为了取 .size()，而且**站级口径混在一个按人统计的响应里**，
        // 正是口径混淆的温床。证据与核实过程见 docs/audit/history/review/2026-09-16-死端点评估.md「删除登记表」#9。
        return stats;
    }

    @Override
    public Orders findOrder(Long orderId) {
        return orderMapper.getById(orderId);
    }

    @Override
    public Orders orderDetail(Long orderId) {
        Orders order = orderMapper.getById(orderId);
        if (order == null) {
            return null;
        }
        order.setItems(orderItemMapper.listByOrderId(orderId));
        // 回桶行（[2026-09-26]）：标明哪些明细是桶装水、并给出**默认回桶数**。
        // 完成页据此只对桶装水画回桶步进器 —— 以前它对每条明细都画，默认值还等于送出数，
        // 于是瓶装水那行也会被提交成"回桶 N"，撞上桶账的物理上限（占用 0）——
        // 报错是"回收空桶数(2)超过该客户当前持有数(0)"，配送员看不懂，混合单更是必踩。
        // 归属站取 order.getStationId()：客户资产（权益/over）认**归属站**，不是履约站。
        decorateReturnPlan(order);
        // 「待我确认的转单」由登录人判定（前端此前读的 isTransferTarget 后端并不存在），
        // 所以这里只回填事实，判定留给控制器（它拿得到 AuthContext）。
        // [2026-09-29 清单2] 详情是单条 getById，不带 order_transfer 子查询，转单状态只能靠
        // special_note 文本回退 —— 而转让实际写入的标记是 [转让待确认]，与回退判据 [转让]
        // 对不上 ⇒ 发起人打开自己的详情永远看不到「转单中」，撤回入口也无处可挂。
        // 这里按结构化记录回填（顺序：先回填 kind，再判 transferTarget，它要读 transferKind）。
        OrderTransfer pendingTransfer = orderWorkflowService.pendingTransferOf(orderId);
        if (pendingTransfer != null) {
            order.setTransferPendingKind(pendingTransfer.getKind());
            // sub_kind 同源回填：详情页「撤回转单」只对 TRANSFER 挂（退回站长/取消申请各有各的动作）
            order.setTransferPendingSubKind(pendingTransfer.getSubKind());
        }
        order.setTransferTarget(isTransferTarget(order, pendingTransfer));
        // 备货情况（契约工作包 C4）：配送员出发前要知道"这单备齐了没、还缺哪些商品"。
        // 口径 = 凭据上的需求快照 − 已预留（**不是** inventory.quantity），只读、不下发他站数据；
        // 它只是提示 —— 真正拦住"少扣一点先把单结了"的是完成配送时那次出库校验。
        order.setStockPrep(inventoryReservationService.prepInfoOfOrder(orderId));
        order.setDispatchAgreement(dispatchAgreements.info(orderId));
        order.setIndependentBusinessRules(barrelPolicy.isEnabled() || barrelLedger.independentOrder(orderId));
        // 仅详情下发凭据存在事实；前端据 needCollect 提示先收齐现金，不改后端交桶守卫。
        order.setHasOrderBarrelPurchase(orderBarrelPurchases.hasPurchase(orderId));
        // 楼层 / 电梯：送货的人要知道这一单要不要上楼。
        // orderMapper.getById 是纯 orders 查询（不带 address 关联），所以在这里补一次读；
        // 取的是**当前地址**的值而不是下单快照 —— 详见 Orders.addressFloor 的字段注释。
        if (order.getAddressId() != null) {
            Address addr = addressMapper.getById(order.getAddressId());
            if (addr != null) {
                order.setAddressFloor(addr.getFloor());
                order.setAddressHasElevator(addr.getHasElevator());
            }
        }
        // 历史订单两个数（2026-09-27 真机反馈「刚刚配送过，还显示近一年配送 0 次」）：
        // 模板里 `{{order.historyCount || 0}}` / `{{order.lastOrderDays || 0}}` 一直在渲染，
        // 而后端**从来没下发过这两个字段** ⇒ 恒等于 0。口径与文案都在服务端定：
        //   · 近一年配送 = 近 365 天**送到过**的单数（含 status=3 已送达，见 Orders#historyCount）；
        //   · 最近一次 = 最近一次下单距今天数。
        // 站别一律用结算站口径（coalesce(settle, delivery, station)），跨站外派出去的单不该算给别站。
        if (order.getCustomerId() != null) {
            Long statStation = StationUtil.settleStation(order);
            if (statStation != null) {
                order.setHistoryCount(customerMapper.countDeliveredOrdersWithinDays(
                        order.getCustomerId(), statStation, 365));
                java.time.LocalDateTime last = customerMapper.getLastOrderTime(order.getCustomerId(), statStation);
                order.setLastOrderDays(last == null ? null
                        : (int) java.time.temporal.ChronoUnit.DAYS.between(last.toLocalDate(), java.time.LocalDate.now()));
            }
        }
        // [2026-09-26] 列表已遮蔽客户档案，但详情此前漏了这一步；履约权限不等于读取归属站客户画像的权限。
        // 保留 receiverName/receiverPhone 等订单快照供配送联系客户，只清除关联客户档案字段。
        CustomerProfileMask.maskIfCrossStation(order);
        return order;
    }

    /**
     * 把「回桶计划」贴到订单明细上（只读投影，不落库）：
     * 桶装水明细 → {@code barrelItem=true} + {@code suggestedReturnQty=默认回收数}；
     * 其余明细（瓶装水 / 饮水机）→ {@code barrelItem=false}、默认值为 null，完成页不为它们画回桶行。
     *
     * <p>口径的唯一实现在 {@code BarrelService#returnPlanOfOrder}（本方法只做搬运）。</p>
     */
    private void decorateReturnPlan(Orders order) {
        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) return;
        Map<Long, Integer> suggestedByItem = new HashMap<>();
        for (BarrelService.ReturnPlanItem row
                : barrelService.returnPlanOfOrder(order.getId(), order.getCustomerId(), order.getStationId())) {
            if (row.getOrderItemId() != null) {
                suggestedByItem.put(row.getOrderItemId(), row.getSuggestedQty());
            }
        }
        for (OrderItem it : items) {
            Integer suggested = it.getId() == null ? null : suggestedByItem.get(it.getId());
            it.setBarrelItem(suggested != null);
            it.setSuggestedReturnQty(suggested);
        }
    }

    /* ==================== 站长控制台面 ==================== */

    @Override
    public List<Orders> listPendingByStation(Long stationId) {
        return orderMapper.listPendingByStationId(stationId);
    }

    @Override
    public List<Orders> listStationPendingUnassigned(Long stationId) {
        // 只返回未分配配送员的待分配订单
        List<Orders> rows = orderMapper.listStationPendingUnassigned(stationId);
        maskCrossStationProfiles(rows);
        // [2026-09-21] 给每行附上**客户信用标记**，站长端据此上色：
        // 黄 = 有挂账；红 = 已逾期或**超额度**（2026-09-22 起列表也算额度，
        // 与客户详情页的等级逐字同源，见 CustomerRiskService.levelOf）。
        attachCustomerRisk(stationId, rows);
        return rows;
    }

    @Override
    public List<Orders> listStationByStatus(Long stationId, int status) {
        return orderMapper.listByStationIdAndStatus(stationId, status);
    }

    @Override
    public List<Orders> listStationTransferred(Long stationId) {
        return orderMapper.listTransferredOrders(stationId);
    }

    @Override
    public List<Orders> listStationReturn(Long stationId) {
        return orderMapper.listStationReturnOrders(stationId);
    }

    @Override
    public Map<String, Object> crossStationSummary(Long stationId) {
        List<Orders> rows = maskCrossStationProfiles(orderMapper.listCrossStationOrders(stationId));
        java.math.BigDecimal amountTotal = java.math.BigDecimal.ZERO;
        int pending = 0;
        int delivering = 0;
        int done = 0;
        if (rows != null) {
            for (Orders o : rows) {
                if (o.getTotalAmount() != null) {
                    amountTotal = amountTotal.add(o.getTotalAmount());
                }
                int st = o.getStatus() != null ? o.getStatus() : 0;
                if (st == OrderStatus.PENDING) {
                    pending++;
                } else if (st == OrderStatus.DELIVERING || st == OrderStatus.DELIVERED) {
                    delivering++;
                } else if (st == OrderStatus.COMPLETED) {
                    done++;
                }
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("count", rows == null ? 0 : rows.size());
        data.put("amountTotal", amountTotal);
        data.put("pending", pending);
        data.put("delivering", delivering);
        data.put("completed", done);
        data.put("orders", rows == null ? new ArrayList<>() : rows);
        return data;
    }

    @Override
    public Map<String, Object> pendingApprovals(Long stationId) {
        Map<String, Object> data = new HashMap<>();
        data.put("customer", orderMapper.listPendingCustomerCancelRequests(stationId));
        data.put("station", orderMapper.listTransferredOrders(stationId));
        return data;
    }

    /**
     * 给一批订单附上"这个客户欠不欠钱、是不是企业"—— 站长端列表据此上色。
     *
     * <p>⚠️ <b>一次批量查，不要逐行查</b>：列表可能有几十行，逐行算风险就是几十次 SQL
     * （本仓"列表页 N+1"的老坑）。判据本身只有一份实现，在 {@code CustomerRiskService.summarizeStation}。</p>
     *
     * <p>⚠️ 这些是**本站**的待分配单，不受 {@link CustomerProfileMask}（跨站不下发画像）影响；
     * 但反过来，**跨站外派 / 抢单池那两张列表绝不能调本方法** ——
     * "这个客户欠多少钱"是归属站的经营信息，下发给别站就是跨租户泄露（AGENTS §1.1 的可见面）。</p>
     */
    private void attachCustomerRisk(Long stationId, List<Orders> rows) {
        if (stationId == null || rows == null || rows.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> summary = customerRiskService.summarizeStation(stationId);
        for (Orders o : rows) {
            if (o.getCustomerId() == null) {
                continue;
            }
            Map<String, Object> s = summary.get(o.getCustomerId());
            if (s == null) {
                // 该客户在本站没有未结赊账 —— 明确置成"正常"，别留 null 让前端猜
                o.setCustomerRiskLevel(CustomerRiskService.NORMAL);
                o.setCustomerRiskLevelText(CustomerRiskService.textOf(CustomerRiskService.NORMAL));
                o.setCustomerRiskNote("没有未结欠款");
                o.setOutstandingCredit(java.math.BigDecimal.ZERO);
                o.setOverdueDays(0);
                continue;
            }
            o.setCustomerRiskLevel(String.valueOf(s.get("level")));
            // 徽标文案与那句话都直接用服务端算好的，**前端不自己拼也不自带映射表**
            // （同一句"欠了多少 / 逾期几天"若前端再拼一次，列表与详情迟早说成两句不同的话）。
            o.setCustomerRiskLevelText(String.valueOf(s.get("levelText")));
            o.setCustomerRiskNote(String.valueOf(s.get("note")));
            o.setOutstandingCredit((java.math.BigDecimal) s.get("outstandingCredit"));
            o.setOverdueDays((Integer) s.get("overdueDays"));
        }
    }

    /**
     * 抹掉一批订单里**跨站行**的客户画像字段 —— 口径与唯一实现见 {@link CustomerProfileMask}。
     *
     * <p>用在「同一张表里混着本站单与他站履约单」的列表上：只抹跨站行，本站自己的单照常显示客户姓名
     * （那是本站客户，站长与配送员本来就该看到）。**整表都是别站客户**的列表
     * （抢单池 / 指定外派：别站指定本店）不走这里，它们在各自端点里无条件置 null。</p>
     *
     * <p>⚠️ 配送员侧的列表也要过这一道：跨站单一旦被抢单/接收，它就以
     * {@code delivery_staff_id = 本站配送员} 的形态出现在任务、历史、回桶记录里 ——
     * 只在池子与外派两个入口堵，等于"认领前看不到、认领后就看到了"。</p>
     */
    private List<Orders> maskCrossStationProfiles(List<Orders> rows) {
        if (rows != null) {
            for (Orders o : rows) {
                CustomerProfileMask.maskIfCrossStation(o);
            }
        }
        return rows;
    }

    /* ==================== 跨站外派面 ==================== */

    @Override
    public List<Map<String, Object>> listPoolOrders(Long stationId) {
        List<Orders> poolOrders = orderMapper.listPoolOrders(stationId);

        // 获取本站所有商品（用于匹配）
        List<Product> stationProducts = productMapper.listByStationId(stationId);

        // 站名映射：**一次查全表**再按 id 取，不按订单逐条查 station（池里 N 单就有 N 次查询，
        // 而 station 是小表：一次 listAll 的成本远低于 N 次 getById）。见 loadStationNames。
        Map<Long, String> stationNames = loadStationNames();

        // 为每个订单计算商品匹配结果
        List<Map<String, Object>> result = new ArrayList<>();
        for (Orders order : poolOrders) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("id", order.getId());
            // 客户画像（姓名/手机号）**刻意不给**：池子是跨租户可见面，这些值来自 `left join customer`，
            // 是**归属站**的客户画像。这里选择「保留键、显式置 null」而不是「不 put」：
            // ① 另一个列表 /orders/directed-incoming 直接下发实体、只能靠置 null 表达"后端不给"，
            //    两处保持同一形状（键在、值为 null），前端对同一种情况只需一套判断；
            // ② 键仍在，能区分"后端刻意不下发"与"客户端拿着旧版后端"。
            // 前端顺序取值 `customerName || receiverName`，置 null 后自然回落到订单收件人。
            orderData.put("customerName", null);
            orderData.put("customerPhone", null);
            orderData.put("receiverName", order.getReceiverName());
            orderData.put("receiverPhone", order.getReceiverPhone());
            orderData.put("addressDetail", order.getAddressDetail());
            orderData.put("addressSnapshot", order.getAddressSnapshot());
            orderData.put("quantity", order.getQuantity());
            orderData.put("createTime", order.getCreateTime());

            // 金额与钱去向：快照值原样下发 + 定价来源站名 + 后端文案（前端不做算术、不编文案）
            orderData.putAll(feeInfoOf(order, stationId, stationNames, true));

            // 风险提示（后端唯一来源）：非 null 表示本单涉押金/桶权益。**正常路径下池里不该有这种单**
            // （入池的两个入口都在 OrderWorkflowServiceImpl 里硬拦了），这里下发是为了让"规则上线前
            // 放进池里的历史单"在列表上就能看到提示，而不是等站长点完抢单才被拒。
            orderData.put("crossStationRiskNote", orderWorkflowService.crossStationRiskNote(order));

            // 从 order_item 获取商品名称（一单可能有多商品，取第一个）
            String orderProductName = "";
            List<OrderItem> items = order.getItems();
            if (items != null && !items.isEmpty()) {
                orderProductName = items.get(0).getProductNameSnapshot();
                orderData.put("productName", orderProductName);
            }

            // 商品匹配
            Map<String, String> matchResult = checkProductMatch(orderProductName, stationProducts);
            orderData.put("productMatch", matchResult);

            result.add(orderData);
        }
        return result;
    }

    @Override
    public List<Orders> listDispatchTracking(Long stationId) {
        List<Orders> dispatched = orderMapper.listDispatchedOrders(stationId);
        if (dispatched != null && !dispatched.isEmpty()) {
            Map<Long, String> stationNames = loadStationNames();
            for (Orders o : dispatched) {
                o.setDispatchKind(com.example.aquaflow.constant.DispatchKind.ofNote(o.getSpecialNote()).name());
                Long to = o.getDeliveryStationId();
                if (to != null && !to.equals(stationId)) {
                    o.setDeliveryStationName(stationNames.get(to));
                }
                o.setDispatchStaleHint(staleDispatchHint(o));
            }
        }
        return dispatched;
    }

    @Override
    public List<Orders> listDirectedReturns(Long stationId) {
        return orderMapper.listDirectedReturns(stationId);
    }

    @Override
    public List<Orders> listDirectedIncoming(Long stationId) {
        List<Orders> incoming = orderMapper.listDirectedIncoming(stationId);
        attachFeeInfo(incoming, stationId, false);
        if (incoming != null) {
            // 整表都是别站客户 → 无条件抹（不像混着本站单的列表那样逐行判跨站）
            for (Orders o : incoming) {
                CustomerProfileMask.mask(o);
            }
        }
        return incoming;
    }

    /**
     * 给「指定外派（别站指定本店）」列表（{@code Orders} 直出，不像抢单池那样映射成 Map）补上金额与去向信息。
     * 抢单池走 Map（它还要叠商品匹配结果），这里走实体的瞬时字段 —— 两处的
     * {@code feeStationName} / {@code settleNote} / {@code settleToMyStation} 语义必须逐字一致。
     */
    private void attachFeeInfo(List<Orders> orders, Long myStationId, boolean claimContext) {
        if (orders == null || orders.isEmpty()) return;
        Map<Long, String> stationNames = loadStationNames();
        for (Orders o : orders) {
            Map<String, Object> info = feeInfoOf(o, myStationId, stationNames, claimContext);
            o.setDispatchAgreement(dispatchAgreements.info(o.getId()));
            o.setFeeStationName((String) info.get("feeStationName"));
            o.setSettleNote((String) info.get("settleNote"));
            o.setSettleToMyStation((Boolean) info.get("settleToMyStation"));
        }
    }

    /**
     * 下发给「待本站履约」列表（抢单池 / 指定外派）的金额与钱去向信息。
     *
     * <p>字段与来源（<b>全是快照，无一处重算</b>）：</p>
     * <ul>
     *   <li>{@code deliveryFee} / {@code floorFee} / {@code totalAmount} ← {@code orders.delivery_fee}
     *       / {@code orders.floor_fee} / {@code orders.total_amount}（归属站下单那一刻算出并快照的）；</li>
     *   <li>{@code pricingStationId} / {@code feeStationName} ← {@code orders.station_id}（<b>归属站</b>）
     *       查到的站名：跨站单的费用就是按它的站级计费配置算的，所以它是"定价来源站"；</li>
     *   <li>{@code settleNote} ← 后端按语境生成的文案（抢单池是"认领后"，指定外派是"接单后"）；</li>
     *   <li>{@code settleToMyStation} ← 恒 true：能力/权限上这里的单一旦被本站承接，营收就计入本站。</li>
     * </ul>
     *
     * <p>归属站与本站相同时（同站单，例如指定外派被取消后退回、或本站单被误放进列表）把
     * {@code feeStationName} 置空 —— 同站单说"定价来自本站"是废话，界面也少一行噪音。
     * 这里刻意<b>不下发</b>归属站的任何经营信息（成本、库存、站长联系方式都不带），
     * 池子是跨租户可见面，新增字段必须逐个过一遍"这是不是 A 站的敏感信息"。</p>
     */
    private Map<String, Object> feeInfoOf(Orders order, Long myStationId, Map<Long, String> stationNames,
                                          boolean claimContext) {
        Map<String, Object> info = new HashMap<>();
        info.put("dispatchAgreement",dispatchAgreements.info(order.getId()));
        info.put("deliveryFee", order.getDeliveryFee() != null ? order.getDeliveryFee() : java.math.BigDecimal.ZERO);
        info.put("floorFee", order.getFloorFee() != null ? order.getFloorFee() : java.math.BigDecimal.ZERO);
        info.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : java.math.BigDecimal.ZERO);

        Long ownerStationId = order.getStationId();
        String ownerName = ownerStationId == null ? null : stationNames.get(ownerStationId);
        if (ownerName == null) ownerName = "归属站";
        boolean crossStation = ownerStationId != null && myStationId != null && !ownerStationId.equals(myStationId);

        info.put("pricingStationId", ownerStationId);
        info.put("feeStationName", crossStation ? ownerName : null);
        info.put("settleToMyStation", true);
        // 整句话由后端拼好，前端原样展示（前端自拼口径文案 = 本仓禁止的做法，AGENTS §6）。
        info.put("settleNote", claimContext
                ? "认领后本单营收（含配送费/楼层费）计入你站；桶、押金与水票仍记在定价来源站 " + ownerName
                : "接单后本单营收（含配送费/楼层费）计入你站；桶、押金与水票仍记在定价来源站 " + ownerName);
        return info;
    }

    /**
     * 判定订单是否为「转给当前登录人、待其确认」的转单。
     * <p>站间指定退回 -> 归属站站长决策；配送员转单 -> <b>只有接收方</b>看确认条
     * （与 claimTransfer 的口径一致：只有 {@code toStaffId} 能同意）。</p>
     *
     * <p>[2026-09-29 修] 配送员转单旧实现按「本站的人」一刀切，发起人自己也会被判成
     * transferTarget ⇒ 详情条切成「同意转单/拒绝转单」，而 claimTransfer 只认接收方 ——
     * 他点下去待确认记录不会被消解，转单永远悬着（发起人真正该看到的是「撤回转单」）。
     * 现口径：接收方 = 确认条；发起人 = 撤回入口；站长 = 标签 + 可代撤（cancelTransfer 放行）。</p>
     *
     * <p>读 {@code AuthContext}：它回答的是"<b>当前登录人</b>是不是接收方"，
     * 与端点身份天然同源（服务的其余方法都不需要登录态，只有这一处需要）。</p>
     */
    private boolean isTransferTarget(Orders order, OrderTransfer pending) {
        if (!order.getTransferPending()) return false;
        Long staffId = com.example.aquaflow.util.AuthContext.getUserId();
        Long stationId = com.example.aquaflow.util.AuthContext.getStationId();
        if ("DIRECTED".equals(order.getTransferKind())) {
            return stationId != null && stationId.equals(order.getStationId());
        }
        if (pending != null) {
            return staffId != null && staffId.equals(pending.getToStaffId());
        }
        // 兼容：没有结构化记录（历史备注判定出来的「转单中」）时维持旧口径
        if (stationId != null && stationId.equals(StationUtil.deliveryStation(order))) return true;
        return order.getDeliveryStaffId() == null
                || (staffId != null && staffId.equals(order.getDeliveryStaffId()));
    }

    /**
     * 站 id → 站名，一次查完。
     *
     * <p>为什么不用 {@code stationMapper.getById(order.getStationId())}：抢单池是 N 单的列表，
     * 那样等于 N 次查询（N+1）。这里把 station 小表整体取一次（本仓的水站数量是个位数），
     * 在内存里按 id 取。</p>
     */
    private Map<Long, String> loadStationNames() {
        Map<Long, String> names = new HashMap<>();
        List<Station> stations = stationMapper.listAll();
        if (stations == null) return names;
        for (Station s : stations) {
            if (s != null && s.getId() != null) {
                names.put(s.getId(), s.getName());
            }
        }
        return names;
    }

    /**
     * 「外派久未接单」的提示文案；不该提示时返回 null。
     *
     * <p>[2026-09-27] 产品原话：「长时间没人接还是给站长弹提示**是否**按照挂牌价」（{@code docs/design/31} §8.3）。
     * 提示放在**外派追踪列表的这一行**上（不是待办卡：那条动作与这张列表都在首页，而首页是 tabBar 页，
     * 待办卡跳不过去 —— 详见 {@code PendingItem} 里那段撤回说明）。</p>
     *
     * <p><b>判据四条，缺一条都会误报</b>：① 还在待配送(1)（已出车/已送达不用站长操心）；
     * ② {@code delivery_staff_id == null}（还没派到具体的人 —— 这就是"没人接"的可判据形态，
     * 比去解析 {@code special_note} 里的双方确认留痕可靠）；③ 距**最后一次变动**超过阈值；
     * ④ 阈值 &gt; 0（≤0 = 本条不启用，同"0 关掉"的既有约定）。</p>
     *
     * <p>⚠️ <b>是提示，不是自动改价</b>：文案只说"要不要按挂牌价结"，改不改由站长点
     * （改价端点在 {@code ManagerInterStationSettlementController}）。</p>
     *
     * <p>[2026-09-29 已拍板] 「长时间」= 分钟级三档 10 / 30 / 60（默认 60），配在
     * {@code aquaflow.dispatch.stale-minutes}（≤0 不启用）；原待拍板项与 {@code stale-hours}
     * 键一并作废（见 {@link #dispatchStaleMinutes} 字段注释）。</p>
     */
    private String staleDispatchHint(Orders o) {
        if (dispatchStaleMinutes <= 0 || o == null) {
            return null;
        }
        if (o.getStatus() == null || o.getStatus() != OrderStatus.PENDING || o.getDeliveryStaffId() != null) {
            return null;
        }
        if (o.getUpdateTime() == null
                || !o.getUpdateTime().isBefore(java.time.LocalDateTime.now().minusMinutes(dispatchStaleMinutes))) {
            return null;
        }
        return "这单外派出去已超过 " + dispatchStaleMinutes + " 分钟还没人接。"
                + "可以按挂牌价结这一单来提高接单站的收益（改不改由你决定，系统不会自动改）。";
    }

    /**
     * 商品匹配算法：提取品牌关键词进行模糊匹配
     * 返回：{ level: "full"/"partial"/"none", matchName: "匹配的商品名", hint: "提示文案" }
     */
    private Map<String, String> checkProductMatch(String orderProductName, List<Product> stationProducts) {
        Map<String, String> result = new HashMap<>();
        if (orderProductName == null || orderProductName.isEmpty()) {
            result.put("level", "none");
            result.put("hint", "未找到匹配商品");
            return result;
        }

        String orderBrand = extractBrand(orderProductName);

        // 精确匹配
        for (Product p : stationProducts) {
            String stationBrand = extractBrand(p.getName());
            if (orderBrand.equals(stationBrand) || orderBrand.contains(stationBrand) || stationBrand.contains(orderBrand)) {
                result.put("level", "full");
                result.put("matchName", p.getName());
                result.put("hint", "高度匹配");
                return result;
            }
        }

        // 检查是否有同类商品（如都是"桶装水"）
        for (Product p : stationProducts) {
            String stationName = p.getName() != null ? p.getName() : "";
            // 检查是否都包含"桶装水"、"矿泉水"、"纯净水"等关键词
            if (isSameCategory(orderProductName, stationName)) {
                result.put("level", "partial");
                result.put("matchName", p.getName());
                result.put("hint", "疑似匹配，请确认库存");
                return result;
            }
        }

        result.put("level", "none");
        result.put("hint", "未找到匹配商品");
        return result;
    }

    /**
     * 品牌关键词提取：去掉规格、包装等信息
     * "农夫山泉纯净水 550ml×24" → "农夫山泉"
     */
    private String extractBrand(String productName) {
        if (productName == null) return "";
        return productName
                .replaceAll("\\d+[mMlL升L]{1,2}", "")      // 移除容量 550ml, 1.5L
                .replaceAll("\\d+×\\d+", "")                // 移除包装 24×1
                .replaceAll("\\d+\\*\\d+", "")              // 移除包装 24*1
                .replaceAll("(桶装|瓶装|整箱|大桶|小桶)", "") // 移除包装词
                .replaceAll("(纯净水|矿泉水|天然水|饮用水|山泉水|水)", "") // 移除水类型
                .replaceAll("\\s+", "")                      // 移除空格
                .trim();
    }

    /**
     * 判断两个商品是否属于同类（如都是桶装水）
     */
    private boolean isSameCategory(String name1, String name2) {
        String[] categories = {"桶装水", "矿泉水", "纯净水", "天然水", "饮用水", "山泉水"};
        for (String cat : categories) {
            if (name1.contains(cat) && name2.contains(cat)) {
                return true;
            }
        }
        return false;
    }
}
