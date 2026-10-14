# -*- coding: utf-8 -*-
"""wxml 事件绑定 vs 页面 js 方法定义 —— 全量一致性审计

背景：小程序 wxml 里 bindtap="fnName" 绑定的函数若在 Page 里不存在，
点击【无反应且不报错】（微信不提示），极难发现。历史上已踩过多次
（配送端 statusClassMap 未定义、异常页调用 Page 方法等）。

用法：python audit_wxml_handlers.py
输出：每个「wxml 绑定了但 js 未定义」的方法，按页面分组。
"""
import glob
import argparse
import io
import os
import re
import sys

MINIAPPS = ['miniapp-user', 'miniapp-delivery']
# 注：miniapp-station（站长端半成品，缺 app.js、页面残缺）已于 2026-09-12 归档到
# archive/miniapp-station，不再参与扫描。站长职能由 miniapp-delivery 承担。
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', default=os.path.dirname(os.path.abspath(__file__)),
                    help='待审小程序工作区；默认是本脚本所在仓库')
root = os.path.abspath(parser.parse_args().root)

# bindtap / catchtap / bind:tap / capture-bind:tap ...
BIND_RE = re.compile(
    r'\b(?:capture-)?(?:bind|catch)[:-]?([a-zA-Z]+)\s*=\s*"([A-Za-z_$][\w$]*)"')
# Page 对象里的方法：行首缩进 2+ 的 `name(` 或 `name: function(` / `async name(`
JS_DEF_RE = re.compile(r'^\s{2,}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\(', re.M)
JS_DEF2_RE = re.compile(r'^\s{2,}([A-Za-z_$][\w$]*)\s*:\s*(?:async\s+)?function', re.M)


def upload_wrapper_handlers(js, source):
    """只认已导入且在 Page 中展开的本仓恢复包装器，逐项核对其真实赋值。"""
    # [2026-10-14] R2 的 12 个方法由包装器安装，旧扫描只看 Page 内方法而误报。
    # 不按方法名豁免：未展开、导错模块或包装器实现缺失仍须报缺失绑定。
    imported = re.search(
        r'^\s*const\s*\{\s*wrapUploadRecovery\s*\}\s*=\s*require\([\'\"]([^\'\"]+)[\'\"]\)',
        source, re.M)
    registered = re.search(r'\bPage\s*\(\s*\{\s*\.\.\.wrapUploadRecovery\s*\(\s*\{', source)
    if not imported or not registered:
        return set()
    helper = os.path.abspath(os.path.join(os.path.dirname(js), imported.group(1) + '.js'))
    expected = os.path.join(root, 'miniapp-delivery', 'utils', 'upload-recovery-page.js')
    if helper != expected or not os.path.isfile(helper):
        return set()
    wrapped = io.open(helper, encoding='utf-8').read()
    if not re.search(r'module\.exports\s*=\s*\{\s*wrapUploadRecovery\s*\}', wrapped):
        return set()
    return set(re.findall(r'^\s*page\.([A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?function\b', wrapped, re.M))

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
        defined |= upload_wrapper_handlers(js, jsrc)
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
