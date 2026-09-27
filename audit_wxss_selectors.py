#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""捕获「同元素两个类被当成父子选择器」的 WXSS 缺陷（2026-09-26 立）。

判据（事故形状，见 create.wxss 里那段注释与 docs/audit/2026-09-26-...-返工契约）：
  WXML 写的是 `class="popup-content asset-warning"`（**同一个元素**两个类），
  而 WXSS 写的是 `.asset-warning .popup-content`（要求前者是后者的**祖先**）。
  两条规则永远匹配不到 → 整块定位/尺寸样式静默失效，元素退回基础样式
  （`bottom:0; transform: translateY(100%)` = 被推出屏幕），
  兄弟遮罩却照常盖住整屏，现象是「整屏灰白、看不到卡片和按钮」。
  两个静态门禁都查不出：audit_wxml_handlers.py 只看事件绑定，audit_js_syntax.py 不解析 wxss。

本脚本只报**高置信**冲突：对每个 WXML 文件收集「同一元素上的类组合」，
若该文件的 WXSS 里存在 `.C .D`（两段都是单类），而 C 与 D 曾出现在同一元素上，
就报一行。嵌套写法（真的父子）不受影响，因为嵌套元素上不会同时挂这两个类。

用法：
    python audit_wxss_selectors.py            # 扫全部小程序目录
    python audit_wxss_selectors.py miniapp-user
退出码：0 = 无冲突；1 = 有冲突（进门禁）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIRS = ['miniapp-user', 'miniapp-delivery']

# class="a b {{cond ? 'c' : ''}}" → 取字面类名（模板表达式整段丢掉）
CLASS_ATTR = re.compile(r'class\s*=\s*"([^"]*)"')
CLASS_TOKEN = re.compile(r'[A-Za-z_][\w-]*')
# WXSS 里 .C .D（两段都是**单个**类，中间只有空白或换行）
DESCENDANT = re.compile(r'(?<![\w.\-#])\.([A-Za-z_][\w-]*)\s+\.([A-Za-z_][\w-]*)\b')


def same_element_class_pairs(wxml_text):
    """返回 {(C, D)}：曾出现在**同一个 class 属性**里的类组合（无序）。"""
    pairs = set()
    for m in CLASS_ATTR.finditer(wxml_text):
        raw = m.group(1)
        # 只保留不在 {{}} 里的类名：模板分支里的类名可能是条件性的，
        # 拿它当判据会产生假阳性（本脚本宁少报不误报）。
        cleaned = re.sub(r'\{\{.*?\}\}', ' ', raw)
        classes = set(CLASS_TOKEN.findall(cleaned))
        for c in classes:
            for d in classes:
                if c != d:
                    pairs.add((c, d))
    return pairs


def scan(miniapp):
    base = os.path.join(ROOT, miniapp)
    if not os.path.isdir(base):
        return []
    violations = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if not name.endswith('.wxml'):
                continue
            wxml_path = os.path.join(dirpath, name)
            wxss_path = wxml_path[:-5] + '.wxss'
            if not os.path.isfile(wxss_path):
                continue
            with open(wxml_path, encoding='utf-8') as f:
                pairs = same_element_class_pairs(f.read())
            if not pairs:
                continue
            with open(wxss_path, encoding='utf-8') as f:
                lines = f.read().splitlines()
            seen = set()
            for i, line in enumerate(lines, 1):
                code = line.split('/*')[0]          # 去掉行内注释
                if not code.strip().startswith('.'):
                    continue                        # 只看规则选择器行
                for m in DESCENDANT.finditer(code):
                    c, d = m.group(1), m.group(2)
                    if (c, d) in pairs and (c, d) not in seen:
                        seen.add((c, d))
                        rel = os.path.relpath(wxss_path, ROOT).replace('\\', '/')
                        wrel = os.path.relpath(wxml_path, ROOT).replace('\\', '/')
                        violations.append(
                            '{}:{}  选择器 `.{} .{}` 要求 {} 是 {} 的祖先，'
                            '但 {} 里它们是**同一个元素**上的两个类'.format(
                                rel, i, c, d, c, d, wrel))
    return violations


def main():
    dirs = sys.argv[1:] or DEFAULT_DIRS
    all_violations = []
    for d in dirs:
        all_violations.extend(scan(d))
    if all_violations:
        print('WXSS 选择器与 WXML 结构不符：{} 处'.format(len(all_violations)))
        for v in all_violations:
            print('  ✗ ' + v)
        print('\n修法：把 `.C .D` 改成 `.C.D`（或 `.D.C`），或把类挂到真正的外层元素上，')
        print('      并确认基础样式的 bottom/right/transform 残留已被覆盖到屏幕内。')
        return 1
    print('WXSS 同元素类选择器检查通过：无 `.C .D` 冲突')
    return 0


if __name__ == '__main__':
    sys.exit(main())
