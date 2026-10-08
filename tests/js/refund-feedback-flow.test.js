const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')
const done = armWatchdog()
let passed = 0
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const ref = { refundType: 'BARREL_RETURN', refundId: 12 }
const success = { data: Object.assign({ id: 31 }, ref) }
function customer(api = {}, wx = createWx()) {
  const app = createApp({ globalData: { isLogin: true, customerId: 7, accessToken: 'synthetic-session', refreshToken: 'synthetic-refresh' } })
  const calls = []
  const feedback = Object.assign({
    getRefundNotes: async () => ({ data: Object.assign({ objectText: '退押金申请 #12', notes: [] }, ref) }),
    appendRefundNote: async p => { calls.push(p); return success },
    getMyFeedback: async () => ({ data: [] }), submitFeedback: async p => { calls.push(p); return { data: null } },
    getRefundOptions: async () => ({ data: { options: [], hasMore: false } })
  }, api)
  const page = loadPage('miniapp-user/pages/service/index.js', { wx, app, stubs: { 'api/feedback': feedback } })
  page.onLoad(ref)
  return { page, wx, app, calls }
}
function manager(api = {}, wx = createWx()) {
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 17, stationId: 2, role: 'STATION_MANAGER' } }, isStationManager: () => true })
  const calls = []
  const feedback = Object.assign({ getCustomerFeedbacks: async () => ({ data: [] }),
    appendRefundNote: async p => { calls.push(p); return success } }, api)
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customer-feedback/index.js', { wx, app, stubs: { 'api/feedback': feedback } })
  page.onLoad(); page._noteEpoch = 1
  page.setData({ list: [page.decorate(Object.assign({ id: 9, refundObjectText: '退押金申请 #12', content: '原说明' }, ref))] })
  page.onOpenRefundNote({ currentTarget: { dataset: { id: 9 } } })
  return { page, wx, app, calls }
}
async function test(name, run) { await run(); passed++; process.stdout.write('PASS ' + name + '\n') }
async function main() {
  await test('optional explanation sends only object/key/note and clears only confirmed intent', async () => {
    const f = customer(); await f.page.loadRefundThread(); await f.page.onSubmit()
    assert.strictEqual(f.calls.length, 1); assert.strictEqual(f.calls[0].content, '')
    assert.deepStrictEqual(Object.keys(f.calls[0]).sort(), ['contact','content','idempotencyKey','refundId','refundType'])
    assert.strictEqual(f.page.data.pendingRefundNote, false); assert.strictEqual(f.wx.__storage.size, 0)
  })
  await test('double tap is one request and unknown failure retains same key for retry', async () => {
    const d = deferred(), sent = []; let n = 0
    const f = customer({ appendRefundNote: p => { sent.push(p); return ++n === 1 ? d.promise : Promise.resolve(success) } })
    await f.page.loadRefundThread(); f.page.onContentInput({ detail: { value: '原说明' } })
    const a = f.page.onSubmit(); await f.page.onSubmit(); assert.strictEqual(sent.length, 1)
    d.reject(new Error('网络中断')); await a; assert.strictEqual(f.page.data.pendingRefundNote, true)
    await f.page.onSubmit(); assert.strictEqual(sent.length, 2); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey)
  })
  await test('unknown submission cannot be silently replaced by edited note', async () => {
    const sent = []; const f = customer({ appendRefundNote: async p => { sent.push(p); throw new Error('未知结果') } })
    await f.page.loadRefundThread(); f.page.setData({ content: '原文' }); await f.page.onSubmit()
    f.page.setData({ content: '新文' }); await f.page.onSubmit(); assert.strictEqual(sent.length, 1)
    await f.page.onRetryOriginalNote(); assert.strictEqual(sent.length, 2); assert.strictEqual(sent[1].content, '原文'); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey)
  })
  await test('page revisit recovers original request while another customer gets no old draft', async () => {
    const wx = createWx(); const a = customer({ appendRefundNote: async () => { throw new Error('未知') } }, wx)
    await a.page.loadRefundThread(); a.page.setData({ content: '本人草稿' }); await a.page.onSubmit()
    const b = customer({}, wx); await b.page.loadRefundThread(); assert.strictEqual(b.page.data.content, '本人草稿')
    b.app.globalData.customerId = 8; const c = loadPage('miniapp-user/pages/service/index.js', { wx, app: b.app, stubs: { 'api/feedback': { getRefundNotes: async () => ({ data: Object.assign({ objectText: '申请', notes: [] }, ref) }) } } })
    c.onLoad(ref); await c.loadRefundThread(); assert.strictEqual(c.data.content, '')
  })
  await test('storage failure sends nothing', async () => {
    const f = customer(); await f.page.loadRefundThread(); f.wx.setStorageSync = () => { throw new Error('存储失败') }
    await f.page.onSubmit(); assert.strictEqual(f.calls.length, 0); assert.strictEqual(f.page.data.submitting, false)
  })
  await test('denied or malformed context cannot enable submit', async () => {
    const f = customer({ getRefundNotes: async () => { throw new Error('无权访问') } }); await f.page.loadRefundThread(); await f.page.onSubmit()
    assert.strictEqual(f.page.data.refundReady, false); assert.strictEqual(f.calls.length, 0)
    const bad = customer({ getRefundNotes: async () => ({ data: Object.assign({ objectText: '申请', notes: [] }, ref, { refundId: 99 }) }) }); await bad.page.loadRefundThread(); assert.strictEqual(bad.page.data.refundReady, false)
  })
  await test('malformed success preserves retry record', async () => {
    const f = customer({ appendRefundNote: async () => ({ data: { id: 31 } }) }); await f.page.loadRefundThread(); await f.page.onSubmit()
    assert.strictEqual(f.page.data.pendingRefundNote, true); assert.strictEqual(f.wx.__storage.size, 1)
  })
  await test('late customer response after switch does not clear old intent or new page input', async () => {
    const d = deferred(); const f = customer({ appendRefundNote: () => d.promise }); await f.page.loadRefundThread()
    const run = f.page.onSubmit(); f.app.globalData.customerId = 8; f.page.setData({ content: '新身份内容' }); d.resolve(success); await run
    assert.strictEqual(f.page.data.content, '新身份内容'); assert.strictEqual(f.wx.__storage.size, 1); assert.strictEqual(f.wx.__calls.toast.length, 0)
  })
  await test('late response after page hide is ignored', async () => {
    const d = deferred(); const f = customer({ appendRefundNote: () => d.promise }); await f.page.loadRefundThread()
    const run = f.page.onSubmit(); f.page.onHide(); d.resolve(success); await run; assert.strictEqual(f.wx.__storage.size, 1); assert.strictEqual(f.wx.__calls.toast.length, 0)
  })
  await test('reused customer page clears prior identity context and ignores late personal history', async () => {
    const d = deferred(); let calls = 0; const f = customer({ getMyFeedback: () => ++calls === 1 ? d.promise : Promise.resolve({ data: [] }) });
    await f.page.loadRefundThread(); f.page.setData({ content: '旧身份草稿', history: [{ id: 1, content: '旧说明' }], refundOptions: [ref] })
    const oldHistory = f.page.loadHistory(); f.app.globalData.customerId = 8; f.page.onShow()
    assert.strictEqual(f.page.data.refundRef, null); assert.strictEqual(f.page.data.content, ''); assert.strictEqual(f.page.data.refundOptions.length, 0)
    d.resolve({ data: [{ id: 1, content: '迟到旧说明' }] }); await oldHistory
    assert.deepStrictEqual(f.page.data.history, [])
  })
  await test('refund option pagination appends earlier server objects and retries failure', async () => {
    const seen = []; const f = customer({ getRefundOptions: async p => { seen.push(p); return { data: { options: [Object.assign({ objectText: '原款' }, ref, { refundId: p })], hasMore: p === 1 } } } })
    await f.page.onShowRefundOptions(); await f.page.onMoreRefundOptions(); assert.deepStrictEqual(seen, [1, 2]); assert.strictEqual(f.page.data.refundOptions.length, 2)
  })
  await test('ordinary anonymous feedback still uses original endpoint and requires content', async () => {
    const f = customer(); f.page.setData({ refundRef: null, anonymous: true }); await f.page.onSubmit(); assert.strictEqual(f.calls.length, 0)
    f.page.setData({ content: '普通反馈' }); await f.page.onSubmit(); assert.strictEqual(f.calls.length, 1); assert.strictEqual(f.calls[0].anonymous, true); assert.strictEqual(f.calls[0].refundType, undefined)
  })
  await test('manager optional note appends without edit or financial payload', async () => {
    const f = manager(); await f.page.onSubmitRefundNote(); assert.strictEqual(f.calls.length, 1); assert.strictEqual(f.calls[0].content, '')
    assert.deepStrictEqual(Object.keys(f.calls[0]).sort(), ['contact','content','idempotencyKey','refundId','refundType']); assert.strictEqual(f.wx.__storage.size, 0)
  })
  await test('manager failure retries identical key and content', async () => {
    const sent = []; let first = true; const f = manager({ appendRefundNote: async p => { sent.push(p); if (first) { first = false; throw new Error('未知') } return success } })
    f.page.onNoteInput({ detail: { value: '原补充' } }); await f.page.onSubmitRefundNote(); await f.page.onSubmitRefundNote()
    assert.strictEqual(sent.length, 2); assert.strictEqual(sent[0].idempotencyKey, sent[1].idempotencyKey)
  })
  await test('late manager response after identity switch keeps original pending record', async () => {
    const d = deferred(); const f = manager({ appendRefundNote: () => d.promise }); const run = f.page.onSubmitRefundNote()
    f.app.globalData.userInfo = { staffId: 18, stationId: 3, role: 'STATION_MANAGER' }; f.page.setData({ noteContent: '新站内容' }); d.resolve(success); await run
    assert.strictEqual(f.page.data.noteContent, '新站内容'); assert.strictEqual(f.wx.__storage.size, 1); assert.strictEqual(f.wx.__calls.toast.length, 0)
  })
  await test('ordinary feedback has no reply button and linked feedback has a real handler', async () => {
    const src = fs.readFileSync(path.join(ROOT,'miniapp-delivery/pages/station-mgmt/customer-feedback/index.wxml'),'utf8'), tree = parseWxml(src)
    const base = { denied: false, type: 'list', list: [{ id: 1, content: '普通' }] }
    assert.strictEqual(renderElements(tree,base).filter(n => n.attrs.bindtap === 'onOpenRefundNote').length, 0)
    base.list[0] = Object.assign({ id: 2 }, ref)
    assert.strictEqual(renderElements(tree,base).filter(n => n.attrs.bindtap === 'onOpenRefundNote').length, 1)
  })
  await test('barrel history link targets original request without sending user or station', async () => {
    const wx = createWx(); const page = loadPage('miniapp-user/pages/barrel/index.js',{ wx }); page.setData({ records: [{ id: 12, statusText: '已退款' }] })
    page.onRefundFeedback({ currentTarget: { dataset: { id: 12 } } }); assert.deepStrictEqual(wx.__calls.nav,[{ type:'navigateTo',url:'/pages/service/index?refundType=BARREL_RETURN&refundId=12' }])
  })
  process.stdout.write('AQUAFLOW_SUITE_OK '+passed+'\n'); done()
}
main().catch(e => { done(); console.error(e); process.exitCode=1 })
