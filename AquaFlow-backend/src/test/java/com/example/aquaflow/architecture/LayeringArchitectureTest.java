package com.example.aquaflow.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 分层门禁 —— 把 AGENTS §6「Controller 只做认证 + DTO 校验 + 调服务」从**文本规约**变成
 * **违规即红**的测试（2026-09-29 立）。
 *
 * <h3>为什么用「基线子集」而不是直接要求 0</h3>
 * 历史上 50 个 Controller 里 35 个注入了 Mapper、18 个直接写库、11 处事务开在 HTTP 层 ——
 * 一口气清零不现实。所以：**旧账留在基线里（只许少、不许多），新账一律不许欠**。
 * <ul>
 *   <li>新写一个注入 Mapper / 直接写库 / 开事务的 Controller ⇒ 集合里冒出新名字 ⇒ 红；</li>
 *   <li>把某个 Controller 清干净了 ⇒ 它会从实际集合里消失，基线不动也仍然绿 ——
 *       <b>但请顺手把它从基线删掉</b>（基线只减不增，否则"修好了"永远留不下痕迹）。</li>
 * </ul>
 *
 * <p><b>为什么扫源码而不是反射扫注解</b>：{@code private FooMapper bar;} 反射能看见，
 * 但「{@code barMapper.insert(...)} 这种**调用**」反射看不见 —— 而后者才是真正的越层写库。
 * 源码扫描一处搞定两者，也和仓库既有的 Python 静态门禁同思路（本测试的价值是它
 * <b>跑在 {@code gradlew test} 里</b>，不需要另记一条命令）。</p>
 *
 * <p>跑法：{@code gradlew test --tests '*LayeringArchitectureTest'}。找不到源码树时
 * <b>直接失败</b>、不静默通过（静默通过 = 门禁形同虚设）。</p>
 */
class LayeringArchitectureTest {

    /** 写方法动词（与本基线同源；加词前先确认它真的会写库，别把 {@code listXxx} 算进去）。 */
    private static final String WRITE_VERBS =
            "insert|update|delete|save|remove|clear|mark|set[A-Z]|add|sub|increase|decrease"
            + "|sync|apply|confirm|reject|finish|release|ship|transfer|lock|cancel|approve"
            + "|bind|unbind|reset|truncate|upsert|batch";

    private static final Pattern MAPPER_FIELD = Pattern.compile("private\\s+\\w+Mapper\\s+\\w+");
    private static final Pattern MAPPER_CALL = Pattern.compile("\\w+Mapper\\.(\\w+)\\s*\\(");
    private static final Pattern WRITE_CALL = Pattern.compile("\\w+Mapper\\.(" + WRITE_VERBS + ")\\w*\\s*\\(");
    private static final Pattern TX_ANNOTATION = Pattern.compile(
            "(?m)^\\s*(?:@org\\.springframework\\.transaction\\.annotation\\.)?@?Transactional\\b");

    /**
     * 基线（2026-09-29 实测，BarrelController 下沉后的值；同日第二批把
     * LoginController / DeliveryBindingController 也清干净后由 34 减到 32）。
     * 只许减不许增。
     */
    private static final Set<String> BASELINE_MAPPER_FIELDS = Set.of(
            "AddressController", "CompanyInfoController", "CustomerController",
            "CustomerNotificationController", "DeliveryController",
            "DeliveryEarningController", "DepositRecordController", "DevLoginController",
            "FeedbackController", "FileManageController", "ManagerAlertController",
            "ManagerBarrelLossController", "ManagerCustomerPrivilegeController",
            "ManagerDeliveryConfigController", "ManagerGrossProfitController",
            "ManagerOrderAssistController", "ManagerPayrollController", "ManagerPendingSummaryController",
            "ManagerStationStatusController", "ManagerTodoController", "NoticeController",
            "OrderController", "OrderImageController", "PaymentController", "ProductController",
            "SearchController", "StaffController", "StationController", "StationTicketDiscountController",
            "TicketAccountController", "TicketPackageController", "TicketRecordController");

    /** 基线：直接调用 Mapper **写方法**的 Controller（15 个，2026-09-29 第二批下沉后由 17 减到 15）。只许减不许增。 */
    private static final Set<String> BASELINE_WRITE_CALLS = Set.of(
            "CompanyInfoController", "CustomerNotificationController",
            "DevLoginController", "FeedbackController", "FileManageController",
            "ManagerCustomerPrivilegeController", "ManagerDeliveryConfigController",
            "ManagerGrossProfitController", "ManagerPayrollController", "ManagerStationStatusController",
            "NoticeController", "StaffController", "StationController", "StationTicketDiscountController",
            "TicketPackageController");

    /**
     * 基线：HTTP 层开事务的 Controller。2026-09-29 第二批下沉（selectRole / createStationAndBind →
     * AuthTokenService，绑定 8 流程 → StaffStationApplicationService）后**已清空** ——
     * 这里保持空集：再出现一个带 {@code @Transactional} 的 Controller 就是红。
     */
    private static final Set<String> BASELINE_TX = Set.of();

    /** {@code BASELINE_TX} 里所有注解的总数，防止"从 A 挪 5 处到 B"后集合没变。基线 0 处。 */
    private static final int BASELINE_TX_ANNOTATIONS = 0;

    @Test
    @DisplayName("Controller 不得新增注入 Mapper 的类（基线 32，只减不增）")
    void controllersMustNotInjectNewMappers() {
        Set<String> actual = new TreeSet<>();
        for (Path f : controllerSources()) {
            if (MAPPER_FIELD.matcher(read(f)).find()) {
                actual.add(baseName(f));
            }
        }
        Set<String> newOnes = new TreeSet<>(actual);
        newOnes.removeAll(BASELINE_MAPPER_FIELDS);
        assertTrue(newOnes.isEmpty(),
                () -> "新增了注入 Mapper 的 Controller：" + newOnes
                        + "\n  规矩（AGENTS §6）：Controller 只做认证 + DTO 校验 + 调服务；"
                        + "写库下沉到 service。"
                        + "\n  基线共 " + BASELINE_MAPPER_FIELDS.size() + " 个，只许减不许增 —— "
                        + "新代码请走 service，别扩名单。");
    }

    @Test
    @DisplayName("Controller 不得新增直接调用 Mapper 写方法的类（基线 15，只减不增）")
    void controllersMustNotCallMapperWrites() {
        Set<String> actual = new TreeSet<>();
        for (Path f : controllerSources()) {
            Matcher m = WRITE_CALL.matcher(read(f));
            while (m.find()) {
                actual.add(baseName(f));
                break; // 一个类记一次即可
            }
        }
        Set<String> newOnes = new TreeSet<>(actual);
        newOnes.removeAll(BASELINE_WRITE_CALLS);
        assertTrue(newOnes.isEmpty(),
                () -> "新增了直接写库的 Controller：" + newOnes
                        + "\n  判据：`XxxMapper.insert/update/delete/...(` 出现在 controller/ 下 = 越层写库。"
                        + "\n  动过手脚的 Controller（BarrelController，2026-09-29）可当范本看。");
    }

    @Test
    @DisplayName("HTTP 层不得新增 @Transactional（基线 0 处 / 0 类，出现即红）")
    void controllersMustNotOpenNewTransactions() {
        Set<String> classes = new TreeSet<>();
        int total = 0;
        for (Path f : controllerSources()) {
            Matcher m = TX_ANNOTATION.matcher(read(f));
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                classes.add(baseName(f));
                total += n;
            }
        }
        Set<String> newOnes = new TreeSet<>(classes);
        newOnes.removeAll(BASELINE_TX);
        assertTrue(newOnes.isEmpty(),
                () -> "新增了在 HTTP 层开事务的 Controller：" + newOnes
                        + "\n  事务边界属于 service（范例：BarrelService#returnEmptyWithRecord）。");
        final int totalCount = total;
        assertTrue(totalCount <= BASELINE_TX_ANNOTATIONS,
                () -> "HTTP 层 @Transactional 总数 " + totalCount + " 超过基线 " + BASELINE_TX_ANNOTATIONS
                        + "（类集合没变但注解变多了？从 A 挪到 B 也要计入）。");
    }

    @Test
    @DisplayName("BarrelController 零容忍：无 Mapper 字段、无 Mapper 调用、无事务（下沉后不许回潮）")
    void barrelControllerStaysClean() {
        Path f = controllerSources().stream()
                .filter(p -> baseName(p).equals("BarrelController"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 BarrelController.java，源码树定位有误"));
        String src = read(f);
        List<String> violations = new ArrayList<>();
        if (MAPPER_FIELD.matcher(src).find()) {
            violations.add("注入了 Mapper 字段");
        }
        Matcher m = MAPPER_CALL.matcher(src);
        while (m.find()) {
            violations.add("调用了 " + m.group(0));
        }
        if (TX_ANNOTATION.matcher(src).find()) {
            violations.add("开了 @Transactional");
        }
        assertTrue(violations.isEmpty(),
                () -> "BarrelController 回潮了：" + violations
                        + "\n  2026-09-29 下沉的三件事：读走 listStationRecords、写走 requestReturn/"
                        + "returnEmptyWithRecord、事务在 service。别加回来。");
    }

    /* ==================== 源码树定位与读取 ==================== */

    private static List<Path> controllerSources() {
        Path dir = locate("controller");
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = s.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
            if (out.isEmpty()) {
                fail("controller 目录下没有 .java 文件：" + dir);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("列举 controller 源码失败: " + dir, e);
        }
    }

    /**
     * 从 user.dir 向上/向下找 {@code src/main/java/com/example/aquaflow/<pkg>}。
     * <p>Gradle 测试的 workingDir 是模块目录（AquaFlow-backend），IDE 通常是项目根或模块根，
     * 两种都兼容；<b>都找不到就失败</b> —— 门禁静默跳过等于没有。</p>
     */
    private static Path locate(String pkg) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            for (Path cand : List.of(
                    p.resolve("src/main/java/com/example/aquaflow").resolve(pkg),
                    p.resolve("AquaFlow-backend/src/main/java/com/example/aquaflow").resolve(pkg))) {
                if (Files.isDirectory(cand)) {
                    return cand;
                }
            }
        }
        throw new IllegalStateException("找不到源码树（user.dir=" + start
                + "）。本测试必须扫真实源码才能当门禁 —— 请在仓库内运行 gradlew test。");
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + f, e);
        }
    }

    private static String baseName(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.length() - ".java".length());
    }
}
