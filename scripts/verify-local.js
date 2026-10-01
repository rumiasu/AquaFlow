#!/usr/bin/env node
/**
 * 本地一键验证（**Windows 版**）：把仓库既有的静态门禁 + 本轮新增的门禁串起来跑一遍。
 *
 * 跑法：node scripts/verify-local.js
 * 退出码：0 = 全部门禁都**真的跑过**且全过；1 = 有失败，**或**有整组门禁因缺工具（python / git）
 *         根本没跑（会打印 `AQUAFLOW_VERIFY_LOCAL_INCOMPLETE`）—— 判据与 `verify.sh` 的 F-02 修复一致：
 *         **「没跑」不许当成「通过」**（AGENTS §5）。
 *         ⚠️ 与"本机这次没验"有关的**前置条件缺失**（后端没起、没打 jar）走 skip 且**不**算 incomplete：
 *         那是"这次没验"，不是"工具没了" —— 前者只列出并给出补跑命令（CI 上两项都会被真的跑到）。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它，而不是直接用 scripts/verify.sh
 * ---------------------------------------------------------------------------
 * `scripts/verify.sh` 是 bash，且**本机受限沙箱里 Cygwin/Git-bash 起不来**
 * （`couldn't create signal pipe, Win32 error 5`，见 skill §8.31）。所以本机从来没有
 * 一条"把所有门禁跑一遍"的命令 —— 每次都是手工一条条敲，也就没人会去敲。
 * 本脚本用 node 复刻它**能复刻的部分**，并明确标出**哪些没跑到**（不许当成通过）。
 *
 * ⚠️ 它**不替代** `verify.sh`：那边还有库重建 + 全量集成测试两步，
 *    这在 CI（Linux）上跑；本脚本负责"本机能跑的门禁一条不漏"。
 *
 * ⚠️ 判据一律用**退出码 + ASCII 哨兵**，两者都看：
 *    只判退出码不算判过（用例 await 的 Promise 永不 resolve 时 node 会静默退出、退出码仍是 0）。
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')

const repoRoot = path.resolve(__dirname, '..')
const PY = process.env.AQUAFLOW_PYTHON || 'python'

/**
 * 跑一个命令并把 stdout/stderr **分开**落文件。
 * 不用管道：受限沙箱建不了 named pipe，spawnSync 会回 status=null + EPERM，
 * 那时子进程根本没跑（skill §8.31）。stdout/stderr 混在同一个 fd 里也会互相插队
 * —— 本轮实测过 `mysql` 的口令警告被插进查询结果、进而被当成 SQL 解析。
 */
function runStream(cmd, args, opts) {
  const o = opts || {}
  const stamp = `${process.pid}-${Math.random().toString(36).slice(2)}`
  const outFile = path.join(os.tmpdir(), `verify-out-${stamp}.txt`)
  const errFile = path.join(os.tmpdir(), `verify-err-${stamp}.txt`)
  const fo = fs.openSync(outFile, 'w')
  const fe = fs.openSync(errFile, 'w')
  let r
  try {
    r = spawnSync(cmd, args, {
      cwd: o.cwd || repoRoot,
      shell: !!o.shell,
      stdio: ['ignore', fo, fe],
      timeout: o.timeout || 180000
    })
  } finally {
    fs.closeSync(fo); fs.closeSync(fe)
  }
  const stdout = fs.readFileSync(outFile, 'utf8')
  const stderr = fs.readFileSync(errFile, 'utf8')
  try { fs.unlinkSync(outFile); fs.unlinkSync(errFile) } catch (e) { /* 忽略 */ }
  return {
    status: r.status,
    stdout, stderr,
    eperm: !!(r.error && r.error.code === 'EPERM'),
    signaled: r.signal || null
  }
}

const results = []

/**
 * 「整组门禁根本没跑」位（对齐 `verify.sh` 的 F-02 修复，2026-10-01 补）。
 *
 * ⚠️ 这一位**必须有**：原实现只在 `fail` 时 `exit 1`，skip 一律不影响退出码，
 * 于是缺 python / git 时（PATH 被裁剪、受限沙箱）整组门禁跳过、结尾仍打印
 * `AQUAFLOW_VERIFY_LOCAL_OK` 且 exit 0 —— 与 F-02 在 `verify.sh` 上修掉的那个假绿**逐字相同**，
 * 只是换了入口（而本仓真正在本机跑的是这一个）。
 * 置位只认**工具缺失 / 子进程起不来**；"后端没起、jar 没打"属前置条件，只 skip。
 */
let MISSING_DEP = 0

function record(name, status, detail) {
  results.push({ name, status, detail })
  const mark = status === 'pass' ? '✓' : status === 'skip' ? '−' : '✗'
  console.log(`  ${mark} ${name}${detail ? '   [' + detail + ']' : ''}`)
}

/** 跑一个"看退出码"的门禁。`okCodes` 之外的都算失败；`skipCodes` 算跳过。 */
function gate(name, cmd, args, opts) {
  const o = Object.assign({ okCodes: [0], skipCodes: [] }, opts || {})
  const r = runStream(cmd, args, o)
  // ⚠️ EPERM = 子进程**根本没跑**（沙箱建不了 named pipe，skill §8.31）。它确实"不是门禁失败"，
  //    但也**不是通过** —— 按 F-02 判据置 MISSING_DEP，否则在受限环境里整轮全 skip 仍是绿灯。
  if (r.eperm) { record(name, 'skip', 'EPERM：沙箱不允许该子进程（没跑成，不算通过）'); MISSING_DEP = 1; return }
  if (r.signaled) { record(name, 'fail', `被信号 ${r.signaled} 终止`); return }
  if (o.skipCodes.includes(r.status)) { record(name, 'skip', `退出码 ${r.status} = 跳过`); return }
  if (o.okCodes.includes(r.status)) {
    // 收尾摘要取最后一行非空输出（给人看的信息）
    const tail = (r.stdout + r.stderr).split(/\r?\n/).filter(l => l.trim()).pop() || ''
    record(name, 'pass', tail.slice(0, 70))
    return
  }
  record(name, 'fail', `退出码 ${r.status}`)
  const lines = (r.stdout + r.stderr).split(/\r?\n/).filter(l => l.trim()).slice(-6)
  lines.forEach(l => console.log('        | ' + l.trim().slice(0, 150)))
}

function have(cmd) {
  const r = runStream(cmd, ['--version'], { timeout: 15000 })
  return !r.eperm && r.status === 0
}

/**
 * 探一个本机 HTTP 地址是否有人应答（等价于 `verify.sh:139-144` 那句
 * `curl -s -o /dev/null --max-time 3 http://127.0.0.1:8080/...`）。
 *
 * <p>为什么必须探：`smoke-check.js` 在服务没起时是 `exit 1`（它答不出"库通不通 / 认证拦没拦"，
 * 那是**判不了**而不是**不通过**）。不探就直跑 ⇒ 每次没起后端都报"有门禁失败"，
 * 这条命令很快就会被当成永远红、没人再看（`verify.sh:139-144` 早就探了，本文件漏了）。</p>
 *
 * <p>同步实现、不引依赖：起一个 `node -e` 子进程，只看退出码（不建管道，符合 skill §8.31）。
 * URL 用 `JSON.stringify` 生成 JS 字面量，不拼 shell。</p>
 */
function httpAlive(url) {
  const code = 'const h=require("http");const q=h.get(' + JSON.stringify(url) + ',s=>process.exit(s.statusCode?0:1));'
    + 'q.on("error",()=>process.exit(1));'
    + 'q.setTimeout(3000,()=>{q.destroy();process.exit(1)});'
  const r = spawnSync(process.execPath, ['-e', code], { stdio: 'ignore', timeout: 15000 })
  return r.status === 0
}

console.log('='.repeat(74))
console.log('AquaFlow 本地验证（Windows / node 版）')
console.log('='.repeat(74))

// ---------------------------------------------------------------------------
// ① 仓库既有静态门禁（对应 verify.sh 第 3 步）
// ---------------------------------------------------------------------------
console.log('\n[1] 仓库既有静态门禁')
if (!have(PY)) {
  console.log('  − python 不可用，跳过整组（CI 上会强制执行）')
  record('python 静态门禁（整组）', 'skip', '没有 python')
  MISSING_DEP = 1
} else {
  gate('wxml 事件绑定（两端）', PY, ['audit_wxml_handlers.py'])
  gate('页面可达性（顾客端）', PY, ['page_reach_audit.py', 'miniapp-user'])
  gate('页面可达性（员工端）', PY, ['page_reach_audit.py', 'miniapp-delivery'])
  gate('顾客端静态审计', PY, ['static_audit_user.py', 'miniapp-user'])
  gate('员工端静态审计', PY, ['static_audit_user.py', 'miniapp-delivery'])
  gate('悬空 javadoc', PY, ['audit_comments.py'])
  gate('js 解析期错误（两端）', PY, ['audit_js_syntax.py'], { skipCodes: [2] })
  gate('wxss 同元素类选择器', PY, ['audit_wxss_selectors.py'])
}

// ---------------------------------------------------------------------------
// ② 小程序流程测试（对应 verify.sh 第 4 步）
// ---------------------------------------------------------------------------
console.log('\n[2] 小程序流程测试（真执行页面处理函数）')
if (!have('node')) {
  record('流程测试', 'skip', '没有 node')
} else {
  gate('流程测试 run-all', 'node', ['tests/js/run-all.js'])
}

// ---------------------------------------------------------------------------
// ③ 仓库既有静态门禁之外的两条"数字一致性"检查
// ---------------------------------------------------------------------------
console.log('\n[3] 数字一致性（文档里的计数 vs 真值）')

/*
 * `sql/README.md` 里那句「当前库完整 DDL（NN 张业务表）」是一个**手写计数**，
 * 而本仓判据是「别手写会漂的计数」（AGENTS §0.4）。2026-09-27 实测它写的是 39，
 * 而 `schema.sql` 与真实库都是 51 —— 漂了 12。
 *
 * ⚠️ 本检查**不**在文档里重复这个数字（那样又多一处会漂的地方），而是**相对校验**：
 *    文档写的数必须等于 `schema.sql` 里 `CREATE TABLE` 的条数。
 *    用"数当前值 + 1"当诱饵去找那一行，避免正则去猜文档的行文格式。
 */
function checkSchemaTableCount() {
  const sqlReadme = 'AquaFlow-backend/sql/README.md'
  const schema = 'AquaFlow-backend/sql/schema.sql'
  const p1 = path.join(repoRoot, sqlReadme)
  const p2 = path.join(repoRoot, schema)
  if (!fs.existsSync(p1) || !fs.existsSync(p2)) {
    record('sql/README.md 的表数 vs schema.sql', 'skip', '文件不在')
    return
  }
  const actual = (fs.readFileSync(p2, 'utf8').match(/^CREATE TABLE/gm) || []).length
  const doc = fs.readFileSync(p1, 'utf8')
  /*
   * ⚠️ 第一版我用「按真值 n-1 / n / n+1 构造诱饵去找那一行」——**那是错的**：
   *    把文档改成 40（离真值很远）时它匹配不到，于是**静默 skip**，门禁形同虚设
   *    （反向验证时抓到的）。改成**直接把数字抽出来比对**，抽不到才算"改成了不写数字"。
   */
  const m = /当前库完整 DDL（\**(\d+)\**\s*张业务表/.exec(doc)
  if (!m) {
    record('sql/README.md 的表数 vs schema.sql', 'skip',
      `文档里没找到「当前库完整 DDL（NN 张业务表」这句（schema.sql 实测 ${actual}）—— 若已改成不写数字，这条可删`)
    return
  }
  const claimed = Number(m[1])
  const ok = claimed === actual
  record('sql/README.md 的表数 vs schema.sql', ok ? 'pass' : 'fail',
    ok ? `两边都是 ${actual}` : `文档写 ${claimed}，schema.sql 实测 ${actual}`)
}

checkSchemaTableCount()

/**
 * 敏感信息扫描的**等价实现**（2026-09-28 新增）。
 *
 * <p>为什么在本文件里重写一遍、而不是调 `scripts/scan-secrets.sh`：**那个脚本是 bash**，
 * 本机受限沙箱起不来（Cygwin 建不了 signal pipe，见 skill §8.31）。本仓对这种情况的规矩是
 * 「用等价实现逐条复刻，并说明哪条没跑到」—— 所以这里复刻它的**正则**与**排除规则**，
 * 并把范围对齐成 `<tracked> ∪ <untracked 且未被忽略>`（脚本本身用 `git grep`，只看已跟踪文件；
 * 这里多看一步"即将入库的新文件"，因为**新文件恰恰是最可能夹带硬编码密钥的**）。
 *
 * <p>⚠️ 规则以 `scripts/scan-secrets.sh` 的 PATTERN 为正本：**大小写敏感**（所以 `JWT_SECRET: '…'`
 * 这种大写键名不会命中，`jwt_secret: '…'` 才会），值必须被引号包住、长度 ≥ 12、且不含 `${}` 与空白。
 * 改那边的正则**必须同步改这里**（门禁清单一致性检查会盯着两者都在，但盯不了正则内容 —— 这条靠人）。
 */
function checkSecretsEquivalent() {
  const NAME = '敏感信息（等价实现，正本 scripts/scan-secrets.sh）'
  const tmp = path.join(os.tmpdir(), 'aquaflow-verify-local-files.txt')
  // ⚠️ 必须 `shell: true` 且**整条当命令传**：`runStream('cmd', ['/c', '…带引号…'])` 会因为
  //    cmd 的引号剥离规则**静默失败**（退出码 1、stderr 空），表现为"拿不到清单 → 跳过"，
  //    而那看起来像环境限制、不像自己写错（本文件这一版就踩了一次）。
  const r = runStream(`git ls-files --cached --others --exclude-standard > "${tmp}"`, [], { shell: true })
  if (!fs.existsSync(tmp) || fs.statSync(tmp).size === 0) {
    // git 是**工具**（不是"这次没验"）：拿不到清单 = 这条扫描根本没做 ⇒ 不许算通过。
    record(NAME, 'skip', `拿不到 git 文件清单（退出码 ${r.status}）—— git 不可用，本机这条**没跑成**`)
    MISSING_DEP = 1
    return
  }
  const PAT = /(jwt[_-]?secret|app[_-]?secret|secret[_-]?key|secret-id|access[_-]?key|password|passwd|wx[_-]?app[_-]?secret)["']?[ \t]*[:=][ \t]*["'][^"'${}\s]{12,}["']/g
  const EXCLUDE = /(\.md$|example|\.lock$|application-local\.yml$|\.min\.js$|^archive\/)/
  const files = fs.readFileSync(tmp, 'utf8').split('\n').map(s => s.trim()).filter(Boolean)
  const hits = []
  let scanned = 0
  for (const f of files) {
    if (EXCLUDE.test(f)) continue
    if (!/\.(js|ts|java|yml|yaml|json|sh|py|properties|xml)$/.test(f)) continue
    const abs = path.join(repoRoot, f)
    if (!fs.existsSync(abs)) continue
    scanned++
    PAT.lastIndex = 0
    if (PAT.test(fs.readFileSync(abs, 'utf8'))) hits.push(f)
  }
  if (hits.length) {
    record(NAME, 'fail', `${hits.length} 个文件疑似硬编码密钥（只列文件，不回显内容）`)
    hits.slice(0, 10).forEach(h => console.log('        | ' + h))
    return
  }
  record(NAME, 'pass', `扫过 ${scanned} 个文件，零命中`)
}

checkSecretsEquivalent()

/**
 * `AGENTS.md` 的体积预算（2026-09-28 新增）。
 *
 * <p>为什么这条要进本机门禁：那个文件**自己写明了**「≤ 65536 字节，**超出会被静默削尾**」——
 * 而"静默"正是最坏的部分：截断不报错、不告警，下一个人读到的是一份**少了尾巴的判据集**，
 * 却以为它是完整的（同 AGENTS §6.1「被信任的文本比没有文本更危险」）。
 * 它自己的规矩还要求「余量 < 4 KB 先瘦身」，所以这里对余量**只警告不判红**
 * （判红会逼人为了过门禁去删判据，方向反了）。</p>
 */
function checkAgentsBudget() {
  const NAME = 'AGENTS.md 体积预算（≤65536，超了会被静默削尾）'
  const p = path.join(repoRoot, 'AGENTS.md')
  if (!fs.existsSync(p)) {
    record(NAME, 'skip', '文件不在')
    return
  }
  const size = fs.statSync(p).size
  const LIMIT = 65536
  const headroom = LIMIT - size
  if (size > LIMIT) {
    record(NAME, 'fail', `${size} 字节，超上限 ${size - LIMIT} —— 会被静默削尾，必须先瘦身`)
    return
  }
  if (headroom < 4096) {
    // 不判红：文件自己的规矩是"加段前先瘦身"，这是提醒而不是失败
    record(NAME, 'pass', `${size} 字节，余量仅 ${headroom}（<4KB：按该文件 §10，加内容前先瘦身）`)
    return
  }
  record(NAME, 'pass', `${size} 字节，余量 ${headroom}`)
}

checkAgentsBudget()

// ---------------------------------------------------------------------------
// ④ 本轮新增的门禁
// ---------------------------------------------------------------------------
console.log('\n[4] 本轮新增的门禁')
// 「克隆得到吗」（2026-09-28 新增）：按 **git** 视角查 CI/发布依赖的件，
// 因为"文件在工作区里在" ≠ "别人 clone 得到" —— application-prod.yml 一度被 gitignore 却没被跟踪，
// 本机所有门禁全绿，而新克隆的 CI 第一步就会红。
gate('CI/发布依赖件是否入库（防"克隆后缺失"）',
  'node', ['scripts/check-tracked-inputs.js'])
// sql/ 与 sql/README.md 双向一致（2026-09-28 新增）：正向"每个脚本都要有出处"、
// 反向"必跑清单点到的脚本必须真实存在"。README 是迁移清单的正本，脱节会让运维在改生产库时做错决定。
gate('迁移清单与 sql/ 目录双向一致',
  'node', ['scripts/check-sql-catalog.js'])
// 台账判据可执行化（2026-09-30 新增，第六批 F-49）：台账 §2 的「度量方法」列落成断言 ——
// MUST（领域不变量）不达标即红；FINDING 与 §4 状态位互证（标 FIXED 却不达标 = 红）。
// 为什么要有：判据写在表里没有一条能跑 ⇒ 下一轮既证明不了这维涨了、也证明不了跌了。
gate('台账判据可执行化（MUST 不变量 + 登记项互证）',
  'node', ['scripts/check-ledger-claims.js'])
// 三处验证入口（ci.yml / verify.sh / 本文件）的**门禁清单**是否一致（2026-09-28 新增）。
// 为什么要有：新加一道门禁只接进其中一处，另一处**永远不会告诉你它漏了** ——
// 表现出来就是本仓记录过的「本机全绿、CI 红」（或反过来），且本地反复重跑也复现不出。
gate('三处入口的门禁清单一致',
  'node', ['scripts/check-gate-parity.js'])
// 「待拍板」标记的可追溯性（2026-09-28 新增）：AGENTS §0.6 是强制项（留标记 + 登记正本 + 回复列出），
// 而这三件事**没有一件是机器在盯的** —— 实测抓到一个退桶押金去向的决定只挂在小程序一行注释里。
// 代码侧严格判（每个标记都要指向真实存在的 docs/** 正本）；文档侧只列不判（提及 ≠ 使用，见脚本头部）。
gate('待拍板标记都能找到正本（AGENTS §0.6）',
  'node', ['scripts/check-pending-decisions.js'])
// 端点契约对账（2026-09-28 新增）：controller 注解里的实路径 ↔ `docs/api/01-REST-API参考.md` 的登记。
// 为什么要有：这俩是**同一条事实的两处表述**（同 `sql/README.md` ↔ `sql/` 那对），必然会漂 ——
// 实测当天就漂了 6 条（本轮新加的站间结算 4 条 + 押金交付 2 条，文档一个字没动）。
// 解析器踩过两个坑（数组别名 `@RequestMapping({...})`、裸 `@GetMapping`），校准后 283 ↔ 283 零假阳性。
gate('端点契约与 API 参考双向一致',
  'node', ['scripts/check-api-doc.js'])
gate('生产配置覆盖（prod 必需变量都在 .env.example 有定义）',
  'node', ['scripts/check-prod-config.js'])
// 发布物检查：**前置条件是先 bootJar**。`check-jar-no-local-config.js` 在 jar 不在时故意 `exit 1`
// （fail-closed 是对的：它宁可报错也不肯说"没发现问题"）。所以这里先判前置条件：
// 没打 jar 就 skip 并写明补跑命令，而不是把"这次没打 jar"渲染成"有门禁失败"。
// （CI / verify.sh 的 [5] 步会先 `gradlew bootJar` 再跑它，那边永远不会走到这个 skip 分支。）
const jarDir = path.join(repoRoot, 'AquaFlow-backend', 'build', 'libs')
const hasJar = fs.existsSync(jarDir) && fs.readdirSync(jarDir).some(f => f.endsWith('.jar'))
if (hasJar) {
  gate('发布物不含本地开发配置（需先 bootJar）',
    'node', ['scripts/check-jar-no-local-config.js'])
} else {
  record('发布物不含本地开发配置（需先 bootJar）', 'skip',
    'build/libs 下没有 jar → 先 `cd AquaFlow-backend && gradlew.bat bootJar` 再重跑')
}
// 部署冒烟检查：**先探端口再决定跑不跑**（与 verify.sh 同判据，见 httpAlive 的注释）。
if (httpAlive('http://127.0.0.1:8080/api/stations/public')) {
  gate('部署冒烟检查（打本机 8080）',
    'node', ['scripts/smoke-check.js'])
} else {
  record('部署冒烟检查（打本机 8080）', 'skip',
    '本机 8080 没有服务 → 不跑；部署后单独跑：node scripts/smoke-check.js <URL> --prod')
}
// 小程序文本体检（2026-09-30 新增，合成 F-33 + F-13 的一半）：按扩展名**全仓遍历**两端小程序的
// `.js/.wxml/.wxss/.json`，查 ① UTF-8 BOM（skill §8.28：带 BOM 的 .wxss 让开发者工具报编译错且
// 不指名文件，而当时四个门禁全绿）② `<text>` 内出现开发词（AGENTS §6 的面向用户文案禁令；
// 注释里随便写，脚本先剥注释再匹配）。退出码 3 = 环境不允许（找不到两端小程序目录），故按跳过处理。
// ⚠️ 它取代了 `scripts/debug/__check-no-bom.js` 这个**没有任何入口**的孤儿脚本（check-gate-parity.js 的
//    GATE_PATTERN 认不出 `__` 前缀；旧脚本还是硬编码 16 文件清单）。旧脚本有意保留，别再往它清单里加文件。
gate('小程序文本体检（BOM / 面向用户文案的开发词）',
  'node', ['scripts/check-miniapp-text.js'], { skipCodes: [3] })

// ---------------------------------------------------------------------------
// ⑤ 需要显式给出的三项（不自动跑）
// ---------------------------------------------------------------------------
console.log('\n[5] 需要显式执行的三项（本脚本不自动跑）')
console.log('  · 备份 / 恢复演练：node scripts/backup-restore-drill.js drill')
console.log('    （会写演练库 aquaflow_restoredrill；成功会自己清理）')
console.log('  · 生产启动姿态：node scripts/prod-startup-check.js')
console.log('    （拿 build/libs 的 jar 真起 13 次：必需变量缺失必须拒启、COS 四件套缺失只降级；')
console.log('      先 gradlew bootJar；会自建/自清一次性库 aquaflow_prodstartup_check）')
console.log('  · 后端集成测试：cd AquaFlow-backend && gradlew.bat test --no-daemon --project-cache-dir .gradle_eval')
console.log('    （需 MySQL 的 aquaflow_test 库；本机 8080 在跑时别用默认 project-cache-dir）')

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------
const pass = results.filter(r => r.status === 'pass').length
const skip = results.filter(r => r.status === 'skip').length
const fail = results.filter(r => r.status === 'fail').length
console.log('\n' + '='.repeat(74))
console.log(`通过 ${pass} / 跳过 ${skip} / 失败 ${fail}`)
if (skip) {
  console.log('跳过的项（**不能当成通过**）：')
  results.filter(r => r.status === 'skip').forEach(r => console.log(`  − ${r.name}：${r.detail}`))
}
if (fail) {
  console.log('失败：')
  results.filter(r => r.status === 'fail').forEach(r => console.log(`  ✗ ${r.name}：${r.detail}`))
  console.log('\n[verify-local] ✗ 有门禁未通过')
  process.exit(1)
}
// 结尾判据（2026-10-01 补，对齐 verify.sh 的 F-02 修复）：
// **缺工具导致整组没跑时不许返回 0**，否则 PATH 被裁剪的机器上会给出假绿。
if (MISSING_DEP) {
  console.log('')
  console.log('⚠️  本次验证**不完整**：有门禁因缺工具（python / git）或子进程起不来而没跑成（见上方 skip 行）。')
  console.log('   判据：**「没跑」不许当成「通过」**（AGENTS §5）—— 这些门禁在 CI 上会强制执行。')
  console.log('AQUAFLOW_VERIFY_LOCAL_INCOMPLETE 1')
  process.exit(1)
}
// ASCII 哨兵：受限沙箱里子进程写的中文可能被编码毁掉，判据别建在中文上
console.log('AQUAFLOW_VERIFY_LOCAL_OK ' + pass)
console.log('[verify-local] ✓ 本机能跑的门禁全部通过（跳过的项已在上方列出）')
process.exit(0)
