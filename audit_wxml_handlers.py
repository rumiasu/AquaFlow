# -*- coding: utf-8 -*-
"""wxml 事件绑定 vs 页面 js 方法定义 —— 全量一致性审计

背景：小程序 wxml 里 bindtap="fnName" 绑定的函数若在 Page 里不存在，
点击【无反应且不报错】（微信不提示），极难发现。历史上已踩过多次
（配送端 statusClassMap 未定义、异常页调用 Page 方法等）。

用法：python audit_wxml_handlers.py
输出：每个「wxml 绑定了但 js 未定义」的方法，按页面分组。
"""
import glob
import io
import os
import re
import sys

MINIAPPS = ['miniapp-user', 'miniapp-delivery']
# 注：miniapp-station（站长端半成品，缺 app.js、页面残缺）已于 2026-09-12 归档到
# archive/miniapp-station，不再参与扫描。站长职能由 miniapp-delivery 承担。
root = os.path.dirname(os.path.abspath(__file__))

# bindtap / catchtap / bind:tap / capture-bind:tap ...
BIND_RE = re.compile(
    r'\b(?:capture-)?(?:bind|catch)[:-]?([a-zA-Z]+)\s*=\s*"([A-Za-z_$][\w$]*)"')
# Page 对象里的方法：行首缩进 2+ 的 `name(` 或 `name: function(` / `async name(`
JS_DEF_RE = re.compile(r'^\s{2,}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\(', re.M)
JS_DEF2_RE = re.compile(r'^\s{2,}([A-Za-z_$][\w$]*)\s*:\s*(?:async\s+)?function', re.M)

total = 0
pages_with_issue = 0
report = []

for app in MINIAPPS:
    base = os.path.join(root, app)
    if not os.path.isdir(base):
        continue
    for wxml in sorted(glob.glob(os.path.join(base, '**', '*.wxml'), recursive=True)):
        if 'node_modules' in wxml:
            continue
        js = wxml[:-5] + '.js'
        if not os.path.exists(js):
            continue
        src = io.open(wxml, encoding='utf-8').read()
        bound = {m.group(2) for m in BIND_RE.finditer(src)}
        if not bound:
            continue
        jsrc = io.open(js, encoding='utf-8').read()
        defined = set(JS_DEF_RE.findall(jsrc)) | set(JS_DEF2_RE.findall(jsrc))
        missing = sorted(n for n in bound if n not in defined)
        if missing:
            rel = os.path.relpath(wxml, root).replace('\\', '/')
            report.append((rel, missing))
            pages_with_issue += 1
            total += len(missing)

for rel, missing in report:
    print('[%s]' % rel)
    for m in missing:
        print('    x %s' % m)

print('')
print('缺失绑定: %d 个，涉及 %d 个页面' % (total, pages_with_issue))
sys.exit(1 if total else 0)
