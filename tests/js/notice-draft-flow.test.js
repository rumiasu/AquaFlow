/**
 * 「公告发布 / 草稿」流程测试（站长端「营业状态与公告」页）。
 *
 * 跑法：node tests/js/notice-draft-flow.test.js
 *
 * 钉住的是 2026-09-26 那条产品裁定（**一步两键**，不拆分多步）：
 *   · 右键「发布」= 保存并上线（status 1）—— 新建与编辑都一样，点一下顾客就可见；
 *   · 左键「存为草稿」= status 0（顾客看不到，之后可在列表里发布）；
 *   · **编辑一条已发布的公告再点「存为草稿」= 把它下架** ⇒ 必须先弹二次确认，
 *     取消确认时一个写请求都不发（这条最容易"手滑让公告从顾客端静默消失"）；
 *   · toast 必须跟真实 status 一致（曾写死「已发布」而实际存的是草稿）。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')

// 完成哨兵：**只用 ASCII**。受限沙箱下子进程往文件描述符写中文会被编码毁成 `?`，
// 于是中文完成标记匹配不到、正常套件被误判成"未跑完"（2026-09-27 实测）。
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

function newPage() {
  const calls = { createNotice: [], updateNotice: [] }
  const stubs = {
    'api/station-mgmt': {
      getStationStatus: async () => ({ code: 0, data: { operatingStatus: 1, options: [] } }),
      updateStationStatus: async () => ({ code: 0 }),
      getNotices: async () => ({ code: 0, data: [] }),
      createNotice: async (payload) => { calls.createNotice.push(payload); return { code: 0 } },
      updateNotice: async (id, payload) => { calls.updateNotice.push({ id, payload }); return { code: 0 } },
      deleteNotice: async () => ({ code: 0 })
    }
  }
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/station-status/index.js', { stubs, wx, app: createApp() })
  return { page, calls, wx }
}

const fill = (page, title, content) => {
  page.data.editForm.title = title
  page.data.editForm.content = content
}

console.log('公告发布/草稿 · 流程测试（真实执行页面处理函数）')

// Node 24 不允许"顶层 await + require"混用，用例统一在 async 主函数里顺序执行
;(async () => {
const doneWatchdog = armWatchdog()

await test('新建 → 点「发布」：直接上线（status=1），toast 说已发布', async () => {
  const { page, calls, wx } = newPage()
  page.openAddNotice()
  assert.strictEqual(page.data.isAdd, true)
  fill(page, '春节期间配送安排', '初一至初三暂停配送')
  await page._submitNotice(1)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.createNotice.length, 1, '应发一次新建请求')
  assert.strictEqual(calls.createNotice[0].status, 1, '点发布必须直接上线（不再先存草稿）')
  assert.strictEqual(calls.createNotice[0].type, 2, '站长发的是水站通知（type=2）')
  const toast = wx.__calls.toast.map(t => t.title || '').join(' | ')
  assert.ok(toast.indexOf('已发布') > -1, 'toast 要说已发布：' + toast)
})

await test('新建 → 点「存为草稿」：status=0，toast 说草稿（不谎报已发布）', async () => {
  const { page, calls, wx } = newPage()
  page.openAddNotice()
  fill(page, '草稿标题', '草稿内容')
  await page._submitNotice(0)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.createNotice[0].status, 0)
  const toast = wx.__calls.toast.map(t => t.title || '').join(' | ')
  assert.ok(toast.indexOf('草稿') > -1, toast)
  assert.ok(toast.indexOf('已发布') === -1, '存草稿不能说已发布：' + toast)
})

await test('弹窗文案：说清"发布立刻可见 / 存草稿先留着"，且内容提示里提到发布', async () => {
  const { page } = newPage()
  page.openAddNotice()
  assert.ok(page.data.saveHint.indexOf('发布') > -1 && page.data.saveHint.indexOf('草稿') > -1,
    '提示要同时说清两个结果：' + page.data.saveHint)

  const wxml = fs.readFileSync(path.join(__dirname, '..', '..', 'miniapp-delivery',
    'pages', 'station-mgmt', 'station-status', 'index.wxml'), 'utf8')
  const m = wxml.match(/placeholder="([^"]*顾客能看懂为准[^"]*)"/)
  assert.ok(m, '应能找到内容框的 placeholder')
  assert.ok(m[1].indexOf('发布') > -1, '内容提示要提「发布」：' + m[1])
  // 两个按钮的字样
  assert.ok(/bindtap="onSaveDraft"[^>]*>存为草稿</.test(wxml), '左键应是「存为草稿」')
  assert.ok(/bindtap="onSaveNotice"[^>]*>发布</.test(wxml), '右键应是「发布」')
})

await test('编辑**已发布** → 点「发布」：保持可见（status=1），不弹确认', async () => {
  const { page, calls, wx } = newPage()
  page.openEditNotice({ currentTarget: { dataset: { item: { id: 7, title: '停水通知', content: '明早停水', status: 1 } } } })
  fill(page, '停水通知', '明早 8 点停水')
  await page._submitNotice(1)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(wx.__calls.modal.length, 0, '改错别字这种常规路径不该弹确认')
  assert.strictEqual(calls.updateNotice[0].payload.status, 1, '保持已发布')
})

await test('编辑**已发布** → 点「存为草稿」：先二次确认；确认后才下架（status=0）', async () => {
  const { page, calls, wx } = newPage()
  page.openEditNotice({ currentTarget: { dataset: { item: { id: 7, title: '停水通知', content: '明早停水', status: 1 } } } })
  fill(page, '停水通知', '明早 8 点停水')          // 自动确认（harness 默认 confirm=true）
  await page._submitNotice(0)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(wx.__calls.modal.length, 1, '必须先问一句"要不要下架"')
  assert.ok(wx.__calls.modal[0].title.indexOf('下架') > -1, wx.__calls.modal[0].title)
  assert.strictEqual(calls.updateNotice.length, 1)
  assert.strictEqual(calls.updateNotice[0].payload.status, 0)
})

// 这一条是 2026-09-26 真机反馈的直接回归：当时 confirmText 写成「下架为草稿」(5 字)，
// 微信既不弹窗也不报错 ⇒ 站长点「存为草稿」毫无反应。harness 现在会拦，这里再显式钉一遍。
await test('二次确认弹窗的按钮文案必须 ≤4 字（超过微信会静默丢弃整个弹窗）', async () => {
  const { page, wx } = newPage()
  page.openEditNotice({ currentTarget: { dataset: { item: { id: 7, title: 't', content: 'c', status: 1 } } } })
  fill(page, 't', 'c')
  await page._submitNotice(0)
  const modal = wx.__calls.modal[0]
  assert.ok(modal, '应弹出确认框')
  ;['confirmText', 'cancelText'].forEach((k) => {
    assert.ok(modal[k] && [...modal[k]].length <= 4, `${k}「${modal[k]}」超过 4 个字`)
  })
})

await test('编辑**已发布** → 存草稿时点「再想想」：一个写请求都不发（公告不消失）', async () => {
  const { page, calls, wx } = newPage()
  page.openEditNotice({ currentTarget: { dataset: { item: { id: 7, title: '停水通知', content: '明早停水', status: 1 } } } })
  fill(page, '停水通知', '明早 8 点停水')
  wx.__modalAutoConfirm = false
  await page._submitNotice(0)
  const modal = wx.__calls.modal[wx.__calls.modal.length - 1]
  modal.success({ confirm: false, cancel: true })   // 站长选了"再想想"
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(calls.updateNotice.length, 0, '取消确认后不许改状态')
})

await test('编辑**草稿** → 点「发布」直接上线；点「存为草稿」仍为草稿且不弹确认', async () => {
  const a = newPage()
  a.page.openEditNotice({ currentTarget: { dataset: { item: { id: 8, title: '草稿', content: '内容', status: 0 } } } })
  fill(a.page, '草稿', '内容')
  await a.page._submitNotice(1)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(a.calls.updateNotice[0].payload.status, 1, '草稿点发布应上线')

  const b = newPage()
  b.page.openEditNotice({ currentTarget: { dataset: { item: { id: 8, title: '草稿', content: '内容', status: 0 } } } })
  fill(b.page, '草稿', '内容')
  await b.page._submitNotice(0)
  await new Promise((r) => setTimeout(r, 10))
  assert.strictEqual(b.wx.__calls.modal.length, 0, '本来就是草稿，不需要确认')
  assert.strictEqual(b.calls.updateNotice[0].payload.status, 0)
})

await test('两个按钮都要过表单校验：空标题/空内容不发请求', async () => {
  const { page, calls, wx } = newPage()
  page.openAddNotice()
  fill(page, '', '')
  await page._submitNotice(1)
  await page._submitNotice(0)
  assert.strictEqual(calls.createNotice.length, 0, '空表单不许建公告（草稿也不行）')
  assert.ok(wx.__calls.toast.some(t => (t.title || '').indexOf('标题') > -1))
})

console.log('')
doneWatchdog()
if (failures.length) {
  console.log('失败 ' + failures.length + ' 项 / 通过 ' + passed + ' 项')
  process.exitCode = 1
} else {
  console.log('全部通过：' + passed + ' 项（流程测试，真实执行页面处理函数）')
  console.log(MARK_ASCII + ' ' + passed)
}
})()
