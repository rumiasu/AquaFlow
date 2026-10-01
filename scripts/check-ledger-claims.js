#!/usr/bin/env node
/**
 * 台账判据可执行化（F-49，2026-09-30 第六批）：把 `docs/audit/项目健康度台账.md` §2 里
 * 「度量方法」列写成**能跑的命令**，让"台账说谎"本身成为一条红灯。
 *
 *   node scripts/check-ledger-claims.js
 *
 * ## 为什么要有它
 *
 * 台账的 D6 自己就栽过三次：「高风险端点全有用例」（错 4 条，见 F-47）、
 * 「断言强度 = grep isSuccess」（295 处根本分不出强弱）、`SECURITY.md` 说
 * 「新增方法自动生效」（与实现完全相反，F-67）。**判据写在表里、没有一条能跑**
 * ⇒ 下一轮既证明不了这维涨了、也证明不了跌了。
 *
 * ## 三类断言（红的口径不一样，别混）
 *
 * | 类别 | 含义 | 红的条件 |
 * |---|---|---|
 * | **MUST** | 领域不变量 / 已关闭项的回归防线 | 实测不达标 = 立刻红（exit 1） |
 * | **FINDING** | 对应 §4 一条登记项的**未修现状** | 台账标 `FIXED` 却实测不达标（说谎/回归）才红；标 `OPEN` 的不达标 = 已知未修，只提示 |
 * | **MANUAL** | 静态 grep 判不了的（读清单/读设计），只列出 | 永不红 —— 它们就是"没有门禁"的那部分 |
 *
 * FINDING 反向也查：实测达标但台账仍 `OPEN` ⇒ 打印「可翻状态」提示（不红）——
 * 防的是另一个方向的漂移：代码修了、状态位忘了翻（本仓第四批就补翻过 9 条）。
 *
 * ## 判据实现的边界（如实写）
 *
 * - **只做静态文本**，不起服务、不连库（与其余 check-* 门禁同层级）。
 * - Java/JS 的注释剥离是**行级**的：字符串字面量里的 `//`（如 URL）会连带吃掉行尾，
 *   这只可能造成**漏报**（把真代码当注释）、不会造假阳性到 MUST 上 —— 所有 MUST 的
 *   目标串都不与 URL 同行。
 * - 用例件数等"会漂的计数"只打印、不判红（正本是 `build/test-results/test/*.xml`，AGENTS §0.4）。
 *
 * 跑法见台账 §5；接线在 ci.yml / verify.sh / verify-local.js 三处（由 check-gate-parity 对账）。
 */

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const LEDGER = 'docs/audit/项目健康度台账.md'
const BACKEND = path.join(ROOT, 'AquaFlow-backend', 'src')
const SKIP_DIRS = new Set(['node_modules', 'build', 'out', 'archive', 'backup', 'generated-images',
  '.git', '.gradle', '.dsh-code-index', '.openvisio', '.gradlehome', '.gradle_alt', '.gradle_alt2',
  '.gradle_alt3', '.gradle-user', '.dsh', '.agents', '.vscode', '.idea'])

// ---------------------------------------------------------------------------
// 文件遍历与注释剥离
// ---------------------------------------------------------------------------
function walk(dir, out = []) {
  let ents
  try { ents = fs.readdirSync(dir, { withFileTypes: true }) } catch (e) { return out }
  for (const e of ents) {
    if (e.name.startsWith('.') || SKIP_DIRS.has(e.name) || /^build_/.test(e.name)) continue
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}
function stripLineComments(t) {
  return t.replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n').map(l => { const i = l.indexOf('//'); return i >= 0 ? l.slice(0, i) : l }).join('\n')
}
function stripXmlComments(t) { return t.replace(/<!--[\s\S]*?-->/g, '') }
function read(rel) { return fs.readFileSync(path.join(ROOT, rel), 'utf8') }

const javaAll = walk(BACKEND).filter(f => f.endsWith('.java'))
const mainJava = javaAll.filter(f => f.includes(`${path.sep}main${path.sep}`))
const testJava = javaAll.filter(f => f.includes(`${path.sep}test${path.sep}`))
const mainSrc = mainJava.map(f => ({ f, t: stripLineComments(fs.readFileSync(f, 'utf8')) }))
const allJavaSrc = javaAll.map(f => ({ f, t: stripLineComments(fs.readFileSync(f, 'utf8')) }))
const testSrc = testJava.map(f => ({ f, t: stripLineComments(fs.readFileSync(f, 'utf8')) }))
const mapperXml = walk(path.join(BACKEND, 'main', 'resources', 'mapper'))
  .filter(f => f.endsWith('.xml')).map(f => ({ f, t: stripXmlComments(fs.readFileSync(f, 'utf8')) }))

function grepAll(files, re) {
  let n = 0
  const byFile = []
  for (const { f, t } of files) {
    const m = t.match(re)
    if (m) { n += m.length; byFile.push({ rel: path.relative(ROOT, f), n: m.length }) }
  }
  return { n, byFile }
}
function fileNamesOf(grepResult) { return grepResult.byFile.map(x => path.basename(x.rel)) }

// ---------------------------------------------------------------------------
// 台账状态解析：条目既可以是 `#### F-XX \`OPEN\`[P1]` 块，也可以是表格行
// ---------------------------------------------------------------------------
const ledgerText = read(LEDGER)
function ledgerStatus(id) {
  const block = new RegExp(`^####\\s+${id}\\s+\`([^\`]+)\``, 'm').exec(ledgerText)
  if (block) return block[1]
  const row = new RegExp(`^\\|\\s*${id}\\s*\\|\\s*\`([^\`]+)\``, 'm').exec(ledgerText)
  if (row) return row[1]
  return null
}
const isFixed = (s) => s !== null && /FIXED/.test(s)
const isOpen = (s) => s !== null && /OPEN/.test(s)

// ---------------------------------------------------------------------------
// 结果记录
// ---------------------------------------------------------------------------
const results = []
function must(id, claim, ok, detail) {
  results.push({ kind: 'MUST', id, claim, ok, detail })
  return ok
}
function finding(id, ledgerId, claim, measuredOk, detail) {
  const status = ledgerStatus(ledgerId)
  if (status === null) {
    results.push({ kind: 'FINDING', id, ledgerId, claim, ok: false, verdict: 'RED', detail: `台账里找不到 ${ledgerId} 的状态行（登记缺失）` })
    return
  }
  let verdict, ok
  if (!measuredOk && isFixed(status)) { verdict = 'RED'; ok = false }
  else if (!measuredOk && isOpen(status)) { verdict = 'KNOWN-OPEN'; ok = true }
  else if (measuredOk && isOpen(status)) { verdict = 'HINT'; ok = true }
  else { verdict = 'OK'; ok = true }
  results.push({ kind: 'FINDING', id, ledgerId, claim, ok, verdict, status, detail })
}
function info(id, text) { results.push({ kind: 'INFO', id, detail: text }) }
function manual(id, text) { results.push({ kind: 'MANUAL', id, detail: text }) }

// ---------------------------------------------------------------------------
// MUST：领域不变量 / 已关闭项的回归防线
// ---------------------------------------------------------------------------

// M-01 · D1.1 置 payment_status=2 的写入口只能从 0/1 迁入（src/main 范围；测试夹具里的裸 UPDATE 不算写入口）
{
  const g = grepAll(mainSrc, /set\s+payment_status\s*=/gi)
  const files = fileNamesOf(g)
  const allInOrderMapper = g.byFile.every(x => x.rel.endsWith('OrderMapper.java'))
  const guarded = /in\s*\(\s*0\s*,\s*1\s*\)/.test(mainSrc.find(x => x.f.endsWith('OrderMapper.java')).t)
  must('M-01', 'D1.1 src/main 写 orders.payment_status 的 SQL 恰 2 条（CAS + markPaidIfCollectable in(0,1)）且都在 OrderMapper',
    g.n === 2 && allInOrderMapper && guarded,
    `实测 ${g.n} 条 @ [${files.join(', ')}]，in(0,1) 守卫=${guarded}`)
}

// M-02 · D1.2 非 CAS 的支付流水写原语已删且零调用（墓碑注释被剥离后应为 0）
{
  const g = grepAll(allJavaSrc, /paymentRecordMapper\.updateStatus\(/g)
  must('M-02', 'D1.2 paymentRecordMapper.updateStatus( 调用 = 0（F-19 已删）', g.n === 0, `实测 ${g.n}`)
}

// M-03 · D1.3 两条退款路径共用同一流水构造与水票回补方法（def + ≥2 调用点）
{
  const a = grepAll(mainSrc, /insertRefundRecord\s*\(/g).n
  const b = grepAll(mainSrc, /restoreTicketsForOrder\s*\(/g).n
  must('M-03', 'D1.3 insertRefundRecord / restoreTicketsForOrder 各 ≥3 处（1 定义 + 2 路径调用）', a >= 3 && b >= 3,
    `insertRefundRecord=${a}, restoreTicketsForOrder=${b}`)
}

// M-04 · D1.4 计价单一实现：调用方只有下单与报价两处
{
  const g = grepAll(mainSrc, /\.calcForOrder\s*\(/g)
  const names = fileNamesOf(g).sort()
  const expected = ['OrderServiceImpl.java', 'PaymentServiceImpl.java'].sort()
  must('M-04', 'D1.4 .calcForOrder( 的调用方恰为 OrderServiceImpl + PaymentServiceImpl（报价与下单同一实现）',
    JSON.stringify(names) === JSON.stringify(expected), `调用方=[${names.join(', ')}]`)
}

// M-05 · D3.4 回桶默认值唯一实现
{
  const decl = grepAll(mainSrc, /(?:public|private|protected)[\w<>,\s]*\breturnPlanOfOrder\s*\(/g)
  must('M-05', 'D3.4 returnPlanOfOrder 定义恰 1 处（唯一实现）', decl.n === 1, `定义 ${decl.n} 处`)
}

// M-06 · D3.5 库存预留调用完整：补预留进"库存增加"路径、释放进"取消"路径
{
  const backfillFiles = fileNamesOf(grepAll(mainSrc, /backfillReservations/g))
  const releaseFiles = fileNamesOf(grepAll(mainSrc, /releaseForOrder/g))
  const backfillOk = backfillFiles.includes('InventoryServiceImpl.java') && backfillFiles.includes('InventoryReservationServiceImpl.java')
  const releaseOk = releaseFiles.includes('PaymentServiceImpl.java') && releaseFiles.includes('InventoryReservationServiceImpl.java')
  must('M-06', 'D3.5 backfillReservations 在库存增加路径、releaseForOrder 在取消/退款路径（PaymentServiceImpl）',
    backfillOk && releaseOk, `backfill@[${backfillFiles.join(',')}] release@[${releaseFiles.join(',')}]`)
}

// M-07 · D4.1 HTTP 层无事务（注释剥离后；LayeringArchitectureTest 是另一道同判据的运行时门禁）
{
  const ctrl = mainSrc.filter(x => x.f.includes(`${path.sep}controller${path.sep}`))
  const g = grepAll(ctrl, /@Transactional/g)
  must('M-07', 'D4.1 Controller 的 @Transactional = 0（分层基线之一）', g.n === 0, `controller 文件 ${ctrl.length} 个，命中 ${g.n}`)
}

// M-08 · D4.2 并发写有当前读（判据是"存在成规模的行锁"，具体条数只打印）
{
  const j = grepAll(mainSrc, /for\s+update/gi).n
  const x = grepAll(mapperXml, /for\s+update/gi).n
  must('M-08', 'D4.2 FOR UPDATE 行锁 ≥4（当前读守卫存在）', (j + x) >= 4, `main java ${j} + mapper xml ${x}`)
}

// M-09 · D4.3 事务内不吞业务异常（逐个 @Transactional 方法体做括号配对）
{
  let violations = 0
  const where = []
  for (const { f, t } of mainSrc) {
    let idx = 0
    while ((idx = t.indexOf('@Transactional', idx)) !== -1) {
      let brace = -1
      for (let j = idx + 14; j < t.length; j++) {
        const c = t[j]
        if (c === '{') { brace = j; break }
        if (c === ';' || c === '\n') break            // 接口声明/注解行内换行 ⇒ 不是方法体
      }
      if (brace < 0) { idx += 14; continue }
      let depth = 0, k = brace
      for (; k < t.length; k++) { if (t[k] === '{') depth++; else if (t[k] === '}') { depth--; if (depth === 0) break } }
      if (/catch\s*\(\s*BusinessException/.test(t.slice(brace, k + 1))) { violations++; where.push(path.relative(ROOT, f)) }
      idx = k + 1
    }
  }
  must('M-09', 'D4.3 @Transactional 方法体内 catch (BusinessException) = 0（吞业务异常会伪装成 500/回滚异常）',
    violations === 0, `违规 ${violations} 处${where.length ? ' @ ' + [...new Set(where)].join(', ') : ''}`)
}

// M-10 · D6.2 门禁缺依赖时不许判绿（F-02 的回归防线）
{
  const sh = read('scripts/verify.sh')
  must('M-10', 'D6.2 verify.sh 有 MISSING_DEP 位 + AQUAFLOW_VERIFY_INCOMPLETE 哨兵（F-02 回归防线）',
    /MISSING_DEP=0/.test(sh) && /AQUAFLOW_VERIFY_INCOMPLETE/.test(sh), '两条串都在')
}

// M-11 · D6.3 门禁正则覆盖 final/static 等价写法（F-01 回归防线）
{
  const lay = fs.readFileSync(path.join(ROOT, 'AquaFlow-backend/src/test/java/com/example/aquaflow/architecture/LayeringArchitectureTest.java'), 'utf8')
  // 判据锚在**正则源码串**本身（javadoc 里讨论老写法的段落不算）：`(?:(?:final|static)\\s+)*`
  const ok = lay.includes('(?:(?:final|static)') && /BASELINE_MAPPER_FIELDS_COUNT/.test(lay)
  must('M-11', 'D6.3 LayeringArchitectureTest 的 Mapper 正则覆盖 private final/static 写法（F-01 回归防线）',
    ok, ok ? '正则含 (?:(?:final|static) 且有字段数上限' : '正则回退成了老写法')
}

// M-12 · D6.4 CI 触发面（F-04 回归防线）：push 与 pull_request 都不过滤分支
{
  const ci = read('.github/workflows/ci.yml')
  const pushOk = /push:\s*\n(?:.*\n)*?\s*branches:\s*\["\*\*"\]/.test(ci)
  const prOk = /pull_request:\s*\n(?:.*\n)*?\s*branches:\s*\["\*\*"\]/.test(ci)
  must('M-12', 'D6.4 CI push/pull_request 触发面 branches 都是 ["**"]（F-04 回归防线）', pushOk && prOk,
    `push=${pushOk} pull_request=${prOk}`)
}

// M-13 · D6.5 测试与 CI 的配置差集已钉死（F-15 回归防线）
{
  const t = read('AquaFlow-backend/src/test/resources/application-test.yml')
  must('M-13', 'D6.5 application-test.yml 钉死 dev-login-enabled=false（F-15：否则"本地绿、CI 红"）',
    /dev-login-enabled:\s*false/.test(t), 'dev-login-enabled: false')
}

// M-14 · D6.6 断言强度：整条只断言 isSuccess() 的用例 = 0（改进判据：逐条断言语句，非 295 处粗 grep）
{
  let weak = 0
  const hits = []
  for (const { f, t } of testSrc) {
    const parts = t.split(/@Test\b/)
    for (let i = 1; i < parts.length; i++) {
      const stmts = [...parts[i].matchAll(/(assert\w+|fail)\s*\(([\s\S]*?)\)\s*;/g)]
      if (stmts.length === 0) continue
      if (stmts.every(s => /isSuccess\(\)/.test(s[2]))) { weak++; hits.push(path.relative(ROOT, f)) }
    }
  }
  must('M-14', 'D6.6 断言全部只是 isSuccess() 的用例 = 0（弱断言判据，逐句解析）', weak === 0,
    `弱断言用例 ${weak} 个${hits.length ? ' @ ' + [...new Set(hits)].slice(0, 3).join(', ') : ''}`)
}

// M-15 · D8.6 面向用户文案无开发词（先剥 <!-- -->，只看会渲染的部分）
{
  const devWords = ['接口', '后端', '前端', '服务端', '落库', '端点', '字段', '部署', '重新构建']
  const hits = []
  for (const app of ['miniapp-user', 'miniapp-delivery']) {
    for (const f of walk(path.join(ROOT, app))) {
      if (!/\.(wxml|wxss)$/.test(f)) continue
      const t = stripXmlComments(fs.readFileSync(f, 'utf8'))
      for (const seg of t.match(/<text[^>]*>[\s\S]*?<\/text>/g) || []) {
        for (const w of devWords) if (seg.includes(w)) hits.push(`${path.relative(ROOT, f)}:${w}`)
      }
    }
  }
  must('M-15', 'D8.6 <text> 内开发词 = 0（AGENTS §6 文案禁令）', hits.length === 0,
    hits.length ? hits.slice(0, 5).join(' ') : '0 命中')
}

// M-16 · D9.4a 已删写原语不许回潮（F-19 回归防线）
{
  const a = grepAll(allJavaSrc, /updateDeliveryStaff/g).n + grepAll(mapperXml, /updateDeliveryStaff/g).n
  must('M-16', 'D9.4 updateDeliveryStaff 全仓（含墓碑剥离后）= 0（F-19 已删）', a === 0, `实测 ${a}`)
}

// M-17 · 本门禁自己的接线：三处入口都**真的会跑**本门禁（防"三处一起漏"时没人叫）
//   ⚠️ 必须剥掉注释再找：第一版只查原文 includes，结果 verify.sh 里三行 `#` 注释提到脚本名、
//   把「可执行行被删」遮住了 —— 反向验证当场抓出这个假绿（注释 ≠ 会跑）。
{
  const stripSh = (t) => t.split('\n').filter(l => !/^\s*#/.test(l)).join('\n')          // verify.sh / ci.yml 的 # 注释行
  const stripJs = (t) => stripLineComments(t)                                             // verify-local.js 的 // 与块注释
  const entries = [
    ['scripts/verify.sh', stripSh],
    ['.github/workflows/ci.yml', stripSh],
    ['scripts/verify-local.js', stripJs]
  ]
  const missing = entries.filter(([rel, strip]) => !strip(read(rel)).includes('check-ledger-claims.js')).map(([rel]) => rel)
  must('M-17', 'F-49 接线：ci.yml / verify.sh / verify-local.js 三处都有**可执行**的本门禁调用（剥注释后）',
    missing.length === 0, missing.length ? `缺：${missing.join(', ')}` : '三处齐')
}

// M-18 · 门禁家族自身的存在性（pariity 门禁跑之前得先有文件）
{
  const need = ['scripts/verify.sh', '.github/workflows/ci.yml', 'scripts/verify-local.js', 'scripts/check-gate-parity.js']
  const missing = need.filter(rel => !fs.existsSync(path.join(ROOT, rel)))
  must('M-18', '门禁入口与 parity 对账文件都存在', missing.length === 0, missing.length ? `缺 ${missing.join(', ')}` : '齐')
}

// ---------------------------------------------------------------------------
// FINDING：与 §4 登记项互证（台账说谎才红）
// ---------------------------------------------------------------------------

// FND-1 · D3.2 状态机接线 ↔ F-10（只在 1 处接线 = 已知未修；AGENTS 若删掉那句宣称也算解除）
{
  const g = grepAll(mainSrc, /isValidTransition\s*\(/g)
  const defs = grepAll(mainSrc, /(?:public|private|protected|static)[\w<>,\s]*\bisValidTransition\s*\(/g).n
  const calls = g.n - defs
  let agentsClaims = false
  try { agentsClaims = /isValidTransition/.test(read('AGENTS.md')) } catch (e) { agentsClaims = true }
  const ok = calls >= 2 || !agentsClaims
  finding('FND-1', 'F-10', 'D3.2 isValidTransition 接线（调用点 ≥2，或文档不再宣称它拒非法流转）', ok,
    `调用点 ${calls} 处，AGENTS 仍宣称=${agentsClaims}`)
}

// FND-2 · D6.1 资金只读端点的测试覆盖 ↔ F-47（4 条零覆盖 = 已知未修）
{
  const joined = testSrc.map(x => x.t).join('\n')
  const probes = [
    /payments\/by-order/.test(joined),
    /payments\/by-customer/.test(joined),
    /payments\/customer\//.test(joined),
    /["'(]\/api\/payments(\?|["'])/.test(joined) || /payments\/all/.test(joined)
  ]
  const ok = probes.every(Boolean)
  finding('FND-2', 'F-47', 'D6.1 PaymentController 四条只读端点有用例（by-order / by-customer / customer/{id} / 根+all）',
    ok, `覆盖 ${probes.filter(Boolean).length}/4`)
}

// FND-3 · D7.5 存活探针 ↔ F-46（无静态健康端点 = 已知未修）
{
  const g = grepAll(mainSrc, /api\/system\/health/g)
  finding('FND-3', 'F-46', 'D7.5 存在不查库的静态探针端点 GET /api/system/health', g.n > 0, `src/main 命中 ${g.n}`)
}

// FND-4 · D2.5 限流注册表 ↔ F-48（注册 7 条、用例只钉 2 条 = 已知未修）
{
  const wmc = mainSrc.find(x => x.f.endsWith('WebMvcConfig.java'))
  let registry = []
  if (wmc) {
    const start = wmc.t.indexOf('registry.addInterceptor(rateLimitInterceptor)')
    const end = wmc.t.indexOf('registry.addInterceptor(authInterceptor)', start)
    if (start >= 0 && end > start) registry = [...wmc.t.slice(start, end).matchAll(/"([^"]+)"/g)].map(m => m[1])
  }
  const rlt = testSrc.find(x => x.f.endsWith('RateLimitIntegrationTest.java'))
  const tested = rlt ? [...new Set([...rlt.t.matchAll(/"(\/api\/auth\/[^"]+)"/g)].map(m => m[1]))] : []
  const ok = registry.length > 0 && registry.length === tested.length && registry.every(p => tested.includes(p))
  finding('FND-4', 'F-48', 'D2.5 WebMvcConfig 限流注册表 == RateLimitIntegrationTest 钉住的清单',
    ok, `注册 ${registry.length} 条 [${registry.join(', ')}]；用例钉 ${tested.length} 条 [${tested.join(', ')}]`)
}

// FND-5 · D9.4b 零调用方法 ↔ F-45（只有"定义 + 接口声明" = 已知未修；真调用方（带 `.`
//   前缀）出现、或整段删干净，都算解除 —— ⚠️ 不能用"提及数 − 定义数"：接口声明没有
//   public/private 关键字，会被定义正则漏计、把声明误判成调用方。
{
  const mentions = grepAll(mainSrc, /confirmOrderCollection/g).n
  const callers = grepAll(mainSrc, /\.confirmOrderCollection\s*\(/g).n
  const ok = callers >= 1 || mentions === 0
  finding('FND-5', 'F-45', 'D9.4 confirmOrderCollection 零调用（0 调用且未删除 = OPEN；删除或接线即解除）',
    ok, `提及 ${mentions}、真调用方（"." 前缀）${callers}`)
}

// FND-6 · D9.1 超大文件棘轮 ↔ F-18 行（该行标 FIXED 并写明"只剩 3 个"，超了就是回归/说谎）
{
  const big = mainJava.map(f => ({ rel: path.relative(ROOT, f), n: fs.readFileSync(f, 'utf8').split('\n').length }))
    .filter(x => x.n > 1000).sort((a, b) => b.n - a.n)
  finding('FND-6', 'F-18', 'D9.1 >1000 行源文件 ≤3（F-18 拆分后的棘轮）', big.length <= 3,
    `${big.length} 个：${big.map(x => path.basename(x.rel) + '=' + x.n).join(', ')}`)
}

// ---------------------------------------------------------------------------
// INFO：会漂的计数只打印，不判红（正本见各自行）
// ---------------------------------------------------------------------------
{
  const endpoints = grepAll(mainSrc, /@(Get|Post|Put|Delete|Patch)Mapping\b/g).n
  info('I-01', `方法级端点 ${endpoints}（正本：controller 注解；对账走 check-api-doc.js）`)
  const tables = (read('AquaFlow-backend/sql/schema.sql').match(/^CREATE TABLE/gm) || []).length
  info('I-02', `schema.sql 表数 ${tables}（正本：sql/schema.sql）`)
  const tr = path.join(ROOT, 'AquaFlow-backend', 'build', 'test-results', 'test')
  if (fs.existsSync(tr)) {
    let classes = 0, cases = 0
    for (const f of fs.readdirSync(tr).filter(x => x.endsWith('.xml'))) {
      const head = fs.readFileSync(path.join(tr, f), 'utf8').slice(0, 2000)
      const m = /tests="(\d+)"/.exec(head); if (m) { classes++; cases += Number(m[1]) }
    }
    info('I-03', `测试件数 ${cases} / ${classes} 类（build/test-results；正本同 audit_scenario_matrix STATS，跑完测试再对）`)
  } else {
    info('I-03', '测试件数：build/test-results/test 不存在（没跑过测试，跳过）')
  }
}

// ---------------------------------------------------------------------------
// MANUAL：静态判不了的 —— 只列出，不判红（它们正是"还没有门禁"的部分）
// ---------------------------------------------------------------------------
manual('X-01', 'D1.5/D1.6 唯一键 NULL 保护与对账等式语义 —— 读 schema.sql + ReconciliationService（集成测试有 E1–E14 用例）')
manual('X-02', 'D4.4/D4.5 CAS 行数检查逐处核对与并发用例覆盖 —— LayeringArchitectureTest 管注解层，其余靠 review')
manual('X-03', 'D5.2/D5.4 迁移幂等与清单线性执行 —— 正本 sql/README.md + 逐脚本读预检（check-sql-catalog 只管清单↔文件）')
manual('X-04', 'D7.6 超时配置 / D8.4 弹窗确认闭环 / D10.4 AGENTS 判据抽查 —— 读文件，无静态判据')
manual('X-05', 'F-09 两端 prod.baseUrl 占位域名 —— 发布前置项，值必须由人填（api.js 自带 throw 拦截）')

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
const musts = results.filter(r => r.kind === 'MUST')
const finds = results.filter(r => r.kind === 'FINDING')
const reds = results.filter(r => r.ok === false)

console.log('='.repeat(78))
console.log('台账判据可执行化（check-ledger-claims）—— MUST 不变量 / FINDING 登记项互证')
console.log('='.repeat(78))

console.log('\n[MUST] 领域不变量与已关闭项的回归防线')
for (const r of musts) console.log(`  ${r.ok ? '✓' : '✗'} ${r.id} ${r.claim}\n      ${r.detail}`)

console.log('\n[FINDING] 与 §4 登记项互证（台账标 FIXED 却不达标才红）')
for (const r of finds) {
  const mark = r.verdict === 'RED' ? '✗' : r.verdict === 'OK' ? '✓' : r.verdict === 'HINT' ? '~' : '·'
  const tail = r.verdict === 'HINT' ? `  ← 台账 ${r.ledgerId} 可翻 FIXED`
    : r.verdict === 'KNOWN-OPEN' ? `  ← 台账 ${r.ledgerId}=${r.status} 已知未修`
      : r.verdict === 'RED' ? `  ← 台账 ${r.ledgerId}=${r.status} 与实测矛盾（说谎或回归）` : ''
  console.log(`  ${mark} ${r.id} ↔ ${r.ledgerId} ${r.claim}${tail}\n      ${r.detail}`)
}

console.log('\n[INFO] 会漂的计数（只打印；正本各见其行）')
for (const r of results.filter(x => x.kind === 'INFO')) console.log(`  · ${r.id} ${r.detail}`)

console.log('\n[MANUAL] 静态判不了的项（没有门禁，别当通过）')
for (const r of results.filter(x => x.kind === 'MANUAL')) console.log(`  · ${r.id} ${r.detail}`)

console.log('')
console.log('-'.repeat(78))
if (reds.length) {
  console.error(`[check-ledger-claims] ✗ ${reds.length} 处不达标：`)
  for (const r of reds) console.error(`    - ${r.id} ${r.claim}（${r.detail}）`)
  process.exit(1)
}
const hints = finds.filter(r => r.verdict === 'HINT')
console.log(`[check-ledger-claims] ✓ ${musts.length} 条 MUST 全过，${finds.length} 条 FINDING 与台账状态互证一致` +
  (hints.length ? `（其中 ${hints.length} 条实测已达标、台账可翻状态：${hints.map(h => h.ledgerId).join(', ')}）` : ''))
console.log('AQUAFLOW_LEDGER_CLAIMS_OK')
process.exit(0)
