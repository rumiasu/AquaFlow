package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一水票的**平台预设档**（v54）：「档位由站长配置、平台给预设档（10/20/100 张）」。
 *
 * <p>本类盯两件事：</p>
 * <ol>
 *   <li><b>建议值算得对、依据说得清</b>：基准是"本站最便宜的一桶水的钱"（走 {@code PriceUtil}
 *       的 TICKET 那一级，与客户用票抵扣时订单水费同源），没配水票价时依据文案要变成"零售价"；</li>
 *   <li><b>建议值真的能存</b>：把预设档原样 POST 回 <b>真实的</b> {@code POST /api/ticket-packages}，
 *       必须成功、且服务端算出的均价与预设下发的均价逐字相等 ——
 *       这正是"一键填入"的价值所在，光断言数字不算证明。
 *       本仓对"结算页一个价、下单另一个价"这类双轨有明确记录，所以这里也调真端点而不是只看字段。</li>
 * </ol>
 *
 * <p>⚠️ 预设档是**草稿**：本端点只读，不会替站长建档位 —— 因为"本站有上架的 {@code product_id=0} 档位"
 * 就是统一水票的站级开关，自动落行等于替所有水站开通一个折扣工具。</p>
 */
@DisplayName("v54 · 统一水票平台预设档")
class TicketPackagePresetIntegrationTest extends AbstractIntegrationTest {

    private static final String PRESETS = "/api/ticket-packages/presets";

    @Test
    @DisplayName("按本站最便宜的一桶水折算三档；原样存回后均价与预设逐字相等")
    void presetsAreSaveableAsIs() {
        long station = createStation("预设档站");
        long manager = createStaff("预设档站长", "STATION_MANAGER", station, 1);
        // 两个桶装水：贵的水票价 20.00，便宜的只配零售价 12.00 → 基准应取便宜的 12.00，
        // 且依据是"零售价"（混合情况下按**胜出的那个商品**判定，不是"本站有没有配过水票价"）
        long pricey = createProduct("预设档贵水", 1, "22.00", "50.00", 1, "20.00");
        long cheap = createProduct("预设档便宜水", 1, "12.00", "50.00", 0, "0.00");
        createInventoryFull(station, pricey, 100, 1, "20.00");
        createInventoryFull(station, cheap, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api res = get(PRESETS, mgr);
        assertEquals(0, res.code(), "站长应能取到预设档: " + res);
        assertEquals(0, new BigDecimal("12.00").compareTo(res.data().path("baseUnitPrice").decimalValue()),
                "基准 = 本站最便宜的一桶水（12.00，不是贵水的 20.00 水票价）");
        assertEquals("本站已上架桶装水的最低零售价（还没配水票价）",
                res.data().path("basePriceBasis").asText(),
                "依据必须按胜出的那个商品判定：最便宜的那桶没配水票价");
        assertEquals(0, res.data().path("productId").asLong(), "预设档是站级通用票（productId=0）的档位");
        assertEquals(3, res.data().path("presets").size(), "10 / 20 / 100 三档");
        assertEquals(10, res.data().path("presets").get(0).path("qty").asInt());
        assertEquals("9.5 折", res.data().path("presets").get(0).path("discountText").asText());
        assertEquals("10 张 9.5 折", res.data().path("presets").get(0).path("title").asText());
        assertEquals(0, new BigDecimal("114.00").compareTo(
                        res.data().path("presets").get(0).path("price").decimalValue()),
                "12.00 × 10 × 0.95 = 114.00");
        assertEquals(0, new BigDecimal("11.40").compareTo(
                        res.data().path("presets").get(0).path("unitPrice").decimalValue()));

        // ===== 关键：把预设档原样存回去，必须成功且均价一字不差 =====
        for (int i = 0; i < 3; i++) {
            var p = res.data().path("presets").get(i);
            String body = "{\"productId\":0,\"qty\":" + p.path("qty").asInt()
                    + ",\"price\":" + p.path("price").asText()
                    + ",\"title\":\"" + p.path("title").asText() + "\"}";
            Api saved = post("/api/ticket-packages", mgr, body);
            assertEquals(0, saved.code(), "预设档必须能原样保存（第 " + i + " 档）: " + saved);
            assertEquals(0, p.path("unitPrice").decimalValue().compareTo(
                            saved.data().path("unitPrice").decimalValue()),
                    "服务端算出的均价必须与预设下发的均价逐字相等（第 " + i + " 档）—— "
                            + "预设 " + p.path("unitPrice").asText() + " vs 存档 " + saved.data().path("unitPrice").asText());
            assertEquals(0, p.path("price").decimalValue().compareTo(
                            saved.data().path("price").decimalValue()), "总价也必须一致");
        }

        // 三档都已上架 → 本站的统一水票就算开通了（判据是"有没有上架的 product_id=0 档位"）
        assertEquals(3, get("/api/ticket-packages?stationId=" + station + "&productId=0", mgr).data().size(),
                "存完后客户端应能看到 3 个在售的统一票档位（= 本站已开通统一票）");

        // 预设档本身不给重复：再取一次还是三档（不因为已有档位而变化）
        assertEquals(3, get(PRESETS, mgr).data().path("presets").size());
    }

    @Test
    @DisplayName("本站没有已上架桶装水 → 不给建议（空数组 + 依据为空），绝不凭空捏一个数")
    void noBarrelOnShelfGivesNoSuggestion() {
        long station = createStation("无桶预设站");
        long manager = createStaff("无桶预设站长", "STATION_MANAGER", station, 1);
        // 只有瓶装水：统一票只抵桶装水，拿瓶装水的价当"一桶水的钱"会把基准拉低到离谱
        long bottle = createProduct("无桶站瓶装水", 2, "2.00", "0.00", 0, "0.00");
        createInventoryFull(station, bottle, 100, 0, "0.00");
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        Api res = get(PRESETS, mgr);
        assertEquals(0, res.code(), "没有桶装水不是错误，只是没有建议: " + res);
        assertEquals(0, res.data().path("presets").size(), "取不到基准价时 presets 必须是空数组");
        assertTrue(res.data().path("baseUnitPrice").isNull(), "基准价应为 null");
        assertTrue(res.data().path("basePriceBasis").isNull(), "依据应为 null（前端据此提示「请自己填价」）");

        // 没上架（enabled=0）的桶装水同样不算数
        long offShelf = createProduct("无桶站未上架桶装水", 1, "12.00", "50.00", 0, "0.00");
        jdbc.update("INSERT INTO inventory(station_id, product_id, quantity, enabled, ticket_enabled, ticket_price) "
                + "VALUES (?,?,?,0,0,0.00)", station, offShelf, 100);
        assertEquals(0, get(PRESETS, mgr).data().path("presets").size(), "未上架的桶装水不得作为基准");
    }

    @Test
    @DisplayName("预设档是站长定价的辅助信息：顾客令牌调不动（@RequireRole）")
    void customerCannotReadPresets() {
        long station = createStation("预设档越权站");
        createStaff("预设档越权站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("预设档越权客户", "preset-authz-openid");

        assertNotEquals(0, get(PRESETS, customerToken(customer)).code(),
                "顾客不得读取站长的定价辅助信息");
        assertEquals(0, get(PRESETS, staffToken(
                        createStaff("预设档第二个站长", "STATION_MANAGER", station, 1),
                        "STATION_MANAGER", station)).code(),
                "反证：同一个端点用站长令牌必须能通（上面的拒绝不是因为端点坏了）");
    }
}
