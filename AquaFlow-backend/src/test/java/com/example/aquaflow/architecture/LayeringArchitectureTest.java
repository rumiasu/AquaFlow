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
 * <h3>2026-10-01 加固（F-01 / F-12）：把"看得见的写法"补齐</h3>
 * 两处判据此前都只认"最常见的那一种写法"，等价写法一律逃逸：
 * <ul>
 *   <li><b>注入检测（F-01）</b>漏 {@code private final XxxMapper}（构造函数注入）与全限定名写法 ——
 *       已有 2 个真实类因此既不在基线里、也永远不红，见 {@link #MAPPER_FIELD} 的注释；</li>
 *   <li><b>写调用检测（F-12）</b>原是一张手写动词表，漏 {@code settleIfCollected} 这类
 *       "名字里没有动词"的 {@code @Update} —— 现在直接扫 Mapper 源码上的写注解，
 *       见 {@link #mapperWriteMethods()} 的注释。</li>
 * </ul>
 * 两处都按老规矩收口：<b>判据收紧、基线只减不增</b>（本次只把此前看不见的 2 个存量类补登进基线）。
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

    /**
     * Mapper 字段：{@code private [final|static] [包名.]XxxMapper 字段名;}。
     *
     * <p>[2026-10-01，F-01] 原正则 {@code private\s+\w+Mapper\s+\w+} 要求 {@code private} 与
     * {@code …Mapper} 之间<b>只有一个词</b>，于是两种等价写法直接逃逸：</p>
     * <ul>
     *   <li>{@code private final CustomerMapper customerMapper;} —— 构造函数注入，Spring 最常见的
     *       写法，却因为多了一个 {@code final} 而看不见；</li>
     *   <li>{@code private com.example.aquaflow.mapper.OrderMapper orderMapper;} —— 全限定名，
     *       包名把那个位置占掉了。</li>
     * </ul>
     * <p>两个真实逃逸类（{@code ManagerReceivableController} / {@code ManagerExceptionController}）
     * 因此既不在基线里、也永远不会红：门禁对外宣称"基线 32、只减不增"，真实是 34。
     * <b>值得记一笔的是 —— 越规范、越现代的写法（构造函数注入 + {@code private final}）反而越不受约束</b>；
     * 而这条门禁的全部价值就是"新代码不许再欠账"，看不见新写法等于没有门禁。</p>
     *
     * <p>现在额外覆盖 {@code static} 与 {@code List<XxxMapper>} 这类容器字段（后者本仓暂无）。
     * 已知边界：同一行声明多个字段（{@code private A a, B b;}）只算 1 处，本仓没有这种写法；
     * 字段总数上限（{@link #BASELINE_MAPPER_FIELDS_COUNT}）只做"不许变多"的兜底。</p>
     */
    private static final Pattern MAPPER_FIELD = Pattern.compile(
            "private\\s+(?:(?:final|static)\\s+)*(?:[\\w.]+Mapper\\s+\\w+"
                    + "|(?:List|Set|Collection|Map)\\s*<[^>]*Mapper[^>]*>\\s+\\w+)");

    /** {@code XxxMapper.方法名(} —— 它算不算"写"由 {@link #mapperWriteMethods()} 判，不靠名字猜。 */
    private static final Pattern MAPPER_CALL = Pattern.compile("\\w+Mapper\\.(\\w+)\\s*\\(");

    /** Mapper 接口的方法声明行（{@code int foo(}）—— 写注解在它上面，扫到声明就把名字记进写方法集合。 */
    private static final Pattern METHOD_DECLARATION =
            Pattern.compile("^\\s*[\\w<>\\[\\],.\\s]*\\s(\\w+)\\s*\\(");

    /**
     * Mapper 源码里的写注解 —— F-12 的新判据（旧的 {@code WRITE_VERBS} 手写动词表已删）。
     *
     * <p>必须同时认<b>全限定写法</b>：{@code BarrelRecordLotMapper} 写的是
     * {@code @org.apache.ibatis.annotations.Insert} —— 和 F-01 是同一个教训，
     * 只是这次逃逸的是注解而不是字段（"换个更啰嗦但等价的写法就看不见"）。</p>
     */
    private static final Pattern WRITE_ANNOTATION =
            Pattern.compile("@(?:[\\w.]+\\.)?(Insert|Update|Delete)\\b");

    /** XML mapper 里的写语句 id（{@code OrderMapper#save} 的 SQL 在 resources/mapper 那边）。 */
    private static final Pattern XML_WRITE_STATEMENT =
            Pattern.compile("<\\s*(?:insert|update|delete)\\s+id\\s*=\\s*\"(\\w+)\"");

    /** XML 注释：扫描前先剥掉，别把注释里的示例（如已删除的 {@code <update id="update">}）当成真写方法。 */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern TX_ANNOTATION = Pattern.compile(
            "(?m)^\\s*(?:@org\\.springframework\\.transaction\\.annotation\\.)?@?Transactional\\b");

    /**
     * 基线（2026-09-29 实测，BarrelController 下沉后的值；同日第二批把
     * LoginController / DeliveryBindingController 也清干净后由 34 减到 32）。
     *
     * <p>[2026-10-01，F-01] 补上两个<b>一直逃逸在基线外</b>的类 —— 它们不是新欠的账，
     * 是正则修好之后才第一次被看见的存量欠账：{@code ManagerReceivableController}
     * （{@code private final CustomerMapper}，构造函数注入）、{@code ManagerExceptionController}
     * （全限定名 {@code com.example.aquaflow.mapper.OrderMapper}）。32 → <b>34</b>。</p>
     *
     * <p>只许减不许增。</p>
     */
    private static final Set<String> BASELINE_MAPPER_FIELDS = Set.of(
            "AddressController", "CompanyInfoController", "CustomerController",
            "CustomerNotificationController", "DeliveryController",
            "DeliveryEarningController", "DepositRecordController", "DevLoginController",
            "FeedbackController", "FileManageController", "ManagerAlertController",
            "ManagerBarrelLossController", "ManagerCustomerPrivilegeController",
            "ManagerDeliveryConfigController", "ManagerExceptionController", "ManagerGrossProfitController",
            "ManagerOrderAssistController", "ManagerPayrollController", "ManagerPendingSummaryController",
            "ManagerReceivableController", "ManagerStationStatusController", "ManagerTodoController",
            "NoticeController", "OrderController", "OrderImageController", "PaymentController",
            "ProductController", "SearchController", "StaffController", "StationController",
            "StationTicketDiscountController", "TicketAccountController", "TicketPackageController",
            "TicketRecordController");

    /**
     * {@code BASELINE_MAPPER_FIELDS} 里所有 Mapper 字段声明的总数（2026-10-01 实测 68 处）。
     * 与 {@link #BASELINE_TX_ANNOTATIONS} 同理：防止"从 A 挪几处到 B"、或"同一个类里再多注入几处"
     * 之后类集合没变。口径是<b>匹配到的声明处数</b>（同一行声明多个字段只算 1 处，本仓无此写法）。
     */
    private static final int BASELINE_MAPPER_FIELDS_COUNT = 68;

    /**
     * 基线：直接调用 Mapper <b>写方法</b>的 Controller（15 个，2026-09-29 第二批下沉后由 17 减到 15）。
     * 只许减不许增。
     *
     * <p>[2026-10-01，F-12] 集合没变，但判据换掉了：写方法不再靠名字猜（见
     * {@link #mapperWriteMethods()}），实测仍然正好是这 15 个类。</p>
     */
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
    @DisplayName("Controller 不得新增注入 Mapper 的类（基线 34，只减不增）")
    void controllersMustNotInjectNewMappers() {
        Set<String> actual = new TreeSet<>();
        int totalFields = 0;
        for (Path f : controllerSources()) {
            Matcher m = MAPPER_FIELD.matcher(read(f));
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                actual.add(baseName(f));
                totalFields += n;
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
        final int counted = totalFields;
        assertTrue(counted <= BASELINE_MAPPER_FIELDS_COUNT,
                () -> "Controller 里的 Mapper 字段声明共 " + counted + " 处，超过基线 "
                        + BASELINE_MAPPER_FIELDS_COUNT + " 处（类集合没变但字段变多了？"
                        + "同一个类里再多注入一处也算欠账）。");
    }

    @Test
    @DisplayName("Controller 不得新增直接调用 Mapper 写方法的类（基线 15，只减不增）")
    void controllersMustNotCallMapperWrites() {
        Set<String> writeMethods = mapperWriteMethods();
        Set<String> actual = new TreeSet<>();
        for (Path f : controllerSources()) {
            Matcher m = MAPPER_CALL.matcher(read(f));
            while (m.find()) {
                if (writeMethods.contains(m.group(1))) {
                    actual.add(baseName(f));
                    break; // 一个类记一次即可
                }
            }
        }
        Set<String> newOnes = new TreeSet<>(actual);
        newOnes.removeAll(BASELINE_WRITE_CALLS);
        assertTrue(newOnes.isEmpty(),
                () -> "新增了直接写库的 Controller：" + newOnes
                        + "\n  判据：`XxxMapper.<方法>(`，且该方法在 Mapper 源码里带"
                        + " @Insert/@Update/@Delete（或 XML mapper 里的写语句）= 越层写库。"
                        + "\n  Mapper 新增写方法<b>不用</b>再来这里加词 —— 判据是注解本身。"
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

    /* ==================== Mapper 写方法提取（F-12 的判据） ==================== */

    /**
     * 真·写方法名集合 = Mapper 源码里带 {@code @Insert/@Update/@Delete} 的方法名
     * ∪ {@code resources/mapper/*.xml} 里的 {@code <insert|update|delete id="...">}。
     *
     * <p><b>为什么不再用手写动词表</b>（[2026-10-01，F-12]）：旧判据是
     * {@code insert|update|...|upsert|batch} 这张名词表，而 {@code OrderMapper} 上的
     * {@code settleIfCollected / claimIfUnassigned / dispatchIfStatus / reassignStaffIf /
     * outsourceToStationIf / appendSpecialNote} 全是 {@code @Update} ——
     * <b>名字里没有动词的写方法一律漏检</b>，而且每加一个写方法都要人去补词，漏一次就永久看不见。
     * 现在的判据是"这个方法在 Mapper 里是不是写"，以后 Mapper 新增写方法<b>自动生效</b>，
     * 不需要再维护词表。</p>
     *
     * <p><b>为什么同时扫 XML</b>：{@code OrderMapper#save}（下单的唯一插入语句）的 SQL 不在注解里，
     * 而在 {@code resources/mapper/OrderMapper.xml}。只扫注解的话，"Controller 直接调
     * {@code orderMapper.save(...)}"这种最危险的越层写库会漏过去。</p>
     *
     * <p>扫不到任何写方法 = 定位或解析坏了 ⇒ 直接失败（本测试的既有原则：不静默通过）。</p>
     */
    private static Set<String> mapperWriteMethods() {
        Set<String> out = new TreeSet<>();
        for (Path f : mapperSources()) {
            boolean writePending = false;
            for (String line : read(f).split("\\R")) {
                if (WRITE_ANNOTATION.matcher(line).find()) {
                    writePending = true; // 注解可能跨多行，直到遇见方法声明才消
                }
                Matcher m = METHOD_DECLARATION.matcher(line);
                if (m.find()) {
                    if (writePending) {
                        out.add(m.group(1));
                    }
                    writePending = false;
                }
            }
        }
        for (Path f : xmlMapperSources()) {
            Matcher m = XML_WRITE_STATEMENT.matcher(XML_COMMENT.matcher(read(f)).replaceAll(""));
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        if (out.isEmpty()) {
            fail("没扫到任何 Mapper 写方法（注解 SQL 与 XML mapper 都为空）—— 判据坏了，门禁不许静默通过");
        }
        return out;
    }

    /* ==================== 源码树定位与读取 ==================== */

    private static List<Path> controllerSources() {
        return listFiles(locate("controller"), ".java", "controller");
    }

    /** Mapper 接口源码 —— 写注解（F-12 的判据）在这里。 */
    private static List<Path> mapperSources() {
        return listFiles(locate("mapper"), ".java", "mapper");
    }

    /** XML mapper（resources/mapper/*.xml）—— 写方法的 SQL 也可能写在这边，别漏。 */
    private static List<Path> xmlMapperSources() {
        return listFiles(locateRelative("src/main/resources/mapper", "resources/mapper"),
                ".xml", "XML mapper");
    }

    private static List<Path> listFiles(Path dir, String suffix, String what) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = s.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
            if (out.isEmpty()) {
                fail(what + " 目录下没有 " + suffix + " 文件：" + dir);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("列举 " + what + " 失败: " + dir, e);
        }
    }

    /**
     * 从 user.dir 向上/向下找 {@code src/main/java/com/example/aquaflow/<pkg>}。
     * <p>Gradle 测试的 workingDir 是模块目录（AquaFlow-backend），IDE 通常是项目根或模块根，
     * 两种都兼容；<b>都找不到就失败</b> —— 门禁静默跳过等于没有。</p>
     */
    private static Path locate(String pkg) {
        return locateRelative("src/main/java/com/example/aquaflow/" + pkg, pkg);
    }

    /** 同 {@link #locate}，只是相对模块根的路径可以换（XML mapper 在 {@code src/main/resources/mapper}）。 */
    private static Path locateRelative(String relative, String what) {
        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = start; p != null; p = p.getParent()) {
            for (Path cand : List.of(p.resolve(relative), p.resolve("AquaFlow-backend").resolve(relative))) {
                if (Files.isDirectory(cand)) {
                    return cand;
                }
            }
        }
        throw new IllegalStateException("找不到 " + what + "（user.dir=" + start
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
