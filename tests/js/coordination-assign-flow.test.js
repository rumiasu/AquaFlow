/**
 * 「待分配 → 分配配送员」的流程测试（真执行页面处理函数，不是 grep 字面量）。
 *
 * 跑法：node tests/js/coordination-assign-flow.test.js
 *
 * ⚠️ 为什么必须有这一条（2026-09-27 真机实测事故）：
 * 站长在「待分配」点「分配配送员」、弹窗里点某位配送员 —— **毫无反应**。
 * 根因是 `_confirmRisk` 的 confirmText 传了 6 个字的「已确认，分配」，而微信 `wx.showModal`
 * 的按钮文案上限 4 字，超了**既不弹窗、也不走 fail 回调**：那条 Promise 永不 resolve，
 * 于是既不下发分配请求、也不报错、弹窗还开着。
 *
 * 已有的 modal-copy-limit.test.js 是**只认字面量**的静态扫描，而这里的文案是
 * 当参数传进 `_confirmRisk` 的（`confirmText: confirmText || '我已确认'`），它扫不到 ——
 * 这正是"门禁全绿但真机点不动"的形状。所以这条用例**真的把 handler 跑一遍**，
 * 断言「请求发出去了」+「弹窗文案合规」，两道都不靠字符串匹配。
 */

const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

const MARK_ASCII = 'AQUAFLOW_SUITE_OK'
const done = armWatchdog()
let passed = 0
const failures = []

async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log('  ✓ ' + name)
  } catch (e) {
    failures.push(name + ' :: ' + (e && e.message))
    console.log('  ✗ ' + name + ' —— ' + (e && e.message))
  }
}

const RISK_NOTE = '该单涉及押金/桶权益，跨站结算口径不清（押金记归属站、回桶也记回归属站的桶账）。'

/** 造一个页面实例 + 可编排的假后端。风险端点默认返回「涉押金」的提示（最坏路径）。 */
function makePage(overrides) {
  const o = overrides || {}
  const calls = { get: [], post: [] }
  const wx = createWx()
  wx.__modalAutoConfirm = o.confirmModal !== false

  const requestStub = {
    request: () => Promise.resolve({ data: {} }),
    get: (url, params) => {
      calls.get.push({ url, params })
      if (String(url).includes('cross-station-risk')) {
        return Promise.resolve({
          code: 0,
          data: o.riskNote === null ? {} : { depositBarrelRisk: true, riskNote: o.riskNote || RISK_NOTE }
        })
      }
      return Promise.resolve({ code: 0, data: [] })
    },
    post: (url, body) => {
      calls.post.push({ url, body })
      return Promise.resolve({ code: 0, data: null })
    },
    put: () => Promise.resolve({ code: 0, data: null }),
    del: () => Promise.resolve({ code: 0, data: null })
  }

  const deliveryStub = {
    getStaffList: () => Promise.resolve({ code: 0, data: [{ id: 2, name: '配送员', phone: '13800000000' }] }),
    getPoolOrders: () => Promise.resolve({ code: 0, data: [] }),
    getDirectedIncoming: () => Promise.resolve({ code: 0, data: [] }),
    getDispatchTracking: () => Promise.resolve({ code: 0, data: [] }),
    getPendingApprovals: () => Promise.resolve({ code: 0, data: [] }),
    claimPoolOrder: () => Promise.resolve({ code: 0, data: null }),
    cancelDispatch: () => Promise.resolve({ code: 0, data: null }),
    directedReturn: () => Promise.resolve({ code: 0, data: null }),
    approveDirectedReturn: () => Promise.resolve({ code: 0, data: null }),
    rejectDirectedReturn: () => Promise.resolve({ code: 0, data: null }),
    approveStaffReturn: () => Promise.resolve({ code: 0, data: null }),
    rejectStaffReturn: () => Promise.resolve({ code: 0, data: null }),
    approveCancelRequest: () => Promise.resolve({ code: 0, data: null }),
    rejectCancelRequest: () => Promise.resolve({ code: 0, data: null })
  }

  const app = createApp()
  app.canAccessStationBusiness = () => true

  const page = loadPage('miniapp-delivery/pages/coordination/index.js', {
    wx,
    app,
    stubs: {
      'utils/request': requestStub,
      'api/delivery': deliveryStub
    }
  })
  return { page, wx, calls }
}

const tapEvent = (id, name) => ({ currentTarget: { dataset: { id, name } } })

;(async () => {
  console.log('待分配 → 分配配送员 · 流程测试（真执行 handler）')

  await test('点「分配配送员」后弹窗打开，且记住的是被点那一单', async () => {
    const { page } = makePage()
    page.onShowAssign(tapEvent(4))
    assert.strictEqual(page.data.showAssignModal, true, '弹窗应打开')
    assert.strictEqual(page.data.currentOrderId, 4, 'currentOrderId 应记成被点的那一单')
  })

  await test('涉押金单：点配送员 ⇒ 先弹风险确认，确认后**真的下发分配请求**（本次事故的回归点）', async () => {
    const { page, wx, calls } = makePage()
    page.onShowAssign(tapEvent(4))
    await page.onConfirmAssign(tapEvent(2, '配送员'))

    assert.strictEqual(calls.post.length, 1, '必须发出 1 次分配请求（原先一次都不发：点一下毫无反应）')
    assert.ok(/\/api\/delivery\/orders\/assign\/4$/.test(calls.post[0].url),
      '分配请求应打到该订单：实际 ' + calls.post[0].url)
    assert.strictEqual(calls.post[0].body.deliveryStaffId, 2, '应带被选中的配送员 id')
    assert.strictEqual(calls.post[0].body.riskAcknowledged, true,
      '涉押金单必须带 riskAcknowledged，漏传后端会拒（§8.15 静默丢字段同款）')
    assert.strictEqual(page.data.showAssignModal, false, '成功后弹窗应关闭')
    assert.ok(wx.__calls.toast.some((t) => String(t.title || '').indexOf('配送员') >= 0),
      '应给出「已分配给 xxx」的反馈')
  })

  await test('弹窗按钮文案必须 ≤ 4 字（超了微信既不弹窗也不报错，Promise 永不 resolve）', async () => {
    const { page, wx } = makePage()
    page.onShowAssign(tapEvent(4))
    await page.onConfirmAssign(tapEvent(2, '配送员'))
    assert.ok(wx.__calls.modal.length > 0, '涉押金单必须弹过确认框')
    wx.__calls.modal.forEach((m) => {
      ;['confirmText', 'cancelText'].forEach((k) => {
        if (typeof m[k] === 'string') {
          assert.ok([...m[k]].length <= 4, `${k}「${m[k]}」共 ${[...m[k]].length} 字，超过平台上限 4`)
        }
      })
    })
  })

  await test('不涉风险的单：不弹框、直接分配（不该给普通单加摩擦）', async () => {
    const { page, wx, calls } = makePage({ riskNote: null })
    page.onShowAssign(tapEvent(5))
    await page.onConfirmAssign(tapEvent(2, '配送员'))
    assert.strictEqual(wx.__calls.modal.length, 0, '普通单不该弹风险框')
    assert.strictEqual(calls.post.length, 1, '普通单也应直接下发分配请求')
    assert.strictEqual(calls.post[0].body.riskAcknowledged, undefined, '普通单不该带 riskAcknowledged')
  })

  await test('用户在风险框点「取消」⇒ 一个请求都不发（不能绕过双方确认）', async () => {
    const { page, calls } = makePage({ confirmModal: false })
    page.onShowAssign(tapEvent(4))
    await page.onConfirmAssign(tapEvent(2, '配送员'))
    assert.strictEqual(calls.post.length, 0, '取消后不得提交')
  })

  done()
  console.log('')
  if (failures.length) {
    console.log(`失败 ${failures.length} 项 / 通过 ${passed} 项`)
    failures.forEach((f) => console.log('  - ' + f))
    process.exitCode = 1
  } else {
    console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
    console.log(MARK_ASCII + ' ' + passed)
  }
})()
