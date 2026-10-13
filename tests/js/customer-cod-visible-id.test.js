'use strict'
// Actual Page handlers with synthetic memory APIs. WXML assertions inspect bindings
// and substitute their values only; they do not verify native rendering or layout.
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createApp, createWx, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(), tests = []
const test = (name, run) => tests.push({ name, run })
const pagePath = 'miniapp-delivery/pages/station-mgmt/customers/index.js'
const wxml = fs.readFileSync(path.join(ROOT, pagePath.replace(/\.js$/, '.wxml')), 'utf8')
const cases = [
  { name: 'missing addresses', addresses: [undefined, undefined] },
  { name: 'same addresses', addresses: ['合成地址甲', '合成地址甲'] },
  { name: 'different addresses', addresses: ['合成地址甲', '合成地址乙'] }
]
const event = row => ({ currentTarget: { dataset: { id: row.id, name: row.name } } })
function pending() {
  let resolve, reject
  const promise = new Promise((a, b) => { resolve = a; reject = b })
  return { promise, resolve, reject }
}
function fixture(addresses = cases[0].addresses) {
  const rows = [101, 202].map((id, index) => ({ id, name: '合成同名客户', phone: '',
    ...(addresses[index] === undefined ? {} : { addressText: addresses[index] }) }))
  const reads = [], writes = [], listReads = [], wx = createWx()
  const app = createApp({ _loginGeneration: 1, globalData: { isLogin: true,
    accessToken: 'synthetic-token', userInfo: { staffId: 11, role: 'STATION_MANAGER',
      stationId: 11, bindStatus: 'BOUND' } } })
  const page = loadPage(pagePath, { app, wx, stubs: { 'api/station-mgmt': {
    getCustomers: async (stationId, keyword) => {
      listReads.push({ stationId, keyword }); return { code: 0, data: rows }
    },
    getEnterpriseApplies: async () => ({ code: 0, data: [] }),
    getEnterpriseConfig: async () => ({ code: 0, data: { enabled: false } }),
    getOfflinePaymentSummary: id => {
      const d = pending(); reads.push({ id, ...d }); return d.promise
    },
    updateOfflinePayment: (id, body) => {
      const d = pending(); writes.push({ id, body, ...d }); return d.promise
    }
  } } })
  // Use native dotted-path semantics without replacing any Page handler.
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {})
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  return { page, app, wx, rows, reads, writes, listReads }
}
function summary(t, index, marker = 0) {
  t.reads[index].resolve({ code: 0, data: { offlinePaymentEnabled: 0, usable: false,
    orderCount: marker, overdueCount: 0, overdueAmount: '0.00', blockReason: '' } })
}
async function open(t, row, marker = 0) {
  const task = t.page.onCod(event(row)); summary(t, t.reads.length - 1, marker); await task
}
// These are bounded text-binding checks, not a WXML renderer. Reject a conditional
// or interactive number line so missing phone/address cannot hide the identity cue.
function numberText(kind, data) {
  const re = kind === 'list'
    ? /<text\b([^>]*\bclass="customer-phone scope-label customer-number"[^>]*)>(客户编号：[^<]*)<\/text>/g
    : /<view\b([^>]*\bclass="scope-line"[^>]*)>(客户编号：[^<]*)<\/view>/g
  const matches = [...wxml.matchAll(re)]
  assert.strictEqual(matches.length, 1, kind + ' must contain one visible number text node')
  const [, attrs, text] = matches[0]
  assert.ok(!/\b(?:wx:if|wx:elif|wx:else|hidden|style|bind\w+|catch\w+)\b/.test(attrs),
    kind + ' identity must not depend on phone/address or be an action')
  const expression = kind === 'list' ? 'item.id' : 'codModal.customerId'
  assert.strictEqual(text, '客户编号：{{' + expression + '}}')
  if (kind === 'modal' && !data.codModal.visible) return null
  const value = kind === 'list' ? data.item.id : data.codModal.customerId
  return text.replace('{{' + expression + '}}', String(value))
}
test('number bindings sit after name and before phone/current COD result', () => {
  numberText('list', { item: { id: 101 } })
  numberText('modal', { codModal: { visible: true, customerId: 101 } })
  const listNumber = wxml.indexOf('class="customer-phone scope-label customer-number"')
  assert.ok(listNumber > wxml.indexOf('{{item.name}}'))
  assert.ok(listNumber < wxml.indexOf('wx:if="{{item.phone}}"'))
  const modalNumber = wxml.indexOf('客户编号：{{codModal.customerId}}')
  assert.ok(modalNumber > wxml.indexOf('客户：{{codModal.customerName}}'))
  assert.ok(modalNumber < wxml.indexOf('本次核实结果：{{codModal.usableText}}'))
})
for (const c of cases) {
  test(c.name + ': real list handler retains distinct numbers for same-name blank-phone rows', async () => {
    const t = fixture(c.addresses); await t.page.loadData()
    assert.strictEqual(t.page.data.loading, false)
    assert.strictEqual(t.page.data.loadError, '')
    assert.deepStrictEqual(t.listReads, [{ stationId: 11, keyword: '' }])
    assert.deepStrictEqual(t.page.data.list.map(row => row.name), ['合成同名客户', '合成同名客户'])
    assert.ok(t.page.data.list.every(row => row.phone === ''))
    assert.deepStrictEqual(t.page.data.list.map(row => row.addressText), c.addresses)
    assert.deepStrictEqual(t.page.data.list.map(item => numberText('list', { item })),
      ['客户编号：101', '客户编号：202'])
  })
  test(c.name + ': real COD handler shows the selected number despite identical names/phones', async () => {
    const t = fixture(c.addresses); await t.page.loadData()
    for (const row of t.page.data.list) {
      await open(t, row)
      assert.strictEqual(t.page.data.codModal.customerName, '合成同名客户')
      assert.strictEqual(t.page.data.codModal.customerPhoneText, '未填写电话')
      assert.strictEqual(numberText('modal', t.page.data), '客户编号：' + row.id)
    }
    assert.deepStrictEqual(t.reads.map(read => read.id), [101, 202])
  })
  test(c.name + ': switching from A to B saves only B and the original permission payload', async () => {
    const t = fixture(c.addresses); await t.page.loadData()
    await open(t, t.rows[0]); await open(t, t.rows[1])
    t.page.onCodToggle({ detail: { value: true } })
    const save = t.page.onCodSave()
    assert.strictEqual(numberText('modal', t.page.data), '客户编号：202')
    assert.deepStrictEqual(t.writes.map(({ id, body }) => ({ id, body })),
      [{ id: 202, body: { offlinePaymentEnabled: 1 } }])
    t.writes[0].resolve({ code: 0 }); await save
    assert.strictEqual(numberText('modal', t.page.data), null)
    assert.strictEqual(t.listReads.length, 2)
  })
}
test('pending B closes the A number and cannot save A while reading B', async () => {
  const t = fixture(); await t.page.loadData(); await open(t, t.rows[0])
  const b = t.page.onCod(event(t.rows[1]))
  assert.strictEqual(numberText('modal', t.page.data), null)
  await t.page.onCodSave(); assert.strictEqual(t.writes.length, 0)
  summary(t, 1); await b
  assert.strictEqual(numberText('modal', t.page.data), '客户编号：202')
})
test('late same-name A read cannot change B number or its save target', async () => {
  const t = fixture(); await t.page.loadData()
  const a = t.page.onCod(event(t.rows[0])), b = t.page.onCod(event(t.rows[1]))
  summary(t, 1, 2); await b; summary(t, 0, 1); await a
  assert.strictEqual(numberText('modal', t.page.data), '客户编号：202')
  assert.strictEqual(t.page.data.codModal.orderCount, 2)
  const save = t.page.onCodSave(); assert.strictEqual(t.writes[0].id, 202)
  t.writes[0].resolve({ code: 0 }); await save
})
test('A-B-A retains the newest A generation even though name and number match old A', async () => {
  const t = fixture(); await t.page.loadData()
  const a1 = t.page.onCod(event(t.rows[0])), b = t.page.onCod(event(t.rows[1])),
    a2 = t.page.onCod(event(t.rows[0]))
  summary(t, 2, 3); await a2; summary(t, 1, 2); await b; summary(t, 0, 1); await a1
  assert.strictEqual(numberText('modal', t.page.data), '客户编号：101')
  assert.strictEqual(t.page.data.codModal.orderCount, 3)
})
for (const leave of ['onCodClose', 'onHide', 'onUnload']) {
  test(leave + ' prevents a late same-name read from restoring a visible number or saving', async () => {
    const t = fixture(); await t.page.loadData(); await open(t, t.rows[0])
    const b = t.page.onCod(event(t.rows[1])); t.page[leave]()
    summary(t, 1); await b; await t.page.onCodSave()
    assert.strictEqual(numberText('modal', t.page.data), null)
    assert.strictEqual(t.writes.length, 0)
  })
}
for (const field of ['staffId', 'stationId', 'role', 'bindStatus', 'loginGeneration']) {
  test(field + ' changes still reject the old identity read and old identity save', async () => {
    const change = t => field === 'loginGeneration' ? t.app._loginGeneration++
      : t.app.globalData.userInfo[field] = 'changed'
    const t = fixture(); await t.page.loadData()
    const read = t.page.onCod(event(t.rows[1])); change(t); summary(t, 0); await read
    assert.strictEqual(numberText('modal', t.page.data), null)
    const s = fixture(); await s.page.loadData(); await open(s, s.rows[1]); change(s)
    await s.page.onCodSave(); assert.strictEqual(s.writes.length, 0)
  })
}
test('late A save completion cannot close B number or refresh its list', async () => {
  const t = fixture(); await t.page.loadData(); await open(t, t.rows[0])
  const save = t.page.onCodSave(); await open(t, t.rows[1])
  t.writes[0].resolve({ code: 0 }); await save
  assert.strictEqual(numberText('modal', t.page.data), '客户编号：202')
  assert.strictEqual(t.listReads.length, 1)
  assert.strictEqual(t.wx.__calls.toast.length, 0)
})
test('token renewal preserves current B number and repeat saves remain single flight', async () => {
  const t = fixture(); await t.page.loadData()
  const b = t.page.onCod(event(t.rows[1])); t.app.globalData.accessToken = 'synthetic-renewed'
  summary(t, 0); await b
  assert.strictEqual(numberText('modal', t.page.data), '客户编号：202')
  const first = t.page.onCodSave(), second = t.page.onCodSave()
  assert.strictEqual(t.writes.length, 1); assert.strictEqual(t.writes[0].id, 202)
  t.writes[0].resolve({ code: 0 }); await Promise.all([first, second])
})
;(async () => {
  let failed = 0
  for (const { name, run } of tests) {
    try { await run(); console.log('PASS ' + name) }
    catch (err) { failed++; console.error('FAIL ' + name + ': ' + err.message) }
  }
  done()
  if (failed) { console.error(failed + ' failed'); process.exitCode = 1 }
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().catch(err => { done(); console.error(err); process.exitCode = 1 })
