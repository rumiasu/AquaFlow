# -*- coding: utf-8 -*-
"""页面可达性审计：注册页面 vs 实际跳转引用，找出孤岛页面与跳转到不存在页面的死链

用法：
    python page_reach_audit.py [miniapp-user|miniapp-delivery]

退出码：
    0 = 无致命问题
    1 = 存在致命问题（B 段：app.json 注册了但磁盘无 .js → 启动即报错；
                        C 段：代码跳转到未注册页面 → navigateTo:fail）
    A 段（磁盘有 .js 未注册）与 D 段（孤岛页面）只报告，不置红：
    历史遗留的未注册页与暂无入口的中转页不构成运行期故障。
"""
import os
import re
import json
import sys

APP = sys.argv[1] if len(sys.argv) > 1 else "miniapp-user"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), APP)
if not os.path.isdir(ROOT):
    print("找不到目录：%s" % ROOT)
    sys.exit(2)

app = json.load(open(os.path.join(ROOT, "app.json"), encoding="utf-8"))
registered = set(p.strip("/") for p in app["pages"])

# tabbar
tabs = set(t["pagePath"].strip("/") for t in app.get("tabBar", {}).get("list", []))

# 磁盘上的页面文件
ondisk = set()
for dirpath, _, files in os.walk(os.path.join(ROOT, "pages")):
    for f in files:
        if f.endswith(".js"):
            rel = os.path.relpath(os.path.join(dirpath, f), ROOT).replace("\\", "/")
            ondisk.add(rel[:-3])

# 代码里的跳转
links = {}
for dirpath, _, files in os.walk(ROOT):
    if "node_modules" in dirpath:
        continue
    for f in files:
        if not f.endswith(".js"):
            continue
        p = os.path.join(dirpath, f)
        src = open(p, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r"(?:navigateTo|redirectTo|switchTab|reLaunch)\s*\(\s*\{[^}]*url:\s*['\"\`]([^'\"\`]+)", src):
            u = m.group(1).split("?")[0].strip("/")
            links.setdefault(u, []).append(os.path.relpath(p, ROOT))
        # 模板字符串 url: `/pages/...`
        for m in re.finditer(r"url:\s*`([^`]+)`", src):
            u = m.group(1).split("?")[0].strip("/")
            links.setdefault(u, []).append(os.path.relpath(p, ROOT))
        # 数据驱动菜单：{ title: 'xx', url: '/pages/xx/yy' } 存在 data 数组里，
        # 由统一的 onMenuTap 跳转。不识别这类写法会把菜单项误报成"孤岛页面"。
        for m in re.finditer(r"url:\s*['\"](/pages/[^'\"]+)['\"]", src):
            u = m.group(1).split("?")[0].strip("/")
            links.setdefault(u, []).append(os.path.relpath(p, ROOT))

# [2026-09-16 补] wxml 里的 `data-url="/pages/xx/yy"` 也要算作入口。
# 背景：员工端站长页用 `bindtap="onNavigate" data-url="/pages/..."` + 通用处理器
# （pages/station-mgmt/index.js 里 `wx.navigateTo({ url: e.currentTarget.dataset.url })`）
# 渲染整片功能卡片墙；js 里只有变量、没有字面量 URL，此前只扫 js 会把**整片卡片墙的页面
# 全部误报成"孤岛页面"（D 段）**，包括 2026-09-16 新增的欠桶台账页。
# `{{item.url}}` 这类动态绑定无法静态解析，跳过（不计入，也不误判为死链）。
for dirpath, _, files in os.walk(ROOT):
    if "node_modules" in dirpath:
        continue
    for f in files:
        if not f.endswith(".wxml"):
            continue
        p = os.path.join(dirpath, f)
        src = open(p, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r"data-url\s*=\s*[\"'](/pages/[^\"'{}]+)[\"']", src):
            u = m.group(1).split("?")[0].strip("/")
            links.setdefault(u, []).append(os.path.relpath(p, ROOT))

fatal = 0

print("=" * 88)
print("[%s] A. 磁盘上有 .js 但未在 app.json 注册（用户到不了 / 或只能靠 redirect）" % APP)
print("=" * 88)
for p in sorted(ondisk - registered):
    print("  未注册:", p)

print("\n" + "=" * 88)
print("[%s] B. app.json 注册了但磁盘无 .js（启动即报错）" % APP)
print("=" * 88)
missing_js = sorted(registered - ondisk)
if not missing_js:
    print("  无")
for p in missing_js:
    print("  缺失:", p)
fatal += len(missing_js)

print("\n" + "=" * 88)
print("[%s] C. 代码跳转到未注册页面（点了报错 navigateTo:fail）" % APP)
print("=" * 88)
dead = [(u, wh) for u, wh in sorted(links.items()) if u.startswith("pages/") and u not in registered]
if not dead:
    print("  无")
for u, wh in dead:
    print(f"  死链: /{u}   引用自 {wh[:3]}")
fatal += len(dead)

print("\n" + "=" * 88)
print("[%s] D. 已注册但没有任何入口跳转（孤岛页面，仅报告）" % APP)
print("=" * 88)
for p in sorted(registered - set(links.keys()) - tabs):
    print("  孤岛:", p)

print("\n" + "=" * 88)
print("[%s] E. tabBar 页面" % APP)
print("=" * 88)
for t in sorted(tabs):
    ok = "有js" if t in ondisk else "**缺少js**"
    print(f"  {t}  {ok}")
    if t not in ondisk:
        fatal += 1

print("\n致命问题合计: %d" % fatal)
sys.exit(1 if fatal else 0)
