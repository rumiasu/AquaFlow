const assert = require('assert')
const { loadPage, createWx, createApp, armWatchdog } = require('./harness')
const done = armWatchdog(30000)
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const manager = () => createApp({ _loginGeneration: 1, globalData: { userInfo: { role: 'STATION_MANAGER', staffId: 11, stationId: 1 } }, isStationManager: () => true })

async function run() {
  let payment = async () => { throw Error('offline') }
  const app = manager()
  const detail = loadPage('miniapp-delivery/pages/order/detail.js', { app, wx: createWx(), stubs: {
    'api/delivery': {}, 'utils/request': { get: (...args) => payment(...args) }
  } })
  detail.setData({ orderId: 20, order: { id: 20 }, loading: false })
  await detail.loadPayments(20)
  assert.equal(detail.data.paymentsState, 'error'); assert.equal(detail.data.canRefundPayment, false)
  payment = async () => ({ data: [] }); await detail.onRetryPayments()
  assert.equal(detail.data.paymentsState, 'ready'); assert.deepEqual(detail.data.payments, [])
  payment = async () => ({ data: [{ id: 1, status: 2, amount: 0 }] }); await detail.onRetryPayments()
  assert.equal(detail.data.payments[0].canRefund, true)
  payment = async () => ({ data: null }); await detail.onRetryPayments()
  assert.equal(detail.data.paymentsState, 'error'); assert.equal(detail.data.canRefundPayment, false)
  console.log('PASS payment failure, empty, zero and malformed data remain distinct')

  let cross = async () => { throw Error('offline') }
  const orders = loadPage('miniapp-delivery/pages/station-mgmt/orders/index.js', { app, wx: createWx(), stubs: {
    'api/station-mgmt': { getOrders: async () => ({ data: [{ id: 1, status: 1 }] }), getCrossStationOrders: () => cross() },
    'utils/request': { get: async () => ({ data: [] }) }
  } })
  await orders.loadData()
  assert.equal(orders.data.list.length, 1); assert.equal(orders.data.crossState, 'error')
  cross = async () => ({ data: { count: 0, orders: [] } }); await orders.onRetryCrossStation()
  assert.equal(orders.data.crossState, 'ready'); assert.equal(orders.data.crossStation.count, 0)
  const old = deferred(); cross = () => old.promise; const pending = orders.onRetryCrossStation()
  cross = async () => ({ data: { count: 1, orders: [{ id: 2 }] } }); await orders.onRetryCrossStation()
  old.reject(Error('late')); await pending
  assert.equal(orders.data.crossStation.count, 1); assert.equal(orders.data.crossState, 'ready')
  console.log('PASS cross-station partial failure, retry and late failure')

  let reads = 0, modal
  const status = loadPage('miniapp-delivery/pages/station-mgmt/station-status/index.js', { app, wx: createWx(), stubs: {
    'utils/business-dialog': { showBusinessModal: opts => { modal = opts } },
    'api/station-mgmt': { getStationStatus: async () => { reads++; return { data: { operatingStatus: 1, statusText: '营业中', note: '原留言' } } }, getNotices: async () => ({ data: [] }), updateStationStatus: async () => { throw Error('offline') } }
  } })
  await status.loadStatus(); status._statusOwner = status.statusOwner()
  status.onNoteInput({ detail: { value: '未保存留言' } }); status.onShow()
  assert.equal(reads, 1); assert.equal(status.data.noteInput, '未保存留言')
  await status.onSaveStatus(); assert.equal(status.data.statusLoaded, false)
  await status.loadStatus(); assert.equal(status.data.noteInput, '未保存留言'); assert.equal(status.data.current.note, '原留言')
  // The shared harness does not implement WeChat's nested setData paths.
  status.openAddNotice(); status.setData({ editForm: { title: '草稿', content: '' } })
  status.closeEditNotice(); assert.equal(status.data.showEdit, true); modal.success({ confirm: false }); assert.equal(status.data.showEdit, true)
  modal.success({ confirm: true }); assert.equal(status.data.showEdit, false)
  console.log('PASS same-station draft and notice close confirmation')

  const customer = loadPage('miniapp-delivery/pages/station-mgmt/customers/detail/index.js', { app, wx: createWx(), stubs: {
    'api/station-mgmt': {}, 'utils/request': {}, 'utils/return-empty-intent': {}
  } })
  const asset = customer.decorateAssets({ heldBuckets: 0, owedBuckets: null, ticketQuantity: undefined, barrels: [], tickets: [], records: [] })
  assert.equal(asset.heldBucketsDisplay, '0'); assert.equal(asset.owedBucketsDisplay, '待核实'); assert.equal(asset.ticketQuantityDisplay, '待核实')
  console.log('PASS asset summary distinguishes zero and missing counts')
  let riskModal
  const coordination = loadPage('miniapp-delivery/pages/coordination/index.js', { app, wx: createWx(), stubs: {
    'utils/request': { get: async () => { throw Error('offline') } },
    'utils/business-dialog': { showBusinessModal: opts => { riskModal = opts } }, 'api/delivery': {}
  } })
  const risk = coordination._confirmRisk(9, '确认', 'assign')
  await new Promise(resolve => setImmediate(resolve)); assert.equal(riskModal.title, '风险提示未读取')
  riskModal.success({ confirm: true }); assert.deepEqual(await risk, { ok: true, acknowledged: false })
  const cancelRisk = coordination._confirmRisk(9, '确认', 'assign')
  await new Promise(resolve => setImmediate(resolve)); riskModal.success({ confirm: false })
  assert.deepEqual(await cancelRisk, { ok: false, acknowledged: false })
  console.log('PASS unread risk remains unknown, cancel stops and continue never claims acknowledgement')

  const quoteWait = deferred()
  const place = loadPage('miniapp-delivery/pages/station-mgmt/place-order/index.js', { app, wx: createWx(), stubs: {
    'utils/request': { post: () => quoteWait.promise }
  } })
  place.setData({ stationId: 1, step: 3, customer: { id: 4 }, addressId: 5,
    products: [{ id: 7, name: '水', selectedQty: 1 }], cartLines: [{ productId: 7, quantity: 1 }], totalQty: 1 })
  const quote = place.onQuote()
  place.onQty({ currentTarget: { dataset: { id: 7, delta: 1 } } })
  quoteWait.resolve({ data: { totalAmount: 10, methods: [] } }); await quote
  assert.equal(place.data.quote, null); assert.equal(place.data.cartLines[0].quantity, 2); assert.equal(place.data.products[0].selectedQty, 2)
  place.setData({ unknownOrder: true }); place.onQty({ currentTarget: { dataset: { id: 7, delta: 1 } } })
  assert.equal(place.data.cartLines[0].quantity, 2)
  console.log('PASS stale quote is discarded and unknown-order recovery locks quantity')

  let editDialog, writes = []
  const editPage = loadPage('miniapp-delivery/pages/order/detail.js', { app, wx: createWx(), stubs: {
    'api/delivery': {}, 'utils/request': { get: async () => ({ data: [] }) },
    'utils/business-dialog': { showBusinessModal: opts => { editDialog = opts } },
    'api/business-rules': { getAgreement: async () => ({ data: { serviceAmount: 0, barrelMode: 'RETURN_EMPTY', barrelAmount: 0 } }), quoteAgreement: async (...args) => { writes.push(args) } }
  } })
  editPage.setData({ orderId: 20, order: { id: 20 } }); editPage.loadOrderDetail = async () => {}
  let edit = editPage.onEditDispatchQuote(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(editDialog.initialValue, '0'); assert.ok(editDialog.description.includes('¥0'))
  editDialog.success({ confirm: false }); await edit; assert.equal(writes.length, 0)
  edit = editPage.onEditDispatchQuote(); await new Promise(resolve => setImmediate(resolve))
  editPage.onHide(); editDialog.success({ confirm: true, content: '10' }); await edit; assert.equal(writes.length, 0)
  console.log('PASS editable quote preserves zero and cancellation or hide cannot write')

  const owed = loadPage('miniapp-delivery/pages/station-mgmt/owed-barrels/index.js', { app, wx: createWx(), stubs: {
    'api/station-mgmt': { getOwedBarrels: async () => ({ code: 0, data: [{ customerId: 1, productId: 2, overQty: 2 }, { customerId: 1, productId: 3, overQty: 1 }, { customerId: 4, productId: 2, overQty: 3 }] }) }
  } })
  await owed.loadData(); assert.equal(owed.data.groups.length, 2); assert.equal(owed.data.groups[0].items.length, 2)
  assert.deepEqual(owed.data.groups[0].items.map(x => x.overQty), [2, 1])
  console.log('PASS owed records group by customer without netting different products')

  let rejectDialog, reviews = []
  const customers = loadPage('miniapp-delivery/pages/station-mgmt/customers/index.js', { app, wx: createWx(), stubs: {
    'api/station-mgmt': {}, 'utils/business-dialog': { showBusinessModal: opts => { rejectDialog = opts } }
  } })
  customers.setData({ entApplies: [{ id: 3 }] }); customers.doReview = (...args) => reviews.push(args)
  customers.onEntReject({ currentTarget: { dataset: { id: 3, name: '企业' } } })
  assert.equal(rejectDialog.editable, true); assert.equal(rejectDialog.initialValue, '')
  rejectDialog.success({ confirm: false, content: '原因' }); assert.equal(reviews.length, 0)
  app._loginGeneration++; rejectDialog.success({ confirm: true, content: '原因' }); assert.equal(reviews.length, 0)
  customers.onEntReject({ currentTarget: { dataset: { id: 3, name: '企业' } } })
  rejectDialog.success({ confirm: true, content: ' 原因 ' }); assert.deepEqual(reviews[0], [3, false, '原因'])
  console.log('PASS rejection combines context and reason, and rejects stale login')
  console.log('AQUAFLOW_SUITE_OK 9'); done()
}
run().catch(err => { console.error(err); done(); process.exitCode = 1 })
