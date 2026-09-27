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
for (const app of MINIAPPS) {
  const dir = path.join(ROOT, app)
  if (!fs.existsSync(dir)) continue
  for (const file of walk(dir, [])) {
    scanned++
    const text = fs.readFileSync(file, 'utf8')
    const lines = text.split('\n')
    lines.forEach((line, i) => {
      const re = /(confirmText|cancelText):\s*'([^']*)'/g
      let m
      while ((m = re.exec(line)) !== null) {
        const value = m[2]
        if ([...value].length > LIMIT) {
          violations.push(`${path.relative(ROOT, file)}:${i + 1}  ${m[1]}='${value}'（${[...value].length} 字）`)
        }
      }
    })
  }
}

console.log('showModal 按钮文案长度 · 静态扫描（两端，上限 4 字）')
console.log(`  扫描 js 文件：${scanned} 个`)
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
