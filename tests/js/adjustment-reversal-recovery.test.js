'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const BASE = 'miniapp-delivery/pages/station-mgmt/customers/adjust/'
const tree = parseWxml(fs.readFileSync(path.join(ROOT, BASE, 'detail/index.wxml'), 'utf8'))
const tests = [], test = (name, run) => tests.push({ name, run }), done = armWatchdog(30000)
const tick = () => new Promise(resolve => setImmediate(resolve))

function fixture(overrides = {}) {
  const wx = createWx(), dialogs = [], writes = [], clipboard = []
  let actor = 'station-1-manager-8'
  let record = Object.assign({ id: 91, adjustNo: 'ADJ-91', customerId: 42, customerName: '合成客户', productId: 7,
    productName: '合成桶', status: 'EFFECTIVE', statusText: '已生效', adjustType: 'BARREL_GRANT',
    adjustTypeText: '补录桶权益', qty: 2, canReverse: false, reverseUnavailableReason: '原调整缺少原批次明细，请核对原单及资产流水' }, overrides)
  let readFailure = null, reverseFailure = null
  wx.setClipboardData = options => { clipboard.push(options.data); if (options.success) options.success() }
  const stubs = {
    'utils/request': { captureSession: () => actor, currentSession: session => session === actor },
    'utils/business-dialog': { showBusinessModal: options => dialogs.push(options) },
    'api/station-mgmt': {
      getAdjustment: async () => { if (readFailure) throw readFailure; return { code: 0, data: record } },
      reverseAdjustment: async (...args) => {
        writes.push(args)
        if (reverseFailure) throw reverseFailure
        record = { ...record, status: 'REVERSED', statusText: '已撤销', reversedBy: 92, canReverse: false }
        return { code: 0, data: { id: 92, adjustNo: 'ADJ-92' } }
      },
      executeAdjustment: async () => ({ code: 0 })
    }
  }
  const app = createApp(), page = loadPage(BASE + 'detail/index.js', { wx, app, stubs })
  page._session = actor
  page.setData({ id: record.id, reverseClientToken: 'same-original-intent' })
  return { page, wx, dialogs, writes, clipboard, stubs, app,
    patch: values => { record = { ...record, ...values } },
    rejectReverse: error => { reverseFailure = error }, failRead: error => { readFailure = error },
    changeActor: () => { actor = 'station-2-manager-9' } }
}
function visible(page) { return renderElements(tree, page.data, { includeText: true }) }
function text(page) { return visible(page).map(x => x.text || '').join('\n') }
function hasAction(page, action) { return visible(page).some(x => x.attrs.bindtap === action) }

test('missing source evidence exposes a lasting reason and real verification actions, never a reverse action', async () => {
  const f = fixture(); await f.page.loadData()
  assert.equal(hasAction(f.page, 'onReverse'), false)
  assert.match(text(f.page), /原调整缺少原批次明细/)
  assert.match(text(f.page), /ADJ-91/)
  assert.ok(hasAction(f.page, 'onOpenCustomerAssets'))
  assert.ok(hasAction(f.page, 'onCopyVerification'))
  f.page.onReverse(); await f.page.doReverse('不能绕过只读判定')
  assert.equal(f.dialogs.length, 0); assert.equal(f.writes.length, 0)
})
test('an old response without eligibility does not promise or dispatch a reversal', async () => {
  const f = fixture({ canReverse: undefined, reverseUnavailableReason: undefined }); await f.page.loadData()
  assert.equal(hasAction(f.page, 'onReverse'), false)
  assert.match(text(f.page), /尚未取得撤销条件/)
  f.page.onReverse(); assert.equal(f.dialogs.length, 0)
})
test('an eligible reversal keeps its original reason and idempotency key and opens its resulting record', async () => {
  const f = fixture({ canReverse: true, reverseUnavailableReason: '' }); await f.page.loadData()
  assert.ok(hasAction(f.page, 'onReverse'))
  f.page.onReverse(); assert.match(f.dialogs[0].description, /重新核对/)
  f.dialogs[0].success({ confirm: true, content: '核对原凭据后的撤销' }); await tick()
  assert.deepEqual(f.writes, [[91, '核对原凭据后的撤销', 'same-original-intent']])
  assert.equal(f.page.data.detail.reversedBy, 92)
  assert.equal(hasAction(f.page, 'onReverse'), false)
})
test('a modal opened before new occupancy is detected cannot submit against refreshed blocked eligibility', async () => {
  const f = fixture({ canReverse: true, reverseUnavailableReason: '' }); await f.page.loadData()
  f.page.onReverse(); f.patch({ canReverse: false, reverseUnavailableReason: '原批次已被占用' }); await f.page.loadData()
  f.dialogs[0].success({ confirm: true, content: '过期确认' }); await tick()
  assert.equal(f.writes.length, 0); assert.match(text(f.page), /原批次已被占用/)
})
test('write-time rejection survives refresh and supplies the original reference to verification navigation and clipboard', async () => {
  const f = fixture({ canReverse: true, reverseUnavailableReason: '' }); await f.page.loadData()
  f.rejectReverse(Object.assign(Error('原批次刚被配送占用'), { businessRejected: true }))
  await f.page.doReverse('核实'); await tick()
  assert.match(text(f.page), /原批次刚被配送占用/)
  f.page.onHide(); await f.page.onShow()
  assert.match(text(f.page), /原批次刚被配送占用/)
  f.page.onOpenCustomerAssets()
  assert.equal(f.wx.__calls.nav.at(-1).url, '/pages/station-mgmt/customers/detail/index?id=42')
  const appConfig = JSON.parse(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/app.json'), 'utf8'))
  const routes = appConfig.pages.concat((appConfig.subPackages || []).flatMap(pkg => pkg.pages.map(page => pkg.root + '/' + page)))
  assert.ok(routes.includes('pages/station-mgmt/customers/detail/index'))
  f.page.onCopyVerification()
  assert.match(f.clipboard[0], /ADJ-91/); assert.match(f.clipboard[0], /91/); assert.match(f.clipboard[0], /原批次刚被配送占用/)
  assert.match(text(f.page), /勿另建相反调整代替撤销/)
})
test('unknown write outcome and failed reread keep the reference and error visible without claiming no write occurred', async () => {
  const f = fixture({ canReverse: true, reverseUnavailableReason: '' }); await f.page.loadData()
  f.rejectReverse(Object.assign(Error('撤销请求超时，请核对原单状态'), { requestMayHaveOccurred: true }))
  f.failRead(Error('详情暂时读取失败'))
  await f.page.doReverse('核实'); await tick()
  assert.match(text(f.page), /ADJ-91/)
  assert.match(text(f.page), /撤销请求超时/)
  assert.ok(hasAction(f.page, 'onRetry'))
  assert.equal(hasAction(f.page, 'onReverse'), false)
  f.page.onCopyVerification(); assert.match(f.clipboard[0], /ADJ-91/)
})
test('account changes block verification sharing and navigation, and clear old write feedback', async () => {
  const f = fixture(); await f.page.loadData()
  f.page.setData({ reverseError: '旧账号提示' }); f.changeActor()
  f.page.onOpenCustomerAssets(); f.page.onCopyVerification(); f.page.onReverse()
  assert.equal(f.wx.__calls.nav.length, 0); assert.equal(f.clipboard.length, 0); assert.equal(f.dialogs.length, 0)
  f.page.onShow(); assert.equal(f.page.data.detail, null); assert.equal(f.page.data.reverseError, '')
})
test('both execute confirmations describe conditional reversal instead of promising it for every adjustment', async () => {
  const f = fixture({ status: 'PENDING' }); await f.page.loadData(); f.page.onExecute()
  assert.doesNotMatch(f.dialogs[0].content, /事后只能通过/); assert.match(f.dialogs[0].content, /撤销需/)
  const list = loadPage(BASE + 'index.js', { wx: f.wx, app: f.app, stubs: f.stubs })
  list.setData({ list: [{ id: 91, adjustNo: 'ADJ-91', isPending: true, hasQty: true, qtyText: '+2' }] })
  list.onExecute({ currentTarget: { dataset: { id: 91 } } })
  assert.doesNotMatch(f.dialogs[1].content, /事后只能通过/); assert.match(f.dialogs[1].content, /撤销需/)
})

;(async () => {
  let failed = 0
  for (const item of tests) {
    try { await item.run(); console.log('ok - ' + item.name) }
    catch (error) { failed++; console.error('not ok - ' + item.name); console.error(error.stack) }
  }
  done()
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})()
