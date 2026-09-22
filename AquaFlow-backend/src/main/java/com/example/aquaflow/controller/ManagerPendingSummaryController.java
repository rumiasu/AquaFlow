package com.example.aquaflow.controller;

import com.example.aquaflow.annotation.RequireRole;
import com.example.aquaflow.common.Result;
import com.example.aquaflow.constant.PendingItem;
import com.example.aquaflow.entity.StaffPayroll;
import com.example.aquaflow.entity.StaffStationApplication;
import com.example.aquaflow.mapper.AlertLogMapper;
import com.example.aquaflow.mapper.BarrelRecordMapper;
import com.example.aquaflow.mapper.CustomerEnterpriseApplyMapper;
import com.example.aquaflow.mapper.GrossProfitMapper;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.PaymentRecordMapper;
import com.example.aquaflow.mapper.StaffPayrollMapper;
import com.example.aquaflow.mapper.StaffStationApplicationMapper;
import com.example.aquaflow.service.OrderBarrelExceptionService;
import com.example.aquaflow.service.ReceivableService;
import com.example.aquaflow.util.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长「待办汇总（按紧急程度分级）」：把"有别人递过来的事要处理"合成一次请求。
 * 2026-09-19 新增（起因：申请全靠站长自己一页页翻才知道，没有任何提醒）。
 *
 * <p><b>本类只做编排 + 分级，不写任何聚合 SQL、不新造任何口径</b> —— 与
 * {@link ManagerTodoController} 同一条判据（那边文件头有完整理由）。每个计数都必须是
 * 某个**已经在用的读数入口**的调用结果，否则就会出现"角标说 3、点进去 0 条"这种最难解释的不一致。</p>
 *
 * <p><b>「哪个条目归哪一级」不写在本类里</b> —— 条目目录是 {@link PendingItem}（key / 中文标签 /
 * 默认级别），本类按目录 {@code switch} 逐条算数。调整分级有两条路，**都不需要改前端**：
 * <ol>
 *   <li>改 {@link PendingItem} 里那一行的默认级别；</li>
 *   <li>运行时覆盖：配置 {@code aquaflow.pending.level-overrides.<key>=P0|P1|P2}
 *       （环境变量写法 {@code AQUAFLOW_PENDING_LEVEL_OVERRIDES_<KEY大写>}）。
 *       非法的级别值会被忽略并保留默认值 —— <b>不抛异常</b>：一个配错的键不该把整张待办卡打成空白。</li>
 * </ol></p>
 *
 * <p><b>为什么与 {@code /api/manager/todo-summary} 并存而不是改造它</b>：
 * todo-summary 回答"**该做的经营动作**"（逾期应收 / 未填成本 / 待确认结算单 / 待处理桶异常），
 * 已被首页待办卡消费，改契约会连带动前端；本端点回答另一个问题 ——
 * "**有没有人递了东西过来等我点头**"。两者条目只有部分重叠（桶异常 / 未填成本 / 结算单 / 逾期），
 * 重叠的那几项刻意**沿用同一个读数入口**以保证数字一致。</p>
 *
 * <p>⚠️ 站点一律取自 {@code AuthContext.requireStationId()}，不接受请求参数 ——
 * 待办里含本站的欠款、人事与客户申请，跨站可见等于把经营底细漏给同行。</p>
 *
 * <p>⚠️ <b>{@code level} 是"给不给红点"的唯一依据</b>：前端 tab 红点**只报 P0**；
 * 条目归哪一级只影响红点与展示分组，**不影响它显示不显示**（P1/P2 照样出现在待办卡里）。</p>
 */
@RestController
@RequestMapping("/api/manager/pending-summary")
@RequireRole({"STATION_MANAGER"})
public class ManagerPendingSummaryController {

    /** 桶异常里「配送员已录入、等站长处置」的状态（与 {@link ManagerTodoController} 同一字面量约定）。 */
    private static final String EXCEPTION_PENDING = "STAFF_RECORDED";

    /** 桶流水一次最多读多少条用于计数（见 {@link #countPendingBarrelReturn}）。 */
    private static final int BARREL_SCAN_LIMIT = 500;

    /** 「运营告警」与运营页一致：最多看最近 200 条（{@code ManagerAlertController} 的上限）。 */
    private static final int ALERT_SCAN_LIMIT = 200;

    /**
     * 级别覆盖配置，格式 {@code key:LEVEL,key2:LEVEL2}（如 {@code pendingTransfer:P2}）。
     *
     * <p>环境变量写法 {@code AQUAFLOW_PENDING_LEVEL_OVERRIDES=pendingTransfer:P2}。</p>
     *
     * <p>⚠️ 为什么是"一个逗号分隔的字符串"而不是 Spring 的 {@code Map<String,String>} 注入：
     * 后者对这种"键本身含小驼峰、值只有三个"的场景需要嵌套的 SpEL 默认值写法，
     * 实测**静默不生效**（`PendingLevelOverrideIntegrationTest` 就是为此写的 ——
     * 它第一次跑就红在"配置没生效"上）。显式解析虽然土，但它**可测、看得见**。</p>
     *
     * <p>空、非法项、非法级别**一律忽略**（保留默认级别）：一个配错的键不该把整张待办卡
     * 打成空白，也不该让接口 500。</p>
     */
    @Value("${aquaflow.pending.level-overrides:}")
    private String levelOverridesRaw;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private StaffStationApplicationMapper staffStationApplicationMapper;

    @Autowired
    private CustomerEnterpriseApplyMapper customerEnterpriseApplyMapper;

    @Autowired
    private OrderBarrelExceptionService orderBarrelExceptionService;

    @Autowired
    private BarrelRecordMapper barrelRecordMapper;

    @Autowired
    private ReceivableService receivableService;

    @Autowired
    private StaffPayrollMapper staffPayrollMapper;

    @Autowired
    private AlertLogMapper alertLogMapper;

    @Autowired
    private GrossProfitMapper grossProfitMapper;

    /**
     * 按级别分组的待办（顺序与 {@link PendingItem} 的声明顺序一致）。
     *
     * <p>每项含：{@code key}（稳定标识，前端据此决定点进哪一页 —— 路由是前端的事）、
     * {@code label}（中文由后端下发）、{@code count}、{@code level}、
     * {@code amount}（只有"有金额含义"的项给，其余 null）。</p>
     *
     * <p>响应另给 {@code p0Total} = **P0 里有几条非零**（不是"待办总数"）。
     * 刻意不给"所有待办相加"的数字 —— 单位不同（单/笔/人/张），相加没有业务含义
     * （同 {@link ManagerTodoController} 的理由）。</p>
     */
    @GetMapping
    public Result<Map<String, Object>> summary() {
        Long stationId = AuthContext.requireStationId();

        // 有金额含义的那一项（逾期应收）需要它；只取一次，避免每条各查一遍
        Map<String, Object> ar = receivableService.overview(stationId);
        Map<String, PendingItem.Level> overrides = parseOverrides();

        List<Map<String, Object>> items = new ArrayList<>();
        for (PendingItem def : PendingItem.values()) {
            BigDecimal amount = null;
            int count = switch (def) {
                // —— P0 ——
                // 待分配：与「待办/配送」页 station-pending 同一方法，**同一道推送闸门**
                // （已收款 或 货到付款 —— "没收到钱的单不进站长视野"由该方法自己保证）
                case PENDING_ASSIGN -> orderMapper.listStationPendingUnassigned(stationId).size();
                // 转单类与站内取消申请**同表同 kind、只差 sub_kind**：这里刻意按互补的两个集合切，
                // 而不是用 NOT IN 排除 —— 新增 sub_kind 时排除式会静默算进"转单请求"（见 PendingItem 注释）
                case PENDING_TRANSFER -> orderMapper
                        .listStaffRequestsBySubKinds(stationId, PendingItem.STAFF_TRANSFER_SUB_KINDS).size();
                case STATION_CANCEL -> orderMapper
                        .listStaffRequestsBySubKinds(stationId, List.of(PendingItem.SUB_CANCEL_REQUEST)).size();
                // 客户取消申请：与「审批 → 客户」子页签同一个方法
                case CUSTOMER_CANCEL -> orderMapper.listPendingCustomerCancelRequests(stationId).size();
                // 他站定向外派：与「他站外派」页签同一个方法
                case DIRECTED_INCOMING -> orderMapper.listDirectedIncoming(stationId).size();
                // 待审退桶：barrel_record.status = 1 是"客户已提交、等站长确认收桶"
                case BARREL_RETURN -> countPendingBarrelReturn(stationId);

                // —— P1 ——
                // 待确认收款：与「待确认收款」页同一个 mapper 方法（含线上购票的无订单流水）
                case PENDING_PAYMENT -> paymentRecordMapper.listPendingByStation(stationId, 200).size();
                case OVERDUE_RECEIVABLE -> {
                    amount = decOf(ar.get("overdueAmount"));
                    yield intOf(ar.get("overdueCustomerCount"));
                }
                // 员工绑定/解绑申请：listByStationAndStatus 传 PENDING（与员工页同一个方法）
                case STAFF_BINDING -> staffStationApplicationMapper
                        .listByStationAndStatus(stationId, StaffStationApplication.STATUS_PENDING).size();
                // 企业身份待审：平台总开关关着时该方法返回空列表（不报错）→ 自然为 0
                case ENTERPRISE_APPLY -> customerEnterpriseApplyMapper.listPendingByStation(stationId).size();
                // 待确认结算单：与 todo-summary 同一个 mapper 计数（保证两处一致）
                case DRAFT_PAYROLL -> staffPayrollMapper.countByStatus(stationId, StaffPayroll.Status.DRAFT);

                // —— P2 ——
                // 抢单池：与「抢单池」页签同一个方法。**机会不是义务**，故归 P2
                case POOL_CLAIMABLE -> orderMapper.listPoolOrders(stationId).size();
                // 未填成本：与 todo-summary 同一个方法
                case COST_NOT_FILLED -> grossProfitMapper.listMissingCost(stationId).size();
                // 待处理桶异常：与 todo-summary 同一个服务方法（保证两处一致）
                case BARREL_EXCEPTION -> {
                    OrderBarrelExceptionService.ExceptionQuery q =
                            new OrderBarrelExceptionService.ExceptionQuery();
                    q.setStatus(EXCEPTION_PENDING);
                    q.setPage(1);
                    q.setSize(1);   // 只要 total，不要明细
                    yield (int) orderBarrelExceptionService.listExceptions(stationId, q).getTotal();
                }
                // 运营告警：与「运营告警」页同一个 mapper 方法（**只含 OPERATION**，系统告警不漏给站长）。
                // 用 list 的 size 而不是 count(*) 是有意的：页面上显示的是"最近 N 条"，
                // 计数必须跟站长看得到的那个列表一致
                case OPERATION_ALERT -> alertLogMapper.listStationAlerts(stationId, ALERT_SCAN_LIMIT).size();
            };
            PendingItem.Level lv = overrides.get(def.key());
            items.add(item((lv != null ? lv : def.defaultLevel()).name(), def.key(), def.label(), count, amount));
        }

        int p0Total = (int) items.stream()
                .filter(i -> PendingItem.Level.P0.name().equals(i.get("level")))
                .filter(i -> intOf(i.get("count")) > 0)
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("p0Total", p0Total);
        return Result.success(data);
    }

    /**
     * 解析覆盖配置 → {key: Level}。
     *
     * <p>每次请求解析一次（十几个条目的字符串切分，纳秒级）：值来自启动期属性、本身不会变，
     * 这样写只是省掉"缓存 + 失效"那套复杂度。空、缺冒号、级别非法的项**一律跳过**。</p>
     */
    private Map<String, PendingItem.Level> parseOverrides() {
        if (levelOverridesRaw == null || levelOverridesRaw.isBlank()) {
            return Map.of();
        }
        Map<String, PendingItem.Level> out = new LinkedHashMap<>();
        for (String part : levelOverridesRaw.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) {
                continue;
            }
            PendingItem.Level level = PendingItem.parseLevel(kv[1]);
            if (level == null) {
                continue;
            }
            out.put(kv[0].trim(), level);
        }
        return out;
    }

    /**
     * 「客户已提交、等站长确认收桶」的条数。
     *
     * <p>桶台账没有现成的"按状态计数"方法，这里用已有的 list 方法过滤 —— <b>不新写 SQL</b>。
     * 扫描上限 {@link #BARREL_SCAN_LIMIT}：退桶是低频动作，同时挂着 500 条待审在真实经营里
     * 不可能出现；真到了那个量级，该修的是"没人处理"这件事本身，不是计数。</p>
     */
    private int countPendingBarrelReturn(Long stationId) {
        // ⚠️ status 是「退桶申请状态」，只对 type=2（退桶）有意义 —— 必须连 type 一起过滤。
        // 人工调整(type=6) 等类型也会写 status=1，只按 status 过滤会让这条待办**永久挂着**
        // 一笔"待审退桶"（实测：一条 type=6 记录让该计数从 2 降到 1 后再也降不下去）。
        return (int) barrelRecordMapper.listByStationId(stationId, BARREL_SCAN_LIMIT).stream()
                .filter(r -> r.getType() != null && r.getType() == 2)
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .count();
    }

    private static Map<String, Object> item(String level, String key, String label,
                                           int count, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", level);
        row.put("key", key);
        row.put("label", label);
        row.put("count", count);
        // 金额只给"有金额含义"的那一项，其余为 null —— 前端不必猜哪一项该显示钱
        row.put("amount", amount);
        return row;
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof BigDecimal bd) {
            return bd.intValue();
        }
        return ((Number) v).intValue();
    }

    private static BigDecimal decOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(v.toString());
    }
}
