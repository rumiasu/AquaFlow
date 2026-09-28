#!/usr/bin/env node
/**
 * 「待拍板」标记的**可追溯性**门禁。
 *
 *   node scripts/check-pending-decisions.js
 *
 * ## 为什么要有它（AGENTS §0.6 是强制的）
 *
 * 仓库规矩原文：改动/结论取决于产品拍板时，**不能只在对话里问一句** —— 当场三件事：
 * ① 在**代码或文档的对应位置**留 `TODO(待拍板)`，写清「问什么 / 两种选择的差别 / 拍板后改哪里」；
 * ② 在文档「待拍板」清单登记（**同一问题只保留一处正本**）；③ 回复用户时明确列出。
 * 判据：**只读代码与文档就知道"悬着一个决定、卡在哪、怎么落地"。**
 *
 * 而这三件事**没有一件是机器在盯的** —— 所以实际发生过：一个 `TODO(待拍板)`
 * 孤零零挂在客户端页面里（2026-09-28 实测 `miniapp-user/pages/barrel/index.js` 的
 * `onEcoRuleTap` 上方，问"退桶扣减押金后的钱是站内余额留用还是水站线下返还"），
 * **全仓库任何文档里都没有它** —— 那个决定只存在于一行注释里，下一个人既不知道它悬着、
 * 也不知道去哪拍。（该处已按 2026-09-27 的裁定改写成结论 + 正本指针，见文末「实测记录」。）
 *
 * ## 判据一：**代码**里的每处标记都必须带一处真实存在的 `docs/**` 正本指针
 *
 * 指针三种写法都认（都是本仓真实在用的写法）：
 *   · 全名     `docs/design/31-站间结算算例-水票计价-决策件.md`
 *   · 编号简写 `docs/design/31`（仓库惯用；在 `docs/design/` 下按前缀唯一匹配到文件）
 *   · 同目录   `./31-…md` / `` `31-…md` ``（`docs/design/` 内部互相引用就长这样）
 * 指针解析不到**真实存在且位于 `docs/` 下**的文件 ⇒ 失败（"指向一个被删掉的文档"同样是找不到）。
 *
 * ## 判据二：`.md` **只列不判** —— 这是实测结论，不是偷懒
 *
 * 2026-09-28 全仓首扫，文档侧 12 处命中里能当"未登记的决定"处理的**只有 1 处**，其余是：
 *   · **提及而非使用**：「另记 `TODO(待拍板)`，不能借 UI 改版实施」「这两条…因此没有按 §0.6
 *     登记成 `TODO(待拍板)`」—— 这类句子**必须**写出标记本身才读得懂；
 *   · **登记表表头**：`| 问题 | TODO(待拍板)：选择及差别 | …|`（它下面的行才是条目）；
 *   · **历史审计记录**：`docs/audit/**` 的契约是「只追加、不当规范读」（AGENTS §6.3）。
 * 机械规则分不出"提及/使用"，硬判只会逼人把正文改成读不懂的样子（把门禁变成目的）。
 * ⇒ **代码侧严格判、文档侧全列出来给人过**；真正要防的是"决定只活在代码注释里"。
 *   ⚠️ 这条**保证不了**"文档里那一条写得够不够清楚" —— 那是人的事。
 *
 * ## 两个实测到的精度坑（都踩过，别再踩回去）
 *
 * · **上下文窗口不能宽**：首版取「本行 ±2/+4」共 7 行，结果把**别的行**的 docs 路径当成
 *   这处的正本（实测 `docs/audit/会话考古-2026-09-26/难题候选-原始报告.md` 第 179 行的标记，
 *   匹配到的是第 181 行里的 `docs/AGENTS.md`）⇒ 窗口收成「本行 + 前 1 行 + 后 2 行」。
 * · **大小写敏感**：`Select-String` 之类默认忽略大小写，会把 `loadTodo()` / `decorateTodo(d)`
 *   当成 `TODO(`（首轮实测：12 处"命中"里 6 处是这种）。这里用大小写敏感的正则。
 *
 * ## 扫描范围
 *
 * 只扫源码与文档，跳过 `build/` `out/` `node_modules/` `archive/` `.git/` `backup/`
 * `generated-images/` `.workbuddy/`（本机记忆，未入库）与各类工具缓存目录。
 * ⚠️ **本脚本自身不参与扫描**：它把标记当字面量引用，还要举假路径当反例，扫自己只会自杀。
 */

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const SELF = path.resolve(__filename)
const SKIP_DIR = /[\\/](build|out|node_modules|archive|\.git|\.gradle[^\\/]*|\.dsh-code-index|backup|generated-images|\.workbuddy|__pycache__)[\\/]/

/** 代码侧：严格判（每个标记都必须指向真实正本）。 */
const CODE_EXT = new Set(['.java', '.js', '.mjs', '.cjs', '.wxml', '.wxs', '.json', '.sh', '.py', '.sql', '.yml', '.yaml'])
/** 文档侧：只列不判（理由见文件头「判据二」）。 */
const DOC_EXT = new Set(['.md'])

/** 大小写**敏感**：只认大写 TODO(待拍板)（`loadTodo(` 不是标记）。 */
const MARK = /TODO\(待拍板\)/
/** 指针候选：`docs/…`（全名或编号简写）、markdown 链接、同目录文件名。 */
// ⚠️ 字符类必须**包含中文**并**排除各种包裹符**：首版写成 `docs\/[A-Za-z0-9_\-.]+`，
// 于是 `docs/design/99-这个编号不存在.md` 只截到 `docs/design/99-`（中文文件名是本仓常态），
// 而 `{@code docs/design/31}` 的 `}` 会被吞进 token 里 ⇒ **合法指针解析失败 = 假红**。
// 实测（2026-09-28）两处都踩过，故：中文照收，空白/引号/各种括号一律当边界。
// ⚠️ `*` 与 `…` 也要排除：注释里那句「没有任何 docs/** 正本指针」会被当成一个候选指针，
// 于是本该报"没有指针"的行报成"指针解析不到：docs/**" —— 判据没错，但**文案把人指错方向**。
const CAND_DOCS = /docs\/[^\s`"'()<>[\]{}（）「」『』【】，。、；;：！？*…]+/g
const CAND_LINK = /\]\(([^)\s]+\.md)\)/g
const CAND_NAME = /`([0-9]{2}-[^`\s]+?\.md)`/g

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (SKIP_DIR.test(p + path.sep)) continue
    if (e.isDirectory()) walk(p, out)
    else if (CODE_EXT.has(path.extname(e.name)) || DOC_EXT.has(path.extname(e.name))) out.push(p)
  }
}

const rel = p => path.relative(ROOT, p).replace(/\\/g, '/')
const isDocFile = p => {
  if (!fs.existsSync(p) || !fs.statSync(p).isFile()) return false
  return rel(p).startsWith('docs/')
}

/** 目录里按前缀唯一匹配（`docs/design/31` → `docs/design/31-….md`）。 */
function byPrefix(p) {
  const dir = path.dirname(p)
  const base = path.basename(p)
  if (!base || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return null
  const hits = fs.readdirSync(dir).filter(n => n.startsWith(base))
  const exactMd = hits.filter(n => n === base + '.md')
  if (exactMd.length === 1) return path.join(dir, exactMd[0])
  return hits.length === 1 ? path.join(dir, hits[0]) : null
}

/** token ⇒ 仓库内真实存在的 `docs/**` 文件（相对路径），解析不到回 null。 */
function resolvePtr(token, fromFile) {
  let t = token.replace(/[.。，,;；:：)）`」』\]]+$/, '')
  if (!t) return null
  let cand = null
  if (t.startsWith('./') || t.startsWith('../')) {
    cand = path.resolve(path.dirname(fromFile), t)
  } else if (/^[0-9]{2}-/.test(t)) {
    cand = path.resolve(path.dirname(fromFile), t)   // 同目录裸文件名
  } else if (t.startsWith('docs/')) {
    cand = path.join(ROOT, t)
  } else {
    return null
  }
  for (const c of [cand, cand + '.md']) {
    if (isDocFile(c)) return rel(c)
  }
  const p = byPrefix(cand)
  if (p && isDocFile(p)) return rel(p)
  return null
}

/** 在窗口文本里找第一个能解析成功的指针；顺带回传"看着像指针但解析不到"的候选。 */
function findPtr(ctx, fromFile) {
  const dead = []
  for (const re of [CAND_DOCS, CAND_LINK, CAND_NAME]) {
    re.lastIndex = 0
    let m
    while ((m = re.exec(ctx)) !== null) {
      const token = m[1] || m[0]
      const hit = resolvePtr(token, fromFile)
      if (hit) return { ptr: hit, dead }
      dead.push(token)
    }
  }
  return { ptr: null, dead }
}

// ---------------------------------------------------------------------------

const files = []
walk(ROOT, files)

const code = []   // 代码侧命中（严格）
const docs = []   // 文档侧命中（只列）
for (const f of files) {
  if (path.resolve(f) === SELF) continue
  const isCode = CODE_EXT.has(path.extname(f))
  const text = fs.readFileSync(f, 'utf8')
  const lines = text.split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    if (!MARK.test(lines[i])) continue
    // 窗口 = 本行 + 前 1 行 + 后 2 行（宽窗口会把别的行的路径算成这处的正本，见文件头）
    const ctx = lines.slice(Math.max(0, i - 1), Math.min(lines.length, i + 3)).join('\n')
    const { ptr, dead } = findPtr(ctx, f)
    const row = { file: rel(f), line: i + 1, ptr, dead: ptr ? [] : [...new Set(dead)] }
    ;(isCode ? code : docs).push(row)
  }
}

const problems = []
for (const r of code) {
  if (!r.ptr) {
    problems.push(r.dead.length
      ? `${r.file}:${r.line} 的正本指针解析不到（看着像指针但仓库里没有）：${r.dead.join('、')}`
      : `${r.file}:${r.line} 的 TODO(待拍板) 没有任何 docs/** 正本指针`)
  }
}

console.log(`[pending] 扫描 ${files.length} 个源码/文档文件（跳过构建产物与缓存目录）`)
console.log('')
console.log(`【代码侧 · 严格判】${code.length} 处 —— 每处都必须能顺着指针找到正本`)
if (!code.length) console.log('  （本次没有代码侧的待拍板标记）')
for (const r of code) {
  console.log(`  ${r.ptr ? '✓' : '✗'} ${r.file}:${r.line}`)
  console.log(`      正本 → ${r.ptr || '（缺！）'}`)
}
console.log('')
const docWithPtr = docs.filter(r => r.ptr)
const docWithout = docs.filter(r => !r.ptr)
console.log(`【文档侧 · 只列不判】${docs.length} 处 —— 带正本 ${docWithPtr.length} / 未带 ${docWithout.length}`)
console.log('  （未带指针的多半是"提及标记本身"的句子或历史审计记录；判据二见脚本头部，'
  + '需要人过一遍：它到底是"悬着没登记"还是"只是提到了这个标记"）')
for (const r of docWithout) {
  console.log(`  · ${r.file}:${r.line}${r.dead.length ? `  ← 提到的路径：${r.dead.join('、')}` : ''}`)
}
console.log('')
console.log('-'.repeat(78))
if (problems.length) {
  console.log(`[pending] ✗ ${problems.length} 处代码标记不可追溯：`)
  for (const p of problems) console.log(`    - ${p}`)
  console.log('  ⇒ 判据（AGENTS §0.6）：悬着的决定**必须能在文档里被找到**；')
  console.log('     只挂一行代码注释 = 下一个人既不知道它悬着、也不知道去哪拍。')
  process.exit(1)
}
console.log(`[pending] ✓ 代码侧 ${code.length} 处待拍板标记全部带有效正本指针`
  + `（文档侧 ${docs.length} 处已列出，供人过）`)
console.log('AQUAFLOW_PENDING_TRACEABLE_OK ' + code.length)
