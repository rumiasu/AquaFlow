#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""注释体检：揪出「悬空注释」等会误导读者的注释问题。

背景（2026-09-14，真实事故）
--------------------------------------------------------------------------
`StationController` 的 `/mine` 上方长期残留一段**不属于任何方法**的 javadoc：

    /**
     * 当前登录客户选择的服务水站      <-- 悬空！字面暗示"顾客可以调"
     */
    /**
     * 当前登录员工所属水站
     */
    @RequireRole({"STATION_MANAGER", "DELIVERY"})   // <- 实为员工接口
    @GetMapping("/mine")

顾客端据此**三次误调**员工专属接口（模板页 → stationId 恒 null、模板存不进；
下单页"再来一单" → 跨站校验沦为死分支；更早的取水站电话）。每次都是
**403 被静默吞掉**，现象是"功能莫名失效"而不是报错，极难定位。

同类还有 `miniapp-user/config/api.js` 里被注释误导的 SEARCH 条目。

结论：**注释是被信任的契约，悬空/过期的注释比没有注释更危险。**
详见 AGENTS.md §6「注释契约」与 §8 第 14 条。

检测项
--------------------------------------------------------------------------
1. 悬空 javadoc（重叠型）：`/** ... */` 之后，跳过空行/行注释，紧接**另一个 `/**`**。
2. 悬空 javadoc（结尾型）：`/** ... */` 之后紧接 `}`（类/方法体结束），说明它没有归属。

注意：`/* ... */`（非 javadoc 的分节注释）后面跟 `/**` 是**正常写法**
（如本仓库 `OrderController` 里的"已删除端点"说明块），不报。

用法
--------------------------------------------------------------------------
    python audit_comments.py                 # 默认扫描 AquaFlow-backend + 两个小程序
    python audit_comments.py <目录> [目录..]  # 指定目录

退出码：0 = 通过；1 = 发现问题（供 CI / scripts/verify.sh 作为门禁）
"""

import os
import re
import sys

DEFAULT_TARGETS = ['AquaFlow-backend', 'miniapp-user', 'miniapp-delivery']
SKIP_DIR_PARTS = {
    'node_modules', 'build', 'out', 'dist', '.git', '.gradle', '.gradle_alt',
    '.gradle_alt2', '.gradle_alt3', '.gradle-user', '.gradlehome',
    'archive', 'backup', 'generated-images', 'target', '__pycache__',
}
SCAN_EXT = ('.java', '.js', '.wxml', '.wxss')

# javadoc 起始 / 结束
RE_JAVADOC_OPEN = re.compile(r'^\s*/\*\*')
RE_BLOCK_CLOSE = re.compile(r'\*/')
# 下一个"有意义"的行：新 javadoc 开始，或以 } 开头（类/方法收尾）
RE_NEXT_JAVADOC = re.compile(r'^\s*/\*\*')
RE_NEXT_CLOSE_BRACE = re.compile(r'^\s*\}')


def comment_lines(text):
    """返回 (行号, 内容) 列表，仅包含注释行——用于把代码行排除在外面。

    这里不追求完美的词法分析，只要足够稳健：逐行判断该行是否处于注释中。
    """
    out = []
    in_block = False
    for i, raw in enumerate(text.split('\n'), 1):
        line = raw
        if in_block:
            out.append((i, line))
            if RE_BLOCK_CLOSE.search(line):
                in_block = False
            continue
        stripped = line.strip()
        if stripped.startswith('/*'):
            out.append((i, line))
            if not RE_BLOCK_CLOSE.search(line):
                in_block = True
        elif stripped.startswith('//'):
            out.append((i, line))
    return out


def iter_files(targets):
    for t in targets:
        if os.path.isfile(t):
            yield t
            continue
        for root, dirs, files in os.walk(t):
            dirs[:] = [d for d in dirs if d not in SKIP_DIR_PARTS]
            if any(part in SKIP_DIR_PARTS for part in root.split(os.sep)):
                continue
            for name in files:
                if name.endswith(SCAN_EXT):
                    yield os.path.join(root, name)


def is_file_header(lines, start, path):
    """判断该 javadoc 是否位于文件最开头（前面只有空行/注释）。

    JS 有一种常见且**合理**的写法：文件级模块说明 javadoc 之后直接跟首个函数的 javadoc，
    两者相邻、中间没有代码——这不是悬空，不能报。
    Java 不会出现这种形态（package / import 必然横在文件头与首个 javadoc 之间），故只对 .js 豁免。
    """
    if not path.endswith('.js'):
        return False
    for l in lines[:start]:
        s = l.strip()
        if s and not (s.startswith('//') or s.startswith('*') or s.startswith('/*')):
            return False
    return True


def scan_file(path):
    """返回该文件里的悬空 javadoc 列表 [(行号, 说明, 预览)]。"""
    try:
        text = open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        return []
    raw_lines = text.split('\n')
    issues = []

    i = 0
    n = len(raw_lines)
    while i < n:
        if not RE_JAVADOC_OPEN.match(raw_lines[i]):
            i += 1
            continue
        # 找到该 javadoc 的结束行
        start = i
        j = i
        closed = False
        while j < n:
            if RE_BLOCK_CLOSE.search(raw_lines[j]):
                closed = True
                break
            j += 1
        if not closed:
            i += 1
            continue
        # 从结束行之后，跳过空行与 // 行注释，找下一个有意义的行
        k = j + 1
        while k < n:
            s = raw_lines[k].strip()
            if s == '' or s.startswith('//'):
                k += 1
                continue
            break
        if k >= n:
            i = j + 1
            continue
        nxt = raw_lines[k]

        # 提取 javadoc 首行文案用于提示
        preview = ''
        for m in range(start, j + 1):
            body = raw_lines[m].strip().lstrip('/*').strip()
            if body:
                preview = body[:48]
                break

        if RE_NEXT_JAVADOC.match(nxt):
            if not is_file_header(raw_lines, start, path):
                issues.append((start + 1,
                               '悬空 javadoc（紧跟另一个 /**，前者无归属）',
                               preview))
        elif RE_NEXT_CLOSE_BRACE.match(nxt):
            issues.append((start + 1,
                           '悬空 javadoc（紧跟 }，后面没有方法/字段）',
                           preview))
        i = j + 1
    return issues


def main():
    targets = sys.argv[1:] or DEFAULT_TARGETS
    total_files = 0
    total_issues = 0
    print('=' * 84)
    print('  注释体检 · 悬空 javadoc 检测（AGENTS.md §6 注释契约 / §8 第 14 条）')
    print('=' * 84)
    for path in iter_files(targets):
        total_files += 1
        for line_no, kind, preview in scan_file(path):
            total_issues += 1
            rel = os.path.relpath(path).replace('\\', '/')
            print(f'  [{rel}:{line_no}] {kind}')
            if preview:
                print(f'      └─ "{preview}"')
    print('-' * 84)
    print(f'扫描文件 {total_files} 个，发现问题 {total_issues} 处')
    if total_issues:
        print()
        print('修复方式：删除该 javadoc，或把它合并进下方方法自己的 javadoc。')
        print('「作废的 javadoc 必须删掉，不能悬空留着占位」——见 AGENTS.md §6。')
    return 1 if total_issues else 0


if __name__ == '__main__':
    sys.exit(main())
