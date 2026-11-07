const assert = require('assert')
const { loadPage, createApp } = require('./harness')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
async function main() {
  let session = true, response = { data: [{ id: 1, statusText: '待处理' }] }, calls = 0
  const app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 2 } } })
  const page = loadPage('miniapp-delivery/pages/my-problem-receipts/index.js', { app, stubs: {
    'utils/request': { captureSession: () => ({}), currentSession: () => session,
      get: async url => { assert.equal(url, '/api/delivery/my-problem-receipts'); calls++; return response } }
  } })
  await page.onShow(); assert.equal(page.data.items[0].statusText, '待处理')
  response = { data: [{ id: 1, statusText: '核实结案（无需补偿）' }] }
  await page.load(); assert.equal(page.data.items[0].statusText, '核实结案（无需补偿）')
  response = {}; await page.load(); assert(page.data.error); assert.equal(page.data.items.length, 0)
  const pending = deferred(); response = pending.promise; const old = page.load(); page.onHide()
  pending.resolve({ data: [{ id: 9 }] }); await old; assert.equal(page.data.items.length, 0)
  session = false; response = { data: [{ id: 10 }] }; await page.onShow(); assert.equal(page.data.items.length, 0)
  assert(calls >= 4); console.log('PASS delivery problem receipt state, errors, and stale-session guards'); console.log('AQUAFLOW_SUITE_OK 1')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
