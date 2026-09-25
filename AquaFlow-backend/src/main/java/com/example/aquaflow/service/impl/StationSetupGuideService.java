package com.example.aquaflow.service.impl;

import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.Inventory;
import com.example.aquaflow.entity.Product;
import com.example.aquaflow.entity.Staff;
import com.example.aquaflow.entity.StaffPieceRate;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.entity.TicketPackage;
import com.example.aquaflow.mapper.InventoryMapper;
import com.example.aquaflow.mapper.ProductMapper;
import com.example.aquaflow.mapper.StaffMapper;
import com.example.aquaflow.mapper.StaffPieceRateMapper;
import com.example.aquaflow.mapper.StationDeliveryConfigMapper;
import com.example.aquaflow.mapper.StationMapper;
import com.example.aquaflow.mapper.TicketPackageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站长「信息完善引导」的**唯一规则目录**（2026-09-19）。
 *
 * <p>产品要求：「所有需要/建议填写的字段，都加一层引导」「商品第一时间引导」「最好都给一些默认字段，
 * 降低填写门槛」。所以这里把散落在各页面的"该填什么"收成一份可遍历的目录 ——
 * **每加一条规则只改这里**，页面只负责渲染后端给的中文（前端禁止自建 key→文案映射，本仓明令）。</p>
 *
 * <p><b>每条规则回答四个问题</b>（都在返回体里，页面直接显示）：</p>
 * <ol>
 *   <li>{@code done} —— 现在完善了没有；</li>
 *   <li>{@code why} —— 不填的**真实后果**（不是"资料不完整"这种废话，而是"配送范围校验整段失效"这种能感知的后果）；</li>
 *   <li>{@code where} —— 去哪填；</li>
 *   <li>{@code suggestion} —— 能给建议值的就给（降低填写门槛），给不出就明确说"需你决定"，**不编一个数**。</li>
 * </ol>
 *
 * <p>⚠️ 与并行工作流的对接：他们做了「待办」目录（{@code constant/PendingItem}）与「水站资料」页。
 * 本类只产出"完善度清单"，**不写待办表、不动他们的文件** —— 待办条目应当由他们把这里的 key 接进目录
 * （见 {@code docs/design/25} §25.6）。</p>
 */
@Service
public class StationSetupGuideService {

    /** 级别：P0 = 不填就等于功能失效；P1 = 影响钱或客户体验；P2 = 锦上添花。 */
    public static final String P0 = "P0";
    public static final String P1 = "P1";
    public static final String P2 = "P2";

    /**
     * 级别 → 给人看的中文（2026-09-23）。`P0`/`P1`/`P2` 是**内部代号**，
     * 直接摆到站长面前等于让他读开发文档；而按本仓约定「展示文案由后端下发」，
     * 这个映射必须留在后端 —— 前端只渲染 `levelText`，**不要自己写 P0→"必填" 的表**。
     */
    private static String levelTextOf(String level) {
        if (P0.equals(level)) {
            return "必填";
        }
        if (P1.equals(level)) {
            return "建议";
        }
        return "可选";
    }

    /**
     * key → 小程序页面路由（待填项"点击直接前往"用，2026-09-23 新增）。
     *
     * <p>为什么放在这里：待填目录是**单一数据源**（见类注释）。原先只下发中文 {@code where}
     * （"水站资料"），前端要做跳转就得自己维护一份 key→路由映射表 —— 与本仓"前端禁止自带映射表"
     * 直接冲突，而且改页面路径时两边必然对不上。</p>
     *
     * <p>⚠️ 路由必须是 {@code miniapp-delivery/app.json} 的 {@code pages} 里**真实存在**的页面；
     *    没有编译期校验，改页面路径时要回来改这里（同时 `app.json`）。取不到就下发空串，
     *    前端据此**不显示「去填写」按钮**（宁可不给入口，也不要点了没反应）。</p>
     */
    private static final Map<String, String> ROUTE_OF = new LinkedHashMap<>();
    static {
        ROUTE_OF.put("stationPhone", "/pages/station-mgmt/station-info/index");
        ROUTE_OF.put("stationPosition", "/pages/station-mgmt/station-info/index");
        ROUTE_OF.put("catalogOnShelf", "/pages/station-mgmt/products/index");
        ROUTE_OF.put("ticketPackage", "/pages/station-mgmt/ticket-packages/index");
        ROUTE_OF.put("deliveryFee", "/pages/station-mgmt/delivery-config/index");
        ROUTE_OF.put("staffPayroll", "/pages/station-mgmt/payroll/index");
        ROUTE_OF.put("stationNotice", "/pages/station-mgmt/station-status/index");
    }

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StationDeliveryConfigMapper stationDeliveryConfigMapper;

    @Autowired
    private StaffMapper staffMapper;

    @Autowired
    private StaffPieceRateMapper staffPieceRateMapper;

    @Autowired
    private TicketPackageMapper ticketPackageMapper;

    /**
     * 站级「统一折扣」档位（v58）。
     *
     * <p>⚠️ 水票档位的判据**必须两套都认**（站级统一折扣 + 按商品的 ticket_package），
     * 见 {@code guide()} 里 ticketPackage 那段的说明 —— 只认一套会让站长"配了还说没配"。</p>
     */
    @Autowired
    private com.example.aquaflow.mapper.StationTicketDiscountMapper stationTicketDiscountMapper;

    /**
     * 本站的完善度清单 + 汇总。返回体里的每个字段都是**给页面直接显示的文案**。
     */
    public Map<String, Object> guide(Long stationId) {
        List<Map<String, Object>> items = new ArrayList<>();
        Station station = stationMapper.getById(stationId);

        // ---- P0：客户看不到 / 找不着 / 规则空转的那几项 ----
        boolean phoneOk = station != null && station.getPhone() != null && !station.getPhone().trim().isEmpty();
        items.add(item("stationPhone", "水站联系电话", P0, phoneOk, "水站资料",
                "客户下单页与退桶流程靠它联系你；缺了会显示成「请咨询客服」，客户找不到人",
                null, phoneOk ? null : "建站时已改为必填；老站请到「水站资料」补一下"));

        // 「水站位置」= 地址 + 坐标**合成一条**（2026-09-23 用户裁定："注册时不是已经设了地址吗，为什么还要再设置选点"）。
        // 为什么合并：建站页与「水站资料」用的都是 wx.chooseLocation —— **一次选点同时产出地址与经纬度**，
        //   拆成两条会让站长以为要重复填两遍。但**库里的两个字段不能合并**：address 是给客户看/找路的文本，
        //   lat/lng 是 GeoUtil 算配送距离的前提（缺坐标 → 配送范围整段失效）。故做成"一条引导项、两处判据"，
        //   并按实际缺哪一半给不同说法 —— 只缺坐标时必须说清"地址已有，不用重填"。
        boolean locationOk = station != null && station.getLat() != null && station.getLng() != null;
        boolean addressOk = station != null && station.getAddress() != null && !station.getAddress().trim().isEmpty();
        String posWhy;
        String posSuggestion;
        if (!addressOk && !locationOk) {
            posWhy = "地址和坐标都还没有：客户找不到你，配送距离也算不出来 —— 「配送范围」会整段失效（超范围单照接）";
            posSuggestion = "到「水站资料」点一下地图选点，地址和坐标一次就都写进去了";
        } else if (!locationOk) {
            posWhy = "有地址但没坐标 —— 算不出配送距离，你设的「配送范围」会整段失效（超范围单照接）";
            posSuggestion = "到「水站资料」点一下地图选点补坐标即可，地址已经有了、不用重填";
        } else {
            posWhy = "有坐标但没地址 —— 客户选站与找路只能看到站名";
            posSuggestion = "到「水站资料」补一下地址（选点时会自动带出来）";
        }
        items.add(item("stationPosition", "水站位置", P0, addressOk && locationOk, "水站资料",
                posWhy, null, (addressOk && locationOk) ? null : posSuggestion));

        List<Inventory> inventory = inventoryMapper.listByStationId(stationId);
        List<Inventory> onShelf = new ArrayList<>();
        for (Inventory inv : inventory) {
            if (inv != null && Integer.valueOf(1).equals(inv.getEnabled()) && inv.getProductId() != null) {
                onShelf.add(inv);
            }
        }
        // 「商品第一时间引导」：没有在架商品，后面的金额门槛/水票/工资都无从谈起
        items.add(item("catalogOnShelf", "上架商品", P0, !onShelf.isEmpty(), "商品与库存",
                "客户看不到任何商品就没法下单；「选品」不等于「上架」，还要填售价与库存",
                null, onShelf.isEmpty() ? "先选 1~2 款常卖的水上架（其余可以后补）" : null));

        boolean feeConfigured = stationDeliveryConfigMapper.getByStationId(stationId) != null;
        items.add(item("deliveryFee", "配送计费", P0, feeConfigured, "配送计费",
                "没保存过 = 起送量 / 配送范围 / 运费 / 楼层费一条都没生效（全按 0 处理）",
                null, feeConfigured ? null : "哪怕本站不收配送费，也去页面点一次保存 —— 保存过才算「明确表示不收」"));

        // ---- P1：影响钱或客户体验 ----
        int deliveryStaff = 0;
        if (stationId != null) {
            List<Staff> staffs = staffMapper.listByStationIdAndRole(stationId, "DELIVERY");
            deliveryStaff = staffs == null ? 0 : staffs.size();
        }
        StaffPieceRate defaultRate = staffPieceRateMapper.getByStationAndProduct(stationId, 0L);
        boolean rateOk = defaultRate != null && defaultRate.getPerBucketAmount() != null;
        items.add(item("staffPayroll", "员工工资结构", P1, deliveryStaff == 0 || rateOk, "工资设置",
                deliveryStaff == 0
                        ? "本站还没有配送员；一旦有人，就要先定工资结构，否则他送的水会按 0 元记工钱"
                        : "有 " + deliveryStaff + " 名配送员，但没配计件单价 —— 他们送的水会按 0 元记工钱（结算单也是 0）",
                null,
                (deliveryStaff > 0 && !rateOk)
                        ? "到「计件工资」页填站级默认单价；若采用固定工资结算，本期先忽略此项（固定工资模式尚未实现，见 docs/design/25）"
                        : null));

        // ⚠️ 水票档位有**两套并存的定价方式**，判据必须两套都认（2026-09-24 修的真实缺陷）：
        //   ① 站级「统一折扣」`station_ticket_discount`（v58）—— 配了上架档位即全站生效
        //      （TicketAccountService 的"统一折扣是否生效"用的就是这个判据）；
        //   ② 按商品的 `ticket_package`。
        //   原来**只认第②套** → 站长在「统一折扣」页里配好了档位，引导仍然说"没配档位"，
        //   于是"设置了、回来还是显示没设置"（产品实际报的问题）。判据分叉比没判据更糟。
        boolean unifiedTicket = stationId != null && stationTicketDiscountMapper.countOnShelf(stationId) > 0;
        Map<Long, Boolean> ticketProductHasPackage = new LinkedHashMap<>();
        List<String> ticketedWithoutPackage = new ArrayList<>();
        if (!unifiedTicket) {
            for (Inventory inv : onShelf) {
                if (inv.getTicketEnabled() == null || !Integer.valueOf(1).equals(inv.getTicketEnabled())) {
                    continue;
                }
                Product p = productMapper.getById(inv.getProductId());
                List<TicketPackage> pkgs = ticketPackageMapper.listOnShelf(stationId, inv.getProductId());
                boolean has = pkgs != null && !pkgs.isEmpty();
                ticketProductHasPackage.put(inv.getProductId(), has);
                if (!has) {
                    ticketedWithoutPackage.add(p != null && p.getName() != null ? p.getName() : ("商品 " + inv.getProductId()));
                }
            }
        }
        boolean ticketOk = ticketedWithoutPackage.isEmpty();
        items.add(item("ticketPackage", "水票档位", P1, ticketOk, "商品与库存",
                ticketOk
                        ? (unifiedTicket
                                ? "本站用的是站级「统一折扣」，价目表已配好"
                                : "没有「开了水票却没配档位」的商品")
                        : "这些商品开了水票但没有档位，客户想买票却看不到价目表：" + String.join("、", ticketedWithoutPackage),
                null, ticketOk ? null
                        : "两种配法任选其一：给这些商品逐个配 10 / 20 / 100 张的档位，"
                          + "或到「水票档位」页改用**站级统一折扣**（一次配好，全站商品共用）"));

        items.add(item("stationNotice", "营业公告", P2, true, "营业状态与公告",
                "歇业/放假时客户能提前知道（软状态，不阻断下单）", null, null));

        int done = 0;
        int p0Pending = 0;
        for (Map<String, Object> it : items) {
            if (Boolean.TRUE.equals(it.get("done"))) {
                done++;
            } else if (P0.equals(it.get("level"))) {
                p0Pending++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("doneCount", done);
        result.put("totalCount", items.size());
        // 未完成项**总数**（含 P1/P2）：状态胶囊上的「待填 N 项」徽标用它，与小框列表的行数一致。
        // ⚠️ 别和 p0PendingCount 混用 —— 那个只数 P0，是「tab 红点亮不亮」的判据（见 PendingItem）。
        result.put("pendingCount", items.size() - done);
        result.put("p0PendingCount", p0Pending);
        // 汇总文案由后端给出，前端不要自己拼（同本仓"文案由后端下发"的约定）
        result.put("summaryText", p0Pending > 0
                ? "还有 " + p0Pending + " 项必填没配好（" + done + "/" + items.size() + " 项已完成）"
                : "必填项都已配好（" + done + "/" + items.size() + " 项已完成）");
        result.put("onlineHint", onlineHintOf(station, p0Pending));
        return result;
    }

    /**
     * 「还差这些」之后那句"下一步做什么"（2026-09-24 产品要求：待填项那里加一句"全部填完可设置上线"）。
     *
     * <p>⚠️ 三种站况必须给**不同**的话，不能一句"配好就能上线"打天下：</p>
     * <ul>
     *   <li><b>已在营业 + 还有必填没配</b>（例：甘泉桶装水）—— 它早就在正常运营了，
     *       对它说"配好就能上线"是**假话**，会让站长以为自己的站还没上线。这时候该说的是
     *       "不影响接单，配好规则才算得准"。</li>
     *   <li><b>待上线 + 还有必填没配</b> —— 这才是产品那句话的主场。</li>
     *   <li><b>必填都配好了</b> —— 直接指向面板底部那个「设置营业状态」按钮（本仓口径：
     *       文案要给出可执行的下一步，不要只说"已完成"）。</li>
     * </ul>
     *
     * <p>营业状态是**软状态**（v32，见 {@code constant.StationOperatingStatus}）：这里只做提示，
     * 文案里**不许**写成"不配好就不能营业"—— 系统从不因待填项阻断下单。</p>
     */
    private static String onlineHintOf(Station station, int p0Pending) {
        if (p0Pending <= 0) {
            return "必填项都配好了 —— 点下面的「设置营业状态」就能上线接单";
        }
        Integer op = station == null ? null : station.getOperatingStatus();
        if (StationOperatingStatus.isPendingLaunch(op)) {
            return "这些必填项配好，就能把营业状态设成「正常运营」上线了";
        }
        // 已经在营业（或状态未知）的站：说实话 —— 不填也能接单，别制造"我还没上线"的错觉
        return "不填也能接单，但配好这些，配送范围和工钱才算得准";
    }

    private static Map<String, Object> item(String key, String label, String level, boolean done,
                                           String where, String why, String suggestion, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("level", level);
        // 人话级别：前端渲染它，不要去渲染 level（P0/P1/P2 是内部代号）
        m.put("levelText", levelTextOf(level));
        m.put("done", done);
        m.put("where", where);
        // 机器可读的跳转目标（见 ROUTE_OF）；取不到下发空串，前端据此不显示「去填写」
        m.put("route", ROUTE_OF.getOrDefault(key, ""));
        m.put("why", why);
        m.put("suggestion", suggestion);
        m.put("action", action);
        return m;
    }


}
