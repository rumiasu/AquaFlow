/**
 * 「退桶试算必须带服务水站」流程测试（2026-09-27）。
 *
 * 跑法：node tests/js/barrel-preview-station.test.js
 *
 * **锁的是什么**：客户在「桶与押金」页改退桶数量时，试算请求必须带上服务水站。
 * 后端 `BarrelController` 对顾客只认 dto（顾客 JWT 里没有水站），漏传恒回
 * `code=1 请先选择服务水站`；而 `api/barrel.js` 的 `previewBarrelReturn` 是
 * 「拿不到站就不传该参数」⇒ 必然失败。
 *
 * **为什么必须有这条测试**：旧实现不判空、失败又被 `catch` 吞成 `preview: null`，
 * 结果顾客看到的是**「押金金额算不出来」**，而真正的原因是「还没选服务水站」——
 * 两件事长得一模一样，是本仓记录过的惯犯形状（"界面说没数据"与"请求根本没发出去"无法区分）。
 * 静态门禁只能查"handler 存不存在"，查不出"handler 里少了一次判断"（AGENTS §8.30 同族）。
 *
 * 覆盖四件事：
 *   ① 本地已选站 ⇒ 试算带上 stationId；
 *   ② 本地没选站、但**上次下单过** ⇒ 走 resolveStationId 的回落拿到站（不该提示"没选站"）；
 *   ③ 完全解析不到站 ⇒ **一个请求都不发**，且必须给顾客可读原因（不能静默）；
 *   ④ 请求失败 ⇒ 原因要显示出来，不许再吞成空白。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

// 完成哨兵：**只用 ASCII**。受限沙箱下子进程往文件描述符写中文会被编码毁成 `?`，
// 于是中文完成标记匹配不到、正常套件被误判成"未跑完"（见 skill §8.31）。
const MARK_ASCII = 'AQUAFLOW_SUITE_OK'

let passed = 0
const failures = []
async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log('  ✓ ' + name)
  } catch (e) {
    failures.push({ name, error: e })
    console.log('  ✗ ' + name + '\n      ' + (e && e.message))
  }
}

/**
 * 组织一次页面加载。
 * @param {object} sc 场景：{ station: 本地存储的站|null, previewThrows: '错误文案' }
 *
 * ⚠️ 关于「上次下单的水站」那一级回落**为什么不在本套件里断言**：
 * `utils/station.js` 在加载期就把 `api/order` 绑进了闭包，而 harness 的模块拦截只在
 * **加载页面那一刻**生效 ⇒ 运行时无法替换它（实测：真函数被调用、stub 计数器恒为 0，
 * 且真函数在 Node 下取不到小程序环境版本、必然回退 null）。
 * 换个说法：**回落算法是 `utils/station.js` 的职责，不是桶页的**；桶页的职责是
 * 「把解析结果原样传给试算」。所以这里只验后者（见第 3 条），不去复制前者的实现。
 */
function newPage(sc) {
  const s = sc || {}
  const calls = { preview: [] }

  const stubs = {
    'api/barrel': {
      getBarrelSummary: async () => ({ code: 0, data: { rightBuckets: 2, actualBuckets: 2 } }),
      getBarrelSummaryByType: async () => ({ code: 0, data: [] }),
      getBarrelRecords: async () => ({ code: 0, data: [] }),
      requestBarrelReturn: async () => ({ code: 0, data: { recordId: 1 } }),
      previewBarrelReturn: async (productId, quantity, stationId) => {
        calls.preview.push({ productId, quantity, stationId })
        if (s.previewThrows) throw new Error(s.previewThrows)
        return { code: 0, data: { refundAmount: 50, blocked: false } }
      }
    }
  }

  const wx = createWx()
  if (s.station) wx.setStorageSync('selectedStation', s.station)
  const page = loadPage('miniapp-user/pages/barrel/index.js', { stubs, wx, app: createApp() })
  // 摆到"已选商品、数量为 1"的状态（真实情况下由打开退桶弹窗填入）
  page.data.returnForm = Object.assign({}, page.data.returnForm, { productId: 3, quantity: 1 })
  return { page, calls, wx }
}

;(async () => {
  const doneWatchdog = armWatchdog()
  console.log('退桶试算 · 服务水站上下文（2026-09-27 修复回归）')

  await test('本地已选水站 ⇒ 试算请求必须带上 stationId', async () => {
    const { page, calls } = newPage({ station: { id: 1, name: '测试水站' } })
    await page.refreshPreview()
    assert.strictEqual(calls.preview.length, 1, '应发出 1 次试算请求')
    assert.strictEqual(calls.preview[0].stationId, 1, '试算必须带 stationId（后端对顾客只认 dto）')
    assert.strictEqual(page.data.previewHint, '', '成功时不该留提示')
    assert.strictEqual(page.data.preview.refundAmount, 50)
  })

  await test('本地已选另一个水站 ⇒ 原样传下去（页面不自己改写水站）', async () => {
    const { page, calls } = newPage({ station: { id: 9, name: '另一个水站' } })
    await page.refreshPreview()
    assert.strictEqual(calls.preview[0].stationId, 9,
      '页面只负责把解析结果传下去；改水站是 utils/station 的回落职责，不是本页的')
  })

  await test('完全解析不到站 ⇒ 一个请求都不发，且必须给顾客可读原因（不许静默）', async () => {
    // 注意：本用例里"解析不到"由"本地没选站"造成 —— resolveStationId 的接口回落
    // 在 Node 下取不到小程序环境版本、必然回退 null（这就是真机上"没选过站"的等价形状）。
    const { page, calls, wx } = newPage({ station: null })
    await page.refreshPreview()
    assert.strictEqual(calls.preview.length, 0, '解析不到站就不该发请求（发了必然失败、顾客白等）')
    assert.strictEqual(page.data.preview, null)
    assert.ok(page.data.previewHint && page.data.previewHint.length > 0,
      '必须留下可读原因 —— 静默失败正是这条修复要消灭的形状')
    assert.ok(/水站/.test(page.data.previewHint), '原因里要提到"水站"，顾客才知道去做什么')
    assert.strictEqual(wx.__calls.toast.length, 1, '同时弹一次提示')
  })

  await test('请求失败 ⇒ 原因要显示出来，不许再吞成空白', async () => {
    const { page } = newPage({ station: { id: 1, name: '测试水站' }, previewThrows: '客户当前在该商品上欠桶 2 个' })
    await page.refreshPreview()
    assert.strictEqual(page.data.preview, null)
    assert.strictEqual(page.data.previewHint, '客户当前在该商品上欠桶 2 个',
      '后端下发的话术是面向顾客的，要原样显示（前端不自造文案）')
  })

  await test('数量非法（0/空）⇒ 清空提示，不残留上一次的原因', async () => {
    const { page, calls } = newPage({ station: { id: 1, name: '测试水站' } })
    page.data.previewHint = '上一次失败的原因'
    page.data.returnForm.quantity = 0
    await page.refreshPreview()
    assert.strictEqual(calls.preview.length, 0, '数量非法不该发请求')
    assert.strictEqual(page.data.previewHint, '', '旧提示必须清掉，否则顾客会误以为这次也失败')
  })

  doneWatchdog()
  console.log('')
  if (failures.length) {
    console.log('失败 ' + failures.length + ' 项：')
    failures.forEach(f => console.log('  - ' + f.name + ' :: ' + (f.error && f.error.message)))
    process.exit(1)
  }
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
  console.log(MARK_ASCII + ' ' + passed)
})()
