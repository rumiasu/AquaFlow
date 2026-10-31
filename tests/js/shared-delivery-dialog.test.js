'use strict'
// Real default dispatcher, BusinessDialog and adapter; only wx, session and API/recovery boundaries are synthetic.
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert')
const root = path.resolve(__dirname, '../..'), tick = () => new Promise(resolve => setImmediate(resolve))
function fixture() {
  let definition, sheet, writes = 0, finished = 0, done = 0
  const native = [], app = { _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 8, stationId: 1, role: 'DELIVERY', bindStatus: 'BOUND' } }, canAccessStationBusiness: () => true }
  const wx = { showActionSheet: options => { sheet = options }, showModal: options => native.push(options),
    getAppBaseInfo: () => ({ theme: 'light' }), hideKeyboard() {}, showLoading() {}, hideLoading() {}, showToast() {} }
  const source = file => fs.readFileSync(path.join(root, 'miniapp-delivery', file), 'utf8')
  vm.runInNewContext(source('components/BusinessDialog/index.js'), { wx, Component: value => { definition = value } })
  const component = { data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
  for (const [key, method] of Object.entries(definition.methods)) component[key] = method.bind(component)
  definition.lifetimes.attached.call(component)
  const adapter = { exports: {} }
  vm.runInNewContext(source('utils/business-dialog.js'), { module: adapter, wx, getCurrentPages: () => [{ selectComponent: () => component }] })
  const body = { reason: '客户不接电话', reasonKey: 'customer_unreachable', clientRequestId: 'DR-synthetic-original' }
  const recovery = { read: () => null, reportPayload: value => value ? body : null, beginReport: () => { writes++; return { value: {} } }, finish: () => { finished++ }, uncertain: () => false }
  const reportModule = { exports: {} }
  vm.runInNewContext(source('utils/delivery-problem.js'), { module: reportModule, wx, getApp: () => app,
    require(id) {
      if (id === './business-dialog') return adapter.exports
      if (id === './order-write-recovery') return recovery
      if (id === '../api/delivery') return { reportOrder: async (id, sent) => {
        assert.equal(sent, body)
        return { code: 0, data: { id: 70, orderId: id, clientRequestId: sent.clientRequestId } }
      } }
      throw new Error('Unexpected dependency: ' + id)
    } })
  return { app, component, native, definition, report: () => reportModule.exports.reportDeliveryProblem(20, { onDone: () => { done++ } }),
    choose: () => sheet.success({ tapIndex: 0 }), cancelSheet: () => sheet.fail({ errMsg: 'showActionSheet:fail cancel' }),
    get counts() { return { writes, finished, done } } }
}
async function main() {
  let f = fixture(), p = f.report()
  f.cancelSheet(); await p
  assert.equal(f.counts.writes, 0)
  f = fixture(); p = f.report(); f.choose(); await tick()
  assert.equal(f.native.length, 0, 'default Modal must reach the actual shared component')
  assert.equal(f.component.data.visible, true)
  f.component.onCancel(); await p
  assert.equal(f.counts.writes, 0)
  f = fixture(); p = f.report(); f.choose(); await tick()
  f.definition.pageLifetimes.hide.call(f.component); await p
  f.component.onConfirm(); assert.equal(f.counts.writes, 0)
  f = fixture(); p = f.report(); f.choose(); await tick()
  f.app._loginGeneration++; f.component.onConfirm(); await p
  assert.equal(f.counts.writes, 0, 'a late confirmation must retain the original session guard')
  f = fixture(); p = f.report(); const duplicate = f.report(); f.choose(); await tick()
  f.component.onConfirm(); f.component.onConfirm(); await Promise.all([p, duplicate])
  assert.deepStrictEqual(f.counts, { writes: 1, finished: 1, done: 1 })
  console.log('PASS shared delivery default uses real BusinessDialog, keeps native sheet and rejects cancel/hide/stale/duplicate writes')
  console.log('AQUAFLOW_SUITE_OK 5')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
