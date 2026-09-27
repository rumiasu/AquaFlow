/**
 * 静态扫描：两端小程序所有 `wx.showModal` 的按钮文案必须 ≤ 4 个字符。
 *
 * 跑法：node tests/js/modal-copy-limit.test.js
 *
 * 为什么值得单开一个套件：这个限制**只在真机上才发作，而且不报错**——
 * 微信 `wx.showModal` 的 `confirmText` / `cancelText` 最多 4 个字符，超了既不弹窗也不报错。
 * 2026-09-26 真机反馈"点存为草稿毫无反应"，根因就是把它写成了 5 个字的「下架为草稿」。
 * 流程测试只能覆盖被测试驱动的页面（下单 / 送达 / 公告），这条扫描覆盖**全部页面**。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..', '..')

// 完成哨兵：**只用 ASCII**。受限沙箱下子进程往文件描述符写中文会被编码毁成 `?`，
// 于是中文完成标记匹配不到、正常套件被误判成"未跑完"（2026-09-27 实测）。
const MARK_ASCII = 'AQUAFLOW_SUITE_OK'
const MINIAPPS = ['miniapp-user', 'miniapp-delivery']
const LIMIT = 4

function walk(dir, out) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) {
      if (name === 'node_modules' || name.startsWith('.')) continue
      walk(full, out)
    } else if (name.endsWith('.js')) {
      out.push(full)
    }
  }
  return out
}

const violations = []
let scanned = 0
let viaParam = 0
for (const app of MINIAPPS) {
  const dir = path.join(ROOT, app)
  if (!fs.existsSync(dir)) continue
  for (const file of walk(dir, [])) {
    scanned++
    const text = fs.readFileSync(file, 'utf8')

    // ① 直接写在 showModal 选项里的字面量
    text.split('\n').forEach((line, i) => {
      const re = /(confirmText|cancelText):\s*'([^']*)'/g
      let m
      while ((m = re.exec(line)) !== null) {
        const value = m[2]
        if ([...value].length > LIMIT) {
          violations.push(`${path.relative(ROOT, file)}:${i + 1}  ${m[1]}='${value}'（${[...value].length} 字）`)
        }
      }
    })

    // ② **当参数传进 helper 的**字面量 —— 这是 2026-09-27 实测事故的形态：
    //    `_confirmRisk(orderId, '已确认，分配')`（6 字）由 helper 塞进 confirmText，
    //    只扫 `confirmText:` 的字面量**扫不到**，于是门禁全绿而真机点不动（点配送员毫无反应）。
    //
    //    判据是**数据流**：`confirmText: <值>` 里的 <值> 若是局部变量，就看它由谁赋值 ——
    //    · 由某个形参赋值（可能经 `||` 兜底）⇒ 该 helper 的这个形参最终进了按钮文案，
    //      于是去查**所有调用点**那个位置的字符串字面量；
    //    · 赋值处**做了截断**（`.slice(0, N)` / `.substring(0, N)` / `.slice(0, MAX_*)`）
    //      ⇒ 已经有护栏，不再判违规（这正是本次修复的形态，别把它扫成问题）。
    //
    //    ⚠️ 只认**简单标识符形参**，并用大括号配平取函数体：用 `[^)]*` 抓形参表 +
    //    `indexOf('\n  }')` 抓函数体都会在解构形参（`{ x }`）或模板串上翻车 ——
    //    实测会拼出非法正则 `\bonLaunch(\b` 让整个门禁崩掉。
    const ESC = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const helperParams = new Map() // 函数名 -> { pos, param }
    const fnRe = /(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{|(?:^|\n)\s{2}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{/g
    let f
    while ((f = fnRe.exec(text)) !== null) {
      const name = f[1] || f[3]
      const rawParams = (f[2] !== undefined ? f[2] : f[4]) || ''
      if (!name || !rawParams) continue
      const params = rawParams.split(',').map((p) => p.trim())
      if (!params.every((p) => /^[A-Za-z_$][\w$]*$/.test(p))) continue

      // 大括号配平取函数体
      let depth = 0
      let end = -1
      for (let i = f.index + f[0].length - 1; i < text.length; i++) {
        const ch = text[i]
        if (ch === '{') depth++
        else if (ch === '}') {
          depth--
          if (depth === 0) { end = i; break }
        }
      }
      if (end < 0) continue
      const body = text.slice(f.index, end)

      // confirmText / cancelText 的值表达式
      const copyRe = /(?:confirmText|cancelText)\s*:\s*([^,\n]+)/g
      let c
      while ((c = copyRe.exec(body)) !== null) {
        const expr = c[1].trim()
        // 形参直接当值（含 `param || '默认'`）
        const direct = params.find((p) => new RegExp('\\b' + ESC(p) + '\\b').test(expr))
        if (direct) {
          if (!helperParams.has(name)) helperParams.set(name, { pos: params.indexOf(direct), param: direct })
          continue
        }
        // 局部变量当值：回查它的赋值来源是不是某个形参
        const localVar = /^([A-Za-z_$][\w$]*)$/.exec(expr)
        if (!localVar) continue
        const v = localVar[1]
        const assignRe = new RegExp('(?:let|var|const)\\s+' + ESC(v) + '\\s*=\\s*([^\\n]+)')
        const asg = assignRe.exec(body)
        if (!asg) continue
        const rhs = asg[1]
        // 有截断护栏 ⇒ 不判（文案已被压到上限内）
        if (/\.(slice|substring|substr)\s*\(\s*0\s*,/.test(rhs)) continue
        const fromParam = params.find((p) => new RegExp('\\b' + ESC(p) + '\\b').test(rhs))
        if (fromParam && !helperParams.has(name)) {
          helperParams.set(name, { pos: params.indexOf(fromParam), param: fromParam })
        }
      }
    }
    for (const [fn, info] of helperParams) {
      const callRe = new RegExp(ESC(fn) + '\\s*\\(([^)]*)\\)', 'g')
      let c
      while ((c = callRe.exec(text)) !== null) {
        // 跳过函数声明处自身
        if (new RegExp('(function\\s+' + ESC(fn) + '|\\n\\s{2}(?:async\\s+)?' + ESC(fn) + '\\s*\\()').test(c[0])) continue
        const args = c[1].split(',').map((s) => s.trim())
        if (args.length <= info.pos) continue
        const lit = /^'([^']*)'$/.exec(args[info.pos])
        if (!lit) continue
        viaParam++
        const value = lit[1]
        if ([...value].length > LIMIT) {
          const lineNo = text.slice(0, c.index).split('\n').length
          violations.push(
            `${path.relative(ROOT, file)}:${lineNo}  ${fn}() 第 ${info.pos + 1} 个实参 '${value}'`
            + `（${[...value].length} 字）最终进了 ${info.param} —— 超长时微信既不弹窗也不报错`
          )
        }
      }
    }
  }
}

console.log('showModal 按钮文案长度 · 静态扫描（两端，上限 4 字）')
console.log(`  扫描 js 文件：${scanned} 个；另检查 ${viaParam} 处"经 helper 参数传入"的文案`)
if (violations.length) {
  console.log('  ✗ 发现超长文案（真机上会既不弹窗也不报错）：')
  violations.forEach((v) => console.log('      ' + v))
  console.log('')
  console.log(`失败：${violations.length} 处`)
  process.exitCode = 1
} else {
  console.log('  ✓ 没有超过 4 个字符的 confirmText / cancelText')
  console.log('')
  console.log('全部通过：1 项（静态扫描）')
  console.log(MARK_ASCII + ' 1')
}
