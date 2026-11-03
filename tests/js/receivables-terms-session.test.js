const assert = require('node:assert/strict')
const { loadPage, createApp, createWx } = require('./harness')
const tick = () => new Promise(setImmediate)
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const A = { customerId: 1, customerName: '甲', dueDays: 30, overdueAmount: 1 }
const B = { customerId: 2, customerName: '乙', dueDays: 10, overdueAmount: 0 }
function setup() {
  const risks = [], saves = [], posts = [], dialogs = []
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, accessToken: 'test', userInfo: { staffId: 1, stationId: 11, role: 'STATION_MANAGER' } } })
  const wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/receivables/index.js', { app, wx, stubs: {
    'utils/request': {
      get: async url => {
        if (url.endsWith('/risk')) { const d = deferred(); risks.push({ url, ...d }); return d.promise }
        if (url.includes('/orders?')) return { code: 0, data: [] }
        return { code: 0, data: { customers: [A, B] } }
      },
      put: async (url, body) => { const d = deferred(); saves.push({ url, body, ...d }); return d.promise },
      post: async url => { posts.push(url); return { code: 0, data: { changedCount: 2 } } }
    },
    'utils/business-dialog': { showBusinessModal: opts => { dialogs.push(opts) } }
  } })
  page.setData({ customer: { ...A }, customers: [{ ...A }, { ...B }], allCustomers: [{ ...A }, { ...B }], loading: false })
  return { page, app, wx, risks, saves, posts, dialogs }
}
const tests = []
const test = (name, run) => tests.push({ name, run })
test('late risk response cannot replace another customer or reopened dialog', async () => {
  const { page, risks } = setup()
  page.onEditTerms(); page.onCloseTerms(); page.setData({ customer: { ...B } }); page.onEditTerms()
  risks[1].resolve({ code: 0, data: { level: 'B' } }); await tick()
  risks[0].resolve({ code: 0, data: { level: 'A' } }); await tick()
  assert.equal(page.data.risk.level, 'B')
  page.onCloseTerms(); page.onEditTerms(); page.onCloseTerms(); page.onEditTerms()
  risks[2].resolve({ code: 0, data: { level: 'old-B' } }); await tick()
  assert.equal(page.data.risk, null)
})
test('late save cannot replace customer, orders, or new terms dialog', async () => {
  const { page, saves } = setup(); page.onEditTerms(); page.onTermsInput({ detail: { value: '15' } })
  const old = page.onSaveTerms(); page.onCloseTerms()
  page.setData({ customer: { ...B }, orders: [{ orderId: 22, customerId: 2 }] }); page.onEditTerms()
  saves[0].resolve({ code: 0, data: { dueDays: 15 } }); await old
  assert.equal(page.data.customer.customerId, 2); assert.equal(page.data.orders[0].customerId, 2)
  assert.equal(page.data.termsVisible, true); assert.equal(page.data.termsInput, '10')
})
test('successful save updates canonical list used by filters', async () => {
  const { page, saves } = setup(); page.onEditTerms(); page.onTermsInput({ detail: { value: '15' } })
  const saving = page.onSaveTerms(); saves[0].resolve({ code: 0, data: { dueDays: 15 } }); await saving
  page.onFilterOverdue(); page.onFilterAll()
  assert.equal(page.data.allCustomers[0].dueDays, 15); assert.equal(page.data.customers[0].dueDays, 15)
  assert.equal(page.data.customer.dueDays, 15)
})
test('closing and reopening cannot send a second write while the first is unresolved', async () => {
  const { page, saves } = setup(); page.onEditTerms(); page.onTermsInput({ detail: { value: '15' } })
  const first = page.onSaveTerms(); page.onCloseTerms(); page.onEditTerms(); page.onTermsInput({ detail: { value: '10' } })
  await page.onSaveTerms(); assert.equal(saves.length, 1)
  saves[0].resolve({ code: 0, data: { dueDays: 15 } }); await first
  assert.equal(page.data.termsInput, '10'); assert.equal(page.data.customer.dueDays, 15); assert.equal(page.data.termsSaving, false)
})
test('ordinary backgrounding keeps the unsaved terms draft', async () => {
  const { page } = setup(); page.onEditTerms(); page.onTermsInput({ detail: { value: '15' } })
  page.onHide(); page.onShow(); await tick()
  assert.equal(page.data.termsVisible, true); assert.equal(page.data.termsInput, '15')
})
test('unsaved terms cannot silently recalculate using old days', async () => {
  const { page, posts, dialogs } = setup(); page.onEditTerms(); page.onTermsInput({ detail: { value: '15' } })
  const operation = page.onRecalcTerms(); if (dialogs[0]) dialogs[0].success({ confirm: true }); await operation
  assert.equal(posts.length, 0); assert.equal(page.data.termsInput, '15'); assert.equal(page.data.termsVisible, true)
})
for (const change of ['close', 'hide', 'identity', 'input']) test('old recalculation confirmation is invalid after ' + change, async () => {
  const { page, app, posts, dialogs } = setup(); page.onEditTerms()
  const operation = page.onRecalcTerms(); assert.equal(dialogs.length, 1)
  if (change === 'close') page.onCloseTerms()
  if (change === 'hide') page.onHide()
  if (change === 'identity') app._loginGeneration++
  if (change === 'input') page.onTermsInput({ detail: { value: '20' } })
  dialogs[0].success({ confirm: true }); await operation; assert.equal(posts.length, 0)
})
test('saved terms can recalculate once after explicit confirmation', async () => {
  const { page, posts, dialogs } = setup(); page.onEditTerms()
  const first = page.onRecalcTerms(), duplicate = page.onRecalcTerms()
  assert.equal(dialogs.length, 1); assert.match(dialogs[0].content, /甲/); assert.match(dialogs[0].content, /30/)
  dialogs[0].success({ confirm: true }); await Promise.all([first, duplicate])
  assert.deepEqual(posts, ['/api/manager/customers/1/credit-terms/recalculate'])
})
;(async () => {
  let failed = 0
  for (const { name, run } of tests) { try { await run(); console.log('PASS ' + name) } catch (e) { failed++; console.error('FAIL ' + name, e.message) } }
  if (failed) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(e => { console.error(e); process.exitCode = 1 })
