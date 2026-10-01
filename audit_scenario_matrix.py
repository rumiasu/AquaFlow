#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""场景测试矩阵一致性门禁：让「矩阵里声称有覆盖」这件事可以被机器核对。

背景（为什么需要它）
--------------------------------------------------------------------------
`docs/audit/2026-09-16-场景测试矩阵.md` 是本仓库的**场景覆盖地图**：它按 S1~S9 的业务场景
编号，把「一个真实用户走一遍会发生什么、哪些环节有断言」写成人能读的表格。新增用例前先看它
找空白，是 AGENTS.md §5 明确指定的做法。

问题在于：**矩阵是一份手写文档，而测试类会被改名、会被合并、会被删掉。**
`ManagerOrderControllerRemovedIntegrationTest` 是删端点时补的；
`OrderEntryAndInjectionIntegrationTest` 是补首单语义时加的 —— 每一次重构，
矩阵里那些 `BarrelReturnGuardIntegrationTest` 之类的名字都可能变成**指向不存在文件的引用**。
文档说"这条有断言"，人会信；实际那个类已经没了，覆盖就是一个洞。这与
AGENTS.md §0「文档已知大面积过期、只作线索」是同一类风险，只不过矩阵恰好是**被指定为入口**的那一份。

同类先例：§8 第 14 条的悬空 javadoc 事故 —— **被信任的文本比没有文本更危险**。

检测项
--------------------------------------------------------------------------
**A. 引用的东西必须真实存在（防"文档里的死链"）**
  1. **测试类引用必须落地**：矩阵里**用反引号包住**的测试类名（形如 `FooIntegrationTest`）必须真有
     同名 `.java`。2026-10 起这条改成**全文件扫描**（原版只看"非墓碑行"，附录小节的引用逃过校验）。
     ⚠️ **只认反引号里的**：示例/占位名请写成普通文字（`FooIntegrationTest` 加反引号 = 声称它存在）。
  2. **`类.方法` 必须两边都在**：`FooIntegrationTest.barMethod` 的类要存在，且该类里要有 `barMethod`；
     左侧是测试类时，右侧必须是**该类自己的**方法。
  3. **无处可寻的裸标识符**：矩阵里形如 `occupiedCountsOwedBarrelsEvenWithoutRights` 的裸驼峰词，
     必须在**测试方法名**或**主源码/测试源码**里能找到。找不到 = 方法被改名或本来就是笔误。
     这条能低噪运行的关键是**同时查主源码**：`statusText` / `customerName` 这类领域字段名
     在 `main/` 里有定义，属于正常引用，不算问题。

**B. ✅ 行必须指向真实测试（防"看着像判据其实恒真"）**
  4. **✅ 行的证据必须至少落到一个真实测试上**：证据栏里的行内代码必须**至少有一个**是
     ① 真实存在的测试类（通配 `*DtoValidationIntegrationTest` 命中一个即可）、
     ② 真实存在的测试方法名（`*Test.java` 里的 `void` 方法）、
     ③ 反引号包住的非自动化证据声明（`live` / `实测` / `人工` …），或
     ④ **真的挂在某个验证入口（ci.yml / verify.sh / verify-local.js）上的**门禁脚本。
     ⚠️ 这条是 2026-10 加固的：原版判据是「证据栏里有**任一**标识符能在仓库里找到」，
     于是 `ReconciliationService`（主源码类名）这类**跟用例无关**的引用也能让「✅ 有断言」成立
     —— 判据形同橡皮图章。现在主源码类名、与 CI 无关的文件名**不再**单独算证据。
     ⚠️ 2026-09-21 首次运行曾抓出 3 行标 ✅ 却指不出任何用例；2026-10 按新判据又筛出 **11 行**
     （1.3 / 5.4 / 5.5 / 6.4 / 6.5 / 7.4 / 8.4 / 8.10 / 9.3 / 9.4 / 9.6）——
     逐行补上真实类名、或显式写成 `live` 声明后才转绿。

**C. 反向：源码里新加的测试类必须被矩阵登记（防"矩阵悄悄过期"）**
  5. **测试类必须登记**：`src/test` 下每个测试类都要在矩阵里被点名（可写成 `Foo.method`）。
     新加的测试类既不在矩阵、也不在 `UNREGISTERED_TESTS` 里 ⇒ **失败**（这一步是"防过期"的关键）。
     ⚠️ 历史欠账已冻结在 `UNREGISTERED_TESTS`（2026-10 实测 72/119 未登记），
     冻结集是**棘轮**：只能减不能增 —— 豁免一个就要写一条理由，且该名字**必须**在源码里仍然存在。

**D. 件数声明不漂（可选）**
  6. 矩阵里的 `<!-- MATRIX-STATS -->` 块由本脚本生成，内容与 `build/test-results/test/*.xml`
     实测值不符即失败。
     ⚠️ 只校验这个**带标记的块**：文档里那些「2026-09-16 全量 194 例」是**带日期的历史快照**，
     不是断言，永远不该拿它们与今天的数字比较（那是把历史记录当规格用）。
     ⚠️ **这一条可以靠重新生成满足**（`--write-stats` 写什么，下一次就读什么）——
     它校验的是"件数有没有手工改坏"，**不是**"覆盖有没有断"。真正能证伪的是 A/B/C 三条。

已知偏差 / 仍然抓不到（照抄 AGENTS.md §7 对 `api_reverse_audit.py` 的告诫）
--------------------------------------------------------------------------
本脚本**只做字符串匹配**，不做语义分析。因此它**证明不了**：
  * 被点名的用例**真的断言了**那一行描述的场景 —— 它只证明"这个名字存在"。
    典型反例：矩阵写「4.5 回收数超过占用被拒 ← `BarrelLedgerIntegrationTest`」，
    用例确实存在，但它到底断没断言上限，本脚本不看。
  * 用例在**本次运行里通过**（本脚本只数 `build/test-results/*.xml` 的件数，不看失败明细）。
  * 状态的准确性：✅/🟡/❌ 的**分级**仍是人工判断（它只拦"✅ 却指不出证据"）。
  * 用例**文件**里是否真的还有 `@Test`（空壳类也能满足"类存在"；反向检查只认文件名）。
  * 认不出"用字符串拼接出来的类名/方法名"，可能漏报；
  * 单测方法名在矩阵里若不写全（例如只写中文描述），本脚本无从校验 —— 那属于矩阵自身的粒度问题。

用法
--------------------------------------------------------------------------
    python audit_scenario_matrix.py                 # 校验
    python audit_scenario_matrix.py --write-stats   # 把实测件数写回矩阵的 STATS 块
                                                    # （结果不可信时**拒绝写**：局部运行 / 比源码旧）
    python audit_scenario_matrix.py --force-stats   # 连"不可信"的那批也写（少见，需自知）
    python audit_scenario_matrix.py --matrix <路径>  # 指定矩阵文件

退出码：0 = 通过；1 = 发现问题（供 CI / scripts/verify.sh 作为门禁）
输出：最后一行一定是 ASCII 哨兵（`AQUAFLOW_SCENARIO_MATRIX_OK` /
      `AQUAFLOW_SCENARIO_MATRIX_FAIL <n>`），供上层脚本判绿 —— 中文在 fd 里会被编码毁掉（skill §8.31）。
"""

import os
import re
import sys
import glob
import datetime

try:  # 让脚本在任何控制台代码页下都能打印中文（勿依赖 PYTHONIOENCODING）
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

DEFAULT_MATRIX = 'docs/audit/2026-09-16-场景测试矩阵.md'
TEST_ROOT = 'AquaFlow-backend/src/test/java'
MAIN_ROOT = 'AquaFlow-backend/src/main/java'
RESULTS_GLOB = 'AquaFlow-backend/build/test-results/test/*.xml'

STATS_BEGIN = '<!-- MATRIX-STATS:BEGIN'
STATS_END = '<!-- MATRIX-STATS:END -->'

# 反引号里的行内代码：矩阵用 ``` 包住类名/方法名/字段名
RE_BACKTICK = re.compile(r'`([^`\n]+)`')
# **测试类名字的形状**（不带锚定，可接 `.method`）：本仓约定 `*IntegrationTest` /
# `*Tests` / `*Test` / `*ScenarioTest`。`Tests$` 与 `Test$` 两个分支已覆盖 `ScenarioTest`。
# 它同时用于三处：判据 A1 的存在性、判据 B 的证据识别、判据 C 的"登记"判定。
RE_TESTCLASS_BASE = re.compile(r'\*?[A-Z][A-Za-z0-9]*(?:IntegrationTest|Tests|Test)')
# 带 `$` 的整 token 形态（判据 A1：[1] 的 `RE_TEST_CLASS` 要求整 token 就是类名）
RE_TEST_CLASS = re.compile(r'^\*?[A-Z][A-Za-z0-9]*(?:IntegrationTest|Tests|Test)$')
# 裸驼峰标识符（小写开头，**至少含一个大写字母**）——可能是测试方法名，也可能是领域字段名。
# ⚠️ 下划线那一条也必须带大写字母，否则会把脚本名/库名一起抓进来：
#    `page_reach_audit`、`aquaflow_test_r10` 都是这样误报过的（2026-09-21 实测）。
RE_CAMEL = re.compile(r'^[a-z][A-Za-z0-9]*_[A-Za-z0-9_]*[A-Z][A-Za-z0-9_]*$|^[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*$')
# 类.成员
RE_MEMBER = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$')


def looks_like_test_class(name):
    """类名是否"测试类形状"。判据 C 的第一道筛：只有这些才要求被矩阵登记。

    ⚠️ 只看**名字**，不看文件内容 —— 空壳类也会被要求登记（这是有意的：
    "这个类是不是空壳"是另一件事，本脚本管不了，也不该假装能管）。
    """
    return bool(RE_TESTCLASS_BASE.fullmatch(name))

# 「墓碑行」：显式写着某东西**已被删除/作废**的行。
# 这类行里出现已不存在的类成员是**正确的历史记录**，不是漂移 —— 判据必须能区分
# "引用了一个不存在的成员"（漂移）与"记录了一个已被删除的成员"（史实）。
# 与 AGENTS.md §8 第 23 条「删代码留墓碑注释」是同一套做法。
# ⚠️ 标记里有裸的「删除」是有意的：矩阵写的是「按产品决定删除」「删除回归」等多种形态，
#    只认「已删除」会漏（2026-09-21 实测漏了 `unconfirmOrderCollection` 那条）。
#    代价是"仅仅提到删除"的行也被豁免 —— 宁可漏报漂移，也不要把史实判成错误。
TOMBSTONE_MARKERS = ('删除', '已作废', '已移除', '墓碑', 'SUPERSEDED', '不再存在', '已废弃')

SKIP_DIR_PARTS = {'build', 'out', '.git', '.gradle', '.gradlehome', 'node_modules',
                  'archive', 'backup', '__pycache__'}

# 「非自动化证据」声明：✅ 行的证据栏里这些词**被反引号包住**时，视为"已显式声明这不是自动化用例"。
# 这是判据 B 的第三条出路 —— 真机 / 真库上的 live 实测**本来就无法进集成测试**，
# 判据必须给它一条可核对的路，否则会被迫把真实证据改写成假话（本仓一贯取舍：判据认识合法形态）。
LIVE_MARKERS = ('live', '实测', '人工', '运维', '手工')

# ---------------------------------------------------------------------------
# 反向检查的「棘轮」：源码里存在、但矩阵**没登记**的测试类。
#
# 为什么需要它：矩阵是一份手写地图，测试类却天天在加。加一个用例、忘了登记，
# 矩阵就会**悄悄过期** —— 下一个人照它"找空白"时，那块空白其实已经被填上了
# （反过来也一样致命：他以为有覆盖，点开却是个空壳）。
#
# 为什么是"冻结集"而不是"直接要求全部登记"：2026-10 实测 119 个测试类里只有 47 个
# 被矩阵点名，= 72 个历史欠账。一次性要求补 72 行会让这道门禁**当天就红**、
# 逼人去关掉它。棘轮的做法是：欠账**登记在案、可见**，但**新欠账一律拒绝**。
#
# 纪律（改这个集合前先读 AGENTS.md §6.1：注释与代码不符比没有注释更危险）：
#   * `UNREGISTERED_TESTS` 只能**减**（把名字从集合里删掉 = 你把它登记进矩阵了）；
#   * 往里**加**一个名字必须写明理由，而本脚本会校验该名字**此刻真的存在**于 `src/test`
#     —— 防止有人把"删掉的旧名字"塞进来当挡箭牌，也防止集合长成一张永远清不掉的清单。
#   * 集合里出现"已经在矩阵里登记"的名字会**报错**（说明这行豁免已经过期）。
#
# 不进入反向检查的测试类（判据：它不回答"哪个业务场景有覆盖"）：
#   * `support/` 的基类（`AbstractIntegrationTest` / `AbstractScenarioTest`）；
#   * 纯工具类单测（`util/*Test`：调静态方法，没有 HTTP 层、没有场景）、
#     架构/配置类门禁（`architecture/`、`config/`、`service/ScheduledJobFailureAlertTest`）、
#     启动上下文（`AquaFlowApplicationTests`）。
#     它们没有行内场景可映射，硬塞进矩阵只会制造空行。
# ⚠️ 放宽豁免面的代价要认清：这几类的"覆盖"不再被矩阵门禁盯着。判据是
#    **它们本来就无法映射到 S1~S9 的某一个业务场景**；新增的集成/场景用例（`integration/**`）
#    一律仍然必须登记。
EXEMPT_TEST_PATH_PARTS = ('/support/', '/util/', '/architecture/', '/config/',
                          '/service/ScheduledJobFailureAlertTest.java')
EXEMPT_TEST_CLASSES = ('AquaFlowApplicationTests',)

UNREGISTERED_TESTS = {
    'AlertWebhookIntegrationTest', 'AnonymousFeedbackIntegrationTest',
    'ArchReviewFixesIntegrationTest', 'AuthBindingDtoValidationIntegrationTest',
    'AuthContextLifecycleIntegrationTest',
    'BarrelRefundDeliveryIntegrationTest', 'BindingApprovalConcurrencyIntegrationTest', 'CatalogOwnershipIntegrationTest',
    'CrossStationListMergeIntegrationTest',
    'CrossStationPoolRiskAndProfileIsolationIntegrationTest',
    'CustomerAddressSearchIntegrationTest', 'CustomerCreditRiskIntegrationTest',
    'CustomerPrivilegeIntegrationTest', 'CustomerRefusalWriteOffIntegrationTest',
    # 2026-10 并发会话（F-14 补零覆盖端点）新增的两个类：**已登记进矩阵 7.10 行**
    # （集成者确认落盘并验证 12 例全绿），故不在这里 —— 这里是"欠账表"，不是"新类回收站"。
    'DeliveryConfigGuideIntegrationTest', 'DeliveryFeeFieldsIntegrationTest',
    'DeliveryFeeIntegrationTest', 'DeliveryFeePoolVisibilityIntegrationTest',
    'DeliveryFloorVisibilityIntegrationTest', 'DeliveryItemProjectionIntegrationTest',
    'DeliveryMyEarningsIntegrationTest', 'DeliveryOrderDtoValidationIntegrationTest',
    'DeliveryPrepAndPayProjectionIntegrationTest', 'DepositOnlyForBarrelIntegrationTest',
    'DisposableBarrelLedgerBoundaryIntegrationTest', 'EmployeePlaceOrderIntegrationTest',
    'EnterpriseIdentityDefaultThresholdIntegrationTest',
    'EnterpriseIdentityDisabledIntegrationTest', 'EnterpriseIdentityIntegrationTest',
    'GrossProfitIntegrationTest',
    'InterStationSettlementIntegrationTest', 'InventoryBackfillIntegrationTest',
    'InventoryLockProtocolIntegrationTest', 'InventoryReconciliationIntegrationTest',
    'InventoryReservationBarrierIntegrationTest', 'InventoryReservationIntegrationTest',
    'ManagerOrderControllerRemovedIntegrationTest',
    # 2026-10 并发会话（F-14）新增，同上
    'ManagerPendingSummaryIntegrationTest', 'ManagerTodoIntegrationTest',
    'OfflinePaymentGuardIntegrationTest', 'OrderSettleStationIntegrationTest',
    'PaidBeforeDispatchIntegrationTest', 'PasswordInitializerIntegrationTest',
    'PaymentDtoValidationIntegrationTest', 'PaymentRefundResurrectionIntegrationTest',
    'PendingLevelOverrideIntegrationTest', 'ReceivableIntegrationTest',
    'ReconciliationAfterNewFeaturesIntegrationTest', 'StaffBindCodeIntegrationTest',
    'StationCreationPhoneIntegrationTest', 'StationCreditTermsIntegrationTest',
    'StationNearbySearchIntegrationTest', 'StationOperatingStatusIntegrationTest',
    'StationOrderListRiskMarkIntegrationTest', 'StationPricingIntegrationTest',
    'StationSetupGuideIntegrationTest', 'StationTicketDiscountIntegrationTest',
    'TicketConsumeIdempotencyIntegrationTest', 'TicketPackageAndLotIntegrationTest',
    'TicketPayPreviewIntegrationTest', 'UnpaidWechatOrderTimeoutIntegrationTest',
    'WechatChannelDisabledIntegrationTest',
}

# ⚠️ 棘轮（2026-10-01 补）：**集合大小也冻结** —— 上面那句"只能减"原来只写在注释里，
# 而代码只校验"这个名字此刻存在于 src/test"，所以"把新用例塞进欠账表、而不是登记进矩阵"
# **不会红**（实测本月新增的回归用例正是躺在下面这张表里）⇒ 棘轮名存实亡。
# 判据：len 超过冻结值即红；把类登记进矩阵后从这里删名 = 正常减小，不红。
# 确需新增豁免时**必须同时调大这个数并在同一行写清理由**（那是一次 review，不是顺手加一行）。
UNREGISTERED_TESTS_FROZEN = 62


def iter_java(root):
    if not os.path.isdir(root):
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
        for name in filenames:
            if name.endswith('.java'):
                yield os.path.join(dirpath, name)


def collect_java_facts():
    """返回索引：类型 -> 文件、类型 -> 成员、全部成员、全部源码文本，以及测试侧索引。

    成员名用宽松正则抓 `public/private/protected ... 名字(` 与字段声明 —— 目的是回答
    「这个名字在这个类里存在吗」，不追求完整词法分析（与 audit_comments.py 同一取舍）。

    ⚠️ **测试类与主源码类必须分开记**（2026-10 加固）：判据 B 要回答的是
    「这一行的证据是不是一个真实用例」，而不是「这个名字在仓库里有没有出现过」。
    原版把两边混进一个 `class_file`，于是主源码类名（`ReconciliationService`）也算"证据命中"，
    ✅ 行因此可以指着一个 Service 类宣称"有断言"。
    """
    class_file = {}
    class_members = {}
    all_members = set()
    all_text = []
    test_class_file = {}       # 测试类 -> 文件（只在 test 树里）
    test_methods = set()       # 测试类里的 `void xxx(` 方法名（矩阵里的裸驼峰方法引用靠它核对）
    newest_src_mtime = 0.0     # 测试树里最新的 .java 修改时刻（判"test-results 是不是旧的"）

    re_method = re.compile(r'^\s*(?:public|private|protected|static|final|synchronized|\s)*'
                           r'(?:[\w<>\[\],.?\s]+)\s+([a-zA-Z_]\w*)\s*\(', re.M)
    re_field = re.compile(r'^\s*(?:public|private|protected|static|final|\s)+'
                          r'[\w<>\[\],.?\s]+\s+([a-zA-Z_]\w*)\s*[;=]', re.M)
    re_void = re.compile(r'\bvoid\s+([a-z]\w*)\s*\(')

    for root in (TEST_ROOT, MAIN_ROOT):
        for path in iter_java(root):
            simple = os.path.basename(path)[:-len('.java')]
            # 测试源优先：同名类在 test 与 main 里同时存在时，测试那份才是矩阵要指的对象
            if simple not in class_file or root == TEST_ROOT:
                class_file[simple] = path
            if root == TEST_ROOT:
                test_class_file[simple] = path
                try:
                    newest_src_mtime = max(newest_src_mtime, os.path.getmtime(path))
                except OSError:
                    pass
            try:
                text = open(path, encoding='utf-8', errors='ignore').read()
            except OSError:
                continue
            all_text.append(text)
            members = set(re_method.findall(text)) | set(re_field.findall(text))
            if simple in class_members:
                members |= class_members[simple]
            class_members[simple] = members
            all_members |= members
            if root == TEST_ROOT:
                test_methods |= set(re_void.findall(text))

    # 前端源码也要一起索引：矩阵里的证据常引用**接口下发字段**（如 `owedSinceText`），
    # 那个名字只出现在小程序的 js/wxml 里，后端 Java 一个都没有。
    # 只扫 Java 会把它误报成"无处可寻的标识符"（2026-09-21 实测）。
    for root, exts in (('miniapp-user', ('.js', '.wxml')), ('miniapp-delivery', ('.js', '.wxml'))):
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
            for name in filenames:
                if name.endswith(exts):
                    try:
                        all_text.append(open(os.path.join(dirpath, name),
                                             encoding='utf-8', errors='ignore').read())
                    except OSError:
                        pass

    return (class_file, class_members, all_members, '\n'.join(all_text),
            test_class_file, test_methods, newest_src_mtime)


def read_results_counts():
    """从 build/test-results 数出真实件数。缺目录返回 None（由调用方决定是跳过还是失败）。

    ⚠️ 用 xml.etree 解析，**不要**先读成字符串再 `[xml]` —— 那会按 ANSI 解码，
    可能弄坏测试名并**静默少算**（AGENTS.md §5 对 PowerShell 版本的同一条告诫）。

    返回值里额外带 `names`（XML 里的类名集合）：调用方靠它判断这批结果是不是
    **只跑了一个子集的局部运行** —— 局部运行下 STATS 比对必然"不一致"，
    而那不是矩阵过期，是测试没跑全（两者必须能区分，否则门禁会周期性假红）。
    """
    files = glob.glob(RESULTS_GLOB)
    if not files:
        return None
    import xml.etree.ElementTree as ET
    classes = tests = failures = errors = skipped = 0
    names = set()
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        classes += 1
        tests += int(root.get('tests') or 0)
        failures += int(root.get('failures') or 0)
        errors += int(root.get('errors') or 0)
        skipped += int(root.get('skipped') or 0)
        names.add(root.get('name') or '')
    return {'classes': classes, 'tests': tests, 'failures': failures,
            'errors': errors, 'skipped': skipped, 'names': names}


# 「门禁脚本」的形状：与 scripts/check-gate-parity.js 的 GATE_PATTERN 同源
# （那边是"三处入口的清单是否一致"的正本；这里只用来判断"某个脚本真的是门禁吗"）。
# ⚠️ 改一处必须同步改另一处 —— 两边口径不一致会让判据 B 的证据类别漂移。
GATE_SCRIPT_PATTERN = re.compile(
    r'(?:^|[\s\'"[(\/])((?:scripts\/)?(?:check-|prod-startup|smoke-check|backup-restore-drill)[\w.-]*\.js'
    r'|(?:audit_|page_reach_|static_audit_)[\w.-]*\.py'
    r'|tests\/js\/run-all\.js|scan-secrets\.sh|provision-test-db\.sh|backup-restore-drill\.js)')
GATE_ENTRY_POINTS = ('.github/workflows/ci.yml', 'scripts/verify.sh', 'scripts/verify-local.js')


def index_gate_scripts():
    """返回「被三处验证入口真的执行的门禁脚本」的名字集合。

    判据 B 的第四类证据用它：证据栏点名 `audit_wxml_handlers.py` 这类脚本时，
    要求它**真的挂在某个入口上** —— 只"仓库里存在这个文件"不算，
    否则任何文档/脚本文件名都能冒充证据，判据又变回橡皮图章（F-20 要修的就是这个）。

    读法与 check-gate-parity.js 一致：**剥掉注释与 `echo` 提示行**，只留真的会执行的行
    （否则"提示里提了一句"会被当成"这道门禁跑了"，见该脚本头部的假绿告诫）。
    """
    names = set()
    for rel in GATE_ENTRY_POINTS:
        if not os.path.isfile(rel):
            continue
        try:
            text = open(rel, encoding='utf-8', errors='ignore').read()
        except OSError:
            continue
        text = re.sub(r'/\*[\s\S]*?\*/', '', text)
        text = '\n'.join(line for line in text.split('\n')
                         if not line.strip().startswith(('#', '//'))
                         and not re.match(r'^echo\b', line.strip()))
        names.update(os.path.basename(m.group(1)) for m in GATE_SCRIPT_PATTERN.finditer(text))
    return names


def stats_block(counts, today):
    lines = [
        STATS_BEGIN + ' （由 audit_scenario_matrix.py 生成，勿手改） -->',
        '> **当前实测**（生成于 %s，来源 `build/test-results/test/*.xml`）：'
        '**%d 个测试类 / %d 个用例 / %d 失败 / %d 错误**。'
        % (today, counts['classes'], counts['tests'], counts['failures'], counts['errors']),
        '>',
        '> 上面这段是**唯一**会与代码一起漂的件数，由门禁维护；'
        '下文各处「某日全量 N 例」是**带日期的历史快照**，不是断言，不要拿它们与今天的数字比较。',
        STATS_END,
    ]
    return '\n'.join(lines)


def extract_stats_block(text):
    i = text.find(STATS_BEGIN)
    if i < 0:
        return None
    j = text.find(STATS_END, i)
    if j < 0:
        return None
    return text[i:j + len(STATS_END)]


def replace_stats_block(text, block):
    i = text.find(STATS_BEGIN)
    if i < 0:
        return None
    j = text.find(STATS_END, i)
    if j < 0:
        return None
    return text[:i] + block + text[j + len(STATS_END):]


def main():
    args = sys.argv[1:]
    write_stats = '--write-stats' in args
    matrix_path = DEFAULT_MATRIX
    if '--matrix' in args:
        k = args.index('--matrix')
        if k + 1 < len(args):
            matrix_path = args[k + 1]

    print('=' * 88)
    print('  场景测试矩阵一致性门禁 · 矩阵声称的测试类/方法必须真实存在')
    print('=' * 88)

    if not os.path.isfile(matrix_path):
        print(f'[致命] 找不到矩阵文件：{matrix_path}')
        return 1
    matrix = open(matrix_path, encoding='utf-8', errors='ignore').read()

    (class_file, class_members, all_members, all_src,
     test_class_file, test_methods, newest_src_mtime) = collect_java_facts()
    print(f'已索引：{len(class_file)} 个 Java 类型（{TEST_ROOT} + {MAIN_ROOT}）；'
          f'其中测试类 {len(test_class_file)} 个 / 测试方法 {len(test_methods)} 个')

    # 逐行收集 token，并记住它出现的行**是不是墓碑行** —— 只出现在墓碑行上的引用豁免校验。
    # ⚠️ 墓碑标记常写在**上一行**（小节标题里写「按产品决定删除」，下一行才举出被删的方法名），
    # 所以判定要带一个**最多 3 行的回看窗口**，只看本行会漏（2026-09-21 实测）。
    raw_lines = matrix.split('\n')
    token_on_live_line = {}
    for ln, raw in enumerate(raw_lines):
        window = '\n'.join(raw_lines[max(0, ln - 3):ln + 1])
        is_tomb = any(k in window for k in TOMBSTONE_MARKERS)
        for m in RE_BACKTICK.finditer(raw):
            tok = m.group(1).strip()
            token_on_live_line[tok] = token_on_live_line.get(tok, False) or (not is_tomb)
    tokens = set(token_on_live_line)

    missing_classes = []     # 引用了不存在的测试类
    missing_members = []     # Class.method 里方法不存在（类属于主源码）
    missing_test_members = []  # 测试类.方法：类不在测试树里、或方法不是测试方法
    orphan_tokens = []       # 裸标识符既不是测试方法也不是任何源码里的名字
    checked_classes = checked_members = checked_test_members = 0

    for tok in sorted(tokens):
        if not token_on_live_line[tok]:
            continue             # 只出现在"已删除/已作废"的行上 = 史实，不是漂移

        # 1) 测试类名
        if RE_TEST_CLASS.match(tok):
            checked_classes += 1
            if tok not in class_file:
                missing_classes.append(tok)
            continue

        # 2) 类.成员
        mm = RE_MEMBER.match(tok)
        if mm:
            cls, member = mm.group(1), mm.group(2)
            if cls in test_class_file:   # 点名的是**测试类**的方法 → 必须真是它自己的方法
                checked_test_members += 1
                if member not in class_members.get(cls, set()):
                    missing_test_members.append(tok)
                continue
            if cls in class_file:
                checked_members += 1
                if member not in class_members.get(cls, set()):
                    missing_members.append(tok)
            # 左侧不是已知类型 → 大概率是 表.列（orders.settle_station_id），跳过
            continue

        # 3) 裸驼峰标识符
        if RE_CAMEL.match(tok):
            if tok in all_members or re.search(r'\b%s\b' % re.escape(tok), all_src):
                continue
            orphan_tokens.append(tok)

    # ---- A1（2026-10 加固）：全文件的测试类引用都必须在 src/test 里真实存在 -------------
    # 原版只校验"出现在非墓碑行上"的 token，于是**附录小节**里的引用全部逃过校验
    # （它们也是"文档说这个类存在"的声称）。测试类名不参与墓碑豁免：一个类要么存在，要么不存在，
    # 「删除某类」的史实行应该写成普通文字（不加反引号），而不是留一个看起来能点的死链。
    all_tokens = set()
    for raw in raw_lines:
        for m in RE_BACKTICK.finditer(raw):
            all_tokens.add(m.group(1).strip())
    file_wide_missing = []
    for tok in sorted(all_tokens):
        if not RE_TEST_CLASS.match(tok) or tok.startswith('*'):
            continue
        if tok not in test_class_file:
            file_wide_missing.append(tok)

    print(f'校验：测试类引用 {checked_classes} 处（全文件扫描另见下），类成员引用 {checked_members} 处')

    # ---- B：✅ 行必须落到一个**真实可核对的证据**上 ----
    # 可接受的证据形态（四类）：
    #   ① 真实存在的测试类（含通配 `*DtoValidationIntegrationTest`，命中一个即可；可带 `.method`）；
    #   ② 真实存在的测试方法名（`*Test.java` 里的 `void` 方法）；
    #   ③ 反引号包住的非自动化证据声明（`live` / `实测` / `人工` …）；
    #   ④ **被 CI 真的执行的**门禁脚本 / 静态工具（`audit_wxml_handlers.py`、`scan-secrets.sh` …）
    #      —— 它同样是机器可核对的证据，不是文档自查；判据是"它挂在某个验证入口上"，
    #      不是"仓库里恰好有同名文件"（只存在文件名的东西可以冒充，见 `looks_like_gate_script`）。
    # ⚠️ 主源码类名（`ReconciliationService`）、与 CI 无关的文档/资产文件名**不算证据**：
    #    2026-10 之前它们也能让「✅ 有断言」成立，那正是 F-20 说的"看着像判据其实恒真"。
    # ⚠️ 已知盲点：`SomeIntegrationTest.someMethod` 这种**跨文件写法**里，方法名不做核对
    #    （只核对类名 + 类内有没有同名方法；`RE_TESTCLASS_BASE` 不认 `Foo.method` 这种形状）。
    gate_scripts = index_gate_scripts()

    def declared_live(tok):
        return tok.strip("`'\" ") in LIVE_MARKERS

    def looks_like_gate_script(tok):
        """证据栏里的脚本名：必须**真的挂在某个验证入口上**才算证据（判据 B 的第 ④ 类）。"""
        base = os.path.basename(tok.strip())
        if not base or base != tok.strip():
            return False
        if base not in gate_scripts and os.path.splitext(base)[0] not in gate_scripts:
            return False
        stem = os.path.splitext(base)[0]
        return any(os.path.isfile(p) or os.path.isfile(os.path.join('scripts', p))
                   for p in (base, stem))

    def has_test_evidence(tok):
        if tok.startswith('*'):                                    # 通配测试类名
            suffix = tok[1:]
            return any(c.endswith(suffix) for c in test_class_file)
        mm = RE_MEMBER.match(tok)
        if mm and RE_TESTCLASS_BASE.match(mm.group(1)):             # 测试类.方法
            return mm.group(1) in test_class_file and mm.group(2) in class_members.get(mm.group(1), set())
        if RE_TESTCLASS_BASE.match(tok):
            return tok in test_class_file
        if RE_CAMEL.match(tok) and tok in test_methods:             # 裸测试方法名
            return True
        if looks_like_gate_script(tok):                             # ④ CI 真跑的门禁脚本
            return True
        return False

    unverifiable = []
    for ln, raw in enumerate(raw_lines, 1):
        if not raw.strip().startswith('|'):
            continue
        cells = [c.strip() for c in raw.strip().strip('|').split('|')]
        if len(cells) < 4 or '✅' not in cells[2]:
            continue
        evidence = cells[3]
        named = [n.strip() for n in re.findall(r'`([^`]+)`', evidence)]
        if any(declared_live(n) for n in named):
            continue                                  # 已显式声明：非自动化证据
        if any(has_test_evidence(n) for n in named):
            continue
        unverifiable.append((ln, cells[0], named[:3]))

    # ---- C：反向检查 —— src/test 里的测试类必须被矩阵登记（棘轮，只能减不能增）----
    # 「登记」= 类名作为行内代码在矩阵里出现过（`FooIntegrationTest` 或 `FooIntegrationTest.method`）。
    # 只要求"出现过"是有意的：矩阵正文 + 附录的写法五花八门，硬套表格格式会让判据变成噪声源。
    registered = {t for t in all_tokens if RE_TESTCLASS_BASE.match(t) and not t.startswith('*')}
    test_side_missing = []       # 源码里有、矩阵没登记、也不在冻结集里 = 新增欠账
    stale_exempt = []            # 冻结集里的名字：要么已登记，要么源码里已经不存在
    for cls, path in sorted(test_class_file.items()):
        if not looks_like_test_class(cls):
            continue
        if any(part in path.replace('\\', '/') for part in EXEMPT_TEST_PATH_PARTS):
            continue
        if cls in EXEMPT_TEST_CLASSES:
            continue
        if cls in registered:
            if cls in UNREGISTERED_TESTS:
                stale_exempt.append((cls, '矩阵里已经登记了它，冻结集里那行豁免已过期'))
            continue
        if cls not in UNREGISTERED_TESTS:
            test_side_missing.append(cls)
    for cls in sorted(UNREGISTERED_TESTS - set(test_class_file)):
        stale_exempt.append((cls, 'src/test 里已经没有这个类了（被删/改名），这行豁免该删掉'))

    problems = 0
    if file_wide_missing:
        problems += len(file_wide_missing)
        print()
        print(f'[A1] 矩阵引用了**不存在**的测试类（{len(file_wide_missing)} 个，全文件扫描）：')
        for t in file_wide_missing:
            print(f'      - {t}')
        print('      → 该类被改名/合并/删除了，矩阵对应那几行的「有覆盖」已经不作数。')
        print('        处理：要么补回用例，要么把矩阵那几行改成实际覆盖状态（🟡/❌）。')
        print('        （若这是"记录某个类已被删除"的史实行，把它写成普通文字、不要加反引号。）')

    if missing_test_members:
        problems += len(missing_test_members)
        print()
        print(f'[A2] 矩阵点名的**测试方法不存在**（{len(missing_test_members)} 处）：')
        for t in missing_test_members:
            print(f'      - {t}')
        print('      → 方法被改名/删掉，但矩阵还在拿它当证据。')
        print(f'      （本轮核对了 {checked_test_members} 处「测试类.方法」引用）')

    if missing_members:
        problems += len(missing_members)
        print()
        print(f'[A3] 矩阵引用的**类成员不存在**（{len(missing_members)} 处）：')
        for t in missing_members:
            print(f'      - {t}')

    if orphan_tokens:
        problems += len(orphan_tokens)
        print()
        print(f'[A4] 矩阵里**无处可寻**的标识符（{len(orphan_tokens)} 个）：')
        for t in orphan_tokens:
            print(f'      - {t}')
        print('      → 既不是测试方法名，也不在任何 main/test 源码里出现。'
              '多半是方法被改名，或矩阵里写错了。')

    if test_side_missing:
        problems += len(test_side_missing)
        print()
        print(f'[C] 源码里存在、但矩阵**没有登记**的测试类（{len(test_side_missing)} 个）：')
        for t in test_side_missing:
            print(f'      - {t}')
        print('      → 矩阵已经过期：它回答不了"这个场景有没有覆盖"。')
        print('        处理：把该类登记进矩阵对应场景行（只写 `类名` 也算登记），')
        print('              或确属"无场景可映射"时加进 audit_scenario_matrix.py 的')
        print(f'              UNREGISTERED_TESTS（当前冻结 {len(UNREGISTERED_TESTS)} 个，只能减不能增，'
              '加一个必须带理由）。')

    if len(UNREGISTERED_TESTS) > UNREGISTERED_TESTS_FROZEN:
        over = len(UNREGISTERED_TESTS) - UNREGISTERED_TESTS_FROZEN
        problems += over
        print()
        print(f'[C·棘轮] UNREGISTERED_TESTS 涨了 {over} 个'
              f'（{len(UNREGISTERED_TESTS)} > 冻结值 {UNREGISTERED_TESTS_FROZEN}）：')
        print('      → 欠账表**只能减不能增**。涨了说明有测试类"没登记进矩阵、直接塞进豁免集"，')
        print('        那正是这条棘轮要拦的事（否则矩阵越用越假，而门禁一直绿）。')
        print('        处理二选一：① 把那个类登记进矩阵对应场景行（正道）；')
        print('        ② 确属"无场景可映射"时，改 audit_scenario_matrix.py 的')
        print('           UNREGISTERED_TESTS_FROZEN 并在同一行写清为什么（走 review）。')

    if stale_exempt:
        # ⚠️ 只**提醒**、不判红（2026-10 定）：豁免表里的名字"已经登记进矩阵"或"源码里已不存在"
        # 都属**无害的口径漂移**，不是"矩阵在骗人"。判红会逼人在一次并发改名（本仓常态）里
        # 去追一个不影响任何结论的名字；而真正该红的两条（新测试类没登记、引用了不存在的类）
        # 仍然会红。清理由读到这行提醒的人顺手做。
        print()
        print(f'[C·提醒] UNREGISTERED_TESTS 里已经过期的豁免（{len(stale_exempt)} 个，不判红）：')
        for t, why in stale_exempt:
            print(f'      - {t}：{why}')
        print('      → 判据：集合里的名字**最好是**"源码里有、矩阵里没有"的真实欠账；')
        print('        已登记的、或源码里已不存在的，顺手从 audit_scenario_matrix.py 里删掉即可。')

    # ---- 件数块 ----
    if unverifiable:
        problems += len(unverifiable)
        print()
        print(f'[B] 标了 ✅ 却**指不出任何真实用例**的行（{len(unverifiable)} 行）：')
        for ln, sid, named in unverifiable:
            hint = ('证据栏没有反引号标识符' if not named
                    else '证据栏的名字都不是真实测试类/测试方法/已接入 CI 的门禁脚本：'
                         + ', '.join(named))
            print(f'      L{ln:<6d} {sid:8s} {hint}')
        print('      → 补上测试类或测试方法名；这一行若靠 live 实测（或人工审计）而非用例，')
        print('        请在证据栏把 `live` / `实测` 这类声明**用反引号包住**，本检查即视为已声明。')
        print('        ⚠️ 主源码类名（如 `ReconciliationService`）不算证据：它证明不了"这一行有断言"。')

    counts = read_results_counts()
    stats_skipped = False      # STATS 判据被跳过（结果不可信）时置位，末尾打一条 ASCII 判据供上层看
    if counts is None:
        stats_skipped = True
        print()
        print('[D] 件数一致性：**跳过** —— 没有 build/test-results/test/*.xml')
        print('    （先跑一次 `gradlew test` 才会生成；CI 上一定存在，本机新克隆时会跳过）')
    else:
        today = datetime.date.today().isoformat()
        fresh = stats_block(counts, today)
        block = extract_stats_block(matrix)
        # 局部运行 / 结果比源码旧 ⇒ 这批件数**不可信**（2026-10 实测踩到过一次：
        # 并发会话正好在跑 `--tests` 定向测试，`--write-stats` 把「2 类 / 12 用例」
        # 写进了文档，而文档那段话是"当前实测"）。判据：「重新生成」只允许拿**全量且不比源码旧**
        # 的结果去覆盖；否则宁可不写（并说清原因），也不让文档记下一个假的全局数字。
        xml_names = {n for n in counts['names'] if n}
        expected_names = {'com.example.aquaflow.' + c for c in test_class_file}
        is_partial = bool(expected_names) and bool(xml_names) and xml_names < expected_names
        xml_files = glob.glob(RESULTS_GLOB)
        xml_mtime = max((os.path.getmtime(f) for f in xml_files), default=0.0)
        is_stale = bool(xml_files) and newest_src_mtime > xml_mtime
        forced = '--force-stats' in args
        if write_stats and (is_partial or is_stale) and not forced:
            stats_skipped = True
            reasons = []
            if is_partial:
                reasons.append('局部运行（XML 里的类只是测试类集合的真子集）')
            if is_stale:
                reasons.append('结果比测试源码旧（改了用例没重跑测试）')
            print()
            print('[D] **拒绝写回件数** —— 这批 test-results 不可信：')
            for r in reasons:
                print(f'      · {r}')
            print(f'      实测 = {counts["classes"]} 类 / {counts["tests"]} 用例'
                  f'（XML {len(xml_files)} 个 / 测试树 {len(expected_names)} 个候选类）')
            print('    → 先跑全量 `gradlew cleanTest test`，再跑本脚本 --write-stats。')
            print('      确实要写入这批数字时显式加 --force-stats（留给"就要记局部运行"的少见场景）。')
        elif write_stats:
            if block is None:
                print()
                print('[D] 矩阵里没有 MATRIX-STATS 块，已追加到文件开头之后。')
                new = replace_stats_block(matrix, fresh)
                if new is None:
                    lines = matrix.split('\n')
                    insert_at = 1 if lines and lines[0].startswith('#') else 0
                    new = '\n'.join(lines[:insert_at] + ['', fresh, ''] + lines[insert_at:])
                open(matrix_path, 'w', encoding='utf-8', newline='\n').write(new)
            else:
                open(matrix_path, 'w', encoding='utf-8', newline='\n').write(
                    replace_stats_block(matrix, fresh))
            print()
            print(f'[D] 已写回件数：{counts["classes"]} 类 / {counts["tests"]} 用例')
        elif block is None:
            print()
            print('[D] 件数一致性：矩阵里没有 MATRIX-STATS 块（不判失败）。')
            print('    建议跑一次 `python audit_scenario_matrix.py --write-stats` 建立基线。')
        else:
            # 上面对 is_partial / is_stale 的判定同样适用于**校验**路径：
            # 局部运行、结果比源码旧 —— 都不是"矩阵过期"，判据必须能区分，
            # 否则门禁会在任何一次定向跑测试后假红，下一个人就会去改数字。
            shown = re.findall(r'(\d+)\s*个测试类\s*/\s*(\d+)\s*个用例', block)
            ok = shown and int(shown[0][0]) == counts['classes'] and int(shown[0][1]) == counts['tests']
            if not ok and is_partial:
                stats_skipped = True
                print()
                print(f'[D] 件数一致性：**跳过** —— test-results 是**局部运行**的产物'
                      f'（{len(counts["names"])} 个类的 XML，而测试树里有 {len(expected_names)} 个候选类）。')
                print('    （判据：STATS 只能与"全量 cleanTest test"的结果比；'
                      '定向跑测试后不必改矩阵，跑全量即可。）')
            elif not ok and is_stale:
                stats_skipped = True
                print()
                print('[D] 件数一致性：**跳过** —— test-results 比测试源码旧'
                      '（改了用例没重跑测试）。')
                print(f'      矩阵 STATS 块 = {shown[0] if shown else "（读不出数字）"}')
                print(f'      实测（旧结果）= ({counts["classes"]}, {counts["tests"]})')
                print('    → 先跑 `gradlew cleanTest test` 让结果对上源码，再看这道门禁。')
            elif not ok:
                problems += 1
                print()
                print('[D] 件数不一致：')
                print(f'      矩阵 STATS 块 = {shown[0] if shown else "（读不出数字）"}')
                print(f'      实测          = ({counts["classes"]}, {counts["tests"]})')
                print('      → 跑 `python audit_scenario_matrix.py --write-stats` 更新。')
            else:
                print()
                print(f'[D] 件数一致：{counts["classes"]} 类 / {counts["tests"]} 用例'
                      f'（失败 {counts["failures"]} / 错误 {counts["errors"]}）')

    print('-' * 88)
    if problems:
        print(f'发现 {problems} 处问题：矩阵与代码已经不一致。')
        # ASCII 哨兵（skill §8.31）：中文在 fd 里会被编码毁成 `?`，上层脚本判绿/判红要靠它。
        print(f'AQUAFLOW_SCENARIO_MATRIX_FAIL {problems}')
        return 1
    print('通过：矩阵声称的测试类/方法全部真实存在，且没有未登记的新测试类。')
    if stats_skipped:
        # 把"STATS 判据这一轮没生效"单独说清（CI 上要能一眼看见，别把"跳过"读成"通过"，
        # 同 AGENTS §5「没跑不许当成通过」）。
        print('AQUAFLOW_SCENARIO_MATRIX_STATS_SKIPPED')
    print('AQUAFLOW_SCENARIO_MATRIX_OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
