#!/usr/bin/env node
/**
 * **三个验证入口的"门禁清单"一致性**检查。
 *
 *   node scripts/check-gate-parity.js
 *
 * ## 为什么要有它
 *
 * 本仓库有三处"跑门禁"的入口，它们是**同一条事实的三份实现**：
 *
 * | 入口 | 谁在用 | 特点 |
 * |---|---|---|
 * | `.github/workflows/ci.yml` | CI | 正本，最全 |
 * | `scripts/verify.sh` | 文档里教新人的那条（`CONTRIBUTING.md`） | **bash**，本机受限沙箱跑不了 |
 * | `scripts/verify-local.js` | 本机（bash 跑不了时的等价实现） | node |
 *
 * 三处一旦漂移，就会出现本仓记录过的那类事故：**"本机全绿、CI 红"**（或反过来），
 * 而人在本地反复重跑也复现不出来（同 §8.27 末段那次 `DEV_LOGIN_ENABLED` 不一致）。
 * 典型的漂移形状：新加了一道门禁，只接进了其中一处 —— **另一处永远不会告诉你它漏了**。
 *
 * ## 判据
 *
 * 1. 从三处各自**读出它跑了哪些脚本**（不靠人维护清单，靠解析文本）；
 * 2. 逐个脚本比对：出现在某处、却没出现在另一处 ⇒ 必须在 `KNOWN_ASYMMETRY` 里**带理由**登记；
 * 3. 没登记的不对称 = 失败。
 *
 * ⚠️ 本脚本**只读文本、不执行 bash**（沙箱里 bash 起不来，见 skill §8.31）——
 * 它保证的是"清单一致"，不是"那条命令真能跑通"。后者由各入口自己保证。
 */

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const CI = '.github/workflows/ci.yml'
const SH = 'scripts/verify.sh'
const JS = 'scripts/verify-local.js'

/** 只认识这些"门禁类"脚本（其它 node/python 调用是工具，不是门禁）。 */
const GATE_PATTERN = /(?:^|[\s'"[(\/])((?:scripts\/)?(?:check-|prod-startup|smoke-check|backup-restore-drill)[\w.-]*\.js|(?:audit_|page_reach_|static_audit_)[\w.-]*\.py|tests\/js\/run-all\.js|scan-secrets\.sh|provision-test-db\.sh|backup-restore-drill\.js)/g

/**
 * **有意的不对称**（每条都要有理由；没理由的就不该在这儿）。
 * 判据：这处**为什么**跑不了 / 不该跑那道门禁。
 * ⚠️ 只登记**当前真实存在**的不对称 —— 修好一条就删一条，别留着当装饰
 * （留着会让下一个人以为"这里本来就该缺"，而那正是本门禁要防的）。
 */
const KNOWN_ASYMMETRY = [
  ['backup-restore-drill.js', '会建/删演练库（写库动作），三处都**不自动跑**，只在文档/提示里要求"上线前单独跑"'],
  ['smoke-check.js', '需要**服务已经在跑**：CI 里没有常驻服务，故只有 verify.sh / verify-local 跑（且先探端口）'],
  ['provision-test-db.sh', 'CI 与 verify.sh 共用此准备脚本；verify-local 不操作数据库，因此不运行测试库准备'],
  ['audit_scenario_matrix.py', '**依赖 `build/test-results/test/*.xml`**（要先把集成测试跑完）：verify-local 不跑 Gradle，故只有 CI 与 verify.sh 跑'],
  ['scan-secrets.sh', '**是 bash**，本机受限沙箱起不来（skill §8.31）⇒ verify-local 用**等价实现**在本文件内复刻同一条正则（见 checkSecretsEquivalent），脚本本身仍只在 CI 与 verify.sh 跑']
]

function read(rel) {
  const p = path.join(ROOT, rel)
  if (!fs.existsSync(p)) {
    console.error(`[gate-parity] ✗ 找不到 ${rel}`)
    process.exit(1)
  }
  return fs.readFileSync(p, 'utf8')
}

/**
 * 剥掉注释与"提示行"，只留**真的会执行**的那些行。
 *
 * ⚠️ 为什么必须剥（2026-09-28）：本仓早有一条教训 —— `static_audit_user.py` 起初把
 * 注释里的示例 `require('../../behaviors/stationNavbar')` 当成真实调用（该文件自己的注释里
 * 就是这么写的）。同一形状在这里会**把"提示里提了一句"误判成"这道门禁跑了"**，
 * 于是真正漏接的入口永远不会被这条检查发现（**假绿**）。
 *   · `.js`：整行 `//` 注释与「斜杠星号」块注释（⚠️ 本行**不能**把那一对符号连写出来 ——
 *     写在块注释里会把注释提前闭合，编译期直接报语法错；同 `OrderMapper.java` 里记过的那条坑）；
 *   · `.sh` / `.yml`：整行 `#` 注释，以及 `echo` 开头的**提示行**（那是给人看的，不是调用）。
 */
function stripNonExecutable(text, kind) {
  let t = text.replace(/\/\*[\s\S]*?\*\//g, '')                  // 块注释
  t = t.split('\n').filter(line => {
    const s = line.trim()
    if (s.startsWith('//') || s.startsWith('#')) return false     // 整行注释
    if (kind !== 'js' && /^echo\b/.test(s)) return false          // shell/yaml 的提示行
    return true
  }).join('\n')
  return t
}

function gatesIn(text, kind) {
  const found = new Set()
  const exec = stripNonExecutable(text, kind)
  let m
  GATE_PATTERN.lastIndex = 0
  while ((m = GATE_PATTERN.exec(exec)) !== null) {
    found.add(path.basename(m[1]))
  }
  return found
}

const ciText = read(CI)
const shText = read(SH)
const jsText = read(JS)

/*
 * ⚠️ CI 的 `run: |` 块里调用的是 `python3 audit_x.py` / `node scripts/x.js`，与 verify.sh 同形；
 * 而 verify-local.js 是**用数组常量**传参（`gate(..., PY, ['audit_x.py'])`），文件名在字符串里 ——
 * 上面的正则两种都能抓到（`'audit_x.py'` 前面是引号，也在字符类里）。
 */
const sets = {
  'ci.yml': gatesIn(ciText, 'yaml'),
  'verify.sh': gatesIn(shText, 'sh'),
  'verify-local.js': gatesIn(jsText, 'js')
}

const all = new Set([...sets['ci.yml'], ...sets['verify.sh'], ...sets['verify-local.js']])
const allowed = new Map(KNOWN_ASYMMETRY.map(([f, why]) => [f, why]))

console.log('[gate-parity] 三个入口各自跑哪些门禁（√ = 跑了）')
console.log('')
const rows = [...all].sort()
const w = Math.max(...rows.map(r => r.length), 20) + 2
console.log(`${'门禁'.padEnd(w)}ci.yml  verify.sh  verify-local.js`)
console.log('-'.repeat(w + 30))
const problems = []
for (const g of rows) {
  const inCi = sets['ci.yml'].has(g)
  const inSh = sets['verify.sh'].has(g)
  const inJs = sets['verify-local.js'].has(g)
  const cells = [inCi, inSh, inJs].map(v => (v ? '  √    ' : '  ·    ')).join(' ')
  const missingFrom = [
    inCi ? null : 'ci.yml', inSh ? null : 'verify.sh', inJs ? null : 'verify-local.js'
  ].filter(Boolean)
  const explained = allowed.has(g)
  const mark = missingFrom.length === 0 ? ' ' : (explained ? '~' : '✗')
  console.log(`${mark} ${g.padEnd(w - 2)}${cells}${missingFrom.length && !explained ? '  ← 缺：' + missingFrom.join('、') : ''}`)
  if (missingFrom.length && !explained) {
    problems.push(`${g} 只在部分入口里：缺 ${missingFrom.join('、')}`)
  }
}

console.log('')
console.log('图例：√ 有 / · 无 / ~ 有意不对称（见下）/ ✗ 未登记的不对称')
console.log('')
console.log('有意不对称（每条都已登记理由）：')
for (const [f, why] of KNOWN_ASYMMETRY) {
  console.log(`  · ${f}`)
  console.log(`      ${why}`)
}

console.log('')
console.log('-'.repeat(78))
const missingFiles = [...all].filter(g => !g.endsWith('.sh') && !fs.existsSync(path.join(ROOT, g))
  && !fs.existsSync(path.join(ROOT, 'scripts', g)) && !fs.existsSync(path.join(ROOT, 'tests/js', g)))
if (missingFiles.length) {
  for (const f of missingFiles) problems.push(`入口点名了不存在（也搜不到）的脚本：${f}`)
}
if (problems.length) {
  console.log(`[gate-parity] ✗ ${problems.length} 处清单不一致：`)
  for (const p of problems) console.log(`    - ${p}`)
  console.log('  ⇒ 判据：新加门禁**必须同时接进三处**（或在这里登记理由）—— 否则总有一个入口不会告诉你它漏了。')
  process.exit(1)
}
console.log(`[gate-parity] ✓ ${rows.length} 道门禁在三处入口的清单一致（不对称项均已登记理由）`)
console.log('AQUAFLOW_GATE_PARITY_OK')
