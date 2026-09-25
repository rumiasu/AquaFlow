# =============================================================================
# 一条命令把本地联调环境弄好（2026-09-22 新增）
# =============================================================================
# 它做三件事，顺序固定：
#   ① 同步两端 config/api.js 的 dev 地址（规则见 set-dev-api-host.ps1：热点开着→192.168.137.1，
#      否则→当前 WLAN 地址）；
#   ② 检查 MySQL 服务与 8080 端口 —— 后端已经在跑就不重复起；
#   ③ 在**当前这个终端里前台**启动后端（Ctrl+C 停止）。
#
# ③ 是刻意的：前台跑，进程归**你的终端**，不会像挂在 AI 会话后台任务里那样被一起杀掉
#   （2026-09-21、09-22 两次"前后端搭不上"，根因都是后台任务被终止、后端悄悄没了）。
#
# 用法（在仓库根执行）：
#   pwsh -File scripts/dev-up.ps1
#
# ⚠️ 本文件与 set-dev-api-host.ps1 一样**必须保存为 UTF-8 with BOM** —— Windows PowerShell 5.1
#    对无 BOM 的 .ps1 按系统 ANSI(GBK) 解码，中文变乱码后**直接解析失败**。
#    校验：$b=[IO.File]::ReadAllBytes('scripts/dev-up.ps1'); '{0:X2}{1:X2}{2:X2}' -f $b[0],$b[1],$b[2]
# =============================================================================

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'AquaFlow-backend'

function Write-Step($n, $text) { Write-Host "`n[$n] $text" -ForegroundColor Cyan }

# ── ① 同步 dev 地址 ──────────────────────────────────────────────────────────
Write-Step 1 '同步两端 config/api.js 的 dev 地址'
& (Join-Path $PSScriptRoot 'set-dev-api-host.ps1') -Apply

# 写完再读回来 —— 以文件为准，不靠解析上一条命令的输出（那样太脆）。
$devIp = $null
foreach ($rel in 'miniapp-user/config/api.js', 'miniapp-delivery/config/api.js') {
    $p = Join-Path $root $rel
    if (-not (Test-Path $p)) { continue }
    $m = [regex]::Match([IO.File]::ReadAllText($p), "(?m)^\s*dev:\s*\{\s*baseUrl:\s*'http://([^':]+):8080'")
    if ($m.Success) { $devIp = $m.Groups[1].Value; break }
}

# ── ② 前置检查 ───────────────────────────────────────────────────────────────
Write-Step 2 '前置检查（MySQL / 8080）'

$svc = Get-Service MySQL -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Host '  ⚠️ 找不到名为 MySQL 的服务，数据库状态无法确认。' -ForegroundColor Yellow
} elseif ($svc.Status -ne 'Running') {
    Write-Host "  ⚠️ MySQL 服务是 $($svc.Status) —— 后端起来也会连不上库。" -ForegroundColor Yellow
    Write-Host '     用**管理员** PowerShell 跑：net start MySQL' -ForegroundColor Yellow
} else {
    Write-Host '  ✅ MySQL 服务 Running' -ForegroundColor Green
}

$listener = netstat -ano | Select-String ':8080\s+.*LISTENING' | Select-Object -First 1
if ($listener) {
    $busyPid = ($listener.Line.Trim() -split '\s+')[-1]
    Write-Host "  ℹ️ 8080 已经被 PID $busyPid 占用 —— 后端**已经在跑**，不再重复启动。" -ForegroundColor Green
    Write-Host '     要重启就先停掉它（或直接在 IDE 里重跑），然后重新执行本脚本。' -ForegroundColor DarkGray
    $alreadyUp = $true
} else {
    Write-Host '  ✅ 8080 空闲' -ForegroundColor Green
    $alreadyUp = $false
}

if ($devIp) {
    Write-Host ''
    Write-Host "  手机要访问的地址： http://${devIp}:8080" -ForegroundColor Cyan
    Write-Host '  ⚠️ 手机必须与电脑处在**同一个网络**里，否则这个地址它根本路由不到：' -ForegroundColor Yellow
    Write-Host '     · 电脑用移动WiFi  → 手机也连那个移动WiFi；' -ForegroundColor Yellow
    Write-Host '     · 或电脑连手机热点（手机当 AP，手机自己能访问到本机）；' -ForegroundColor Yellow
    Write-Host '     · 手机只用流量、电脑用移动WiFi = 两个不同网络，**没有任何地址能连通**。' -ForegroundColor Yellow
    Write-Host '  开发者工具不受影响（走 config/api.js 里的 devtoolsBaseUrl = 127.0.0.1）。' -ForegroundColor DarkGray
}

# ── ③ 前台启动后端 ───────────────────────────────────────────────────────────
if ($alreadyUp) { exit 0 }

Write-Step 3 '启动后端（前台；Ctrl+C 停止）'
$env:GRADLE_USER_HOME = Join-Path $root '.gradlehome'
Set-Location $backend
Write-Host "  GRADLE_USER_HOME = $env:GRADLE_USER_HOME" -ForegroundColor DarkGray
Write-Host '  .\gradlew.bat bootRun --no-daemon --project-cache-dir .gradle_eval' -ForegroundColor DarkGray
& (Join-Path $backend 'gradlew.bat') bootRun --no-daemon --project-cache-dir .gradle_eval
