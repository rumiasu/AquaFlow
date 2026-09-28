#!/usr/bin/env node
/**
 * **端点契约对账**：controller 注解里的实路径 ↔ `docs/api/01-REST-API参考.md` 里的登记。
 *
 *   node scripts/check-api-doc.js
 *
 * ## 为什么要有它
 *
 * 本仓的「API 参考」是**手写**的，而端点由注解定义 —— 两者是**同一条事实的两处表述**，
 * 必然会漂（同 `sql/README.md` ↔ `sql/` 那对，那个已有 `check-sql-catalog.js`）。
 * 2026-09-28 实测真漂了 **6 条**：v67 站间结算 4 条 + v66 押金交付 2 条，全是本轮新加的端点，
 * 而文档一个字没动。两个方向的后果不一样：
 *   · **代码有、文档没登记** ⇒ 新人照文档写调用会漏掉这些能力（站间结算这种"钱"的能力尤其不能漏）；
 *   · **文档登记了、代码里没有** ⇒ 照文档调用**必然 404**（`docs/api` 是给运维/接手人看的契约）。
 *
 * ## 判据（双向，零容忍）
 *
 * 两个方向都必须为空。**当前实测 283 ↔ 283、两向皆 0、路径变量差异 0 对** ——
 * 也就是说这道门禁上线时是**零假阳性**的，将来一旦红就是真漂移。
 *
 * ## 三个解析坑（都实测踩过，别改回去）
 *
 * 1. **数组别名写法**：本仓大量用 `@RequestMapping({"/api/stations", "/api/station"})` 做双路径别名。
 *    首版只认 `("…"` 紧跟左括号的形式 ⇒ `StationController` 的类级前缀**整个丢掉**，
 *    于是报出 10 条"代码有文档没" + 77 条"文档有代码没" —— **全是假阳性**（看着像文档烂了）。
 * 2. **裸注解**：`@GetMapping`（不带括号）映射到类根路径，本仓很常用（`OrderController.list`、
 *    `/api/manager/pending-summary` 都是）。首版要求必须带 `(` ⇒ 又报 57 条假阳性。
 * 3. **路径变量名不同不算差异**：文档写 `{id}`、注解里形参叫 `{customerId}` 之类跟"是不是同一个端点"
 *    无关，硬判会满屏噪音 ⇒ 单独归到第 ③ 类，只列不判。
 *
 * ⚠️ 本脚本只看**路径与方法**，不看参数、返回体与权限注解 —— 那三样的正本在 controller 源码里。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const CTRL = path.join(ROOT, 'AquaFlow-backend', 'src', 'main', 'java', 'com', 'example', 'aquaflow', 'controller')
const DOC = path.join(ROOT, 'docs', 'api', '01-REST-API参考.md')
const VERB = { GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT', DeleteMapping: 'DELETE', PatchMapping: 'PATCH' }

/** 取注解里的**全部**路径字面量（数组别名写法要展开成多条）。 */
const pathsOf = body => (body ? [...body.matchAll(/"([^"]*)"/g)].map(m => m[1]) : [])

function classPrefixes(text) {
  const m = /@RequestMapping\(\s*(?:value\s*=\s*)?(\{[^}]*\}|"[^"]*")/.exec(text)
  return m ? pathsOf(m[1]) : ['']
}

const code = new Map()
for (const f of fs.readdirSync(CTRL).filter(n => n.endsWith('.java'))) {
  const text = fs.readFileSync(path.join(CTRL, f), 'utf8')
  const prefixes = classPrefixes(text)
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const m = /@(Get|Post|Put|Delete|Patch)Mapping\b\s*(\(([^)]*)\))?/.exec(lines[i])
    if (!m) continue
    const subs = m[3] === undefined ? [''] : pathsOf(m[3])
    let name = '?'
    for (let j = i; j < Math.min(lines.length, i + 8); j++) {
      const dm = /\bpublic\s+[\w<>,\s\[\].?]+\s+(\w+)\s*\(/.exec(lines[j])
      if (dm) { name = dm[1]; break }
    }
    for (const pre of prefixes) {
      for (const sub of subs) {
        let p = (pre + sub).replace(/\/{2,}/g, '/').replace(/\/$/, '')
        if (!p) p = pre || '/'
        code.set(`${VERB[m[1] + 'Mapping']} ${p}`, `${f}#${name}`)
      }
    }
  }
}

const doc = new Map()
const docLines = fs.readFileSync(DOC, 'utf8').split(/\r?\n/)
for (let i = 0; i < docLines.length; i++) {
  const m = /\|\s*`(GET|POST|PUT|DELETE|PATCH)`\s*\|\s*(.+?)\s*\|/.exec(docLines[i])
  if (!m) continue
  for (const raw of m[2].split('／')) {
    const p = raw.replace(/`/g, '').trim()
    if (p.startsWith('/')) doc.set(`${m[1]} ${p.replace(/\/$/, '')}`, i + 1)
  }
}

const core = p => p.replace(/\{[^}]*\}/g, '{}')
const shape = k => k.split(' ')[0] + ' ' + core(k.split(' ')[1])
const missingInDoc = [...code.keys()].filter(k => !doc.has(k) && !doc.has(shape(k)))
const missingInCode = [...doc.keys()].filter(k => !code.has(k) && !code.has(shape(k)))
const shapeOnly = [...code.keys()].filter(k => !doc.has(k) && doc.has(shape(k)))

console.log(`[api-doc] controller 端点 ${code.size} 条 / 文档登记 ${doc.size} 条`)
console.log(`  ① 代码有、文档没登记：${missingInDoc.length} 条`)
for (const k of missingInDoc) console.log(`     - ${k}    ← ${code.get(k)}`)
console.log(`  ② 文档登记了、代码里没有：${missingInCode.length} 条`)
for (const k of missingInCode) console.log(`     - ${k}    ← 文档第 ${doc.get(k)} 行`)
console.log(`  ③ 仅路径变量名不同（不判差异）：${shapeOnly.length} 对`)

const problems = []
if (missingInDoc.length) problems.push(`${missingInDoc.length} 条端点没进 API 参考（照文档写调用会漏掉这些能力）`)
if (missingInCode.length) problems.push(`${missingInCode.length} 条文档登记在代码里不存在（照文档调用必然 404）`)

console.log('')
console.log('-'.repeat(78))
if (problems.length) {
  console.log('[api-doc] ✗ 端点契约与文档不一致：')
  for (const p of problems) console.log(`    - ${p}`)
  console.log('  ⇒ 判据：`docs/api/01-REST-API参考.md` 是给运维/接手人的契约，')
  console.log('     新增或删除端点时**必须当场同步它**；确实不该登记的（内部/已删）要写进 §6。')
  process.exit(1)
}
console.log(`[api-doc] ✓ ${code.size} 条端点与 API 参考双向一致（${shapeOnly.length} 对仅变量名不同）`)
console.log('AQUAFLOW_API_DOC_OK ' + code.size)
