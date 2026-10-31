'use strict'
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { ROOT, scope, fixture, deferred, event, tap, parse, visible, render } = require('./ua-customer-profile-visible-fixture')
const tests = []
const test = (name, run) => tests.push({ name, run })
const ok = data => ({ code: 0, data })
const rejected = () => Object.assign(new Error('资料校验未通过'), { businessRejected: true, code: 1 })
const unavailable = () => Object.freeze(Object.assign(new Error('request:fail timeout'), { code: 'NETWORK_TIMEOUT', retryable: true }))
const address = { id: 3, name: 'Alice 原名', phone: '13800000000', district: '西湖区', detail: 'A 座 603', isDefault: 0 }
const company = { id: 1, companyName: 'Acme 原公司', contactPerson: 'Alice', contactPhone: '13800000000', paymentMethod: '月结', dueDays: 0 }
const loaders = {
  address: { method: 'loadAddresses', api: 'getAddresses', empty: [], key: 'addresses', word: '还没有收货地址', row: address },
  notice: { method: 'loadNotices', api: 'getNotices', empty: [], key: 'notices', word: '暂时没有公告', row: { id: 3, title: '关于送水安排的公告', type: 2 } },
  company: { method: 'loadInfo', api: 'getCompanyInfo', empty: null, key: 'info', word: '尚未登记企业资料', row: company },
  exception: { method: 'loadExceptions', api: 'get', empty: { records: [] }, key: 'exceptions', word: '暂无异常记录', row: { id: 3, orderId: 18, status: 'MANAGER_PENDING', statusText: '处理中' } }
}
const result = (name, row) => name === 'exception' ? { records: [row] } : name === 'company' ? row : [row]
for (const [name, config] of Object.entries(loaders)) {
  test(name + ': first failure stays distinct from actual empty; retry clears error', async () => {
    let fails = true
    const error = unavailable()
    const f = fixture(name, { api: { [config.api]: async () => { if (fails) throw error; return ok(config.empty) } } })
    assert(visible(name, f.p.data).includes('正在读取'))
    await f.p[config.method]()
    assert.equal(f.p.data.loaded, false)
    assert(f.p.data.loadError)
    assert(!visible(name, f.p.data).includes(config.word))
    assert(visible(name, f.p.data).includes('重新读取'))
    assert.strictEqual(f.calls.logs[0][1], error, 'retain original error identity')
    fails = false
    await f.p.onRetry()
    assert.equal(f.p.data.loadError, '')
    assert.equal(f.p.data.loaded, true)
    assert(visible(name, f.p.data).includes(config.word))
  })
  test(name + ': refresh failure retains prior data and labels it stale', async () => {
    let fails = false
    const f = fixture(name, { api: { [config.api]: async () => {
      if (fails) throw unavailable()
      return ok(result(name, config.row))
    } } })
    await f.p[config.method]()
    const before = JSON.stringify(f.p.data[config.key])
    fails = true
    const refresh = f.p[config.method]()
    assert(visible(name, f.p.data).includes('正在更新'))
    await refresh
    assert.equal(JSON.stringify(f.p.data[config.key]), before)
    assert(visible(name, f.p.data).includes('上次读取') || visible(name, f.p.data).includes('上次'))
    assert(!visible(name, f.p.data).includes(config.word))
  })
  test(name + ': latest response wins; hidden late response ignored', async () => {
    const first = deferred(), second = deferred(), third = deferred()
    const queue = [first, second, third]
    const f = fixture(name, { api: { [config.api]: () => queue.shift().promise } })
    const old = f.p[config.method](), fresh = f.p[config.method]()
    second.resolve(ok(result(name, config.row)))
    await fresh
    first.reject(unavailable())
    await old
    assert.equal(f.p.data.loadError, '')
    const before = JSON.stringify(f.p.data[config.key])
    const hidden = f.p[config.method]()
    f.p.onHide()
    third.resolve(ok(config.empty))
    await hidden
    assert.equal(JSON.stringify(f.p.data[config.key]), before)
    assert.equal(f.p.data.loading, false)
  })
  test(name + ': permission failure is Chinese and hides cached content', async () => {
    let fails = false
    const f = fixture(name, { api: { [config.api]: async () => {
      if (fails) throw Object.freeze(Object.assign(new Error('FORBIDDEN'), { code: 403 }))
      return ok(result(name, config.row))
    } } })
    await f.p[config.method]()
    fails = true
    await f.p[config.method]()
    assert.equal(f.p.data.accessDenied, true)
    assert(visible(name, f.p.data).includes('去登录'))
    assert(!visible(name, f.p.data).includes('FORBIDDEN'))
  })
  test(name + ': malformed response is failure, not empty', async () => {
    const f = fixture(name, { api: { [config.api]: async () => ({ data: undefined }) } })
    await f.p[config.method]()
    assert(f.p.data.loadError)
    assert.equal(f.p.data.loaded, false)
  })
}
test('address: default action blocks duplicate requests; select uses order route too', async () => {
  const d = deferred()
  let writes = 0
  const f = fixture('address', { api: { getAddresses: async () => ok([address]), setDefaultAddress: () => { writes++; return d.promise } } })
  f.p.onLoad({ from: 'order' })
  await f.p.loadAddresses()
  assert(visible('address', f.p.data).includes('选择送水地址'))
  assert(!visible('address', f.p.data).includes('设为默认'))
  const pending = f.p.onSetDefault(tap({ id: 3 }))
  await f.p.onSetDefault(tap({ id: 3 }))
  f.p.onSelectAddress(tap({ item: address }))
  assert.equal(f.calls.write.length, 0)
  assert.equal(writes, 1)
  d.resolve(ok(null)); await pending
  f.p.onSelectAddress(tap({ item: address }))
  assert.equal(f.calls.write.length, 1)
  assert.strictEqual(f.calls.write[0].value, address)
})
test('address: delete confirmation is retained and uncertain response refreshes once automatically', async () => {
  let writes = 0
  const f = fixture('address', { api: { getAddresses: async () => ok([address]), deleteAddress: async () => { writes++; throw unavailable() } } })
  await f.p.loadAddresses()
  f.p.onDeleteAddress(tap({ id: 3 }))
  assert.equal(writes, 0)
  f.calls.modal[0].success({ confirm: true })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(writes, 1)
  assert.equal(f.p.data.actionUnknown, false)
  await f.p.changeAddress(3, 'delete')
  assert.equal(writes, 2)
  assert.equal(f.p.data.actionUnknown, false)
  assert(visible('address', f.p.data).includes('地址列表已更新'))
})
test('edit: failed original read hides and blocks blank form; retry restores exact address', async () => {
  let fails = true, writes = 0
  const f = fixture('edit', { api: {
    getAddressDetail: async () => { if (fails) throw unavailable(); return ok(address) },
    updateAddress: async () => { writes++; return ok(null) }
  } })
  f.p.setData({ isEdit: true, formReady: false, addressId: 3 })
  await f.p.loadAddress(3)
  assert.equal(f.p.data.formReady, false)
  assert(!render('edit', f.p.data).includes('data-field="name"'))
  await f.p.onSubmit()
  assert.equal(writes, 0)
  fails = false
  await f.p.onRetry()
  assert.equal(f.p.data.formData.name, address.name)
  assert.equal(f.p.data.formReady, true)
})
test('edit: wrong ID and absent details never open empty editor', async () => {
  for (const data of [null, {}, { id: 999 }]) {
    const f = fixture('edit', { api: { getAddressDetail: async () => ok(data) } })
    f.p.setData({ isEdit: true, formReady: false, addressId: 3 })
    await f.p.loadAddress(3)
    assert.equal(f.p.data.formReady, false)
    assert(f.p.data.loadError)
  }
})
test('edit: late refresh cannot overwrite typing and floor/elevator zero survive', async () => {
  const d = deferred()
  const f = fixture('edit', { api: { getAddressDetail: () => d.promise } })
  f.p.setData({ loaded: true, formReady: true })
  const loading = f.p.loadAddress(3)
  f.p.onInputChange(event('name', 'New Alice 原样'))
  d.resolve(ok({ ...address, floor: 0, hasElevator: 0 }))
  await loading
  assert.equal(f.p.data.formData.name, 'New Alice 原样')
  const g = fixture('edit', { api: { getAddressDetail: async () => ok({ ...address, floor: 0, hasElevator: 0, lat: 0, lng: 0 }) } })
  await g.p.loadAddress(3)
  assert.equal(g.p.data.formData.floor, '0')
  assert.equal(g.p.data.formData.hasElevator, 0)
  assert.equal(g.p.data.formData.lat, 0)
})
test('edit: save unknown preserves input and rejects duplicate resubmission', async () => {
  const d = deferred()
  let writes = 0, sent
  const f = fixture('edit', { api: { createAddress: payload => { writes++; sent = payload; return d.promise } } })
  f.p.setData({ formData: { ...f.p.data.formData, name: 'Alice 原名', phone: '13800000000', detail: 'A座603', floor: '', hasElevator: null } })
  const pending = f.p.onSubmit()
  await f.p.onSubmit()
  assert.equal(writes, 1)
  assert.equal(f.p.data.submitting, true)
  d.reject(unavailable()); await pending
  assert.equal(f.p.data.formData.name, 'Alice 原名')
  assert.equal(f.p.data.saveUnknown, false)
  assert.equal(sent.hasElevator, null)
  assert.equal(sent.floor, null)
  await f.p.onSubmit()
  assert.equal(writes, 2)
  assert(!visible('edit', f.p.data).includes('返回列表核对'))
})
test('edit: definite rejection retains input and retry; successful save stays locked', async () => {
  let writes = 0
  const f = fixture('edit', { api: { createAddress: async () => { if (++writes === 1) throw rejected(); return ok({ id: 4 }) } } })
  f.p.setData({ 'formData.detail': '原门牌', 'formData.name': 'Alice' })
  await f.p.onSubmit()
  assert.equal(f.p.data.saveUnknown, false)
  assert.equal(f.p.data.formData.detail, '原门牌')
  await f.p.onSubmit()
  assert.equal(f.p.data.saveSucceeded, true)
  await f.p.onSubmit()
  assert.equal(writes, 2)
  f.p.onUnload()
  f.calls.timers[0]()
  assert.equal(f.calls.nav.length, 0)
})
test('edit: denied save does not guess employee identity; location denial preserves input', async () => {
  const f = fixture('edit', { api: { createAddress: async () => { throw Object.assign(new Error('FORBIDDEN'), { code: 403 }) } } })
  f.p.setData({ 'formData.detail': '原门牌', 'formData.name': 'Alice' })
  await f.p.onSubmit()
  assert.equal(f.p.data.accessDenied, true)
  assert(!f.calls.modal[0].content.includes('当前是员工'))
  const g = fixture('edit')
  g.p.setData({ 'formData.detail': '手填门牌' })
  g.p.onAutoLocate()
  g.calls.setting.success({ authSetting: { 'scope.userLocation': false } })
  assert.equal(g.p.data.locating, false)
  assert.equal(g.p.data.formData.detail, '手填门牌')
  assert(g.p.data.locationMessage.includes('手动'))
})
test('company: onShow and slow reads retain active edits; save sends unmodified names', async () => {
  const d = deferred(); let reads = 0, payload
  const f = fixture('company', { api: {
    getCompanyInfo: async () => { reads++; return ok(company) },
    updateCompanyInfo: data => { payload = data; return d.promise }
  } })
  await f.p.loadInfo()
  assert.equal(f.p.data.form.dueDays, '0')
  f.p.onStartEdit()
  f.p.onInput(event('companyName', 'Acme 原名'))
  f.p.onInput(event('contactPerson', 'Alice'))
  f.p.onShow()
  assert.equal(reads, 1)
  const pending = f.p.onSave()
  await f.p.onSave()
  assert.equal(payload.companyName, 'Acme 原名')
  assert.equal(payload.contactPerson, 'Alice')
  assert.equal(payload.dueDays, 0)
  d.resolve(ok(company)); await pending
  assert.equal(f.p.data.editing, false)
  assert.equal(f.p.data.saveSucceeded, true)
})
test('company: unacknowledged save preserves input and permits direct retry or cancel', async () => {
  let writes = 0
  const f = fixture('company', { api: { getCompanyInfo: async () => ok(company), updateCompanyInfo: async () => { writes++; throw unavailable() } } })
  await f.p.loadInfo(); f.p.onStartEdit(); f.p.onInput(event('companyName', 'Unsaved 用户输入'))
  await f.p.onSave(); assert.equal(f.p.data.saveUnknown, false)
  assert.equal(f.p.data.form.companyName, 'Unsaved 用户输入')
  await f.p.onSave(); assert.equal(writes, 2)
  assert(!visible('company', f.p.data).includes('已核对，继续编辑'))
  f.p.onCancelEdit(); assert.equal(f.p.data.editing, true); assert.equal(f.calls.modal.length, 1); f.calls.modal[0].success({ confirm: false }); assert.equal(f.p.data.form.companyName, 'Unsaved 用户输入'); f.p.onCancelEdit(); f.calls.modal[1].success({ confirm: true }); assert.equal(f.p.data.editing, false)
})
test('company: successful null read clears stale info; missing save result remains retryable', async () => {
  let current = company
  const f = fixture('company', { api: { getCompanyInfo: async () => ok(current), updateCompanyInfo: async () => ok(null) } })
  await f.p.loadInfo()
  f.p.onStartEdit()
  await f.p.onSave()
  assert.equal(f.p.data.saveUnknown, false)
  assert.equal(f.p.data.editing, true)
  current = null
  await f.p.loadInfo()
  assert.equal(f.p.data.info, null)
  assert.equal(f.p.data.form.companyName, company.companyName)
})
test('exception: failed next page never advances; retry and refresh races keep correct rows', async () => {
  const rows = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, status: 'MANAGER_PENDING', statusText: '处理中' }))
  const d = deferred(); let nextFails = true, requests = []
  const f = fixture('exception', { api: { get: async (url, query) => {
    requests.push(query.page)
    if (query.page === 1) return ok({ records: rows })
    if (nextFails) throw unavailable()
    return d.promise
  } } })
  await f.p.loadExceptions()
  await f.p.loadMore()
  assert.equal(f.p.data.page, 1)
  assert.equal(f.p.data.exceptions.length, 20)
  assert(f.p.data.moreError)
  nextFails = false
  const pending = f.p.loadMore()
  await f.p.loadExceptions()
  d.resolve(ok({ records: [{ id: 999 }] })); await pending
  assert.equal(f.p.data.page, 1)
  assert(!f.p.data.exceptions.some(x => x.id === 999))
  assert.deepEqual(requests, [1, 2, 2, 1])
})
test('exception: internal labels, unknown quantities and compensation do not invent facts', () => {
  const f = fixture('exception')
  const original = Object.freeze({ id: 8, status: 'FUTURE_DONE', statusText: 'FUTURE_DONE', category: 'NEW_CODE', categoryText: 'NEW_CODE' })
  const row = f.p.decorateException(original)
  assert.equal(row.status, 'FUTURE_DONE')
  assert.equal(row.statusText, 'FUTURE_DONE')
  assert.equal(row.statusLabel, '状态待核实')
  assert.equal(row.categoryLabel, '类型待核实')
  assert.equal(row.stepIndex, -1)
  assert.equal(row.discrepancyText, '回桶差异待核实')
  assert.equal(row.compensationText, '补偿结果待核实')
  f.p.setData({ loaded: true, loading: false, exceptions: [row] })
  assert(!visible('exception', f.p.data).includes('FUTURE_DONE'))
})
test('exception: detail uses same signed discrepancy, both amounts and intact long notes', () => {
  const f = fixture('exception')
  const note = 'User Note 原样 A&B <确认>\\n'.repeat(30)
  const row = f.p.decorateException({ id: 9, orderId: 18, status: 'EXECUTED', statusText: '已完成', categoryText: '多回桶',
    discrepancy: -2, refundCashAmount: '12.50', refundTicketQty: 3, staffNote: note, managerNote: '按原记录执行' })
  f.p.setData({ loaded: true, loading: false, exceptions: [row] })
  f.p.onExceptionTap(tap({ id: 9 }))
  assert.equal(f.p.data.detailVisible, true)
  assert.equal(f.p.data.selectedException.discrepancyText, '多回 2 桶')
  assert.equal(f.p.data.selectedException.staffNote, note)
  const html = render('exception', f.p.data)
  assert(html.includes('¥12.50') && html.includes('3 张水票'))
  assert(html.includes('User Note 原样 A&amp;B &lt;确认&gt;'))
  f.p.onCloseDetail()
  assert.equal(f.p.data.detailVisible, false)
})
test('exception: suggested amount is never shown as executed compensation', () => {
  const f = fixture('exception')
  const row = f.p.decorateException({ status: 'EXECUTED', statusText: '已完成', suggestedCashAmount: 8 })
  assert(row.compensationText.startsWith('建议方案'))
  assert(!row.compensationText.includes('已执行'))
})
test('notice: unknown type remains generic; detail navigation stays in owned boundary', async () => {
  const f = fixture('notice', { api: { getNotices: async () => ok([{ id: 4, type: 99, title: 'English 原公告' }]) } })
  await f.p.loadNotices()
  assert.equal(f.p.data.notices[0].typeLabel, '公告')
  assert(visible('notice', f.p.data).includes('English 原公告'))
  f.p.onNoticeTap(tap({ id: 4 }))
  assert.equal(f.calls.nav[0].url, '/pages/home/notice?id=4')
  f.calls.nav[0].fail()
  assert(f.calls.toast[0].title.includes('未能打开'))
})
test('all owned templates parse, handlers resolve, files have no BOM', () => {
  for (const [name, base] of Object.entries(scope)) {
    const f = fixture(name)
    const source = fs.readFileSync(path.join(ROOT, base + '.wxml'), 'utf8')
    parse(source)
    for (const m of source.matchAll(/(?:bind|catch)[\w:]*="([\w]+)"/g)) assert.equal(typeof f.p[m[1]], 'function', base + ' handler ' + m[1])
    for (const ext of ['js', 'wxml', 'wxss']) {
      const bytes = fs.readFileSync(path.join(ROOT, base + '.' + ext))
      assert(!bytes.subarray(0, 3).equals(Buffer.from([239, 187, 191])), base + ' BOM')
    }
  }
})
test('ownership: retained forms cannot be written or displayed under a new login session', async () => {
  for (const name of ['edit', 'company']) {
    let writes = 0
    const f = fixture(name, { api: { getCompanyInfo: async () => ok(company),
      createAddress: async () => { writes++; return ok({ id: 8 }) },
      updateCompanyInfo: async () => { writes++; return ok(company) } } })
    if (name === 'company') { await f.p.loadInfo(); f.p.onStartEdit() }
    else { f.p.ensurePageSession(); f.p.onInputChange(event('detail', 'Private 原账号门牌')) }
    f.switchSession()
    f.p.onShow()
    assert.equal(f.p.data.sessionChanged, true)
    await (name === 'company' ? f.p.onSave() : f.p.onSubmit())
    assert.equal(writes, 0)
    assert.equal(f.p.data.sessionChanged, true)
    assert(!render(name, f.p.data).includes('data-field="companyName"'))
    assert(!render(name, f.p.data).includes('data-field="detail"'))
  }
})
test('ownership: a write reply after session switch cannot show successful save', async () => {
  const d = deferred()
  const f = fixture('company', { api: { getCompanyInfo: async () => ok(company), updateCompanyInfo: () => d.promise } })
  await f.p.loadInfo(); f.p.onStartEdit()
  const pending = f.p.onSave()
  f.switchSession()
  d.resolve(ok(company)); await pending
  assert.equal(f.p.data.saveSucceeded, false)
  assert.equal(f.p.data.saveUnknown, false)
  assert.equal(f.p.data.accessDenied, true)
})
test('edit: a retry started after typing also preserves dirty input', async () => {
  const f = fixture('edit', { api: { getAddressDetail: async () => ok(address) } })
  await f.p.loadAddress(3)
  f.p.onInputChange(event('name', 'My edited Alice'))
  await f.p.onRetry()
  assert.equal(f.p.data.formData.name, 'My edited Alice')
})
test('metadata: session change is unknown, not a definite business rejection', async () => {
  const error = Object.freeze(Object.assign(new Error('登录身份已变化，请重新进入后查询原购买'), { sessionChanged: true }))
  const f = fixture('edit', { api: { createAddress: async () => { throw error } } })
  f.p.setData({ 'formData.detail': '原门牌' })
  await f.p.onSubmit()
  assert.equal(f.p.data.saveUnknown, false)
  assert(!f.p.data.saveMessage.includes('地址未保存'))
  assert.strictEqual(f.calls.logs[0][1], error)
})

test('R2 exception: suggested, registered and executed amounts name the deposit-account route in list and detail', () => {
  const f = fixture('exception')
  const variants = [
    { fields: { refundCashAmount: '12.50', refundTicketQty: 3, status: 'EXECUTED' }, prefix: '已执行方案：', route: '去向：客户押金账户', amount: '¥12.50' },
    { fields: { refundCashAmount: '12.50', status: 'MANAGER_APPROVED' }, prefix: '登记方案（执行状态待核实）：', route: '去向：客户押金账户', amount: '¥12.50' },
    { fields: { suggestedCashAmount: '8.00', status: 'EXECUTED' }, prefix: '建议方案：', route: '拟计入客户押金账户', amount: '¥8.00' },
    { fields: { refundCashAmount: '12.50', status: 'FUTURE_CODE' }, prefix: '登记方案（执行状态待核实）：', route: '去向：客户押金账户', amount: '¥12.50' }
  ]
  for (const [index, variant] of variants.entries()) {
    const row = f.p.decorateException({ id: index + 50, statusText: '处理中', ...variant.fields })
    assert(row.compensationText.startsWith(variant.prefix))
    assert(row.compensationText.includes(variant.route))
    assert(row.compensationText.includes(variant.amount))
    assert(!/现金|现金到账|退款到账/.test(row.compensationText))
    if (variant.fields.refundCashAmount) assert.equal(row.refundCashAmount, variant.fields.refundCashAmount)
    f.p.setData({ loaded: true, loading: false, exceptions: [row], detailVisible: false })
    assert(visible('exception', f.p.data).includes(variant.route))
    f.p.onExceptionTap(tap({ id: row.id }))
    assert.equal(f.p.data.selectedException.compensationText, row.compensationText)
    assert(visible('exception', f.p.data).includes(variant.route))
  }
})
test('R2 location: late getSetting success or failure after unload cannot open a map or dialog', () => {
  const f = fixture('edit')
  f.p.setData({ 'formData.detail': '保留原输入' })
  f.p.onAutoLocate()
  const settings = f.calls.setting
  f.p.onUnload()
  settings.fail({ errMsg: 'getSetting:fail' })
  settings.success({ authSetting: { 'scope.userLocation': false } })
  assert.equal(f.calls.location, undefined)
  assert.equal(f.calls.modal.length, 0)
  assert.equal(f.calls.toast.length, 0)
  assert.equal(f.p.data.formData.detail, '保留原输入')
})
test('R2 location: a session change while getSetting is pending invalidates success and failure', () => {
  const f = fixture('edit')
  f.p.setData({ 'formData.detail': '原账号输入' })
  f.p.onAutoLocate()
  const settings = f.calls.setting
  f.switchSession()
  settings.fail({ errMsg: 'getSetting:fail' })
  settings.success({ authSetting: { 'scope.userLocation': false } })
  assert.equal(f.calls.location, undefined)
  assert.equal(f.calls.modal.length, 0)
  assert.equal(f.p.data.sessionChanged, true)
  assert.equal(f.p.data.locating, false)
  assert.equal(f.p.data.formData.detail, '原账号输入')
})
test('R2 location: protected onShow after account switch rejects old map success, fail and complete', () => {
  const f = fixture('edit')
  f.p.setData({ 'formData.detail': '原账号输入' })
  f.p.onAutoLocate()
  f.calls.setting.success({ authSetting: { 'scope.userLocation': true } })
  const map = f.calls.location
  f.p.onHide()
  f.switchSession()
  f.p.onShow()
  map.success({ address: '旧地图返回的地址', latitude: 1, longitude: 2 })
  map.fail({ errMsg: 'chooseLocation:fail auth deny' })
  map.complete()
  assert.equal(f.p.data.sessionChanged, true)
  assert.equal(f.p.data.formData.detail, '原账号输入')
  assert.equal(f.p.data.formData.lat, null)
  assert.equal(f.calls.modal.length, 0)
  assert.equal(f.calls.toast.length, 0)
})
test('R2 location: old map callbacks detect a changed session before onShow is called', () => {
  const f = fixture('edit')
  f.p.setData({ 'formData.detail': '原账号输入' })
  f.p.onAutoLocate()
  f.calls.setting.fail({ errMsg: 'getSetting:fail' })
  const map = f.calls.location
  f.switchSession()
  map.success({ address: '不能回填', latitude: 1, longitude: 2 })
  map.fail({ errMsg: 'chooseLocation:fail auth deny' })
  map.complete()
  assert.equal(f.p.data.sessionChanged, true)
  assert.equal(f.p.data.locating, false)
  assert.equal(f.p.data.formData.detail, '原账号输入')
  assert.equal(f.calls.modal.length, 0)
})
test('R2 location: old operation callbacks cannot affect a newer location or save', async () => {
  const f = fixture('edit')
  const maps = []
  f.wx.chooseLocation = options => { maps.push(options); f.calls.location = options }
  f.p.onAutoLocate()
  const oldSettings = f.calls.setting
  oldSettings.fail({ errMsg: 'getSetting:fail' })
  oldSettings.fail({ errMsg: 'duplicate late failure' })
  assert.equal(maps.length, 1)
  const oldMap = maps[0]
  oldMap.fail({ errMsg: 'chooseLocation:fail cancel' })
  oldMap.complete()
  f.p.onAutoLocate()
  const newSettings = f.calls.setting
  assert.equal(f.p.data.locating, true)
  const message = f.p.data.locationMessage
  oldMap.success({ address: '旧操作地址', latitude: 1, longitude: 2 })
  oldMap.fail({ errMsg: 'chooseLocation:fail auth deny' })
  oldMap.complete()
  oldSettings.success({ authSetting: { 'scope.userLocation': false } })
  assert.equal(f.p.data.locating, true)
  assert.equal(f.p.data.locationMessage, message)
  assert.equal(f.p.data.formData.detail, '')
  assert.equal(f.calls.modal.length, 0)
  newSettings.success({ authSetting: { 'scope.userLocation': true } })
  assert.equal(maps.length, 2)
  maps[1].success({ address: '新操作地址', latitude: 30, longitude: 120 })
  maps[1].complete()
  assert.equal(f.p.data.formData.detail, '新操作地址')
  assert.equal(f.p.data.locating, false)
  const saving = fixture('edit', { api: { createAddress: async () => { throw rejected() } } })
  saving.p.setData({ 'formData.detail': '保存时的原门牌' })
  saving.p.onAutoLocate()
  const settingsBeforeSave = saving.calls.setting
  const pendingSave = saving.p.onSubmit()
  settingsBeforeSave.fail({ errMsg: 'getSetting:fail' })
  settingsBeforeSave.success({ authSetting: { 'scope.userLocation': false } })
  assert.equal(saving.calls.location, undefined)
  assert.equal(saving.calls.modal.length, 0)
  assert.equal(saving.p.data.locating, false)
  assert(saving.p.data.locationMessage.includes('选点已暂停'))
  await pendingSave
  assert.equal(saving.p.data.formData.detail, '保存时的原门牌')
})
test('R2 location: same-session map hide/show round trip still fills the selected address', () => {
  const f = fixture('edit')
  f.p.onShow()
  f.p.onAutoLocate()
  f.calls.setting.success({ authSetting: { 'scope.userLocation': true } })
  const map = f.calls.location, sequence = f.p._locationSequence
  f.p.onHide()
  f.p.onShow()
  assert.equal(f.p._locationSequence, sequence, 'map onHide must not invalidate the operation')
  map.success({ address: '演示新地址', name: 'A 座', latitude: 30, longitude: 120 })
  map.complete()
  assert.equal(f.p.data.formData.detail, '演示新地址A 座')
  assert.equal(f.p.data.formData.lat, 30)
  assert.equal(f.p.data.formData.lng, 120)
  assert.equal(f.p.data.locating, false)
  assert(f.p.data.locationMessage.includes('位置已填入'))
})
test('R2 location: permission dialog callbacks keep the original operation and account', () => {
  for (const source of ['settings', 'map']) {
    for (const invalidation of ['unload', 'session']) {
      const f = fixture('edit')
      f.p.onAutoLocate()
      if (source === 'settings') f.calls.setting.success({ authSetting: { 'scope.userLocation': false } })
      else {
        f.calls.setting.success({ authSetting: { 'scope.userLocation': true } })
        f.calls.location.fail({ errMsg: 'chooseLocation:fail auth deny' })
        f.calls.location.complete()
      }
      const modal = f.calls.modal[0]
      assert(modal)
      if (invalidation === 'unload') f.p.onUnload()
      else f.switchSession()
      modal.success({ confirm: true })
      assert.equal(f.calls.openSetting, undefined)
    }
  }
  const current = fixture('edit')
  current.p.onAutoLocate()
  current.calls.setting.success({ authSetting: { 'scope.userLocation': false } })
  current.calls.modal[0].success({ confirm: true })
  assert.equal(current.calls.openSetting, true)
})

async function main() {
  const timer = setTimeout(() => { console.error('PROFILE_VISIBLE_TIMEOUT'); process.exit(1) }, 30000)
  let count = 0
  try {
    for (const t of tests) { await t.run(); count++; console.log('PASS ' + t.name) }
    console.log('AQUAFLOW_SUITE_OK ' + count)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
module.exports = { main }
