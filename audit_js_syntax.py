# -*- coding: utf-8 -*-
"""小程序 js 语法审计（node --check 全量）—— 一个语法错误会让**整个模块**加载失败

背景（2026-09-19 真实事故）：`miniapp-delivery/api/station-mgmt.js` 里同一个
`const updateOfflinePayment` 被声明了两次 → `SyntaxError: Identifier ... has already
been declared` → 该模块**整体加载失败**，而订单/商品/库存/工资/对账等站长接口全在这一个
模块里导出 → 站长端一批页面同时白屏。四个既有静态门禁与后端 381 条用例**一个都没报出来**：
它们只扫 wxml 绑定、require 层级、悬空注释，都不解析 delivery 端的 js。

判据：`const`/`let` 重复声明、少一个括号、多一个逗号 —— 这些都是**解析期**错误，
「按语义分析」是看不出来的，必须交给真正的 JS 解析器。故本脚本对两端全部 js 跑 `node --check`。

用法：python audit_js_syntax.py
退出码：0 全部可解析 / 1 有文件解析失败 / 2 环境缺 node（**跳过而不是假装通过**）
"""
import glob
import os
import subprocess
import sys

MINIAPPS = ['miniapp-user', 'miniapp-delivery']
root = os.path.dirname(os.path.abspath(__file__))

# 生成物 / 第三方代码不在审计范围：微信开发者工具的构建输出与 npm 包都不是手写源码
SKIP_DIR_PARTS = {'node_modules', 'miniprogram_npm'}

node = None
for candidate in (['node'] if os.name != 'nt' else ['node.exe', 'node']):
    try:
        subprocess.run([candidate, '--version'], stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, check=True)
        node = candidate
        break
    except (OSError, subprocess.CalledProcessError):
        continue

if node is None:
    # 与 scripts/verify.sh 对 python 的处理同一口径：工具缺失只报"跳过"，
    # 绝不打印"全部通过"（否则缺工具的机器上会得到一次假绿）。
    print('[audit_js_syntax] 未找到 node，跳过（CI 上必然存在，勿据此判绿）')
    sys.exit(2)

files = []
for app in MINIAPPS:
    base = os.path.join(root, app)
    if not os.path.isdir(base):
        continue
    for path in sorted(glob.glob(os.path.join(base, '**', '*.js'), recursive=True)):
        parts = set(os.path.normpath(path).split(os.sep))
        if parts & SKIP_DIR_PARTS:
            continue
        files.append(path)

failures = []
for path in files:
    proc = subprocess.run([node, '--check', path], stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        out = proc.stdout.decode('utf-8', 'replace').strip().splitlines()
        # node 的首行固定是文件路径，真正的原因在后面；只留 SyntaxError 那一行
        reason = next((ln.strip() for ln in out if 'Error' in ln), out[-1] if out else '未知错误')
        failures.append((os.path.relpath(path, root).replace('\\', '/'), reason))

for rel, reason in failures:
    print('[%s]' % rel)
    print('    x %s' % reason)

print('')
print('已检查 js 文件: %d 个，解析失败: %d 个' % (len(files), len(failures)))
sys.exit(1 if failures else 0)
