package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存 / 员工 / 商品目录 / 站长商品管家 —— 2026-09-16 之前全部零覆盖（矩阵 C1）。
 *
 * <p>重点是三件容易"看起来能用其实不能用"的事：库存入库必须同时落流水、
 * 员工增删改必须限定在本站、站长商品管家的上架与优先展示（上限 3）必须真的生效。</p>
 */
class InventoryStaffProductIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("库存：站长查本站、入库落流水、顾客一律被拒")
    void inventoryIsManagerOnlyAndInboundWritesRecord() {
        long stationA = createStation("库存站A");
        long stationB = createStation("库存站B");
        long managerA = createStaff("库存站长", "STATION_MANAGER", stationA, 1);
        long customer = createCustomer("库存客户", "inv-openid");
        long product = createProduct("库存水", 1, "10.00", "30.00", 0, "0.00");
        createInventory(stationA, product, 10);

        String mgr = staffToken(managerA, "STATION_MANAGER", stationA);
        String cus = customerToken(customer);

        Api list = get("/api/inventory", mgr);
        assertEquals(0, list.code(), "站长查本站库存: " + list);
        assertEquals(1, list.data().size());

        Api inbound = post("/api/inventory/inbound?stationId=" + stationA, mgr,
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":5}]}");
        assertEquals(0, inbound.code(), "入库: " + inbound);
        assertEquals(15, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                stationA, product), "入库必须真的加库存");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                        + "AND type='INBOUND' AND delta=5", stationA, product),
                "[AQ-029] 入库必须留流水，否则库存与流水对不上");

        assertNotEquals(0, post("/api/inventory/inbound?stationId=" + stationB, mgr,
                "{\"items\":[{\"productId\":" + product + ",\"quantity\":5}]}").code(),
                "不得向别站入库");
        assertEquals(0, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=?", stationB));

        assertEquals(0, get("/api/inventory/records?limit=5", mgr).code(), "库存流水应可查");
        assertTrue(get("/api/inventory/records", mgr).data().isArray());

        assertEquals(401, get("/api/inventory", null).status());
        assertNotEquals(0, get("/api/inventory", cus).code(), "顾客不该读库存");
        assertNotEquals(0, get("/api/inventory/records", cus).code(), "顾客不该读库存流水");
    }

    @Test
    @DisplayName("员工：只能管本站、不能自建站长、增删改与画像全通")
    void staffCrudIsStationScoped() {
        long stationA = createStation("员工站A");
        long stationB = createStation("员工站B");
        long managerA = createStaff("员工站长A", "STATION_MANAGER", stationA, 1);
        long managerB = createStaff("员工站长B", "STATION_MANAGER", stationB, 1);
        long staffB = createStaff("他站配送员", "DELIVERY", stationB, 1);
        long customer = createCustomer("员工客户", "staff-openid");

        String mgrA = staffToken(managerA, "STATION_MANAGER", stationA);
        String mgrB = staffToken(managerB, "STATION_MANAGER", stationB);

        // 建站长 = 自行提权，必须拒绝
        assertNotEquals(0, post("/api/staff", mgrA,
                "{\"name\":\"我自己\",\"phone\":\"13800000000\",\"role\":\"STATION_MANAGER\"}").code(),
                "不得通过员工接口创建站长");
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff WHERE name='我自己'"));

        // status 显式给 1：员工列表 SQL 带 `status = 1`，不传会让新员工建出来就"隐形"
        Api created = post("/api/staff", mgrA,
                "{\"name\":\"新配送员\",\"phone\":\"13800000001\",\"role\":\"DELIVERY\",\"status\":1}");
        assertEquals(0, created.code(), "建配送员: " + created);
        long newStaff = longOf("SELECT id FROM staff WHERE name='新配送员'");
        assertEquals(stationA, longOf("SELECT station_id FROM staff WHERE id=?", newStaff),
                "员工归属强制绑定登录站，请求体伪造不了");

        assertEquals(0, get("/api/staff", mgrA).code());
        assertEquals(2, get("/api/staff", mgrA).data().size(), "列表只含本站（站长本人 + 新员工）");
        assertNotEquals(0, get("/api/staff?stationId=" + stationB, mgrA).code(), "不得列举他站员工");

        assertEquals(0, get("/api/staff/" + newStaff, mgrA).code());
        assertNotEquals(0, get("/api/staff/" + staffB, mgrA).code(), "不得读他站员工");
        assertNotEquals(0, put("/api/staff/" + staffB, mgrA, "{\"name\":\"改名\"}").code(), "不得改他站员工");
        assertNotEquals(0, delete("/api/staff/" + staffB, mgrA).code(), "不得删他站员工");
        assertNotEquals(0, get("/api/staff/" + staffB + "/profile", mgrA).code(), "不得看他站员工画像");

        Api updated = put("/api/staff/" + newStaff, mgrA, "{\"name\":\"改名后\",\"status\":0}");
        assertEquals(0, updated.code(), "改本站员工: " + updated);
        assertEquals("改名后", jdbc.queryForObject("SELECT name FROM staff WHERE id=?", String.class, newStaff));
        assertEquals(0, intOf("SELECT status FROM staff WHERE id=?", newStaff));

        Api profile = get("/api/staff/" + newStaff + "/profile", mgrA);
        assertEquals(0, profile.code(), "员工画像: " + profile);

        assertEquals(0, delete("/api/staff/" + newStaff, mgrA).code());
        assertEquals(0, intOf("SELECT COUNT(*) FROM staff WHERE id=?", newStaff));

        assertEquals(401, get("/api/staff", null).status());
        assertNotEquals(0, get("/api/staff", customerToken(customer)).code(), "顾客不该读员工列表");
    }

    @Test
    @DisplayName("商品目录：旧的全局写端点已删除，站长写入口收敛到 /my-products（归属=本站）")
    void productCatalogEndpoints() {
        long station = createStation("商品站");
        long otherStation = createStation("商品别站");
        long manager = createStaff("商品站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("商品客户", "prod-openid");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        int productsBefore = intOf("SELECT COUNT(*) FROM product");

        // 1) 旧的全局写端点已删除：站长不能再往"通用商品库"里塞全局商品，顾客更不能。
        //    （原实现：站长建的商品全平台可见、还能改价/停用影响所有站 —— 缺陷 1/2）
        assertNotEquals(0, post("/api/products", mgr,
                "{\"name\":\"目录水\",\"category\":1,\"price\":12.00,\"deposit\":30.00,\"status\":1}").code(),
                "POST /api/products 必须已下线（写入口是 /api/manager/my-products）");
        assertNotEquals(0, post("/api/products", cus, "{\"name\":\"顾客建的商品\",\"price\":1.00}").code(),
                "顾客不得建商品");
        assertEquals(productsBefore, intOf("SELECT COUNT(*) FROM product"), "被拒的请求不得落库");

        // 2) 站长写入口：/api/manager/my-products —— 建出来的是**本站自定义商品**
        Api created = post("/api/manager/my-products", mgr,
                "{\"name\":\"目录水\",\"category\":1,\"brand\":\"Aqua\",\"spec\":\"18.9L\","
                        + "\"price\":12.00,\"deposit\":30.00,\"quantity\":3,\"enabled\":1}");
        assertEquals(0, created.code(), "建自定义商品: " + created);
        long productId = created.data().asLong();
        assertEquals(station, longOf("SELECT owner_station_id FROM product WHERE id=?", productId),
                "站长建的商品必须归属本站，不再进通用库");
        assertEquals(3, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, productId), "初始库存应落到本站");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                        + "AND type='INBOUND'", station, productId), "初始库存也必须留流水");

        // 3) 顾客侧可见性：本站在售能看到，别站看不到；不带站上下文读不到详情
        assertEquals(0, get("/api/products/sale-by-station?stationId=" + station, cus).code());
        assertEquals(1, get("/api/products/sale-by-station?stationId=" + station, cus).data().size(),
                "本站顾客应能看到本站自定义商品");
        assertEquals(0, get("/api/products/sale-by-station?stationId=" + otherStation, cus).data().size(),
                "别站顾客不得看到该自定义商品");
        assertNotEquals(0, get("/api/products/" + productId, cus).code(),
                "不带 stationId 时不得读到站级自定义商品");
        assertEquals(0, get("/api/products/" + productId + "?stationId=" + station, cus).code(),
                "带对的 stationId 才可读");

        // 4) 编辑：自定义商品的名称等目录字段可改（通用库商品改不动，见 CatalogOwnershipIntegrationTest）
        assertEquals(0, put("/api/manager/my-products/" + productId, mgr,
                "{\"name\":\"目录水改名\",\"price\":13.00}").code(), "改自定义商品");
        assertEquals("目录水改名",
                jdbc.queryForObject("SELECT name FROM product WHERE id=?", String.class, productId));

        // 5) 停用是**软删除**：行还在、历史订单/桶账引用不断，只是从在售列表消失。
        //    断言"查不到"是错的 —— 这行注释就是防止下一个人再写错。
        assertEquals(0, delete("/api/manager/my-products/" + productId, mgr).code());
        assertEquals(0, intOf("SELECT status FROM product WHERE id=?", productId), "软删除后 status 应为 0");
        assertEquals(1, intOf("SELECT COUNT(*) FROM product WHERE id=?", productId), "物理行必须保留");
        assertEquals(0, get("/api/products/sale-by-station?stationId=" + station, cus).data().size(),
                "下架后不再出现在在售列表");
    }

    @Test
    @DisplayName("站长商品管家（新入口）：建自定义/改/上下架/优先展示(上限3)/入库/盘点/删")
    void managerProductConsoleEndpoints() {
        long station = createStation("管家站");
        long manager = createStaff("管家站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("管家客户", "mp-openid");

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        // 建自定义商品（含初始库存与上架）：这是"自己定义"入口的落点
        Api created = post("/api/manager/my-products", mgr,
                "{\"name\":\"管家水\",\"category\":1,\"price\":12.00,\"deposit\":30.00,\"quantity\":10,\"enabled\":1}");
        assertEquals(0, created.code(), "建商品（含库存）: " + created);
        long productId = created.data().asLong();
        assertTrue(productId > 0);
        assertEquals(10, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, productId), "建商品时带的数量应写进本站库存");
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                + "AND type='INBOUND' AND delta=10", station, productId), "初始库存也要留流水");

        Api list = get("/api/manager/catalog", mgr);
        assertEquals(0, list.code(), "选品列表: " + list);
        assertEquals(1, list.data().size());
        assertEquals(0, get("/api/manager/my-products", mgr).code(), "我的商品列表");

        // 改自定义商品（名称/价格可改）+ 改库存走盘点入口
        assertEquals(0, put("/api/manager/my-products/" + productId, mgr,
                "{\"name\":\"管家水2\",\"price\":13.00,\"quantity\":12}").code(), "改商品");
        assertEquals("管家水2", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String.class, productId));
        assertEquals(12, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, productId));
        assertEquals(1, intOf("SELECT COUNT(*) FROM inventory_record WHERE station_id=? AND product_id=? "
                + "AND type='ADJUST' AND delta=2", station, productId), "改库存必须留 ADJUST 流水");

        // 上下架 = 本站设置（不动通用库的 status）
        assertEquals(0, put("/api/manager/catalog/" + productId, mgr, "{\"enabled\":0}").code(), "下架");
        assertEquals(0, intOf("SELECT enabled FROM inventory WHERE station_id=? AND product_id=?",
                station, productId), "下架必须落到本站库存行");
        assertEquals(1, intOf("SELECT status FROM product WHERE id=?", productId), "商品本身仍是在售状态");

        Api inbound = post("/api/inventory/inbound?stationId=" + station, mgr,
                "{\"items\":[{\"productId\":" + productId + ",\"quantity\":7}]}");
        assertEquals(0, inbound.code(), "入库: " + inbound);
        assertEquals(19, intOf("SELECT quantity FROM inventory WHERE station_id=? AND product_id=?",
                station, productId));

        // 优先展示上限 3：第 4 个必须被拒（前端首页只摆 3 个坑）
        long p2 = post("/api/manager/my-products", mgr, "{\"name\":\"管家水B\",\"category\":1,\"price\":1.00,\"deposit\":30.00,\"quantity\":1}").data().asLong();
        long p3 = post("/api/manager/my-products", mgr, "{\"name\":\"管家水C\",\"category\":1,\"price\":1.00,\"deposit\":30.00,\"quantity\":1}").data().asLong();
        long p4 = post("/api/manager/my-products", mgr, "{\"name\":\"管家水D\",\"category\":1,\"price\":1.00,\"deposit\":30.00,\"quantity\":1}").data().asLong();
        assertEquals(0, put("/api/manager/catalog/" + productId, mgr, "{\"priorityDisplay\":1}").code());
        assertEquals(0, put("/api/manager/catalog/" + p2, mgr, "{\"priorityDisplay\":1}").code());
        assertEquals(0, put("/api/manager/catalog/" + p3, mgr, "{\"priorityDisplay\":1}").code());
        assertNotEquals(0, put("/api/manager/catalog/" + p4, mgr, "{\"priorityDisplay\":1}").code(),
                "优先展示第 4 个必须被拒");
        assertEquals(3, intOf("SELECT COUNT(*) FROM inventory WHERE station_id=? AND priority_display=1", station));

        assertNotEquals(0, get("/api/manager/catalog", cus).code(), "顾客不得进站长商品管家");
        assertNotEquals(0, post("/api/manager/my-products", cus, "{\"name\":\"x\",\"category\":1,\"price\":1.00}").code());

        // 删除同样是软删除：status 置 0、物理行保留（历史订单/桶账仍引用它）
        assertEquals(0, delete("/api/manager/my-products/" + productId, mgr).code());
        assertEquals(0, intOf("SELECT status FROM product WHERE id=?", productId));
        assertEquals(1, intOf("SELECT COUNT(*) FROM product WHERE id=?", productId), "物理行保留");
    }
}
