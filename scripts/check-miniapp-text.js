#!/usr/bin/env node
/**
 * 小程序「文本体检」门禁：① **UTF-8 无 BOM**；② `<text>` 内不出现开发词。
 *
 *   node scripts/check-miniapp-text.js
 * 退出码：0 = 通过；1 = 有 BOM/编码损坏、或 `<text>` 里出现开发词；3 = 环境不允许（两端小程序目录都不在）
 *
 * ## 为什么要有它 —— 两条**没有任何机器门禁**的规则，合成本脚本
 *
 * ① **BOM**（skill §8.28）：`Set-Content -Encoding UTF8` 写的是 **BOM + CRLF**，仓库其余文件是 LF 无 BOM。
 *    带 BOM 的 `.wxss` 会让开发者工具报「编译 .wxss 文件错误」且**不指名文件**，而当时的四个门禁
 *    与 `page_reach_audit` **全绿** —— 排查成本全落在人身上。
 *    ⚠️ 本脚本**取代 `scripts/debug/__check-no-bom.js` 那个没有入口的孤儿脚本**：它被硬编码成 16 个文件的
 *    一次性清单（新文件永远不会被它看到），且 `check-gate-parity.js` 的 `GATE_PATTERN` 认不出 `__` 前缀
 *    （= 它没有任何入口调用）。旧脚本**有意保留不删**（留给人决定），但**不要**再往它的清单里加文件。
 *
 * ② **开发词禁令**（AGENTS §6，2026-09-24 立规）：面向站长/顾客的文案里不许出现
 *    `接口 / 后端 / 前端 / 服务端 / 落库 / 端点 / 字段 / 部署 / 重新构建`。
 *    典型历史事故：客户画像页的排障提示原文是「后端未部署画像接口，请重新构建并启动后端」——
 *    写给开发看的话，渲染给了站长（`miniapp-delivery/pages/station-mgmt/customers/detail/index.wxml` 的注释里记着这次事故）。
 *    ⚠️ **注释里随便写** —— 本仓 `.wxml` 注释里大量出现这些词（解释口径、记录事故），
 *    所以本脚本**只查会渲染给用户的文本**，先整体剥掉 `<!-- ... -->` 再匹配（误报会让门禁立刻不可用）。
 *
 * ## 判据（诚实写清边界：这是**保守规则**，宁可漏检、不要误报）
 *
 * BOM：按 `.js/.wxml/.wxss/.json` **扩展名全仓遍历**两端小程序目录，逐个读**字节**看前三字节是不是 `EF BB BF`；
 *      同时报 U+FFFD（替换字符 = 编码已损坏）。CRLF 只**打印**不判红（`.gitattributes` 已把 blob 钉成 LF，
 *      工作区里出现 CRLF 是检出配置问题，不是"内容写坏了"——旧脚本也不判它，这里不擅自扩大红线）。
 *
 * 开发词，三个形状（都在**剥掉注释之后**匹配）：
 *   ① 单行 `<text …>文字</text>`（主规则，覆盖绝大多数实际渲染的文案）；
 *   ② `<text …>{{ … }}</text>` 插值里的**中文字面量**（`{{a ? '接口坏了' : '重新构建'}}` 这种）；
 *   ③ 别的元素属性内联插值 `…="{{ … '开发词' … }}"`（历史事故的形态：
 *      文案是在 `data-*` / `text=` 属性里直接写的字面量）。
 *
 * ⚠️ **已知漏检（写在这里是为了下一个人别把它当全覆盖）**：
 *   · **跨行 `<text>`**：`<text>` 开标签与文字不在同一行时，规则 ① 看不见
 *     （实测本仓存在这种形态：如 `miniapp-delivery/pages/station-mgmt/payroll/index.wxml` 的 `<text class="row-sub">` 换行接文字）；
 *   · **动态拼接的文案**：文字在 `.js` 里拼好、经 `{{jsField}}` 渲染（本仓推荐写法），静态扫描**看不到**
 *     —— 它只能被流程测试或走查覆盖，不是本门禁能保证的；
 *   · **只匹配字面量**：把开发词拆成两段拼（`'接' + '口'`）或写成变量名（`devFields`）都不会命中 —— 有意的，
 *     因为"变量名里含 `字段`/`接口`"在两端 js/wxml 里是正常代码（大写驼峰 `devFields` 也不该报）。
 *
 * ## 为什么它必须接进**三处**入口
 *
 * 本仓 `scripts/check-gate-parity.js` 强制 `ci.yml` / `verify.sh` / `verify-local.js` **三处门禁清单一致**，
 * 新加门禁只接一处时，另一个入口**永远不会告诉你它漏了**（表现为「本机全绿、CI 红」，本地复现不出）。
 */

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
/** 在维护的两个端（`archive/miniapp-station` 是历史留档、已缺 app.js，**不扫**）。 */
const MINIAPPS = ['miniapp-user', 'miniapp-delivery']
const EXTS = ['.js', '.wxml', '.wxss', '.json']
/** 不扫的目录（生成物 / 依赖 / 工具缓存）。 */
const SKIP_DIR = new Set(['node_modules', 'miniprogram_npm', 'dist', 'build', '.git'])
const BOM = [0xef, 0xbb, 0xbf]

/** 开发词表 —— 正本口径是 AGENTS §6（面向站长/顾客的文案禁令），**改口径要同步改这里与 AGENTS**。 */
const DEV_WORDS = ['接口', '后端', '前端', '服务端', '落库', '端点', '字段', '部署', '重新构建']
// ⚠️ **不带 `g` 标志**：下面只用它做 `.test()` 布尔判断。带 `g` 时 `lastIndex` 会在命中后残留，
// 同一个正则被复用时可能"这一次不命中、下一次才命中"（本仓 `run-all.js` 的哨兵匹配踩过同一形状的坑）。
const DEV_RE = new RegExp('(' + DEV_WORDS.join('|') + ')')

/** 剥掉 WXML 注释：注释里的这些词是**解释**，不是渲染给用户的文案（误报会逼人删掉有用的事故记录）。 */
function stripComments(text) {
  return text.replace(/<!--[\s\S]*?-->/g, '')
}

/** 递归收集两类小程序目录下目标扩展名的文件（`.git` / `node_modules` 等一律跳过）。 */
function walk(dir, out) {
  let entries
  try {
    entries = fs.readdirSync(dir)
  } catch (e) {
    return out
  }
  for (const name of entries) {
    if (SKIP_DIR.has(name)) continue
    const full = path.join(dir, name)
    let st
    try {
      st = fs.statSync(full)
    } catch (e) {
      continue
    }
    if (st.isDirectory()) walk(full, out)
    else if (EXTS.includes(path.extname(name).toLowerCase())) out.push(full)
  }
  return out
}

/**
 * 在**一行**里找开发词。
 * 三种形状各自独立，宁可漏检也不误报 —— 见文件头「已知漏检」。
 */
function devHitsInLine(line) {
  const hits = []
  const reText = /<text\b[^>]*>([^<]*)<\/text\s*>/g
  const reInterpInText = /<text\b[^>]*>([\s\S]*?)<\/text\s*>/g
  const reAttrInterp = /=\s*"([^"]*)"/g
  let m
  // ① 单行 <text>文字</text>（含属性）
  while ((m = reText.exec(line)) !== null) {
    if (DEV_RE.test(m[1])) hits.push(m[1])
  }
  // ② <text> 内插值中的中文字面量
  while ((m = reInterpInText.exec(line)) !== null) {
    const inner = m[1]
    const reStr = /'([^']*)'|"([^"]*)"/g
    let s
    while ((s = reStr.exec(inner)) !== null) {
      const lit = s[1] !== undefined ? s[1] : s[2]
      if (DEV_RE.test(lit)) hits.push(lit)
    }
  }
  // ③ 其它元素属性内联插值里的字面量（只认引号内的中文字面量，避免把变量名当违规）
  while ((m = reAttrInterp.exec(line)) !== null) {
    const attrVal = m[1]
    if (!attrVal.includes('{{')) continue
    const reStr = /'([^']*)'|"([^"]*)"/g
    let s
    while ((s = reStr.exec(attrVal)) !== null) {
      const lit = s[1] !== undefined ? s[1] : s[2]
      if (DEV_RE.test(lit)) hits.push(lit)
    }
  }
  return hits
}

// ---------------------------------------------------------------------------
// 扫描
// ---------------------------------------------------------------------------
const missingApps = MINIAPPS.filter(a => !fs.existsSync(path.join(ROOT, a)))
if (missingApps.length === MINIAPPS.length) {
  console.error(`[miniapp-text] 环境不允许：两端小程序目录都不在（找过：${MINIAPPS.join(' / ')}）`)
  process.exit(3)
}

const files = []
for (const app of MINIAPPS) {
  const dir = path.join(ROOT, app)
  if (fs.existsSync(dir)) walk(dir, files)
}

console.log('[miniapp-text] 小程序文本体检（BOM/编码 + `<text>` 开发词）')
console.log(`  扫描范围：${MINIAPPS.join(' + ')} 下的 ${EXTS.join(' ')}（剥离注释后按行匹配）`)
console.log('')

const bomFiles = []
const mojibakeFiles = []
const crlfFiles = []
const devHits = []
let textLines = 0

for (const file of files) {
  const rel = path.relative(ROOT, file)
  const buf = fs.readFileSync(file)
  const hasBom = buf.length >= 3 && buf[0] === BOM[0] && buf[1] === BOM[1] && buf[2] === BOM[2]
  if (hasBom) bomFiles.push(rel)

  const text = buf.toString('utf8')
  if (text.includes('\uFFFD')) mojibakeFiles.push(rel)
  if (/\r\n/.test(text)) crlfFiles.push(rel)

  if (path.extname(file).toLowerCase() !== '.wxml') continue

  // ⚠️ 先剥注释：本仓 .wxml 注释里大量出现这些词（记录事故、解释口径），它们是**解释**不是渲染文案。
  const body = stripComments(text)
  body.split('\n').forEach((line, i) => {
    if (!line.includes('<text') && !line.includes('{{')) return
    textLines++
    for (const hit of devHitsInLine(line)) {
      devHits.push({ rel, line: i + 1, hit, src: line.trim().slice(0, 120) })
    }
  })
}

console.log(`  ① 字节检查：${files.length} 个文件`)
if (bomFiles.length) {
  console.log(`     ✗ UTF-8 BOM（前三字节 EF BB BF）—— 开发者工具会报编译错且不指名文件：`)
  for (const f of bomFiles) console.log(`        ${f}`)
} else {
  console.log('     ✓ 无 BOM')
}
if (mojibakeFiles.length) {
  console.log(`     ✗ 含 U+FFFD 替换字符（编码已损坏）：`)
  for (const f of mojibakeFiles) console.log(`        ${f}`)
}
if (crlfFiles.length) {
  console.log(`     · CRLF 行尾（${crlfFiles.length} 个，**只提示不判红**：.gitattributes 已把 blob 钉成 LF）`)
}

console.log('')
console.log(`  ② 开发词检查：wxml 里含 \`<text>\`/插值的行 ${textLines} 行`)
if (devHits.length) {
  console.log(`     ✗ ${devHits.length} 处面向用户的文案里有开发词：`)
  for (const h of devHits) {
    console.log(`        ${h.rel}:${h.line}  「${h.hit}」`)
    console.log(`            | ${h.src}`)
  }
} else {
  console.log('     ✓ 无（剥掉注释后）')
}

console.log('')
console.log('-'.repeat(78))
const problems = bomFiles.length + mojibakeFiles.length + devHits.length
if (problems) {
  console.log(`[miniapp-text] ✗ ${problems} 处问题：`)
  if (bomFiles.length) console.log(`    - ${bomFiles.length} 个文件带 BOM（用 edit/write 工具重写，别用 Set-Content -Encoding UTF8）`)
  if (mojibakeFiles.length) console.log(`    - ${mojibakeFiles.length} 个文件编码损坏（中文已变替换字符）`)
  if (devHits.length) console.log(`    - ${devHits.length} 处开发词（换成人话，或收进 <help-tip>；注释里随便写）`)
  console.log('  ⇒ 判据来源：AGENTS §6（文案禁令）+ skill §8.28（BOM 编译错）')
  process.exit(1)
}
console.log('[miniapp-text] ✓ 无 BOM、无编码损坏、`<text>` 里无开发词')
console.log(`AQUAFLOW_MINIAPP_TEXT_OK ${files.length} ${textLines}`)
