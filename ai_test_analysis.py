#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 辅助测试分析：先把**可核对的机器事实**摆出来，再让 AI 做缺口分析。

为什么不是"直接问 AI 覆盖够不够"
--------------------------------------------------------------------------
让 AI 凭空判断"测试够不够"只会得到一段听起来合理、无法核对的话。本仓的既定纪律是
「能验证才写，不能验证就放进待确认」（AGENTS.md §10），所以这里的分工是：

    本脚本 = 只产出**事实**（可复算、可证伪、每条都能指到文件行）
    AI     = 在事实之上做**判断**（哪里是洞、下一个用例该打哪、哪条不变式没人守）

事实与判断分开，判断才可被反驳 —— 这才是"AI 辅助"能落地的前提。

产出的六类事实
--------------------------------------------------------------------------
1. **端点 → 测试反向索引**：每个 HTTP 端点被哪些测试类引用过。
   （`api_reverse_audit.py` 走的是"端点 → 小程序调用方"，方向不同；本脚本补的是"端点 → 测试"。）
2. **零测试端点**：一个测试类都没引用过的端点，按业务命名空间分组。
3. **不变式覆盖**：AGENTS.md §1 点名的那些领域不变式锚点（结算站、桶账唯一写入口、
   幂等键、对账等式……）在测试源码里的出现次数。**0 次 = 这条不变式没有任何用例在守。**
4. **矩阵 vs 现实**：场景矩阵里标 ✅ 但证据栏指不出任何真实测试类的行。
5. **改动与测试不同步**：工作区里改了 `src/main` 却没有配套改测试的文件。
6. **对账等式的测试引用**：每个等式键名（b3a/b3d/E6/p2a……）在测试里被点名过没有。

已知偏差（必须读，别把下界当全量）
--------------------------------------------------------------------------
* 端点识别走**字符串字面量拼接**：`"/api/x/" + id + "/y"` 能认，
  但**路径段由函数参数传入**（`buildUrl(kind)`）或来自常量映射表时认不出 →
  **零测试端点数是个下界（偏多）**。这与 `api_reverse_audit.py` 的第三条偏差同源
  （见 AGENTS.md §7），引用前请回原文件复核。
* 端点路径模板 `{id}` 按 `[^/]+` 匹配，因此 `/a/{id}/b` 与 `/a/x/b` 会互相命中；
  这是**放宽**方向（宁可少报零覆盖端点），不会漏掉"有测试"的结论。
* 只看 `src/test/java`，不认小程序侧的测试（本仓没有）。

用法
--------------------------------------------------------------------------
    python ai_test_analysis.py                  # 打印 Markdown 报告
    python ai_test_analysis.py --json out.json  # 同时落一份机器可读的事实包
    python ai_test_analysis.py --top 30         # 每个清单最多列多少条（默认 40）

退出码：**恒为 0**。这是分析工具不是门禁 —— 它会报"零测试端点"这类需要人判断的东西，
让它拦红 CI 只会逼人加白名单。要硬门禁请用 `audit_scenario_matrix.py`。
"""

import os
import re
import sys
import json
import glob
import subprocess

try:  # 任何控制台代码页下都能打印中文
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

CTRL_ROOT = 'AquaFlow-backend/src/main/java'
TEST_ROOT = 'AquaFlow-backend/src/test/java'
MATRIX = 'docs/audit/2026-09-16-场景测试矩阵.md'

# 类级前缀。注意**三种写法**都要认，少认一种会静默丢掉整个 Controller 的前缀：
#   @RequestMapping("/api/x")                        ← 最常见
#   @RequestMapping(value = "/api/x", produces = ..)  ← 带其它属性
#   @RequestMapping({"/api/stations", "/api/station"}) ← **数组写法**（StationController 就是它）
# 第三种漏认的后果：该 Controller 的所有端点都变成裸路径（`/mine`、`/{id}`），
# 于是"零测试端点"里混进一批假条目，而且它们永远匹配不到测试里的 `/api/stations/...`。
# 2026-09-21 实测踩过一次，8 条假条目全部来自 StationController。
RE_CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?\{?\s*["\']([^"\']*)["\']')
RE_METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*)?["\']([^"\']*)["\'])?')

RE_TEST_CLASS_DECL = re.compile(r'\bclass\s+([A-Z]\w*)')

# AGENTS.md §1「不可凭直觉改写的领域不变量」里的锚点。
# 每个锚点 = 一条"必须有人守"的规则；测试里 0 次出现就意味着这条规则只靠代码自觉。
INVARIANTS = {
    'settle_station_id': '三站语义：营收归结算站（读取一律 coalesce）',
    'settleStation': '结算站推导正本 StationUtil.settleStation',
    'markPaidIfCollectable': '唯一把 payment_status 写成已付(2) 的入口',
    'recordCashCollection': '现金收款必须补写 PAID 流水（账证一致）',
    'applyDepositOnPaid': '押金只在真收到钱时入账',
    'confirmPendingToPaid': '待收款流水就地确认（换站只搬 PENDING）',
    'movePendingToStation': '待收款流水跟着结算站搬站',
    'first_barrel_order': '首单口径：整段跳过回桶核对',
    'firstBarrelOrder': '首单口径（实体字段）',
    'consumeLots': '桶权益批次的唯一消耗入口',
    'returnEmpty': '还桶是清欠的唯一动作',
    'syncOwedSince': '欠桶起始时间的唯一维护点',
    'settleIfCollected': '核销的 CAS 必须带 payment_status = 2',
    'countCustomerOfStation': '归属判据 = 绑定 ∪ 本站订单（唯一正确实现）',
    'CustomerProfileMask': '履约站侧只能看到订单快照，不许下发客户画像',
    'isValidTransition': '订单状态机：非法流转必须被拒',
    'active_order_id': 'uk_payment_active_order：一单一条活跃流水',
    'uk_payment_idempotency': '无订单支付必须带客户端幂等键',
    'restoreTicketsForOrder': '退款按原路径回补水票批次',
    'creditPurchasedTickets': '购票按实付均价快照进批次',
    'consumeFifo': '水票消耗按 FIFO',
    'createLot': '批次唯一写入口 TicketLotService / BarrelLedgerService',
    'dailyReconcile': '每日 03:00 落对账结果（不平发 SYSTEM 告警）',
    'involvesDepositOrBarrelRights': '涉押金/桶权益的单禁止进抢单池',
}

# 对账等式键名 → 含义。测试里点名过没有，直接决定"这条等式被谁守着"。
EQUALITY_KEYS = ['depositAccount', 'paymentStatus', 'barrelState', 'inventory',
                 'E3_rightVsLot', 'E4_occupiedOutOfRange', 'E5_physicalConservation',
                 'E6_depositShortfall', 'E7_storageStale', 'E8_ticketVsLot']

SKIP_DIR_PARTS = {'build', 'out', '.git', '.gradle', '.gradlehome', 'node_modules',
                  'archive', 'backup', '__pycache__'}


def iter_files(root, ext):
    if not os.path.isdir(root):
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
        for name in filenames:
            if name.endswith(ext):
                yield os.path.join(dirpath, name)


def read(path):
    try:
        return open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        return ''


# ---------------------------------------------------------------- 端点抽取

def collect_endpoints():
    """返回 [(METHOD, path, controller_simple_name, file, line)]。"""
    out = []
    for path in iter_files(CTRL_ROOT, '.java'):
        if not os.path.basename(path).endswith('Controller.java'):
            continue
        text = read(path)
        cls = os.path.basename(path)[:-len('.java')]
        m = RE_CLASS_MAPPING.search(text)
        base = m.group(1) if m else ''
        for mm in RE_METHOD_MAPPING.finditer(text):
            verb = mm.group(1).upper()
            sub = mm.group(2) or ''
            full = (base + sub) or base
            if not full.startswith('/'):
                full = '/' + full
            line = text[:mm.start()].count('\n') + 1
            out.append((verb, full, cls, path, line))
    return out


# ------------------------------------------------- 测试里的路径模式（含拼接）

def path_patterns_in(text):
    """从一份 Java 测试源码里抽出所有 `/api/...` 路径模式。

    难点是拼接：`"/api/delivery/orders/" + order + "/complete"`。
    这里做**前向扫描**：读到字面量后，只要后面紧跟 `+`，就继续吃掉
    下一个字面量（直接续上）或一个表达式（记成通配 \\x00）。
    这是启发式而非完整词法分析 —— 与 audit_comments.py 同一取舍。
    """
    pats = []
    i = 0
    n = len(text)
    while True:
        i = text.find('"/api/', i)
        if i < 0:
            break
        cur = i + 1                      # 进入开引号
        buf = []
        ok = True
        while True:
            j = text.find('"', cur)
            if j < 0:
                ok = False
                break
            buf.append(text[cur:j])
            cur = j + 1
            k = cur
            while k < n and text[k] in ' \t\r\n':
                k += 1
            if k >= n or text[k] != '+':
                break                    # 表达式结束
            k += 1
            while k < n and text[k] in ' \t\r\n':
                k += 1
            if k < n and text[k] == '"':  # 又是一个字面量，直接续
                cur = k + 1
                continue
            # 一个变量/表达式：扫到顶层 + , ) ; 为止
            depth = 0
            s = k
            stopped_at_plus = False
            while s < n:
                c = text[s]
                if c in '([{':
                    depth += 1
                elif c in ')]}':
                    if depth == 0:
                        break
                    depth -= 1
                elif depth == 0 and c in ',;':
                    break
                elif depth == 0 and c == '+' and text[s:s + 2] != '++':
                    stopped_at_plus = True
                    break
                s += 1
            buf.append('\x00')            # 通配一段
            if not stopped_at_plus:
                break
            k = s + 1
            while k < n and text[k] in ' \t\r\n':
                k += 1
            if k < n and text[k] == '"':
                cur = k + 1
                continue
            # `+ "/x"` 之外还有更复杂的表达式，放弃这条后半段
            break
        if ok and buf:
            # ⚠️ **必须在 `?` 处截断**：测试里的调用几乎都带查询串
            # （`get("/api/barrels/summary?stationId=" + station, ...)`）。
            # 不截断就会拼出 `/api/barrels/summary?stationId=`，与端点模板 `/api/barrels/summary`
            # 匹配不上 —— 后果是**一大批在用的端点被误报成"零测试引用"**。
            # 2026-09-21 实测：修之前报 34 个零测试端点，抽查发现 `barrels/summary`、
            # `inventory/inbound`、`order-templates` 其实全都有用例。
            # 端点路径本身不含查询串（只取自 `@RequestMapping` 的值），所以截断是安全的。
            pat = ''.join(buf).split('?')[0]
            if pat:
                pats.append(pat)
        i = cur
    return pats


def pattern_to_regex(pat):
    """把路径模式编译成匹配端点模板的正则：字面量转义，通配 \\x00 → [^/]*。"""
    parts = pat.split('\x00')
    return re.compile('^' + '[^/]*'.join(re.escape(p) for p in parts) + '$')


# 「赋给变量的 /api 路径常量」：`private static final String ITEMS = "/api/manager/earning-items";`
RE_PATH_CONST = re.compile(r'=\s*"(/api/[^"\s]*)"')


def path_prefixes_in(text):
    """把路径常量当作**前缀**模式。

    形状：常量声明处写全了路径，但调用处是 `get(ITEMS + "/" + id, ...)` ——
    拼接跨了一个变量，前向扫描拼不出来，于是该子树下的端点会被误报成"零测试引用"。
    2026-09-21 实测：`StaffEarningItemIntegrationTest` 的 4 个 earning-items 端点就是这么误报的。

    放宽成"这个前缀底下的一切"是安全的：常量本身已经限定了子树，
    而它必须写成 `= "/api/..."` 才算数（不是随便一个字符串字面量）。
    """
    return {m.group(1).rstrip('/') for m in RE_PATH_CONST.finditer(text)}


def endpoint_to_regex(path):
    """端点模板 {id} → [^/]+。"""
    parts = re.split(r'(\{[^}]*\})', path)
    return re.compile('^' + ''.join('[^/]+' if p.startswith('{') else re.escape(p)
                                    for p in parts if p) + '$')


# ------------------------------------------------------------------ 主流程

def main():
    argv = sys.argv[1:]
    top = 40
    if '--top' in argv:
        try:
            top = int(argv[argv.index('--top') + 1])
        except (IndexError, ValueError):
            pass
    json_out = None
    if '--json' in argv:
        try:
            json_out = argv[argv.index('--json') + 1]
        except IndexError:
            pass

    endpoints = collect_endpoints()

    # 测试类 → 它用到的路径模式（含"路径常量"前缀，见 path_prefixes_in）
    test_pats = {}
    test_prefixes = {}
    test_text_by_class = {}
    test_all_text = []
    for path in iter_files(TEST_ROOT, '.java'):
        text = read(path)
        test_all_text.append(text)
        cls = os.path.basename(path)[:-len('.java')]
        pats = path_patterns_in(text)
        prefixes = path_prefixes_in(text)
        if pats or prefixes:
            test_pats[cls] = [pattern_to_regex(p) for p in pats]
            test_prefixes[cls] = prefixes
        test_text_by_class[cls] = text
    test_all = '\n'.join(test_all_text)

    def covered_by(ep):
        """返回引用了该端点的测试类集合。

        两种匹配：① 前向扫描拼出来的完整路径模式；② 路径常量前缀（常量子树里的一切）。
        """
        hits = set()
        for cls, rxs in test_pats.items():
            if any(r.match(ep) for r in rxs):
                hits.add(cls)
                continue
            for p in test_prefixes.get(cls, ()):
                if ep == p or ep.startswith(p + '/'):
                    hits.add(cls)
                    break
        return sorted(hits)

    # 1) 端点 → 测试类
    index = {}
    for verb, ep, ctrl, f, line in endpoints:
        index[(verb, ep)] = covered_by(ep)

    zero = [(v, e, c, f, l) for (v, e, c, f, l) in endpoints if not index.get((v, e))]

    # 2) 不变式覆盖
    inv = []
    for token, desc in INVARIANTS.items():
        in_tests = len(re.findall(r'\b%s\b' % re.escape(token), test_all))
        inv.append((token, desc, in_tests))
    inv_untested = [x for x in inv if x[2] == 0]
    inv_thin = [x for x in inv if 0 < x[2] <= 2]

    # 3) 对账等式：既要看"这个词出现过没有"，更要看"这个**键名**有没有被单独断言过"。
    #    两者差别很关键：多数用例是 `runReconcile()` 之后**遍历所有项断言为 0**，
    #    从不点名任何一条键 → 一旦某条等式的**定义**有洞（把合法业务状态算成差异），
    #    遍历式断言只会告诉你"不平了"，不会告诉你"是哪条、为什么"。
    #    2026-09-21 场景用例抓到 b3d/E6 误报，正是因为此前没有任何用例单独点过这些键。
    eq = [(k, len(re.findall(r'\b%s\b' % re.escape(k), test_all)),
           len(re.findall(r'"%s"' % re.escape(k), test_all))) for k in EQUALITY_KEYS]

    # 4) 矩阵：标 ✅ 但证据栏指不出任何真实测试类
    matrix_gaps = []
    if os.path.isfile(MATRIX):
        known_cls = {os.path.basename(p)[:-len('.java')] for p in iter_files(TEST_ROOT, '.java')}
        # 证据栏也可能只写**测试方法名**（矩阵两种写法都出现过）。方法名同样算可核对，
        # 否则会把 L73「`occupiedCountsOwedBarrelsEvenWithoutRights`」误报成"指不出测试"。
        known_m = set()
        for p in iter_files(TEST_ROOT, '.java'):
            known_m |= set(re.findall(r'\bvoid\s+([a-z]\w*)\s*\(', read(p)))
        for ln, raw in enumerate(read(MATRIX).split('\n'), 1):
            if not raw.strip().startswith('|'):
                continue
            cells = [c.strip() for c in raw.strip().strip('|').split('|')]
            if len(cells) < 4:
                continue
            if '✅' not in cells[2]:
                continue
            named = re.findall(r'`([A-Za-z]\w*)`', cells[3])
            resolvable = [n for n in named if n in known_cls or n in known_m]
            if not resolvable:
                if not named:
                    why = '证据栏只有文字描述，没有任何可核对的标识符'
                else:
                    # 把"是什么但不是测试"说清楚 —— 否则读者无法判断这是缺口还是正常
                    kinds = []
                    for n in named[:4]:
                        kinds.append(f'{n}（非测试标识符）')
                    why = '证据栏的标识符都不是测试类/方法：' + '、'.join(kinds)
                matrix_gaps.append((ln, cells[0], why))

    # 5) 改动与测试不同步
    drift = []
    try:
        out = subprocess.run(['git', 'status', '--porcelain'], capture_output=True, text=True,
                             timeout=30).stdout
        main_changed, test_changed = [], []
        for line in out.split('\n'):
            p = line[3:].strip().strip('"')
            if p.startswith(CTRL_ROOT) or p.startswith('AquaFlow-backend/src/main'):
                main_changed.append(p)
            elif p.startswith(TEST_ROOT) or p.startswith('AquaFlow-backend/src/test'):
                test_changed.append(p)
        if main_changed and not test_changed:
            drift = main_changed
    except Exception:
        pass

    # ---------------------------------------------------------- 输出
    W = 92
    print('=' * W)
    print('  AI 辅助测试分析 · 机器事实包（脚本只给事实，判断留给 AI / 人）')
    print('=' * W)
    print(f'端点 {len(endpoints)} 个 | 测试类 {len(test_text_by_class)} 个 | '
          f'其中有 HTTP 路径引用的 {len(test_pats)} 个')
    print()

    print('## 1. 零测试端点（没有任何测试类引用过）')
    print()
    if not zero:
        print('  无。')
    else:
        by_ns = {}
        for v, e, c, f, l in zero:
            ns = '/'.join(e.split('/')[:3])
            by_ns.setdefault(ns, []).append((v, e, c, l))
        print(f'  共 {len(zero)} 个（**这是下界**：路径段由函数参数传入的调用认不出来，见脚本头「已知偏差」）')
        for ns in sorted(by_ns):
            rows = by_ns[ns]
            print(f'  · {ns}  —— {len(rows)} 个')
            for v, e, c, l in rows[:top]:
                print(f'      {v:6s} {e}')
    print()

    print('## 2. 领域不变式锚点（AGENTS.md §1）作为**符号**在测试源码里出现几次')
    print()
    print('  ⚠️ 读法：0 次 **不等于行为没被测** —— 很多规则是通过 HTTP 端到端间接覆盖的')
    print('     （例如订单状态机由 `OrderStateMachineIntegrationTest` 走接口验证，')
    print('      而不是直接调 `isValidTransition`）。')
    print('     它回答的是另一个问题：**这条规则的实现细节有没有被直接钉住？**')
    print('     没有的话，行为断言仍可能全绿，而实现已经悄悄漂移（§8.15 就是这样丢过字段）。')
    print()
    if not inv_untested:
        print('  无。')
    else:
        for token, desc, cnt in inv_untested:
            print(f'  [0 次] {token:34s} {desc}')
    if inv_thin:
        print()
        print(f'  （另外 {len(inv_thin)} 条只有 1~2 次引用，属于"提过但没真守"：）')
        for token, desc, cnt in inv_thin[:top]:
            print(f'  [{cnt} 次] {token:34s} {desc}')
    print()

    print('## 3. 对账等式：词出现过几次 / **键名**被单独断言过几次')
    print()
    print('  ⚠️ 读法：第二列 0 不代表这条等式没人管 —— 多数用例是 `runReconcile()` 之后')
    print('     **遍历所有项断言为 0**。它的用途是回答"这条等式的**定义**有没有被单独钉住"：')
    print('     定义有洞（把合法业务状态算成差异）时，遍历式断言只会说"不平了"，')
    print('     说不出是哪条。2026-09-21 场景用例抓到 b3d/E6 误报，就是因为此前无人点名。')
    print()
    print(f'  {"等式键名":28s} {"词出现":>6s} {"键名被断言":>10s}')
    for k, bare, quoted in eq:
        flag = '  ← 定义从未被单独核对' if quoted == 0 else ''
        print(f'  {k:28s} {bare:6d} {quoted:10d}{flag}')
    print()

    print('## 4. 标 ✅ 但**没有自动化用例**支撑的行（覆盖靠报告 / 字段 / 人工）')
    print()
    print('  ⚠️ 这一节与门禁 `audit_scenario_matrix.py` 的第 4 项**判据不同，不是冲突**：')
    print('     门禁问「证据可不可核对」（认测试类/方法、认仓库里真实存在的脚本与报告、')
    print('     认显式写明的 live 实测声明）—— 过不了就红，因为那是"一句无人能查的话"。')
    print('     这里问的是「这一行的覆盖背后到底有没有**测试用例**」——')
    print('     靠审计报告或人工核对当然是合法证据，但它不会在回归里替你挡住改动。')
    print()
    if not matrix_gaps:
        print('  无。')
    else:
        for ln, sid, why in matrix_gaps[:top]:
            print(f'  L{ln:<5d} {sid:8s} {why}')
    print()

    print('## 5. 工作区改动与测试不同步')
    print()
    if not drift:
        print('  无（改了 main 也动了 test，或本次没有 main 改动）。')
    else:
        print(f'  改了 {len(drift)} 个 main 文件但一个测试文件都没动：')
        for p in drift[:top]:
            print(f'      {p}')
    print()

    print('-' * W)
    print('下一步（这部分是**判断**，请让 AI 在以上事实上做，别凭空问）：')
    print('  a) 第 1 节里挑**业务关键路径**的端点补契约用例（运维/认证类可以留白，但要显式登记）。')
    print('  b) 第 2 节 0 次的锚点：先回答"这条规则现在靠什么保证"，答不出就必须补用例。')
    print('  c) 第 3 节 0 次的等式：确认它是"只在日结里算、测试另有覆盖"还是"真的没人守"。')
    print('  d) 缺的是**链路**还是**环节**：链路用 integration/scenario/ 下的场景用例，')
    print('     环节用现有 *IntegrationTest。两者都要有，别用一种替代另一种。')
    print('  e) 每条结论都必须能指到 `文件:行号`，指不到的就放进"待确认"而不是当结论说。')

    if json_out:
        pack = {
            'endpoint_count': len(endpoints),
            'zero_test_endpoints': [{'method': v, 'path': e, 'controller': c} for v, e, c, _, _ in zero],
            'invariants_untested': [{'token': t, 'desc': d} for t, d, _ in inv_untested],
            'invariants_thin': [{'token': t, 'desc': d, 'hits': n} for t, d, n in inv_thin],
            'equalities': [{'key': k, 'word_hits': b, 'key_asserted': q} for k, b, q in eq],
            'matrix_gaps': [{'line': l, 'id': i, 'why': w} for l, i, w in matrix_gaps],
            'main_changed_without_tests': drift,
            'endpoint_test_index': [
                {'method': v, 'path': e, 'tests': hits}
                for (v, e), hits in sorted(index.items(), key=lambda kv: (kv[0][1], kv[0][0]))
            ],
        }
        open(json_out, 'w', encoding='utf-8', newline='\n').write(
            json.dumps(pack, ensure_ascii=False, indent=2))
        print()
        print(f'机器事实包已写入：{json_out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
