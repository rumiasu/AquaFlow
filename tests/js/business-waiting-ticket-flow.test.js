const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog()
const response = data => ({ code: 0, data })
const waiting = { schemaAvailable: true, limit: 200, counts: { waitingStock: 0, returnsTotal: 0, recoveriesTotal: 0, barrelsTotal: 0 }, stock: [], returns: [], recoveries: [], barrels: [] }
const batch = { paymentId: 7, customerName: '客户', productName: '水票', remainingQty: 2, refundAmount: 20, paymentMethodText: '现金' }
async function main() {
  const wx = createWx(), app = createApp({ globalData: { isLogin: true, userInfo: { staffId: 1, stationId: 1, role: 'STATION_MANAGER' } } })
  let reads = 0, refunds = 0, fail = false, resolveRefund
  const api = {
    getWaiting: async () => response(waiting),
    getRefusals: async args => response({ stationId: 1, scope: args.scope, limit: 50, items: [], nextBeforeId: null }),
    getTicketExitBatches: async () => { reads++; if (fail) throw Error('离线'); return response(refunds ? [] : [batch]) },
    refundTickets: () => { refunds++; return new Promise(resolve => { resolveRefund = resolve }) }
  }
  wx.showModal = opt => { wx.pendingModal = opt }
  const page = loadPage('miniapp-delivery/pages/station-mgmt/business-waiting/index.js', { wx, app, stubs: { 'api/business-rules': api, 'utils/pending-reminder': { syncPendingReminder: async () => {} } } })
  await page.onShow()
  assert.equal(reads, 0); assert.equal(page.data.ticketOpen, false)
  await page.onOpenTickets(); assert.equal(reads, 1); assert.equal(page.data.tickets.length, 1)
  const e = { currentTarget: { dataset: { id: 7 } } }
  page.onTicketRefund(e); page.onTicketRefund(e); assert.equal(page.data.ticketBusy, true)
  const pending = wx.pendingModal.success({ confirm: true }); assert.equal(refunds, 1)
  page.onTicketRefund(e); assert.equal(refunds, 1)
  resolveRefund(response(null)); await pending
  assert.equal(page.data.tickets.length, 0); assert.equal(reads, 2)
  fail = true; await page.onOpenTickets(); assert.equal(page.data.tickets.length, 0); assert.equal(page.data.ticketError, '离线')
  page.onCloseTickets(); assert.equal(page.data.ticketOpen, false)
  fail = false; await page.onOpenTickets(); assert.equal(page.data.ticketError, '')
  page.onHide(); await page.onShow(); assert.equal(page.data.ticketOpen, false); assert.equal(page.data.ticketLoading, false)
  console.log('AQUAFLOW_SUITE_OK 1'); done()
}
main().catch(e => { console.error(e); process.exitCode = 1; done() })
