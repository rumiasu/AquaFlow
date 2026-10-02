#!/usr/bin/env node
/**
 * 部署冒烟检查：判「这一次发布到底起对了没有」。
 *
 * 跑法：
 *   node scripts/smoke-check.js                          # 打本机 http://127.0.0.1:8080
 *   node scripts/smoke-check.js https://你的域名          # 打远端（上线后那一次）
 *   node scripts/smoke-check.js https://你的域名 --prod   # 远端 + 要求"生产姿态"（见下）
 *
 * 退出码：0 = 必需项全过；1 = 有必需项失败；2 = 用法错
 *
 * ---------------------------------------------------------------------------
 * 为什么不是"扫一遍端点看有没有 200"
 * ---------------------------------------------------------------------------
 * 一次发布"起没起对"有三个**互相独立**的层面，任意一个单独看都会给出错误结论：
 *
 *   ① **活着**（进程在、HTTP 在答）：只看这个 ⇒ 后端连不上数据库也照样 200；
 *   ② **连得上库**：只看这个 ⇒ 认证被关掉、后门敞开也看不出来；
 *   ③ **环境姿态对**（没有开发后门 / 认证确实在拦）：只看这个 ⇒ 服务根本没起来也会"通过"。
 *
 * 所以本脚本把三项都跑，并把**结论**和**事实**分开打印：
 *   · 必需项（活着 / 连得上库 / 认证在拦）—— 不过就是 exit 1；
 *   · 姿态项（dev-login 后门）—— **本地本来就该开着**，所以只在 `--prod` 下才算失败，
 *     其余情况作为"事实"打印出来。这条最容易被误读成"检查失败"，故显式区分。
 *
 * ⚠️ 本脚本**不写任何东西**：全是 GET，且不登录、不带 token。
 *    它刻意不复用 `dev-login` 去拿会话 —— 那会污染真实库（自动注册客户），
 *    而"未认证访问受保护端点必须 401"本身就是一条更值得测的判据。
 */

const http = require('http')
const https = require('https')

const args = process.argv.slice(2)
const baseArg = args.find(a => !a.startsWith('--'))
const REQUIRE_PROD = args.includes('--prod')
const BASE = (baseArg || 'http://127.0.0.1:8080').replace(/\/+$/, '')

/** 发一次 GET，返回 {status, body(已按 UTF-8 解码), error}。**不抛异常**。 */
function get(path) {
  return new Promise((resolve) => {
    const url = new URL(BASE + path)
    const mod = url.protocol === 'https:' ? https : http
    const req = mod.request({
      hostname: url.hostname,
      port: url.port || (url.protocol === 'https:' ? 443 : 80),
      path: url.pathname + url.search,
      method: 'GET',
      timeout: 8000
    }, (res) => {
      const chunks = []
      res.on('data', c => chunks.push(c))
      res.on('end', () => {
        // 必须显式按 UTF-8 解：这个仓的响应里有中文，按默认编码解会变乱码
        // （PowerShell 里 Invoke-WebRequest 就是这个坑，看着像"数据坏了"，其实是显示层）
        resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') })
      })
    })
    req.on('timeout', () => { req.destroy(new Error('超时(8s)')) })
    req.on('error', (e) => resolve({ status: null, body: '', error: e.message }))
    req.end()
  })
}

/** 从响应体里安全取 code 字段（非 JSON 就返回 undefined）。 */
function codeOf(body) {
  try {
    const j = JSON.parse(body)
    return j && typeof j === 'object' ? j.code : undefined
  } catch (e) {
    return undefined
  }
}

const required = []
const posture = []
function req_(name, ok, fact) { required.push({ name, ok, fact }) }
function pos(name, ok, fact, note) { posture.push({ name, ok, fact, note }) }

async function main() {
  console.log(`[smoke] 目标：${BASE}${REQUIRE_PROD ? '（要求生产姿态）' : ''}`)
  console.log('='.repeat(64))

  // ① 活着：**静态探针** /api/system/health（F-46，2026-09-30）——
  //    为什么不能拿别的端点当 liveness：本仓业务错/系统错都包成 HTTP 200（GlobalExceptionHandler，
  //    业务契约不许改）⇒ 按状态码的探针在别处恒绿；而 /api/stations/public 依赖 DB，
  //    "进程活着但库断"时分不出是哪层坏了。三态分层：本端点=进程活着，下面 ②=连得上库。
  const probe = await get('/api/system/health')
  const alive = probe.status !== null
  req_('存活：静态探针有应答（/api/system/health）', alive,
    alive ? `HTTP ${probe.status}` : `无响应（${probe.error}）`)

  // 服务都没起来时，"库通不通 / 认证拦没拦"问不出结论 ——
  // 硬跑只会把"无应答"印成"未通过"，让人以为查出了别的问题。
  if (!alive) {
    console.log('必需项：')
    required.forEach(r => {
      console.log(`  ${r.ok ? '✓' : '✗'} ${r.name}`)
      console.log(`      ${r.fact}`)
    })
    console.log('')
    console.error('[smoke] ✗ 服务没有应答 —— 后面的检查无法判定（先确认进程与端口，再看日志）')
    process.exit(1)
  }

  // 探针本身要真的工作：静态端点必须 200 + code=0。
  // code=404 通常是"后端还是老进程、新端点没起来"（本轮实测的旧 bootRun 形状）——
  // 这条判据不许放宽成"有应答就算过"，否则探针永远不会发现自己不存在。
  const probeCode = codeOf(probe.body)
  req_('存活探针：HTTP 200 + code=0（静态、不查库）',
    probe.status === 200 && probeCode === 0,
    `HTTP ${probe.status}, code=${probeCode}`)

  // ② 连得上库：只读公开端点要读 station 表才答得出来 ⇒ body.code=0 即"HTTP 通 + 库读通"。
  const db = await get('/api/stations/public')
  const dbCode = codeOf(db.body)
  req_('数据库：公开端点能读到数据（body.code=0）', dbCode === 0,
    dbCode === undefined ? `响应不是预期的 JSON：${db.body.slice(0, 80)}` : `code=${dbCode}`)

  // ③ 认证确实在拦：未带 token 访问受保护端点必须**真 401**（不是 200+code=1）
  const guarded = await get('/api/manager/alerts')
  req_('认证：未认证访问受保护端点必须 401', guarded.status === 401,
    `HTTP ${guarded.status}${guarded.status === 200 ? '（200 说明认证没拦住）' : ''}`)

  // ④ 环境姿态：prod 下 dev-login 必须不存在。
  //    判据（2026-09-27 实测）：该端点是 @Profile("!prod")，所以
  //      · prod：路径不存在 → HTTP 200 + body.code=404「接口不存在」
  //      · 非 prod：Bean 在 → HTTP 200 + body.code=1「请求方法不支持：该路径只接受 POST」
  //    两者的 HTTP 状态**都是 200**，只能看 body.code —— 这正是本仓「判据看 code 不看 HTTP」的又一例。
  const devLogin = await get('/api/auth/dev-login')
  const devCode = codeOf(devLogin.body)
  const devAbsent = devCode === 404
  pos('环境姿态：dev-login 后门' + (REQUIRE_PROD ? '（生产要求关闭）' : ''),
    REQUIRE_PROD ? devAbsent : true,
    devAbsent ? '已关闭（code=404，路径不存在）' : `仍然存在（code=${devCode}）`,
    devAbsent ? '' : (REQUIRE_PROD
      ? '⛔ 生产环境开着开发登录后门：任何微信用户都能自助换取员工会话'
      : '本机 local profile 本来就该开着（真机联调退路），不算问题'))

  // ⑤ 顺带记录：有没有 actuator（没有是**已知现状**，不是失败）
  //    ⚠️ 判据必须严格等于 404 才是"未引入"；`undefined`（响应不是 JSON）只说明**判不了**，
  //       不能印成"存在" —— 那是把"没答"读成"有"（本脚本第一版就是这么误报的）。
  const actuator = await get('/actuator/health')
  const actCode = codeOf(actuator.body)
  pos('可观测性：actuator 健康端点', true,
    actCode === 404 ? '未引入（存活探针用 /api/system/health，连库判据用 /api/stations/public）'
      : actCode === undefined ? `无法判定（HTTP ${actuator.status}，响应非预期 JSON）`
        : `存在（HTTP ${actuator.status}，code=${actCode}）`,
    '')

  const print = (rows) => rows.forEach(r => {
    console.log(`  ${r.ok ? '✓' : '✗'} ${r.name}`)
    console.log(`      ${r.fact}`)
    if (r.note) console.log(`      ↳ ${r.note}`)
  })

  console.log('必需项：')
  print(required)
  console.log('姿态项（不通过不一定是失败，看上面的说明）：')
  print(posture)

  const failed = required.filter(r => !r.ok).length + posture.filter(r => !r.ok).length
  console.log('')
  if (failed) {
    console.error(`[smoke] ✗ ${failed} 项未通过`)
    process.exit(1)
  }
  console.log('[smoke] ✓ 全部通过' + (REQUIRE_PROD ? '' : '（未校验生产姿态；上线后请加 --prod 再跑一次）'))
  process.exit(0)
}

main().catch(e => {
  console.error('[smoke] 脚本自身出错：' + e.message)
  process.exit(2)
})
