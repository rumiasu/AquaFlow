const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(15000), tick = () => new Promise(resolve => setImmediate(resolve))
function makeDialog(wx) {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'miniapp-delivery/components/BusinessDialog/index.js'), 'utf8'), { Component: value => { definition = value }, wx, Promise })
  const component = { data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
  for (const [name, fn] of Object.entries(definition.methods)) component[name] = fn.bind(component)
  definition.lifetimes.attached.call(component); definition.pageLifetimes.show.call(component)
  return { component, definition }
}
async function main() {
  const wx = createWx(), app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 5, stationId: 1, role: 'STATION_MANAGER' } } }), writes = []
  const page = loadPage('miniapp-delivery/pages/station-mgmt/customers/adjust/detail/index.js', { wx, app, stubs: { 'api/station-mgmt': { reverseAdjustment: async (...args) => { writes.push(args); return { data: { id: 20, adjustNo: 'R20' } } } } } })
  page.setData({ detail: { id: 9, adjustNo: 'A9', adjustTypeText: '补录', hasQty: true, qtyText: '+2', isEffective: true }, reverseClientToken: 'original-key' }); page.loadData = () => {}; page.onShow()
  global.getCurrentPages = () => [{}]
  page.onReverse(); await tick(); assert.equal(writes.length, 0); assert.equal(page._reversePrompt, null); assert.equal(wx.__calls.modal.length, 0)
  console.log('PASS D21 F native fallback cancels unsupported editable description without writing')
  const { component, definition } = makeDialog(wx); global.getCurrentPages = () => [{ selectComponent: () => component }]
  page.onReverse(); assert.equal(component.data.input, ''); assert(component.data.description.includes('A9')); assert(component.data.description.includes('+2'))
  component.onInput({ detail: { value: '保留的原因' } }); component.onCancel(); await tick(); assert.equal(writes.length, 0)
  page.onReverse(); assert.equal(component.data.input, '保留的原因'); component.onInput({ detail: { value: '核对差异' } }); component.onConfirm(); component.onConfirm(); await tick()
  assert.equal(writes.length, 1); assert.equal(writes[0][0], 9); assert.equal(writes[0][1], '核对差异'); assert.equal(writes[0][2], 'original-key')
  definition.lifetimes.detached.call(component)
  console.log('PASS D21 F component separates description, empty initial input, cancel and single confirmation')
  done(); console.log('AQUAFLOW_SUITE_OK 2')
}
main().catch(error => { done(); console.error(error); process.exitCode = 1 })
