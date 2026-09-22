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
1. **测试类引用必须落地**：矩阵里出现的 `FooIntegrationTest` / `FooTests` 必须真有同名 `.java`。
2. **`类.方法` 必须两边都在**：`FooIntegrationTest.barMethod` 的类要存在，且该类里要有 `barMethod`。
3. **无处可寻的裸标识符**：矩阵里形如 `occupiedCountsOwedBarrelsEvenWithoutRights` 的裸驼峰词，
   必须在**测试方法名**或**主源码/测试源码**里能找到。找不到 = 方法被改名或本来就是笔误。
   这条能低噪运行的关键是**同时查主源码**：`statusText` / `customerName` 这类领域字段名
   在 `main/` 里有定义，属于正常引用，不算问题。
4. **✅ 行必须可核对**：标了 ✅ 的行，证据栏要么点名**真实存在的**测试类/测试方法，
   要么显式声明它是非用例证据（写「live 实测」之类）—— 否则那一行的「有覆盖」是一句无人能查的话。
   ⚠️ 这条不是吹毛求疵：2026-09-21 首次运行就抓出 3 行标 ✅ 却指不出任何用例，
   补上类名后才补上了它们与代码之间那根可核对的线。
5. **件数声明不漂**（可选）：矩阵里的 `<!-- MATRIX-STATS -->` 块由本脚本生成，
   内容与 `build/test-results/test/*.xml` 实测值不符即失败。
   ⚠️ 只校验这个**带标记的块**：文档里那些「2026-09-16 全量 194 例」是**带日期的历史快照**，
   不是断言，永远不该拿它们与今天的数字比较（那是把历史记录当规格用）。

已知偏差（照抄 AGENTS.md §7 对 `api_reverse_audit.py` 的告诫）
--------------------------------------------------------------------------
本脚本**只做字符串匹配**，不做语义分析，因此：
  * 认不出"用字符串拼接出来的类名/方法名"，可能漏报；
  * 单测方法名在矩阵里若不写全（例如只写中文描述），本脚本无从校验 —— 那属于矩阵自身的粒度问题。

用法
--------------------------------------------------------------------------
    python audit_scenario_matrix.py                 # 校验
    python audit_scenario_matrix.py --write-stats   # 把实测件数写回矩阵的 STATS 块
    python audit_scenario_matrix.py --matrix <路径>  # 指定矩阵文件

退出码：0 = 通过；1 = 发现问题（供 CI / scripts/verify.sh 作为门禁）
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
# 测试类名（本仓约定：*IntegrationTest，少数 *Tests）
RE_TEST_CLASS = re.compile(r'^[A-Z][A-Za-z0-9]*(?:IntegrationTest|Tests)$')
# 裸驼峰标识符（小写开头，**至少含一个大写字母**）——可能是测试方法名，也可能是领域字段名。
# ⚠️ 下划线那一条也必须带大写字母，否则会把脚本名/库名一起抓进来：
#    `page_reach_audit`、`aquaflow_test_r10` 都是这样误报过的（2026-09-21 实测）。
RE_CAMEL = re.compile(r'^[a-z][A-Za-z0-9]*_[A-Za-z0-9_]*[A-Z][A-Za-z0-9_]*$|^[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*$')
# 类.成员
RE_MEMBER = re.compile(r'^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$')

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


def iter_java(root):
    if not os.path.isdir(root):
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
        for name in filenames:
            if name.endswith('.java'):
                yield os.path.join(dirpath, name)


def collect_java_facts():
    """返回 (类名 -> 文件路径, 类名 -> 该类里的成员名集合, 全部成员名集合, 全部源码文本)。

    成员名用宽松正则抓 `public/private/protected ... 名字(` 与字段声明 —— 目的是回答
    「这个名字在这个类里存在吗」，不追求完整词法分析（与 audit_comments.py 同一取舍）。
    """
    class_file = {}
    class_members = {}
    all_members = set()
    all_text = []

    re_method = re.compile(r'^\s*(?:public|private|protected|static|final|synchronized|\s)*'
                           r'(?:[\w<>\[\],.?\s]+)\s+([a-zA-Z_]\w*)\s*\(', re.M)
    re_field = re.compile(r'^\s*(?:public|private|protected|static|final|\s)+'
                          r'[\w<>\[\],.?\s]+\s+([a-zA-Z_]\w*)\s*[;=]', re.M)

    for root in (TEST_ROOT, MAIN_ROOT):
        for path in iter_java(root):
            simple = os.path.basename(path)[:-len('.java')]
            # 测试源优先：同名类在 test 与 main 里同时存在时，测试那份才是矩阵要指的对象
            if simple not in class_file or root == TEST_ROOT:
                class_file[simple] = path
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

    return class_file, class_members, all_members, '\n'.join(all_text)


def read_results_counts():
    """从 build/test-results 数出真实件数。缺目录返回 None（由调用方决定是跳过还是失败）。

    ⚠️ 用 xml.etree 解析，**不要**先读成字符串再 `[xml]` —— 那会按 ANSI 解码，
    可能弄坏测试名并**静默少算**（AGENTS.md §5 对 PowerShell 版本的同一条告诫）。
    """
    files = glob.glob(RESULTS_GLOB)
    if not files:
        return None
    import xml.etree.ElementTree as ET
    classes = tests = failures = errors = skipped = 0
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
    return {'classes': classes, 'tests': tests, 'failures': failures,
            'errors': errors, 'skipped': skipped}


def repo_file_index():
    """仓库内所有文件的「文件名（去扩展名）」与「相对路径」两个集合。

    用途：判断矩阵证据栏点名的东西**真的存在吗**。证据不一定是测试类 ——
    8.10 的证据是一份审计报告、9.3 的证据是静态门禁脚本本身，它们同样可核对，
    判据必须认它们，否则会把合法证据判成"指不出任何用例"。
    """
    stems, rels = set(), set()
    for dirpath, dirnames, filenames in os.walk('.'):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
        for name in filenames:
            rel = os.path.relpath(os.path.join(dirpath, name)).replace('\\', '/')
            rels.add(rel)
            stems.add(os.path.splitext(name)[0])
    return stems, rels


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

    class_file, class_members, all_members, all_src = collect_java_facts()
    print(f'已索引：{len(class_file)} 个 Java 类型（{TEST_ROOT} + {MAIN_ROOT}）')

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
    missing_members = []     # Class.method 里方法不存在
    orphan_tokens = []       # 裸标识符既不是测试方法也不是任何源码里的名字
    checked_classes = checked_members = 0

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

    print(f'校验：测试类引用 {checked_classes} 处，类成员引用 {checked_members} 处')

    # ---- ✅ 行必须可核对 ----
    # 证据栏点名的标识符必须能被读者核对。可接受的形态有三类：
    #   ① 真实存在的测试类 / 测试方法；
    #   ② 真实存在的**文件**（脚本、报告 —— 例如 8.10 的证据是一份审计报告、
    #      9.3 的证据是 `page_reach_audit.py` 门禁本身，它们不是用例但同样可核对）；
    #   ③ 通配类名（`*DtoValidationIntegrationTest` 这种），要求至少命中一个真实类；
    #   ④ 显式声明非自动化证据（写「live 实测」之类）。
    # 除此之外才算"指不出任何用例" —— 判据必须认识合法证据形态，否则会变成橡皮图章或噪声源。
    known_methods = {m for m in re.findall(r'\bvoid\s+([a-z]\w*)\s*\(', all_src)}
    file_stems, file_rels = repo_file_index()
    live_markers = ('live', '实测', '人工', '运维', '手工')

    def evidence_resolves(tok):
        if tok in class_file or tok in known_methods or tok in file_stems or tok in file_rels:
            return True
        if tok.startswith('*'):                      # 通配类名
            suffix = tok[1:]
            return any(c.endswith(suffix) for c in class_file)
        return False

    unverifiable = []
    for ln, raw in enumerate(raw_lines, 1):
        if not raw.strip().startswith('|'):
            continue
        cells = [c.strip() for c in raw.strip().strip('|').split('|')]
        if len(cells) < 4 or '✅' not in cells[2]:
            continue
        evidence = cells[3]
        named = re.findall(r'`([^`]+)`', evidence)
        if any(evidence_resolves(n.strip()) for n in named):
            continue
        if any(k in evidence for k in live_markers):
            continue
        unverifiable.append((ln, cells[0], named[:3]))

    problems = 0
    if missing_classes:
        problems += len(missing_classes)
        print()
        print(f'[1] 矩阵引用了**不存在**的测试类（{len(missing_classes)} 个）：')
        for t in missing_classes:
            print(f'      - {t}')
        print('      → 该类被改名/合并/删除了，矩阵对应那几行的「有覆盖」已经不作数。')
        print('        处理：要么补回用例，要么把矩阵那几行改成实际覆盖状态（🟡/❌）。')

    if missing_members:
        problems += len(missing_members)
        print()
        print(f'[2] 矩阵引用的**类成员不存在**（{len(missing_members)} 处）：')
        for t in missing_members:
            print(f'      - {t}')

    if orphan_tokens:
        problems += len(orphan_tokens)
        print()
        print(f'[3] 矩阵里**无处可寻**的标识符（{len(orphan_tokens)} 个）：')
        for t in orphan_tokens:
            print(f'      - {t}')
        print('      → 既不是测试方法名，也不在任何 main/test 源码里出现。'
              '多半是方法被改名，或矩阵里写错了。')

    # ---- 件数块 ----
    if unverifiable:
        problems += len(unverifiable)
        print()
        print(f'[4] 标了 ✅ 却**指不出任何用例**的行（{len(unverifiable)} 行）：')
        for ln, sid, named in unverifiable:
            hint = ('证据栏没有反引号标识符' if not named
                    else '证据栏的名字都不是真实测试类或方法：' + ', '.join(named))
            print(f'      L{ln:<6d} {sid:8s} {hint}')
        print('      → 补上测试类/方法名；若这一行靠的是 live 实测而非用例，')
        print('        请在证据栏写明「live 实测」，本检查即视为已声明。')

    counts = read_results_counts()
    if counts is None:
        print()
        print('[5] 件数一致性：**跳过** —— 没有 build/test-results/test/*.xml')
        print('    （先跑一次 `gradlew test` 才会生成；CI 上一定存在，本机新克隆时会跳过）')
    else:
        today = datetime.date.today().isoformat()
        fresh = stats_block(counts, today)
        block = extract_stats_block(matrix)
        if write_stats:
            if block is None:
                print()
                print('[5] 矩阵里没有 MATRIX-STATS 块，已追加到文件开头之后。')
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
            print(f'[5] 已写回件数：{counts["classes"]} 类 / {counts["tests"]} 用例')
        elif block is None:
            print()
            print('[5] 件数一致性：矩阵里没有 MATRIX-STATS 块（不判失败）。')
            print('    建议跑一次 `python audit_scenario_matrix.py --write-stats` 建立基线。')
        else:
            shown = re.findall(r'(\d+)\s*个测试类\s*/\s*(\d+)\s*个用例', block)
            # 块里第一处数字串即生成值；历史快照不在块内，故不会误判
            ok = shown and int(shown[0][0]) == counts['classes'] and int(shown[0][1]) == counts['tests']
            if not ok:
                problems += 1
                print()
                print('[5] 件数不一致：')
                print(f'      矩阵 STATS 块 = {shown[0] if shown else "（读不出数字）"}')
                print(f'      实测          = ({counts["classes"]}, {counts["tests"]})')
                print('      → 跑 `python audit_scenario_matrix.py --write-stats` 更新。')
            else:
                print()
                print(f'[5] 件数一致：{counts["classes"]} 类 / {counts["tests"]} 用例'
                      f'（失败 {counts["failures"]} / 错误 {counts["errors"]}）')

    print('-' * 88)
    if problems:
        print(f'发现 {problems} 处问题：矩阵与代码已经不一致。')
        return 1
    print('通过：矩阵声称的测试类/方法全部真实存在。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
