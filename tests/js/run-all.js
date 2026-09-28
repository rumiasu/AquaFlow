/**
 * 小程序「流程测试」总跑（契约要求："实际执行页面处理函数并模拟 API/微信回调，
 * 断言请求次数、键、跳转和状态"，而不是 grep 某个字符串）。
 *
 * 跑法：node tests/js/run-all.js
 *
 * 为什么不用 npm 框架：本仓没有 node_modules、两端小程序也没有构建步骤，
 * 引框架就要连带引入依赖与打包配置。这里用 Node 内置能力做最小骨架（见 harness.js）。
 */

const { spawnSync } = require('child_process')
const path = require('path')
const fs = require('fs')
const os = require('os')

const SUITES = [
  { file: 'order-create-flow.test.js', name: '客户下单闭环（工作包 B）' },
  { file: 'delivery-complete-flow.test.js', name: '配送送达页（工作包 C）' },
  { file: 'delivery-detail-return-row.test.js', name: '订单详情页回桶行（首单不给默认回桶值）' },
  { file: 'ticket-purchase-flow.test.js', name: '水票购买（自助预付，不需要水站同意）' },
  { file: 'notice-draft-flow.test.js', name: '公告发布/草稿（站长端）' },
  { file: 'coordination-assign-flow.test.js', name: '待分配→分配配送员（站长端，2026-09-27 真机事故回归）' },
  { file: 'barrel-preview-station.test.js', name: '退桶试算的服务水站上下文（2026-09-27 静默失败回归）' },
  { file: 'barrel-return-refund.test.js', name: '退桶审批 · 退押金并当面交付（v66 第 3 步语义收窄）' },
  { file: 'inter-station-ledger.test.js', name: '站间结算台账（v67：谁欠谁 / 欠多少 / 什么时候算办完）' },
  { file: 'modal-copy-limit.test.js', name: '弹窗按钮文案长度（两端静态扫描）' },
  // [2026-09-27] 两端运行时体验走查（docs/audit/2026-09-27-GPT-两端运行时体验走查.md）的修复回归：
  { file: 'customer-assets-state.test.js', name: '顾客资产加载态与文案（走查 C05 / C06 / C07）' },
  { file: 'customer-order-receivables-truth.test.js', name: '订单事实与应收失败态（体验复核 N02/N03/N09）' },
  { file: 'delivery-runtime-copy.test.js', name: '员工端运行时事实表达（走查 M01–M06 / D01–D07）' },
  { file: 'entry-landing-consistency.test.js', name: '入口 → 落地视图一致性 + 文案禁开发词（走查 M04 / M05 / M06）' }
]

// 每个套件跑到底都会打印两行收尾：中文的「全部通过：N 项…」给人看，
// 以及**纯 ASCII 的 `AQUAFLOW_SUITE_OK N` 给本脚本判**。它是"跑完了"的唯一证据，
// 不能只看退出码：卡死的用例会让 Node 事件循环空转后静默退出、退出码仍是 0。
//
// ⚠️ 为什么不直接匹配中文标记：受限沙箱走文件重定向那条路时，子进程写进去的中文会被
// 编码毁成 `?`（实测变成 `?????3 ???????`），于是**正常套件被误判成"未跑完"**。
// ASCII 不受代码页影响 —— 判据必须建在不会被环境改写的东西上。
const DONE_MARK = 'AQUAFLOW_SUITE_OK'
// ⚠️ 不要给这个正则加 `g`：带 g 的正则对象会在多次 .match 之间残留 lastIndex，
// 六个套件循环里就会出现"某一个套件的完成标记莫名匹配不到"（静默、且看输出完全正常）。
const DONE_RE = new RegExp(DONE_MARK + '\\s+(\\d+)')

/**
 * 跑一个套件，返回 { status, errorCode, ran, output, count, via }。
 *
 * `via` 是**这次子进程的输出是怎么拿到的**，两种形态都必须保住"能判卡死、能数件数"：
 *   · 'pipe' —— 正常路径（CI 走这条）：管道抓输出。
 *   · 'file' —— **受限沙箱**（本机 DSH 的 workspace-write 模式）下进程无法创建 named pipe，
 *     `spawnSync` 直接失败并回 `status=null` + `error.code='EPERM'`，**子进程根本没跑**
 *     （2026-09-27 实测：六个套件全报"未通过"，而测试一条都没执行 —— 属环境限制，非仓库缺陷；
 *     CI 在 Linux 上不受影响）。此时**改用文件描述符重定向**：沙箱拦的是管道，文件照写。
 *     早期版本用的是 `stdio:'inherit'`，它能让测试跑起来、也保住退出码，但输出只落终端、
 *     拿不到字符串 ⇒ `DONE_MARK` 判据失效（"打印一半就静默退出"再也抓不到）。别改回去。
 *     ⚠️ 文件路径**没有超时兜底**（原因见下面那段的注释），所以"卡死"只有 CI/管道路径能自动抓住。
 */
function runSuite(full) {
  const r = spawnSync(process.execPath, [full], { encoding: 'utf8', timeout: 180000 })
  const errorCode = r.error && r.error.code

  if (errorCode !== 'EPERM') {
    const output = (r.stdout || '') + (r.stderr || '')
    if (r.stdout) process.stdout.write(r.stdout)
    if (r.stderr) process.stderr.write(r.stderr)
    const m = output.match(DONE_RE)
    return {
      status: r.status,
      errorCode,
      output,
      count: m ? parseInt(m[1], 10) : 0,
      via: 'pipe'
    }
  }

  // —— 沙箱降级：输出重定向到文件，再读回来（与 pipe 路径同等的判据）——
  const log = path.join(
    os.tmpdir(),
    'aquaflow-run-all-' + process.pid + '-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.log'
  )
  let fd = null
  let output = ''
  let status = null
  let liveError = null
  let logError = null
  try {
    fd = fs.openSync(log, 'w')
  } catch (e) {
    // 连日志文件都建不出来：**不能把"没跑成"说成"套件失败"**（那是把环境限制伪装成缺陷）
    logError = 'LOG_OPEN_FAILED:' + (e && e.code)
  }
  if (fd !== null) {
    try {
      // ⚠️ 这条**故意不给 `timeout`**：Windows 上被杀的子进程仍握着这个文件句柄，
      // 一旦触发超时，stdio 会一直等到句柄关闭才返回、且留下半截输出 ——
      // 2026-09-27 实测：给了 timeout 反而把一个正常套件（有完成标记、退出码 0）误判成"未跑完"。
      // 代价是**本机沙箱下"卡死"不会被自动杀掉**（真正的超时判据由上面的 pipe 路径负责，CI 走那条）。
      const live = spawnSync(process.execPath, [full], { stdio: ['ignore', fd, fd] })
      status = live.status
      liveError = live.error && live.error.code
    } catch (e) {
      liveError = 'REDIRECT_FAILED:' + (e && e.code)
    } finally {
      try { fs.closeSync(fd) } catch (e) { /* 已关就忽略 */ }
    }
    // 读回来并立刻删掉：本机不留临时文件，也不让它们有机会被当成测试夹具
    try {
      output = fs.readFileSync(log, 'utf8')
    } catch (e) {
      if (!logError) logError = 'LOG_READ_FAILED:' + (e && e.code)
    }
    try { fs.unlinkSync(log) } catch (e) { /* 没建成就算了 */ }
  }

  if (output) process.stdout.write(output)
  const m = output.match(DONE_RE)
  return {
    status,
    errorCode: liveError,
    logError,
    output,
    count: m ? parseInt(m[1], 10) : 0,
    via: 'file'
  }
}

let failed = 0
let total = 0

SUITES.forEach((s) => {
  const full = path.join(__dirname, s.file)
  console.log('===== ' + s.name + ' :: ' + s.file + ' =====')
  const r = runSuite(full)
  const pass = r.status === 0 && r.count > 0

  if (r.errorCode === 'ETIMEDOUT') {
    // 用例 await 的 Promise 若永远不 resolve，外部的 timeout 是这里唯一的防线。
    console.log('套件超时未结束（有用例卡住没返回）：判为失败')
  } else if (r.logError || r.errorCode) {
    // **"没跑成"与"跑失败"必须分开报**：把环境限制说成"套件未通过"，下一个人会去改测试，
    // 而真正的问题是沙箱连输出文件都不给写（2026-09-27 实测踩过：误判成"6 个套件全红"）。
    console.log('套件未能运行（' + (r.logError || r.errorCode) + '）：本机沙箱拒绝管道与文件重定向，'
      + '本次拿不到任何结论 —— 判为失败，但**不要**当成"测试有问题"；请到 CI 或换非受限终端复跑')
  } else if (r.status === 0 && r.count === 0) {
    // 退出码 0 但没打印完成标记 = 中途静默退出（有用例卡住 / 进程被提前结束）。
    // 只判退出码会把这种"没跑完"当通过。
    console.log('套件未跑完：退出码 0 但没有输出「' + DONE_MARK + '」完成标记，判为失败')
    if (!r.output) console.log('（该套件这次也没留下任何输出，请复核上面的路径与权限）')
  } else if (r.status !== 0 && r.via === 'file' && !/全部通过|✗|FAIL/.test(r.output)) {
    // 文件路径下被杀掉的子进程可能只留下半截输出。**如实说明"这个结论不完整"**，
    // 别让人把截断的输出当成"这条断言真的失败了"（同 §8「被信任的文本比没有文本更危险」）。
    console.log('（注意：本机沙箱路径的输出可能不完整，以上未必是全貌 —— 建议用 `node tests/js/run-all.js | Tee-Object -FilePath x.log` 复核）')
  } else if (r.status !== 0) {
    // 用例失败时子进程退出码非 0：输出已在上面照原样打出来（便于定位是哪一条）
  }

  if (pass) {
    total += r.count
  } else {
    failed++
  }
})

console.log('')
if (failed) {
  console.log('流程测试失败：' + failed + ' 个套件未通过')
  process.exitCode = 1
} else {
  console.log('流程测试全部通过：' + SUITES.length + ' 个套件，共 ' + total + ' 项断言')
}

// 收尾清一次历史遗留：每次跑完都会删自己的日志，但被强杀（Ctrl+C / 超时）的那次会留下空文件。
// 只删一天前的，避免误删另一个正在并行跑的实例的日志。
try {
  const keepAfter = Date.now() - 24 * 60 * 60 * 1000
  for (const name of fs.readdirSync(os.tmpdir())) {
    if (!name.startsWith('aquaflow-run-all-')) continue
    const full = path.join(os.tmpdir(), name)
    try {
      if (fs.statSync(full).mtimeMs < keepAfter) fs.unlinkSync(full)
    } catch (e) { /* 删不掉就算了，不影响结论 */ }
  }
} catch (e) { /* tmpdir 不可读也不影响结论 */ }
