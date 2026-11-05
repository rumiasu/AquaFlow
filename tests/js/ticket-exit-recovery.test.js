const assert = require('assert')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

const done = armWatchdog()
const PAGE = 'miniapp-delivery/pages/station-mgmt/business-waiting/index.js'
const RECOVERY = 'miniapp-delivery/utils/ticket-exit-recovery.js'
const response = data => ({ code: 0, data })
const waiting = { schemaAvailable: true, limit: 200, counts: { waitingStock: 0, returnsTotal: 0, recoveriesTotal: 0, barrelsTotal: 0 }, stock: [], returns: [], recoveries: [], barrels: [] }
const batch = { paymentId: 7, customerName: '客户', productName: '水票', remainingQty: 2, refundAmount: 20, paymentMethodText: '现金' }
const e = { currentTarget: { dataset: { id: 7 } } }

function freshRecovery() {
  delete require.cache[require.resolve(path.resolve(ROOT, RECOVERY))]
}
function pageFor(wx, app, api) {
  freshRecovery()
  return loadPage(PAGE, {
    wx, app,
    stubs: {
      'api/business-rules': api,
      'utils/pending-reminder': { syncPendingReminder: async () => {} }
    }
  })
}
function appFor(staffId, stationId) {
  return createApp({ globalData: { isLogin: true, userInfo: { staffId, stationId, role: 'STATION_MANAGER' } } })
}
function apiFor(state) {
  return {
    getWaiting: async () => response(waiting),
    getRefusals: async args => response({ stationId: 1, scope: args.scope, limit: 50, items: [], nextBeforeId: null }),
    getTicketExitBatches: async () => response([batch]),
    refundTickets: (...args) => { state.calls.push(args); return state.impl(...args) }
  }
}
async function confirm(page) {
  assert(page.__wx.pendingModal, '应弹出退票确认框')
  return page.__wx.pendingModal.success({ confirm: true })
}
async function open(page) {
  await page.onShow()
  await page.onOpenTickets()
  assert.equal(page.data.tickets.length, 1)
}

async function main() {
  const wx = createWx()
  let modals = 0
  wx.showModal = opt => { modals++; wx.pendingModal = opt }

  // 1) 重复点击只发一次；成功后才解除
  let resolveFirst
  const state = { calls: [], impl: () => new Promise(resolve => { resolveFirst = resolve }) }
  const api = apiFor(state)
  const app = appFor(1, 1)
  const page = pageFor(wx, app, api)
  await open(page)
  page.onTicketRefund(e); page.onTicketRefund(e)
  assert.equal(modals, 1, '重复点击不能弹第二次确认')
  const first = confirm(page)
  assert.equal(state.calls.length, 1, '确认后只能发一次请求 ' + JSON.stringify({ calls: state.calls, err: page.data.ticketRecoveryError, busy: page.data.ticketBusy, rec: page.data.ticketRecovery }))
  page.onTicketRefund(e)
  assert.equal(state.calls.length, 1, '在途时再次点击不能再发请求')
  resolveFirst(response(null))
  await first
  assert.equal(page.data.ticketRecovery, null, '成功后解除恢复记录')

  // 独立场景：确认后网络未知 → 保留记录；新页面（磁盘记录）原键重试
  const timeoutWx = createWx()
  timeoutWx.showModal = opt => { timeoutWx.pendingModal = opt }
  const timeoutState = { calls: [], impl: () => Promise.reject(Object.assign(new Error('请求超时'), { requestMayHaveOccurred: true })) }
  const timeoutApi = apiFor(timeoutState)
  const timeoutPage = pageFor(timeoutWx, appFor(1, 1), timeoutApi)
  await open(timeoutPage)
  timeoutPage.onTicketRefund(e)
  await confirm(timeoutPage)
  await Promise.resolve()
  assert(timeoutPage.data.ticketRecovery, '网络未知必须保留恢复记录')
  assert.equal(timeoutState.calls.length, 1)
  const timeoutKey = timeoutState.calls[0][3]

  // 重启：清掉工具模块内存，依靠磁盘记录恢复
  const restarted = pageFor(timeoutWx, appFor(1, 1), timeoutApi)
  await restarted.onShow()
  assert(restarted.data.ticketRecovery, '重启后必须从存储恢复未知结果')
  assert.equal(restarted.data.ticketRecovery.requestKey, timeoutKey)
  // 未知结果后的重试即使被业务拒绝，也不能反推第一次未成功；原凭据必须保留。
  timeoutState.impl = () => Promise.reject(Object.assign(new Error('当前无权办理'), { businessRejected: true }))
  await restarted.onRetryTicketRefund()
  assert(restarted.data.ticketRecovery, '未知后业务拒绝仍须保留原凭据')
  assert.equal(restarted.data.ticketRecovery.requestKey, timeoutKey)
  assert(restarted.data.ticketError.includes('仍未核实'))
  // 切到另一个员工/站点看不到旧记录；切回原身份仍恢复同键。
  const otherActor = pageFor(timeoutWx, appFor(2, 2), timeoutApi)
  await otherActor.onShow()
  assert.equal(otherActor.data.ticketRecovery, null, '不同员工/站点不得看到旧身份记录')
  const sameActorAgain = pageFor(timeoutWx, appFor(1, 1), timeoutApi)
  await sameActorAgain.onShow()
  assert(sameActorAgain.data.ticketRecovery, '切回原身份必须恢复原记录')
  assert.equal(sameActorAgain.data.ticketRecovery.requestKey, timeoutKey)
  timeoutState.impl = () => Promise.resolve(response(null))
  timeoutState.calls = []
  await restarted.onRetryTicketRefund()
  assert.equal(timeoutState.calls.length, 1)
  assert.deepEqual(timeoutState.calls[0].slice(0, 3), [7, 2, '20.00'])
  assert.equal(timeoutState.calls[0][3], timeoutKey, '重试必须原键原内容')
  assert.equal(restarted.data.ticketRecovery, null, '成功后解除恢复记录')

  // 2) 业务明确拒绝：清除记录；下一次使用新键
  const rejectWx = createWx()
  rejectWx.showModal = opt => { rejectWx.pendingModal = opt }
  const rejectState = { calls: [], impl: () => Promise.reject(Object.assign(new Error('余额已变化'), { businessRejected: true })) }
  const rejectPage = pageFor(rejectWx, appFor(1, 1), apiFor(rejectState))
  await open(rejectPage)
  rejectPage.onTicketRefund(e)
  await confirm(rejectPage)
  await Promise.resolve()
  assert.equal(rejectPage.data.ticketRecovery, null, '业务拒绝后应解除本机记录')
  assert.equal(rejectPage.data.ticketError, '余额已变化')
  await rejectPage.onOpenTickets()
  rejectState.impl = () => Promise.resolve(response(null))
  rejectState.calls = []
  rejectPage.onTicketRefund(e)
  await confirm(rejectPage)
  assert.equal(rejectState.calls.length, 1)
  assert.notEqual(rejectState.calls[0][3], timeoutKey, '业务拒绝后的新请求必须使用新键')

  // 3) 存储失败：请求不得发出
  const storeWx = createWx()
  storeWx.showModal = opt => { storeWx.pendingModal = opt }
  const originalSet = storeWx.setStorageSync
  storeWx.setStorageSync = (k, v) => { if (String(k).includes('ticketExitRecovery')) throw new Error('storage unavailable'); originalSet(k, v) }
  const storeState = { calls: [], impl: () => Promise.resolve(response(null)) }
  const storePage = pageFor(storeWx, appFor(1, 1), apiFor(storeState))
  await open(storePage)
  storePage.onTicketRefund(e)
  await confirm(storePage)
  assert.equal(storeState.calls.length, 0, '存储失败时不能发请求')
  assert(storePage.data.ticketRecoveryError)
  // 恢复存储后第二次点击必须重新可靠落盘，再进入未知；重启仍保留同键原内容。
  storeWx.setStorageSync = originalSet
  storeState.impl = () => Promise.reject(Object.assign(new Error('请求超时'), { requestMayHaveOccurred: true }))
  await storePage.onShow(); await storePage.onOpenTickets()
  storePage.onTicketRefund(e)
  await confirm(storePage)
  assert.equal(storeState.calls.length, 1, '恢复存储后第二次点击应发出一次请求')
  const storedKey = storeState.calls[0][3]
  const storeRestarted = pageFor(storeWx, appFor(1, 1), apiFor(storeState))
  await storeRestarted.onShow()
  assert(storeRestarted.data.ticketRecovery, '写盘成功后重启必须恢复未知记录')
  assert.equal(storeRestarted.data.ticketRecovery.requestKey, storedKey)
  assert.deepEqual([storeRestarted.data.ticketRecovery.paymentId, storeRestarted.data.ticketRecovery.quantity, storeRestarted.data.ticketRecovery.amount], ['7', 2, '20.00'])

  // 4) 内容冲突：已有待核实记录时不能换数量/金额
  const conflictWx = createWx()
  conflictWx.showModal = opt => { conflictWx.pendingModal = opt }
  const conflictState = { calls: [], impl: () => Promise.resolve(response(null)) }
  const conflictPage = pageFor(conflictWx, appFor(1, 1), apiFor(conflictState))
  freshRecovery()
  const recovery = require(path.resolve(ROOT, RECOVERY))
  recovery.prepare({ staffId: 1, stationId: 1 }, { paymentId: 7, quantity: 1, amount: '10.00' })
  const changedBatch = Object.assign({}, batch, { remainingQty: 2, refundAmount: 20 })
  conflictPage.data.tickets = [changedBatch]
  conflictPage.data.ticketOpen = true
  conflictPage.onTicketRefund(e)
  await Promise.resolve()
  assert.equal(conflictState.calls.length, 0, '内容冲突不能发请求')
  assert.equal(conflictPage.data.ticketRecoveryError && conflictPage.data.ticketRecoveryError.includes('不能改数量'), true)

  // 5) 退款成功但本机记录清除失败：保留原键，重试只拿回原结果
  const clearWx = createWx()
  clearWx.showModal = opt => { clearWx.pendingModal = opt }
  const clearState = { calls: [], impl: () => Promise.resolve(response(null)) }
  const clearPage = pageFor(clearWx, appFor(1, 1), apiFor(clearState))
  await open(clearPage)
  const originalRemove = clearWx.removeStorageSync
  clearWx.removeStorageSync = k => { if (String(k).includes('ticketExitRecovery')) throw new Error('remove failed'); originalRemove(k) }
  clearPage.onTicketRefund(e)
  await confirm(clearPage)
  assert(clearPage.data.ticketRecovery, '清理失败必须保留恢复记录')
  assert(clearPage.data.ticketRecoveryError.includes('未能清除'))
  const clearKey = clearState.calls[0][3]
  clearWx.removeStorageSync = originalRemove
  clearState.calls = []
  // 清理失败后的同键重试若被业务拒绝，不能反推首次退款未成功；凭据必须保留。
  clearState.impl = () => Promise.reject(Object.assign(new Error('当前无权办理'), { businessRejected: true }))
  await clearPage.onRetryTicketRefund()
  assert(clearPage.data.ticketRecovery, '清理失败后同键重试业务拒绝不能清掉成功退款凭据')
  assert.equal(clearPage.data.ticketRecovery.requestKey, clearKey)
  assert(clearPage.data.ticketError.includes('仍未核实'))
  // 重启后仍必须保留同一把键，直到原结果被明确核实。
  const clearRestarted = pageFor(clearWx, appFor(1, 1), apiFor(clearState))
  await clearRestarted.onShow()
  assert(clearRestarted.data.ticketRecovery, '清理失败后重启仍须保留原记录')
  assert.equal(clearRestarted.data.ticketRecovery.requestKey, clearKey)
  clearState.impl = () => Promise.resolve(response(null))
  clearState.calls = []
  await clearRestarted.onRetryTicketRefund()
  assert.equal(clearState.calls.length, 1)
  assert.equal(clearState.calls[0][3], clearKey, '清理失败后的重试仍使用原键')
  assert.equal(clearRestarted.data.ticketRecovery, null)

  // 6) 模块读取层：unknown 落盘失败后仍不能把 pending 当成“首次请求”
  const failWx = createWx()
  global.wx = failWx
  freshRecovery()
  const failRecovery = require(path.resolve(ROOT, RECOVERY))
  const failRecord = failRecovery.prepare({ staffId: 1, stationId: 1 }, { paymentId: 7, quantity: 2, amount: 20 })
  const failSet = failWx.setStorageSync
  failWx.setStorageSync = k => { if (String(k).includes('ticketExitRecovery')) throw new Error('disk full'); failSet(k) }
  assert.throws(() => failRecovery.markUnknown(failRecord))
  const reread = failRecovery.read({ staffId: 1, stationId: 1 })
  assert(reread, '落盘失败后仍须能从原持久记录核实')
  assert.equal(reread.phase, 'unknown', 'pending 不能绕过读取层变成首次请求')
  assert.equal(reread.requestKey, failRecord.requestKey)
  failWx.setStorageSync = failSet

  console.log('AQUAFLOW_SUITE_OK 1')
  done()
}
main().catch(e => { console.error(e); process.exitCode = 1; done() })
