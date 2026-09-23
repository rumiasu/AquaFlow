# -*- coding: utf-8 -*-
"""小程序静态审计：require 层级 / wxml 非法表达式 / API 路径拼接 / 页面方法引用

用法：
    python static_audit_user.py [miniapp-user|miniapp-delivery]

退出码：
    0 = 无致命问题
    1 = 存在致命问题（第 1/2/3 段：require 层级错→整页空白；wxml 调全局对象
        或调用 Page 方法→模板不生效且不报错）
    第 4 段（${API.X}/${id} 拼接）只报告，不置红：部分历史写法可用但易踩坑。

⚠️ 扫描前**必须先剥注释**（2026-09-18 补，见下方 strip_* 的 docstring）：不剥注释会把
注释里的示例/旧代码当成真实调用，报出假致命并让 CI 变红。
"""
import os
import re
import sys

APP = sys.argv[1] if len(sys.argv) > 1 else "miniapp-user"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), APP)
if not os.path.isdir(ROOT):
    print("找不到目录：%s" % ROOT)
    sys.exit(2)

issues_require = []
issues_wxml = []
issues_method = []
issues_api = []


def strip_js_comments(src):
    """剥离 JS 注释后再扫描。

    必要性（2026-09-18 实测踩到）：`behaviors/stationNavbar.js` 的 javadoc 里写了调用示例
    `require('../../behaviors/stationNavbar')`（这是**页面**里的写法），而该文件自己的 require
    是 `require('../utils/navbar')`（behaviors/ 相对站根，正确）。不剥注释就会把示例当成真实
    require，判定"写的 ../ 个数=2 应为=1" → **假致命 → CI 变红**。
    本仓另外两个审计脚本（`api_reverse_audit.py`、`audit_comments.py`）早就因为同一类问题剥了
    注释，这里补齐；新增审计时也请照此办理。
    """
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def strip_wxml_comments(src):
    """剥离 wxml 注释：注释掉的旧模板不该让门禁报红（理由同上）。"""
    return re.sub(r"<!--[\s\S]*?-->", "", src)


def depth_of(js_path):
    rel = os.path.relpath(os.path.dirname(js_path), ROOT).replace("\\", "/")
    if rel == ".":
        return 0
    return len(rel.split("/"))


# 1) require 相对路径层级
for dirpath, _, files in os.walk(ROOT):
    if "node_modules" in dirpath or "miniprogram_npm" in dirpath:
        continue
    for f in files:
        if not f.endswith(".js"):
            continue
        p = os.path.join(dirpath, f)
        try:
            src = strip_js_comments(open(p, encoding="utf-8", errors="ignore").read())
        except Exception:
            continue
        for m in re.finditer(r"require\((['\"])((?:\.\./)+)([^'\"]*)", src):
            ups = m.group(2).count("../")
            d = depth_of(p)
            if ups != d:
                issues_require.append((os.path.relpath(p, ROOT), ups, d, m.group(0)))

# 2) wxml 里调用 Page 方法 / 全局对象
for dirpath, _, files in os.walk(ROOT):
    if "node_modules" in dirpath:
        continue
    for f in files:
        if not f.endswith(".wxml"):
            continue
        p = os.path.join(dirpath, f)
        src = strip_wxml_comments(open(p, encoding="utf-8", errors="ignore").read())
        # 全局对象
        for m in re.finditer(r"\{\{[^}]*\b(Math|Date|JSON|parseInt|parseFloat|String|Number|Object|Array)\.", src):
            seg = m.group(0)[:90]
            issues_wxml.append((os.path.relpath(p, ROOT), "GLOBAL", seg))
        # 函数调用 xxx(  （排除过滤器/三元）
        for m in re.finditer(r"\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\(", src):
            issues_method.append((os.path.relpath(p, ROOT), "FUNC", m.group(1)))

# 3) API 拼接反模式：${CONST}/${id} 形式
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".js"):
            continue
        p = os.path.join(dirpath, f)
        src = strip_js_comments(open(p, encoding="utf-8", errors="ignore").read())
        for m in re.finditer(r"\$\{(API\.[A-Z_]+)\}/\$?\{", src):
            issues_api.append((os.path.relpath(p, ROOT), m.group(0)))

print("=" * 90)
print("[%s] 1) require 相对路径层级不匹配（会导致整页空白）" % APP)
print("=" * 90)
if not issues_require:
    print("  无")
for x in issues_require:
    print(f"  {x[0]}\n     写的 ../ 个数={x[1]}  应为={x[2]}   {x[3]}")

print("\n" + "=" * 90)
print("[%s] 2) wxml 使用了全局对象（Math/Date/JSON 等，小程序模板不支持）" % APP)
print("=" * 90)
if not issues_wxml:
    print("  无")
for x in issues_wxml:
    print(f"  {x[0]}  [{x[1]}]  {x[2]}")

print("\n" + "=" * 90)
print("[%s] 3) wxml 中形如 func( 的调用（小程序模板只能取字段，不能调 Page 方法）" % APP)
print("=" * 90)
if not issues_method:
    print("  无")
for x in issues_method:
    print(f"  {x[0]}  ->  {x[2]}()")

print("\n" + "=" * 90)
print("[%s] 4) API 路径拼接反模式 ${API.CONST}/${id}（仅报告）" % APP)
print("=" * 90)
if not issues_api:
    print("  无")
for x in issues_api:
    print(f"  {x[0]}  {x[1]}")

fatal = len(issues_require) + len(issues_wxml) + len(issues_method)
print("\n致命问题合计: %d（第 1/2/3 段），另有 %d 处仅报告（第 4 段）" % (fatal, len(issues_api)))
sys.exit(1 if fatal else 0)
