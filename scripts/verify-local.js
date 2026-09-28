#!/usr/bin/env node
/**
 * 本地一键验证（**Windows 版**）：把仓库既有的静态门禁 + 本轮新增的门禁串起来跑一遍。
 *
 * 跑法：node scripts/verify-local.js
 * 退出码：0 = 全部通过（跳过的项会明确列出）；1 = 有失败
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
function record(name, status, detail) {
  results.push({ name, status, detail })
  const mark = status === 'pass' ? '✓' : status === 'skip' ? '−' : '✗'
  console.log(`  ${mark} ${name}${detail ? '   [' + detail + ']' : ''}`)
}

/** 跑一个"看退出码"的门禁。`okCodes` 之外的都算失败；`skipCodes` 算跳过。 */
function gate(name, cmd, args, opts) {
  const o = Object.assign({ okCodes: [0], skipCodes: [] }, opts || {})
  const r = runStream(cmd, args, o)
  if (r.eperm) { record(name, 'skip', 'EPERM：沙箱不允许该子进程（不是门禁失败）'); return }
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
    record(NAME, 'skip', `拿不到 git 文件清单（退出码 ${r.status}），CI 上由 scan-secrets.sh 强制执行`)
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
gate('发布物不含本地开发配置（需先 bootJar）',
  'node', ['scripts/check-jar-no-local-config.js'])
gate('部署冒烟检查（打本机 8080）',
  'node', ['scripts/smoke-check.js'])

// ---------------------------------------------------------------------------
// ④ 需要显式给出的两项（不自动跑）
// ---------------------------------------------------------------------------
console.log('\n[4] 需要显式执行的三项（本脚本不自动跑）')
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
// ASCII 哨兵：受限沙箱里子进程写的中文可能被编码毁掉，判据别建在中文上
console.log('AQUAFLOW_VERIFY_LOCAL_OK ' + pass)
console.log('[verify-local] ✓ 本机能跑的门禁全部通过（跳过的项已在上方列出）')
process.exit(0)
