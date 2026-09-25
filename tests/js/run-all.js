/**
 * 小程序「流程测试」总跑（契约要求："实际执行页面处理函数并模拟 API/微信回调，
 * 断言请求次数、键、跳转和状态"，而不是 grep 某个字符串）。
 *
 * 跑法：node tests/js/run-all.js
 *
 * 为什么不用 npm 框架：本仓没有 node_modules、两端小程序也没有构建步骤，
 * 引框架就要连带引入依赖与打包配置。这里用 Node 内置能力做最小骨架（见 harness.js）。
 */

const { execFileSync } = require('child_process')
const path = require('path')

const SUITES = [
  { file: 'order-create-flow.test.js', name: '客户下单闭环（工作包 B）' },
  { file: 'delivery-complete-flow.test.js', name: '配送送达页（工作包 C）' }
]

let failed = 0
SUITES.forEach((s) => {
  const full = path.join(__dirname, s.file)
  console.log('===== ' + s.name + ' :: ' + s.file + ' =====')
  try {
    const out = execFileSync(process.execPath, [full], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    process.stdout.write(out)
  } catch (e) {
    // 用例失败时子进程退出码非 0：把它的输出照原样打出来（便于定位是哪一条）
    if (e.stdout) process.stdout.write(String(e.stdout))
    if (e.stderr) process.stderr.write(String(e.stderr))
    failed++
  }
})

if (failed) {
  console.log('流程测试失败：' + failed + ' 个套件未通过')
  process.exitCode = 1
} else {
  console.log('流程测试全部通过：' + SUITES.length + ' 个套件')
}
