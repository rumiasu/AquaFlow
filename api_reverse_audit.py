#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
api_reverse_audit.py —— 「后端端点 ↔ 前端调用」双向审计

背景
----
仓库既有的 api_audit.py / static_audit_user.py 只做「前端调了后端有没有」的正向校验，
因此后端定义了但两端小程序从不调用的 **死端点** 长期无人发现 —— 例如 PaymentController
的 GET/PUT /api/payments/config（PUT 还是空实现却返回 success 的「假成功」端点），
以及 v29 新增的 GET /api/manager/owed-barrels（台账建好、站长却点不到）。

本脚本做双向：反向找死端点，正向找 404 风险。

关键实现点（踩过坑，别再犯）
----------------------------
1. 类级 @RequestMapping 可能是**数组**写法：
   `@RequestMapping({"/api/stations", "/api/station"})`。只匹配单值会丢前缀，
   把 `/api/stations/public` 误报成缺失。两种都要解析。
2. 类级注解**顶格**、方法级注解**有缩进**。判断缩进必须比原始前缀 == ""，
   不能用 strip() —— 否则缩进的方法级注解会被当成类级，端点总数会塌成个位数。
3. 前端路径大量写成 `${API.ORDERS}/${id}/cancel`。必须**先把 config/api.js 的常量
   映射替换进去**，否则 `/api/manager/owed-barrels` 会因为 "manager"、"owed"、"barrels"
   各自在前端别处出现过而被判成"已调用"（假阴性）。
4. 判定「有没有调用」用**整条归一化路径的子串匹配**（`{}` 视作通配占位），
   不要逐段匹配、也不要把任意文本里抽出的 `/api/xxx` 片段当成端点 ——
   后者会把注释与拼接残留都算进来，产生大量假阳性。
5. 归一化：前端 `${...}` 与后端 `{...}` 一律变成 `{}`，两侧才可比。

用法
----
    python api_reverse_audit.py

退出码
------
    0  无致命问题（死端点仅报告，不阻断）
    1  存在「前端调用了后端不存在的端点」（会导致真机 404），视为致命
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
BACKEND_CTRL = os.path.join(ROOT, "AquaFlow-backend", "src", "main", "java",
                            "com", "example", "aquaflow", "controller")
APPS = ["miniapp-user", "miniapp-delivery"]
SCAN_EXT = (".js", ".wxml")

# 映射注解：支持 ① 单值 @GetMapping("/x") ② 数组 @RequestMapping({"/x","/y"})
#              ③ **不带括号** @GetMapping（Spring 允许，CompanyInfoController 就是这么写的 ——
#                 早期版本要求必须有括号，把这批端点整个漏掉，forward 检查因此误报缺失）
ANN_RE = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(([^)]*)\))?', re.M)
STR_RE = re.compile(r'"([^"]*)"')
API_CONST_RE = re.compile(r"([A-Z][A-Z0-9_]*)\s*:\s*'(/[^']*)'")
LOCAL_CONST_RE = re.compile(r"(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*'([^']*)'")


def expand_local_consts(txt):
    """把**页面内**的局部路径常量展开成字面量。

    必要性（2026-09-18 实测踩到，属本脚本第三个偏差）：本仓推荐「新页面用局部路径常量、
    不要动 config/api.js」（见根 AGENTS §2/§6），于是同一个路径被拆成两处 —— 文件顶部
    `const EARNING_ITEMS = '/api/manager/earning-items'`，调用点几百行之后
    `post(EARNING_ITEMS + '/' + id + '/status')`。`:func:`called_pattern` 只容忍段间
    60 字符，对"同一个文件里相隔几百行"完全不够，于是 v44 那三个**确实在用**的端点
    （`earning-items/{id}/status`、`payroll/adjust`、`payroll/{id}/confirm`）全被报成死端点。
    展开之后 `'/api/manager/earning-items' + '/' + id + '/status'` 两段只隔十几字符，即可命中。

    只展开**以 / 开头**的常量值（路径），并按名字长度倒序替换，避免 `PKG` 吃掉 `PKG_MANAGE`；
    用 \\b 词边界，`_` 属词字符，所以 `PKG` 不会误伤 `PKG_MANAGE`。
    """
    local = {m.group(1): m.group(2) for m in LOCAL_CONST_RE.finditer(txt)}
    names = sorted((n for n, v in local.items() if v.startswith("/")), key=len, reverse=True)
    for name in names:
        txt = re.sub(r"\b" + re.escape(name) + r"\b", lambda _m, v=local[name]: v, txt)
    return txt


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def strip_js_comments(src):
    """剥离 JS 注释。必要性：api/station-mgmt.js 里有一行注释
    「不存在 /api/staff/{id}/detach，不要按后者的名字猜路由」，不剥离就会被当成真实调用。"""
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def _is_class_level(src, start):
    """类级注解顶格写（该行注解前无任何字符），方法级注解有缩进。"""
    nl = src.rfind("\n", 0, start)
    return src[nl + 1:start] == ""


def norm(path):
    """把 ${...} 与 {xxx} 一律归一成 {}，并去掉尾部斜杠，使两侧可比。"""
    path = re.sub(r"\$\{[^}]*\}", "{}", path)
    path = re.sub(r"\{[^}]*\}", "{}", path)
    return path.rstrip("/")


def strip_comments(src):
    """剥离 Java 注释后再扫注解。
    必要性（实测踩到）：StationController 的 Javadoc 里**提到了** `@RequestMapping({"/api/stations",
    "/api/station"})`，不剥注释就会把它当成真实注解，造出 `/api/stations/api/stations`
    这类根本不存在的「幻影端点」，污染死端点报告。"""
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def backend_endpoints():
    """返回 [(method, full_path, file, lineno)]"""
    out = []
    if not os.path.isdir(BACKEND_CTRL):
        return out
    for fn in sorted(os.listdir(BACKEND_CTRL)):
        if not fn.endswith(".java"):
            continue
        raw = read(os.path.join(BACKEND_CTRL, fn))
        src = strip_comments(raw)
        prefixes = [""]
        methods = []
        for m in ANN_RE.finditer(src):
            kind, args = m.group(1), (m.group(2) or "")
            paths = STR_RE.findall(args) or [""]
            if _is_class_level(src, m.start()):
                prefixes = [p for p in paths if p]
            else:
                methods.append((kind.upper(), paths, src[:m.start()].count("\n") + 1))
        for kind, paths, lineno in methods:
            for pre in prefixes:
                for sub in paths:
                    out.append((kind, (pre + sub).replace("//", "/"), fn, lineno))
    return out


def api_constants(app):
    """解析 <app>/config/api.js 的常量映射：{ 'ORDERS': '/api/orders', ... }"""
    src = read(os.path.join(ROOT, app, "config", "api.js"))
    return {m.group(1): m.group(2) for m in API_CONST_RE.finditer(src)}


def frontend_text(app):
    """前端全部 js/wxml 文本，且已把 API.* 常量替换成真实路径。"""
    consts = api_constants(app)
    parts = []
    base = os.path.join(ROOT, app)
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames
                       if d not in ("node_modules", "miniprogram_npm", ".git")]
        for f in filenames:
            if not f.endswith(SCAN_EXT):
                continue
            txt = read(os.path.join(dirpath, f))
            txt = strip_js_comments(txt)
            txt = re.sub(r"\$\{API\.([A-Z0-9_]+)\}",
                         lambda m: consts.get(m.group(1), m.group(0)), txt)
            txt = re.sub(r"API\.([A-Z0-9_]+)",
                         lambda m: consts.get(m.group(1), m.group(0)), txt)
            # 最后展开页面内局部路径常量：本仓新页面一律这么写，不展开必误报（见函数 docstring）
            txt = expand_local_consts(txt)
            parts.append(txt)
    return "\n".join(parts)


def api_path_literals(app):
    """只取 <app>/api/*.js 里出现的 /api/... 字面路径（做正向 404 检查用）。"""
    apidir = os.path.join(ROOT, app, "api")
    if not os.path.isdir(apidir):
        return set()
    found = set()
    for f in sorted(os.listdir(apidir)):
        if not f.endswith(".js"):
            continue
        src = read(os.path.join(apidir, f))
        src = strip_js_comments(src)
        consts = api_constants(app)
        src = re.sub(r"\$\{API\.([A-Z0-9_]+)\}", lambda m: consts.get(m.group(1), m.group(0)), src)
        src = re.sub(r"API\.([A-Z0-9_]+)", lambda m: consts.get(m.group(1), m.group(0)), src)
        for m in re.finditer(r"(/api/[A-Za-z0-9_\-/{}]+)", src):
            found.add(norm(m.group(1)))
    return found


def called_pattern(path):
    """由路径的**字面段**构造「有序出现」正则。

    为什么不用整串子串匹配（实测踩到）：前端大量 URL 是拼出来的，
    例如 `API.DELIVERY_ORDERS + '/' + id + '/claim'`，整串在文本里根本不会连续出现，
    会把它们全部误判成死端点（一次实测报了 106 个，绝大多数是假阳性）。
    有序段匹配（各段按顺序出现、间隔不超过 60 字符）既容忍拼接，又不至于像
    "无序段只要都出现过就算调用"那样漏报。
    """
    segs = [s for s in norm(path).split("/") if s and s != "{}"]
    if not segs:
        return None
    return re.compile(r"[\s\S]{0,60}?".join(re.escape(s) for s in segs))


def main():
    eps = backend_endpoints()
    eps_set = set(norm(p) for _, p, _, _ in eps)

    fe_text = "\n".join(frontend_text(a) for a in APPS)

    # ---------------- 反向：后端端点 → 前端有没有调 ----------------
    dead = []
    for kind, path, fn, lineno in eps:
        pat = called_pattern(path)
        if pat is None or pat.search(fe_text):
            continue
        dead.append((kind, path, fn, lineno))

    # ---------------- 正向：前端 api/*.js → 后端有没有 ----------------
    missing = []
    for fp in sorted(set().union(*[api_path_literals(a) for a in APPS]) if APPS else set()):
        if fp in eps_set:
            continue
        # 前缀匹配：后端存在更长的同前缀端点（例如 base 路径 + 变量段）
        if any(b.startswith(fp + "/") for b in eps_set):
            continue
        missing.append(fp)

    print("=" * 78)
    print("  双向审计 · 后端端点 ↔ 前端调用")
    print("=" * 78)
    print(f"后端端点总数: {len(eps)}")
    print()
    print("[A] 后端存在、但两个小程序都没有调用（死端点 / 仅报告）")
    print("-" * 78)
    if dead:
        for kind, path, fn, lineno in dead:
            print(f"  {kind:8s} {path:52s}  {fn}:{lineno}")
    else:
        print("  无")
    print(f"\n  小计: {len(dead)} 个")
    print()
    print("[B] 前端 api/*.js 调用、但后端不存在（会导致真机 404 / 致命）")
    print("-" * 78)
    if missing:
        for fp in missing:
            print(f"  {fp}")
    else:
        print("  无")
    print(f"\n  小计: {len(missing)} 个")
    print()
    print("=" * 78)
    print(f"  死端点 {len(dead)} 个（仅报告）· 前端调用缺失 {len(missing)} 个（致命）")
    print("=" * 78)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
