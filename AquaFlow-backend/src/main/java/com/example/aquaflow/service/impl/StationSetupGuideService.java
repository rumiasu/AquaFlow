package com.example.aquaflow.service.impl;

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

        boolean locationOk = station != null && station.getLat() != null && station.getLng() != null;
        items.add(item("stationLocation", "水站坐标", P0, locationOk, "水站资料",
                "没有坐标就算不出配送距离 —— 你设的「配送范围」会整段失效（超范围单照接）",
                null, locationOk ? null : "到「水站资料」点一下地图选点即可"));

        boolean addressOk = station != null && station.getAddress() != null && !station.getAddress().trim().isEmpty();
        items.add(item("stationAddress", "水站地址", P1, addressOk, "水站资料",
                "客户选站与找路靠它；缺了只显示站名", null, null));

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

        Map<Long, Boolean> ticketProductHasPackage = new LinkedHashMap<>();
        List<String> ticketedWithoutPackage = new ArrayList<>();
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
        boolean ticketOk = ticketedWithoutPackage.isEmpty();
        items.add(item("ticketPackage", "水票档位", P1, ticketOk, "商品与库存",
                ticketedWithoutPackage.isEmpty()
                        ? "没有「开了水票却没配档位」的商品"
                        : "这些商品开了水票但没有档位，客户想买票却看不到价目表：" + String.join("、", ticketedWithoutPackage),
                null, ticketOk ? null : "给开了水票的商品配 10 / 20 / 100 张档位（页面可一键生成建议档位）"));

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
        result.put("p0PendingCount", p0Pending);
        // 汇总文案由后端给出，前端不要自己拼（同本仓"文案由后端下发"的约定）
        result.put("summaryText", p0Pending > 0
                ? "还有 " + p0Pending + " 项必填没配好（" + done + "/" + items.size() + " 项已完成）"
                : "必填项都已配好（" + done + "/" + items.size() + " 项已完成）");
        return result;
    }

    private static Map<String, Object> item(String key, String label, String level, boolean done,
                                           String where, String why, String suggestion, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("level", level);
        m.put("done", done);
        m.put("where", where);
        m.put("why", why);
        m.put("suggestion", suggestion);
        m.put("action", action);
        return m;
    }


}
