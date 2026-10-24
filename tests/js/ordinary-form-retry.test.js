'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { fixture, deferred, event, tap, render } = require('./customer-profile-visible-fixture')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
const { parseWxml, validateConditionalSiblings } = require('./wxml-tree')
const tests = [], test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const lost = () => Object.assign(Error('synthetic response lost'), { requestMayHaveOccurred: true })
const company = { id: 1, companyName: '合成企业', contactPerson: '合成人员', contactPhone: '', paymentMethod: '', dueDays: null }
const form = { name: '合成客户', phone: '13800000000', province: '', city: '', district: '', detail: '合成地址', label: '', isDefault: false,
  lat: null, lng: null, floor: '', hasElevator: null }
const address = { ...form, id: 3, customerId: 7, floor: null, isDefault: 0 }

test('company response loss allows an explicit retry and cancel without a comparison task', async () => {
  let writes = 0
  const f = fixture('company', { api: { getCompanyInfo: async () => ok(company), updateCompanyInfo: async () => { writes++; throw lost() } } })
  await f.p.loadInfo(); f.p.onStartEdit(); f.p.onInput(event('companyName', '合成修改'))
  await f.p.onSave(); await f.p.onSave()
  assert.equal(writes, 2); assert.equal(f.p.data.form.companyName, '合成修改'); assert.equal(f.p.data.saveSucceeded, false)
  assert(!render('company', f.p.data).includes('已核对，继续编辑'))
  f.p.onCancelEdit(); assert.equal(f.p.data.editing, false); assert.equal(f.calls.modal.length, 0)
})
test('existing address update can be retried without losing input or declaring false success', async () => {
  let writes = 0
  const f = fixture('edit', { api: { getAddressDetail: async () => ok(address), updateAddress: async () => { if (++writes === 1) throw lost(); return ok(null) } } })
  f.p.setData({ isEdit: true, addressId: 3 }); await f.p.loadAddress(3)
  f.p.onInputChange(event('detail', '合成新门牌'))
  await f.p.onSubmit(); assert.equal(f.p.data.saveSucceeded, false); assert.equal(f.p.data.formData.detail, '合成新门牌')
  await f.p.onSubmit(); assert.equal(writes, 2); assert.equal(f.p.data.saveSucceeded, true)
})
test('unacknowledged address create is reused after one owned list read proves the desired address exists', async () => {
  let writes = 0, reads = 0, body
  const f = fixture('edit', { api: { createAddress: async payload => { writes++; body = payload; throw lost() },
    getAddresses: async () => { reads++; return ok([{ ...body, id: 33 }]) } } })
  f.p.setData({ formData: { ...form } })
  await f.p.onSubmit(); assert.equal(writes, 1); assert.equal(reads, 0); assert.equal(f.p.data.saveSucceeded, false)
  await f.p.onSubmit(); assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(f.p.data.saveSucceeded, true)
})
for (const change of [{ id: undefined }, { floor: 0 }, { hasElevator: 0 }, { isDefault: 1 }]) test('address lookup does not claim a match for ' + JSON.stringify(change), async () => {
  let writes = 0
  const f = fixture('edit', { api: { createAddress: async () => { if (++writes === 1) throw lost(); return ok({ id: 9 }) },
    getAddresses: async () => ok([{ ...address, ...change }]) } })
  f.p.setData({ formData: { ...form } }); await f.p.onSubmit(); await f.p.onSubmit()
  assert.equal(writes, 2, 'only a valid matching resource avoids a second explicit POST')
})
test('failed address lookup never claims success and permits only the user requested create retry', async () => {
  let writes = 0, reads = 0
  const f = fixture('edit', { api: { createAddress: async () => { writes++; throw lost() }, getAddresses: async () => { reads++; throw lost() } } })
  f.p.setData({ formData: { ...form } }); await f.p.onSubmit()
  assert.equal(writes, 1); assert.equal(reads, 0)
  await f.p.onSubmit(); assert.equal(writes, 2); assert.equal(reads, 1); assert.equal(f.p.data.saveSucceeded, false)
})
test('default address response loss refreshes once automatically without a manual verification panel', async () => {
  let reads = 0, changed = false
  const f = fixture('address', { api: { getAddresses: async () => { reads++; return ok([{ ...address, isDefault: changed ? 1 : 0 }]) },
    setDefaultAddress: async () => { changed = true; throw lost() } } })
  await f.p.loadAddresses(); await f.p.onSetDefault(tap({ id: 3 }))
  assert.equal(reads, 2); assert.equal(f.p.data.addresses[0].isDefault, 1)
  assert.equal(f.p.data.actionUnknown, false); assert(!render('address', f.p.data).includes('刷新并核对'))
})
test('session change during retry lookup cannot create an address for the new identity', async () => {
  let writes = 0; const gate = deferred()
  const f = fixture('edit', { api: { createAddress: async () => { writes++; throw lost() }, getAddresses: () => gate.promise } })
  f.p.setData({ formData: { ...form } }); await f.p.onSubmit()
  const pending = f.p.onSubmit(); f.switchSession(); gate.resolve(ok([])); await pending
  assert.equal(writes, 1); assert.equal(f.p.data.saveSucceeded, false); assert.equal(f.p.data.sessionChanged, true)
})
test('ordinary form retries retain their in-flight lock', async () => {
  let writes = 0; const gate = deferred()
  const f = fixture('company', { api: { getCompanyInfo: async () => ok(company), updateCompanyInfo: () => { writes++; return gate.promise } } })
  await f.p.loadInfo(); f.p.onStartEdit(); const pending = f.p.onSave(); await f.p.onSave(); f.p.onCancelEdit()
  assert.equal(writes, 1); assert.equal(f.p.data.editing, true); gate.resolve(ok(company)); await pending
})
for (const [route, flag, handler] of [['shop','showStationPicker','onStationPickerBeforeLeave'], ['template','showEditModal','onTemplateBeforeLeave']]) {
  test(route + ' native container Back closes the actual panel without navigating', () => {
    const wx = createWx(), p = loadPage('miniapp-user/pages/' + route + '/index.js', { wx, app: createApp() })
    const file = path.join(ROOT, 'miniapp-user/pages/' + route + '/index.wxml'), tree = parseWxml(fs.readFileSync(file, 'utf8'))
    validateConditionalSiblings(tree)
    const containers = []
    function visit(n) { if (n.tag === 'page-container') containers.push(n); for (const c of n.children || []) visit(c) }
    visit(tree); assert.equal(containers.length, 1)
    assert.equal(containers[0].attrs.show, '{{' + flag + '}}'); assert.equal(containers[0].attrs.bindbeforeleave, handler)
    p.setData({ [flag]: true, submitting: true, formLoading: true, form: { name: '合成未保存内容' } })
    p[handler](); assert.equal(p.data[flag], false); assert.equal(wx.__calls.nav.length, 0)
    assert.equal(p.data.form.name, '合成未保存内容'); assert.equal(p.data.submitting, true)
  })
}

async function main() {
  const results = [], timer = setTimeout(() => { console.error('ORDINARY_FORM_TIMEOUT'); process.exit(1) }, 30000)
  try {
    for (const t of tests) { try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, pass: false, error: error.message }); console.log('FAIL ' + t.name + ': ' + error.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    if (results.some(r => !r.pass)) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
