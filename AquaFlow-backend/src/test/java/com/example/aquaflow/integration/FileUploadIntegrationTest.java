package com.example.aquaflow.integration;

import com.example.aquaflow.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片 / 文件上传（矩阵 C3）。本机 <b>COS 未配置</b>（{@code COS_SECRET_ID/KEY} 为空时
 * {@code RequiredConfigChecker} 只 WARN 不拒启），所以这里能钉住的是"拒绝分支"：
 *
 * <ul>
 *   <li>类型/大小/分类白名单必须在<b>碰对象存储之前</b>就拒掉（否则白名单等于没有）；</li>
 *   <li>对象存储不可用时必须是 <b>业务错误 {@code code=1}</b>，绝不能变成 500 —— 上传失败是
 *       可预期的运维状态，不是系统崩溃；</li>
 *   <li>文件删除同样不许因存储不可用而 500（否则站长连废记录都清不掉）。</li>
 * </ul>
 *
 * <p>上传成功路径（真发到 COS）在本机无法验证，属已知空白，不要写成"已测"。</p>
 */
class FileUploadIntegrationTest extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 手工拼 multipart/form-data —— 基类的 HTTP helper 固定发 JSON，而上传端点只吃 multipart。 */
    private Api multipart(String path, String token, Map<String, String> fields,
                          String fileField, String filename, byte[] content) {
        String boundary = "----AquaFlowTest" + System.nanoTime();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey()
                        + "\"\r\n\r\n" + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            if (filename != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                        + "\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(content);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            HttpResponse<String> res = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = res.body() == null || res.body().isBlank()
                    ? om.createObjectNode() : om.readTree(res.body());
            return new Api(res.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException("multipart " + path + " 失败: " + e.getMessage(), e);
        }
    }

    private Api upload(String path, String token, String filename, Map<String, String> fields) {
        return multipart(path, token, fields, "file", filename, "fake-image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("上传端点：先校验登录与角色，再谈文件")
    void uploadRequiresAuthAndRole() {
        long station = createStation("上传站");
        long manager = createStaff("上传站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("上传客户", "upload-openid");
        String cus = customerToken(customer);

        // 未登录：拦截器先挡（真 401），连 multipart 都到不了控制器
        assertEquals(401, upload("/api/common/upload", null, "a.png", fields()).status());
        assertEquals(401, upload("/api/files/upload", null, "a.png", fields()).status());

        // 文件管理是站长专属
        assertNotEquals(0, upload("/api/files/upload", cus, "a.png", fields("category", "general")).code(),
                "顾客不得使用站长的文件管理上传");
        assertNotEquals(0, get("/api/files", cus).code(), "顾客不得列文件");
        assertEquals(0, get("/api/files", staffToken(manager, "STATION_MANAGER", station)).code());
    }

    @Test
    @DisplayName("白名单在碰对象存储之前生效：扩展名/分类非法一律先拒")
    void whitelistRejectsBeforeTouchingStorage() {
        long station = createStation("白名单站");
        long manager = createStaff("白名单站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("白名单客户", "wl-openid");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);

        Api badExt = upload("/api/common/upload", cus, "evil.txt", fields());
        assertNotEquals(0, badExt.code(), "非图片扩展名必须被拒");
        assertTrue(badExt.message().contains("JPG"), "应是类型白名单的拒绝，而不是存储失败: " + badExt);

        Api badExt2 = upload("/api/files/upload", mgr, "sheet.txt", fields("category", "general"));
        assertNotEquals(0, badExt2.code());
        assertTrue(badExt2.message().contains("不支持的文件类型"), "文件管理侧也应是类型拒绝: " + badExt2);

        // [AQ-039] category 会拼进对象路径，必须挡目录穿越
        Api traversal = upload("/api/files/upload", mgr, "a.png", fields("category", "../../private"));
        assertNotEquals(0, traversal.code(), "非法分类必须被拒");
        assertTrue(traversal.message().contains("分类"), "应是分类白名单拒绝: " + traversal);

        // 文件名没有扩展名（Excel/微信有时会这样）也要拒
        assertNotEquals(0, upload("/api/common/upload", cus, "noextension", fields()).code());
    }

    @Test
    @DisplayName("COS 未配置时上传是业务错误(code=1)，不是 500")
    void uploadWithoutCosFailsCleanly() {
        long station = createStation("无存储站");
        long manager = createStaff("无存储站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("无存储客户", "nocos-openid");
        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);
        createCustomerStationConfig(customer, station, 1);

        Api common = upload("/api/common/upload", cus, "a.png", fields());
        assertNotEquals(500, common.code(), "上传失败不该是系统异常: " + common);
        assertNotEquals(0, common.code(), "COS 未配置时应明确失败，而不是假装成功: " + common);

        Api file = upload("/api/files/upload", mgr, "a.png", fields("category", "general"));
        assertNotEquals(500, file.code(), "上传失败不该是系统异常: " + file);
        assertNotEquals(0, file.code(), "COS 未配置时应明确失败: " + file);
        assertEquals(0, intOf("SELECT COUNT(*) FROM file_info"), "上传失败不得留下幽灵记录");
    }

    @Test
    @DisplayName("订单图片：归属校验 + 查询；空列表不报错")
    void orderImagesAreOrderOwned() {
        long station = createStation("图片站");
        long manager = createStaff("图片站长", "STATION_MANAGER", station, 1);
        long customer = createCustomer("图片客户", "img-openid");
        long intruder = createCustomer("别人", "img-openid-2");
        long product = createProduct("图片水", 1, "10.00", "30.00", 0, "0.00");
        long address = createAddress(customer, "图片地址");
        long orderId = createOrderFull(customer, address, station, product, 2, 2, 2,
                "10.00", "30.00", "40.00", true, 2);

        String mgr = staffToken(manager, "STATION_MANAGER", station);
        String cus = customerToken(customer);
        String other = customerToken(intruder);

        Api empty = get("/api/order-images/by-order/" + orderId, mgr);
        assertEquals(0, empty.code(), "订单图片列表: " + empty);
        assertEquals(0, empty.data().size(), "还没传过图，应是空数组而不是报错");
        assertEquals(0, get("/api/order-images/by-order/" + orderId, cus).code(), "客户看自己订单的图");

        assertNotEquals(0, get("/api/order-images/by-order/" + orderId, other).code(),
                "不得看别人订单的图片");
        // 归属不符时上传也要拒（同样先于存储）
        assertNotEquals(0, upload("/api/order-images/upload", other, "a.png", fields("orderId", String.valueOf(orderId))).code(),
                "不得往别人订单传图");
        Api uploadOwn = upload("/api/order-images/upload", cus, "a.png", fields("orderId", String.valueOf(orderId)));
        assertNotEquals(500, uploadOwn.code(), "存储不可用也不该变成系统异常: " + uploadOwn);
        assertNotEquals(0, uploadOwn.code(), "COS 未配置时应明确失败: " + uploadOwn);
        assertEquals(0, intOf("SELECT COUNT(*) FROM order_image"), "上传失败不得留下幽灵记录");
    }

    @Test
    @DisplayName("文件删除：记录能删掉，且存储不可用不会变成 500")
    void fileDeleteDoesNotBreakWithoutStorage() {
        long station = createStation("删除站");
        long manager = createStaff("删除站长", "STATION_MANAGER", station, 1);
        String mgr = staffToken(manager, "STATION_MANAGER", station);

        long fileId = insert("INSERT INTO file_info(file_name, file_size, file_type, mime_type, object_name, "
                        + "category, uploader_id) VALUES (?,?,?,?,?,?,?)",
                "old.png", 1234, "image", "image/png", "public/general/old.png", "general", manager);

        assertEquals(0, get("/api/files?category=general", mgr).code(), "按分类列文件");
        Api deleted = delete("/api/files/" + fileId, mgr);
        // 注意判据是 body 的 code，不是 HTTP 状态：本仓库的系统异常是「HTTP 200 + code=500」
        // （见 GlobalExceptionHandler），只看 status 会把 500 当成成功。
        assertNotEquals(500, deleted.code(), "存储不可用时删除不该变成系统异常: " + deleted);
        assertEquals(0, deleted.code(), "删除应成功: " + deleted);
        assertEquals(0, intOf("SELECT COUNT(*) FROM file_info WHERE id=?", fileId), "记录应被清掉");

        assertNotEquals(0, delete("/api/files/999999", mgr).code(), "删不存在的文件应给出业务错误");
        // 只能删自己上传的
        long otherFile = insert("INSERT INTO file_info(file_name, file_size, file_type, mime_type, object_name, "
                        + "category, uploader_id) VALUES (?,?,?,?,?,?,?)",
                "theirs.png", 10, "image", "image/png", "public/general/theirs.png", "general", 999999);
        assertNotEquals(0, delete("/api/files/" + otherFile, mgr).code(), "不得删别人上传的文件");
        assertEquals(1, intOf("SELECT COUNT(*) FROM file_info WHERE id=?", otherFile));
    }

    @Test
    @DisplayName("文件按水站隔离：看不到他站文件、也删不掉（v45 修的跨租户泄露）")
    void fileListAndDeleteAreStationScoped() {
        long stationA = createStation("文件站A");
        long stationB = createStation("文件站B");
        long mgrA = createStaff("文件站长A", "STATION_MANAGER", stationA, 1);
        long mgrB = createStaff("文件站长B", "STATION_MANAGER", stationB, 1);
        String tokenA = staffToken(mgrA, "STATION_MANAGER", stationA);

        long fileA = insertFile(mgrA, stationA, "a.png");
        long fileB = insertFile(mgrB, stationB, "b.png");
        long platformFile = insertFile(mgrA, null, "banner.png");   // NULL = 平台级，有意保留

        java.util.List<Long> visible = fileIds(tokenA, null);
        assertTrue(visible.contains(fileA), "本站文件必须可见：" + visible);
        assertTrue(visible.contains(platformFile),
                "平台级文件（station_id 为 NULL）全站可见 —— 这个 NULL 是有语义的，不是脏数据");
        assertFalse(visible.contains(fileB),
                "他站文件不得出现在列表里：这正是 v45 修的跨租户泄露（原 listAll() 无任何水站过滤）");
        assertFalse(fileIds(tokenA, "general").contains(fileB), "按分类查同样必须隔离");

        assertNotEquals(0, delete("/api/files/" + fileB, tokenA).code(), "不得删他站文件");
        assertEquals(1, intOf("SELECT COUNT(*) FROM file_info WHERE id=?", fileB), "拒绝后他站文件必须还在");
    }

    /* ==================== v45 夹具 ==================== */

    /** 直接插一条文件登记（本机 COS 未配置，走不了真实上传路径） */
    private long insertFile(long uploaderId, Long stationId, String name) {
        String sql = "INSERT INTO file_info(file_name, file_size, file_type, mime_type, object_name, "
                + "category, uploader_id, station_id) VALUES (?,?,?,?,?,?,?,"
                + (stationId == null ? "NULL)" : "?)");
        return stationId == null
                ? insert(sql, name, 10, "image", "image/png", "public/general/" + name, "general", uploaderId)
                : insert(sql, name, 10, "image", "image/png", "public/general/" + name, "general", uploaderId, stationId);
    }

    /** 当前可见的文件 id 列表；category 为 null 时不带分类参数 */
    private java.util.List<Long> fileIds(String token, String category) {
        String path = category == null ? "/api/files" : "/api/files?category=" + category;
        Api res = get(path, token);
        assertEquals(0, res.code(), "文件列表应可读：" + res.data());
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode n : res.data()) {
            ids.add(n.path("id").asLong());
        }
        return ids;
    }
}
