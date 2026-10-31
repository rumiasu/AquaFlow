'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { parseWxml, renderElements } = require('./wxml-tree')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
function view(route, state) {
  const page = loadPage(route + '.js', { wx: createWx(), app: createApp() })
  const source = fs.readFileSync(path.join(ROOT, route + '.wxml'), 'utf8')
  return renderElements(parseWxml(source), { ...page.data, ...state }, { includeText: true })
}
const text = nodes => nodes.map(n => n.text || '').join('\n')
const addressRoute = 'miniapp-user/pages/address/list'
const loadingAddress = view(addressRoute, { loading: true, loaded: true, addresses: [] })
assert(text(loadingAddress).includes('上次加载的地址，不可选择'))
assert(!text(loadingAddress).includes('暂无收货地址'))
const failedAddress = view(addressRoute, { loading: false, loaded: false, loadError: '连接失败', addresses: [] })
assert(text(failedAddress).includes('地址读取失败')); assert(!text(failedAddress).includes('暂无收货地址'))
const pending = view('miniapp-delivery/pages/station-mgmt/payments/index', { view: 'pending', list: [], loading: false })
const pendingText = text(pending)
assert(pendingText.includes('核对收款方及款项后登记'))
assert(pendingText.includes('待确认不代表已到账'))
// The redesigned list uses one actionable caution; transaction/channel facts are checked in payment suites.
const pendingRow = view('miniapp-delivery/pages/station-mgmt/payments/index', { view: 'pending', list: [{ id: 7, amountText: '1.00' }], loading: false })
assert(pendingRow.some(n => n.tag === 'button' && n.attrs.bindtap === 'onConfirm')); assert(!pendingText.includes('同意购票'))
const history = view('miniapp-delivery/pages/station-mgmt/payments/index', { view: 'ticketHistory', list: [], loading: false })
assert(!text(history).includes('待确认不代表已到账'))
const feedbackBase = { activeSection: 'feedback', denied: false, list: [], disputes: [], type: 'error', errMsg: '连接失败' }
const failedFeedback = view('miniapp-delivery/pages/station-mgmt/customer-feedback/index', feedbackBase)
assert(text(failedFeedback).includes('加载失败')); assert(text(failedFeedback).includes('连接失败'))
assert(!text(failedFeedback).includes('暂无客户反馈'))
assert(failedFeedback.some(n => n.attrs.bindtap === 'onRetry'))
const dispute = { refundType: 'BARREL_RETURN', refundId: 12, statusText: '待处理', objectText: '退押金', canClose: true, actions: [] }
const disputeView = view('miniapp-delivery/pages/station-mgmt/customer-feedback/index', { ...feedbackBase, activeSection: 'disputes', type: 'empty', disputes: [dispute], selectedDispute: dispute, disputeReady: true })
const disputeText = text(disputeView)
for (const phrase of ['普通反馈仅供查看', '结案不会办理退款或改变金额', '顾客可再次提出异议', '结案无需顾客确认', '不取消顾客既有权利']) assert(disputeText.includes(phrase), phrase)
assert(disputeView.some(n => n.attrs.bindtap === 'onCloseDispute'))
console.log('AQUAFLOW_SUITE_OK 1')
