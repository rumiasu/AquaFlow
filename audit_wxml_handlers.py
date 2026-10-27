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


def code_mask(source):
    """保留位置和换行，屏蔽注释/字符串，避免把示例里的伪方法认作实现。"""
    out = list(source)
    state = None
    i = 0
    while i < len(source):
        char = source[i]
        pair = source[i:i + 2]
        if state in ('\"', "'", '`'):
            if char == '\\':
                out[i] = ' '
                i += 1
                if i < len(source) and source[i] not in '\r\n':
                    out[i] = ' '
            else:
                if char not in '\r\n':
                    out[i] = ' '
                if char == state:
                    state = None
        elif state == '//':
            if char in '\r\n':
                state = None
            else:
                out[i] = ' '
        elif state == '/*':
            if char not in '\r\n':
                out[i] = ' '
            if pair == '*/':
                out[i:i + 2] = [' ', ' ']
                i += 1
                state = None
        elif pair in ('//', '/*'):
            out[i:i + 2] = [' ', ' ']
            state = pair
            i += 1
        elif char in ('\"', "'", '`'):
            state = char
            out[i] = ' '
        i += 1
    return ''.join(out)


def object_end(code, start):
    depth = 0
    for pos in range(start, len(code)):
        if code[pos] == '{':
            depth += 1
        elif code[pos] == '}':
            depth -= 1
            if depth == 0:
                return pos + 1
    return None


def top_level(code, start, pos):
    return code[start:pos].count('{') - code[start:pos].count('}') == 1


def station_behavior_handlers(js, source):
    """只认页面真实注册的本站导航 Behavior，核对导入、导出及 methods 的直接成员。"""
    # 2026-10-27：Page behaviors 提供的入口曾被漏掉；不能按事件名豁免或把未注册导入算成实现。
    code = code_mask(source)
    page = re.search(r'\bPage\s*\(\s*(\{)', code)
    if not page:
        return set()
    start = page.start(1)
    end = object_end(code, start)
    if end is None:
        return set()
    registered = set()
    for match in re.finditer(r'\bbehaviors\s*:\s*\[([^\]]*)\]', code[start:end]):
        if top_level(code, start, start + match.start()):
            registered.update(token.strip() for token in match.group(1).split(','))
    handlers = set()
    for match in re.finditer(r'\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*require\([\'\"]([^\'\"]+)[\'\"]\)', source):
        if match.group(1) not in registered or not code[match.start():match.end()].lstrip().startswith('const'):
            continue
        relative = match.group(2)
        helper = os.path.abspath(os.path.join(os.path.dirname(js), relative + ('' if relative.endswith('.js') else '.js')))
        expected = os.path.join(root, 'miniapp-delivery', 'behaviors', 'stationNavbar.js')
        if helper != expected or not os.path.isfile(helper):
            continue
        body = code_mask(io.open(helper, encoding='utf-8').read())
        export = re.search(r'module\.exports\s*=\s*Behavior\s*\(\s*(\{)', body)
        if not export:
            continue
        bstart = export.start(1)
        bend = object_end(body, bstart)
        if bend is None:
            continue
        for methods in re.finditer(r'\bmethods\s*:\s*(\{)', body[bstart:bend]):
            if not top_level(body, bstart, bstart + methods.start()):
                continue
            mstart = bstart + methods.start(1)
            mend = object_end(body, mstart)
            if mend is None:
                continue
            block = body[mstart:mend]
            for pattern in (JS_DEF_RE, JS_DEF2_RE):
                handlers.update(found.group(1) for found in pattern.finditer(block)
                                if top_level(block, 0, found.start(1)))
    return handlers

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
        defined |= station_behavior_handlers(js, jsrc)
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
