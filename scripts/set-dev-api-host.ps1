# =============================================================================
# 把两个小程序的 dev.baseUrl 指向「当前这台机器」的局域网 IP（2026-09-20 新增）
# =============================================================================
# 为什么需要它：dev.baseUrl 必须是**运行后端那台电脑的局域网 IP**（真机上 127.0.0.1 指手机自己），
# 而这个 IP 由 DHCP 分配，**换网络/换路由器就会变**。2026-09-20 实际发生过一次：
# 配置里还写着 192.168.0.243，而机器早已是 10.213.244.181 →
# 开发者工具里所有请求 `net::ERR_CONNECTION_TIMED_OUT`，页面一片空白，
# 看上去像"后端挂了"，实际后端好端端听着 0.0.0.0:8080。
#
# 用法（在仓库根执行）：
#   pwsh -File scripts/set-dev-api-host.ps1            # 只打印：当前 IP + 两端现在配的是什么
#   pwsh -File scripts/set-dev-api-host.ps1 -Apply     # 按当前 IP 改写两端 config/api.js
#   pwsh -File scripts/set-dev-api-host.ps1 -Ip 192.168.1.5 -Apply   # 指定 IP（真机在别的网段时）
#
# ⚠️ 它只改 `dev: { baseUrl: ... }` 那一行，其余内容一字不动（两个 config/api.js 里
#    还有大量端点常量，其中 delivery 那份可能正被别的改动动着）。
#
# ⚠️⚠️ **本文件必须保存为「UTF-8 with BOM」**。Windows PowerShell 5.1 对**没有 BOM** 的 .ps1
#    按系统 ANSI（中文机上是 GBK）解码 —— 里面的中文会变成乱码并**直接解析失败**
#    （报 Unexpected token，且指向的行号看着莫名其妙）。这与 AGENTS §8 第 28 条
#    「小程序文件带 BOM 会让 IDE 编译失败」**方向相反**：那条说的是 .wxss/.wxml，这条说的是 .ps1。
#    改本文件后请确认前三字节是 EF BB BF：
#      $b=[IO.File]::ReadAllBytes('scripts/set-dev-api-host.ps1'); '{0:X2}{1:X2}{2:X2}' -f $b[0],$b[1],$b[2]
# =============================================================================

[CmdletBinding()]
param(
    [string]$Ip,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot          # 仓库根（scripts/ 的上一级）
$targets = @(
    'miniapp-user/config/api.js',
    'miniapp-delivery/config/api.js'
)

function Get-LanIp {
    # 取「默认路由所在网卡」的 IPv4 —— 只有它才是手机能访问到的那个地址。
    # 不能简单取第一个非 127 的地址：本机还有 WSL / VirtualBox / 移动热点 等虚拟网卡
    # （实测 172.27.112.1、192.168.56.1、192.168.137.1 都在，手机上根本连不上）。
    #
    # [2026-09-20 加固] 原来只有「CIM 两连」：Get-NetIPConfiguration → Get-NetRoute+Get-NetIPAddress。
    # 这在**受限会话 / 无 WMI 权限**下会整条失败（实测报「拒绝访问」/ HRESULT 0x80041003），
    # 表现为脚本直接抛「取不到局域网 IP」—— 明明网络是通的，却让人以为没连上 Wi-Fi。
    # 故补两条**只用命令行工具**的兜底（不依赖 CIM，受限 shell 下同样可用）：
    #   ③ route print -4 里默认路由那一行的「接口」列就是本机地址；
    #   ④ ipconfig 里「有默认网关」那块网卡的 IPv4（没有网关的适配器手机上连不到）。
    $cimError = $null
    try {
        $candidates = Get-NetIPConfiguration -ErrorAction Stop |
            Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' } |
            ForEach-Object { $_.IPv4Address.IPAddress }
        if ($candidates) { return @($candidates)[0] }
    } catch {
        $cimError = $_.Exception.Message
    }
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
            Sort-Object RouteMetric | Select-Object -First 1
        if ($route) {
            $addr = Get-NetIPAddress -InterfaceIndex $route.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($addr) { return $addr.IPAddress }
        }
    } catch {
        if (-not $cimError) { $cimError = $_.Exception.Message }
    }

    # ③ route print -4：`0.0.0.0  0.0.0.0  <网关>  <本机地址>  <metric>`
    foreach ($line in (route print -4 2>$null)) {
        if ($line -match '^\s*0\.0\.0\.0\s+0\.0\.0\.0\s+\d{1,3}(?:\.\d{1,3}){3}\s+(\d{1,3}(?:\.\d{1,3}){3})') {
            return $Matches[1]
        }
    }

    # ④ ipconfig：记住每个 IPv4，遇到该网卡的默认网关就认它
    $ip = $null
    foreach ($line in (ipconfig 2>$null)) {
        if ($line -match 'IPv4.*?:\s*(\d{1,3}(?:\.\d{1,3}){3})') {
            $ip = $Matches[1]
        } elseif ($line -match 'Default Gateway|默认网关') {
            if ($line -match '\d{1,3}(?:\.\d{1,3}){3}' -and $ip) { return $ip }
            $ip = $null
        }
    }

    $hint = if ($cimError) { "（CIM 查询失败：$cimError）" } else { '' }
    throw "取不到局域网 IP$hint。请用 -Ip <地址> 显式指定，例如： -Ip 192.168.0.243"
}

if (-not $Ip) { $Ip = Get-LanIp }
if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw "IP 格式不对：$Ip" }

$newBase = "http://${Ip}:8080"
Write-Host ""
Write-Host "目标地址 : $newBase" -ForegroundColor Cyan
Write-Host "后端自检 : " -NoNewline

# 后端是否真的在听、以及从该地址能不能通（401 = 通了但缺 token，属正常）
try {
    $null = Invoke-WebRequest -Uri "$newBase/api/delivery/orders/pending" -TimeoutSec 5 -UseBasicParsing
    Write-Host "可达（HTTP 200）" -ForegroundColor Green
} catch {
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -eq 401) {
        Write-Host "可达（HTTP 401 未授权 = 后端在跑，只是没带 token）" -ForegroundColor Green
    } else {
        Write-Host "不可达：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "        先确认后端已启动（cd AquaFlow-backend; .\gradlew.bat bootRun）" -ForegroundColor Yellow
    }
}
Write-Host ""

$changed = 0
foreach ($rel in $targets) {
    $path = Join-Path $root $rel
    if (-not (Test-Path $path)) { Write-Host "[跳过] 找不到 $rel" -ForegroundColor Yellow; continue }

    $text = [System.IO.File]::ReadAllText($path)
    $pattern = "(?m)^(\s*dev:\s*\{\s*baseUrl:\s*')([^']*)(')"
    $m = [regex]::Match($text, $pattern)
    if (-not $m.Success) { Write-Host "[跳过] $rel 里没找到 dev.baseUrl 那一行" -ForegroundColor Yellow; continue }

    $current = $m.Groups[2].Value
    if ($current -eq $newBase) {
        Write-Host "[已是最新] $rel  ->  $current" -ForegroundColor Green
        continue
    }
    Write-Host "[需修改] $rel" -ForegroundColor Yellow
    Write-Host "         现在: $current"
    Write-Host "         改成: $newBase"
    if ($Apply) {
        # 只替换那一行的引号内内容；用 UTF8 **无 BOM** 回写 —— 带 BOM 会让微信开发者工具
        # 编译失败且不指名文件（AGENTS §8 第 28 条）。
        $replaced = [regex]::Replace($text, $pattern, { param($x) $x.Groups[1].Value + $newBase + $x.Groups[3].Value }, 1)
        [System.IO.File]::WriteAllText($path, $replaced, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "         已写入（UTF-8 无 BOM）" -ForegroundColor Green
        $changed++
    }
}

Write-Host ""
if (-not $Apply) {
    Write-Host "（这是预览。确认无误后加 -Apply 真正改写。）" -ForegroundColor Cyan
} elseif ($changed -eq 0) {
    Write-Host "没有需要改的文件。" -ForegroundColor Green
} else {
    Write-Host "改了 $changed 个文件 —— 回微信开发者工具点「编译」即可。" -ForegroundColor Green
}
