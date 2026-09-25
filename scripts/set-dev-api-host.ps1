# =============================================================================
# 把两个小程序的 dev.baseUrl 指向「当前真能用的那个地址」（2026-09-20 新增；09-21 试过写死；09-22 改回自动）
# =============================================================================
# 为什么需要它：dev.baseUrl 是**手机**要访问的地址（真机上的 127.0.0.1 指手机自己）。
# 而"笔记本自己怎么上网"是会变的：移动WiFi / 手机热点来回切，DHCP 还会换地址 ——
# 2026-09-20 实际发生过一次：配置里还写着 192.168.0.243，而机器早已是 10.213.244.181 →
# 开发者工具里所有请求 `net::ERR_CONNECTION_TIMED_OUT`，页面一片空白，
# 看上去像"后端挂了"，实际后端好端端听着 0.0.0.0:8080。
#
# [2026-09-22 结论] 取地址规则：**热点开着 → 用 192.168.137.1；热点关着 → 用 WLAN 地址**。
#   09-21 一度把默认值**写死**成笔记本热点的 192.168.137.1（想让"一个值走天下"：手机连笔记本热点，
#   笔记本自己用哪个网上行不参与该地址，故移动WiFi ⇄ 手机热点切换都不用改配置）。
#   但 09-21、09-22 两次实测热点都是**关的**，写死的值于是指向一个没人连得上的地址、真机全废，
#   而当下真实可用的 WLAN 地址反倒没被用上。
#   **判据：默认值必须指向"现在真能用"的那个地址，而不是"配置上最优雅"的那个。**
#   另注：热点开着时 192.168.137.1 是 ICS 的固定段（注册表 `ScopeAddress` 实测），
#   手机热点当上行时手机自己是 AP、连不上笔记本热点 —— 那种场景改用 USB 共享网络。
#   开发者工具始终不受影响：config/api.js 里 devtoolsBaseUrl = 127.0.0.1。
#
# 用法（在仓库根执行）：
#   pwsh -File scripts/set-dev-api-host.ps1            # 只打印：取到哪个地址、热点开没开、两端现在配的是什么
#   pwsh -File scripts/set-dev-api-host.ps1 -Apply     # 按上面的规则改写两端 config/api.js（平时就用这条）
#   pwsh -File scripts/set-dev-api-host.ps1 -Lan -Apply              # 强制用当前 WLAN 地址（热点开着也想走 Wi-Fi 时）
#   pwsh -File scripts/set-dev-api-host.ps1 -Ip 192.168.1.5 -Apply   # 显式指定地址
#
# ⚠️ 它只改 `dev: { baseUrl: ... }` 那一行，其余内容一字不动（两个 config/api.js 里
#    还有大量端点常量，其中 delivery 那份可能正被别的改动动着）。
#    ⚠️ `devtoolsBaseUrl` 与 `baseUrl` **写在同一行**，所以那一行的形状**不要拆成多行** ——
#       拆了这个正则就匹配不上，脚本会静默「跳过」。
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
    [switch]$Lan,
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

function Test-HotspotOn {
    # 判据用 **WinRT 的 TetheringOperationalState** —— 这是唯一权威的开关状态。
    #
    # ⚠️⚠️ **不能用 icssvc 服务状态判**（2026-09-22 实测被它骗过一次）：只要有任何代码
    #   **查询**过 WinRT 热点接口，`icssvc` 就会被拉起来并一直保持 Running，**而热点其实还是 Off**。
    #   当时本脚本据此判成"已开启"、把两端地址写成了 192.168.137.1 —— 那地址根本没人连得上，
    #   等于亲手把真机打回"网络错误"。
    #
    # ⚠️ **也不能用「ipconfig / route 里有没有 192.168.137.1」判**，两个方向都会错。本机实测
    #   （2026-09-21，热点处于**关闭**状态）：
    #     · `ipconfig` 与 `route print -4` **都看不到** 192.168.137.1，也没有任何 192.168.137 路由；
    #     · 但裸 TCP 连 `192.168.137.1:8080`（Tomcat）与 `:3306`（MySQL）**都连得上**，
    #       `:80 / :443 / :9999` 连不上 —— 因为那两个服务监听 0.0.0.0；
    #     · `netstat` 出现 `TCP 192.168.137.1:xxxx  192.168.137.1:8080  TIME_WAIT`，
    #       本地端点本身就是 192.168.137.1 ⇒ **本机 TCP 栈把它当本地地址**。
    #   两个后果：① 用 ipconfig 判会**漏判**（地址在，ipconfig 却不显示）；
    #             ② **从笔记本探测这个地址永远是通的**，证明不了手机能不能连上 —— 见下方"后端自检"。
    try {
        [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime] | Out-Null
        $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
        if (-not $profile) { return $false }   # 电脑自己没联网时热点也起不来
        $mgr = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
        return ($mgr.TetheringOperationalState -eq 'On')
    } catch {
        # 取不到就当**没开**：失败要朝向 WLAN 地址（那个至少是真能用的）；
        # 朝向 192.168.137.1 会指向一个没人连得上的地址。
        return $false
    }
}

function Get-IcsScopeAddress {
    # ICS 规范地址的**正本在注册表**（本机实测 = 192.168.137.1）。
    # 读它只为**发现漂移**：脚本里的 $HotspotIp 是写死的，而 ICS 的网段**不是绝对的** ——
    # 「设置 → 网络重置」、或上行网络本身正好占用了 192.168.137.0/24 时，
    # Windows 会把 ICS 换到别的段；那时写死的值就把两端指向一个**不存在**的地址，
    # 现象又是"网络错误"。宁可报红，也不要静默写下去。
    try {
        return (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters' -ErrorAction Stop).ScopeAddress
    } catch {
        return $null   # 读不到（权限不足）不算错，只是这次没法校验
    }
}

# ── 取地址优先级：-Ip 显式指定 > 移动热点（**开着**才用它）> WLAN 默认路由地址 ──
#
# [2026-09-22 改回自动] 09-21 曾把默认值写死成「笔记本热点 192.168.137.1」，理由是那样
#   移动WiFi ⇄ 手机热点来回切都不用改配置。**实测 09-21 与 09-22 两次，热点都是关的**
#   （`icssvc Stopped`）→ 写死的值指向一个**没人连得上**的地址，真机全废；而当下真实可用的
#   `192.168.0.243` 反倒没被用上。
#   判据：**默认值必须指向「现在真能用」的那个地址，不是「配置上最优雅」的那个。**
#   故改回自动：热点开着 → 用它（手机连的就是它，恒定）；关着 → 用 WLAN 地址。
$HotspotIp = '192.168.137.1'

$hotspotOn = Test-HotspotOn
$icsScope  = Get-IcsScopeAddress
$icsDrift  = [bool]($icsScope -and $icsScope -ne $HotspotIp)

if ($Ip) {
    $source = '显式 -Ip 指定'
} elseif ($Lan) {
    $Ip = Get-LanIp
    $source = 'WLAN 默认路由地址（-Lan 显式要求）'
} elseif ($hotspotOn) {
    if ($icsDrift) {
        # 热点开着但 ICS 换过段：写死的 192.168.137.1 是错的，用注册表里的真实值。
        $Ip = $icsScope
        $source = "笔记本移动热点（ICS 已换段，实为 $icsScope）"
    } else {
        $Ip = $HotspotIp
        $source = '笔记本移动热点（192.168.137.1，恒定）'
    }
} else {
    $Ip = Get-LanIp
    $source = 'WLAN 默认路由地址（DHCP 分配，会变）'
}
if ($Ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw "IP 格式不对：$Ip" }

$newBase = "http://${Ip}:8080"
Write-Host ""
Write-Host "地址来源 : $source" -ForegroundColor Cyan
Write-Host "目标地址 : $newBase" -ForegroundColor Cyan

if ($hotspotOn) {
    Write-Host "热点状态 : 已开启（手机连上笔记本热点即可访问上面的地址）" -ForegroundColor Green
    if ($icsDrift) {
        Write-Host "ICS 地址 : 已漂移！注册表 ScopeAddress = $icsScope，脚本里写死的是 $HotspotIp" -ForegroundColor Red
        Write-Host "           本次已按注册表的真实值取址；请把脚本里的 HotspotIp 同步改掉" -ForegroundColor Red
    }
} else {
    Write-Host "热点状态 : 没开 —— 真机只能走上面的 WLAN 地址（手机必须连同一个 Wi-Fi）" -ForegroundColor Yellow
    Write-Host "           想改走笔记本热点：设置 -> 网络和 Internet -> 移动热点 -> 打开，然后重跑本脚本" -ForegroundColor Yellow
}

if ($Ip -eq $HotspotIp) {
    # ⚠️ 热点地址下**刻意不做 HTTP 自检**：本机实测（2026-09-21）热点没开时 192.168.137.1
    #   依然被本机 TCP 栈当作本地地址（8080/3306 都通，见 Test-HotspotOn 注释），
    #   从笔记本打**必然"可达"** —— 那是自己打自己，证明不了手机能不能连上，只会给误导性的绿灯。
} else {
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
