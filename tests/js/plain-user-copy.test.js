'use strict'
// Copy checks execute real request/display and startup error handlers. No business or network writes.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { fixture, flush, observe, ROOT } = require('./cloud-upload.mock.test')
const tests = [], test = (name, run) => tests.push({ name, run })
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8')

for (const side of ['miniapp-user', 'miniapp-delivery']) {
  test(side + ' hides Chinese implementation errors but keeps rejection evidence and the original request', async () => {
    for (const reason of ['接口暂不可用，请重新部署后端', '数据库字段缺失', '幂等请求内容不同']) {
      const f = fixture('https', side), payload = { quantity: 3, idempotencyKey: 'same-intent' }
      const pending = observe(f.load('request').post('/synthetic-copy', payload))
      f.http[0].success({ statusCode: 200, data: { code: 1, message: reason } }); await flush()
      assert.equal(pending.error.message, '请求失败')
      assert.equal(pending.error.originalMessage, reason)
      assert.equal(pending.error.businessRejected, true); assert.equal(pending.error.backendCode, 1)
      assert.equal(f.http.length, 1); assert.equal(JSON.stringify(f.http[0].data), JSON.stringify(payload))
    }
  })
  test(side + ' shows a concise connection notice and retains original diagnostic/write flags', async () => {
    const f = fixture('cloudContainer', side), request = f.load('request')
    const pending = observe(request.post('/synthetic-copy', { idempotencyKey: 'synthetic-original', amount: 12.50 }))
    await flush(); assert.equal(f.calls.length, 1)
    const sent = f.calls[0].options, before = JSON.stringify(sent.data)
    f.calls[0].reject(Error('synthetic SDK rejection')); await flush()
    assert.equal(pending.error.message, '暂时连不上服务，请稍后重试')
    assert.equal(pending.error.originalMessage, '云连接失败，请核实环境关联与服务访问权限后重试')
    assert.equal(pending.error.requestMayHaveOccurred, true)
    assert.equal(JSON.stringify(sent.data), before); assert.equal(f.calls.length, 1)
  })
  test(side + ' keeps pre-dispatch configuration failure nonexecuted without developer instructions in visible copy', async () => {
    const f = fixture('https', side), pending = observe(f.load('request').post('/synthetic-copy', { quantity: 3 }))
    await flush(); assert.equal(f.http.length, 1)
    const failure = Object.freeze({ errMsg: 'CONFIG_ENV synthetic.internal /api/private', transportConfiguration: true, requestMayHaveOccurred: false })
    f.http[0].fail(failure); await flush()
    assert.equal(pending.error.transportConfiguration, true); assert.equal(pending.error.requestMayHaveOccurred, false)
    assert.equal(pending.error.originalMessage, failure.errMsg)
    assert(!/环境|配置|负责人|CONFIG_ENV|\/api\//.test(pending.error.message))
    assert.equal(f.http.length, 1); assert.equal(failure.errMsg, 'CONFIG_ENV synthetic.internal /api/private')
  })
  test(side + ' preserves ordinary business reasons and amounts rather than expanding every term', async () => {
    const f = fixture('https', side), pending = observe(f.load('request').post('/synthetic-copy', { quantity: 3 }))
    const reason = '押金不足 ¥12.50，请先补交押金'
    f.http[0].success({ statusCode: 200, data: { code: 1, message: reason } }); await flush()
    assert.equal(pending.error.message, reason); assert.equal(pending.error.originalMessage, reason)
    assert.equal(pending.error.businessRejected, true); assert.equal(pending.error.backendCode, 1)
    assert.equal(f.http.length, 1)
  })
}
for (const failure of [{ transportConfiguration: true }, { requestMayHaveOccurred: false }]) {
  test('startup error hides raw SDK text while keeping the original error and login validation request', () => {
    let app; const calls = [], dialogs = [], session = {}, error = Object.freeze({ ...failure, errMsg: 'ENV_ID SQL HTTP503 /api/internal synthetic' })
    const wx = { showModal: options => dialogs.push(options) }
    vm.runInNewContext(read('miniapp-user/app.js'), { App: value => { app = value }, wx, console,
      require(id) {
        if (id.includes('storage-keys')) return { STORAGE_KEYS: {} }
        if (id.endsWith('/storage')) return { stationStorage: {} }
        if (id.endsWith('/token')) return { captureSession: () => session, isCurrentSession: () => true, isCurrentCredentials: () => true }
        if (id.endsWith('/cart-storage')) return {}
        if (id.endsWith('/business-dialog')) return { showBusinessModal: options => dialogs.push(options) }
        if (id.endsWith('/config/api')) return { API: { ME: '/api/me' } }
        if (id.endsWith('/transport')) return { send: options => { calls.push(options); options.fail(error) } }
        throw Error('unexpected dependency ' + id)
      } })
    app.globalData.accessToken = 'synthetic-access'; app.validateToken()
    assert.equal(calls.length, 1); assert.equal(calls[0].method, 'GET'); assert.equal(calls[0].path, '/api/me')
    assert.equal(calls[0].header.Authorization, 'Bearer synthetic-access')
    assert.equal(dialogs.length, 1); assert.equal(dialogs[0].title, '服务暂不可用')
    assert.equal(dialogs[0].content, '暂时无法连接服务，请稍后重试。'); assert.equal(dialogs[0].showCancel, false)
    assert.equal(error.errMsg, 'ENV_ID SQL HTTP503 /api/internal synthetic')
  })
}
test('changed financial result titles remain pending and receipt errors retain explicit safety markers', () => {
  const user = read('miniapp-user/pages/order/create.js'), employee = read('miniapp-delivery/pages/order/complete.js')
  assert(user.includes("unknown ? '订单已提交，付款结果待确认'"))
  assert(user.includes("const unknown = outcome.state === 'unknown'"))
  assert(employee.includes("title: '送达结果待确认'")); assert(employee.includes("resultState: 'unknown'"))
  assert(employee.includes('配送结果可能已登记')); assert(employee.includes('已送达或已完成时无需重提'))
  for (const file of ['miniapp-delivery/pages/station-mgmt/products/index.js', 'miniapp-delivery/pages/station-mgmt/receivables/index.js']) {
    const source = read(file); assert(source.includes('malformedResponse: true'))
    assert(!/new Error\('登记回执不完整'\)|Error\('新建商品回执不完整/.test(source))
  }
})
test('familiar accounting words and existing consequence explanations remain concise and unchanged', () => {
  const receivables = read('miniapp-delivery/pages/station-mgmt/receivables/index.wxml')
  assert(receivables.includes('仅登记实际收到的钱并销账，不会向客户扣款。'))
  assert(receivables.includes('核实所选款项已实际收到后登记。'))
  const detail = read('miniapp-delivery/pages/order/detail.js')
  assert(detail.includes("'核销认损'")); assert(detail.includes('暂停退押金，不扣押金、不撤桶权益'))
  assert(detail.includes("managerNote: independent ? '客户拒付，站长确认信用风险；欠款继续追收' : '客户拒付，站长核销认损'"))
})

test('rendered product and delivery copy explains the next action without repeated disclaimers', () => {
  const { parseWxml, renderElements } = require('./wxml-tree')
  const { loadPage, createWx, createApp } = require('./harness')
  const view = (route, state) => {
    const page = loadPage(route + '.js', { wx: createWx(), app: createApp() })
    return renderElements(parseWxml(read(route + '.wxml')), { ...page.data, ...state }, { includeText: true })
  }
  const text = nodes => nodes.map(n => n.text || '').join('\n')
  const product = text(view('miniapp-delivery/pages/station-mgmt/products/index', {
    showEdit: true, isAdd: true, editTemplateId: 'sample', editHelpExpanded: true, showPresetPicker: true,
    editForm: { category: 1, ticketEnabled: true }, brokenImages: {}
  }))
  assert(product.includes('通用桶装水示意图，可更换为实际商品图'))
  assert(product.includes('修改仅在本站生效；库存请在商品卡片中办理。'))
  assert(product.includes('模板图片可更换为实际商品图。'))
  assert(product.includes('开启后请设置票价，可保留已有单张价'))
  assert(!product.includes('不代表具体品牌包装'))
  const inactive = text(view('miniapp-delivery/pages/station-mgmt/products/index', {
    showEdit: true, productInactive: true, editForm: { category: 1 }, brokenImages: {}
  }))
  assert(inactive.includes('商品已停用，资料与价格不可修改'))
  assert(text(view('miniapp-user/pages/mine/account-data/index', { recordsOnly: true }))
    .includes('查看本人已登记的申请及进度。'))
  const detail = view('miniapp-delivery/pages/order/detail', { order: {
    deliveryTimeRequest: '希望18点前送达', notes: ['联系客户后配送'], items: []
  } })
  assert(text(detail).includes('客户期望送达')); assert(text(detail).includes('希望18点前送达'))
  assert(text(detail).includes('安排配送时参考。'))
  assert(!detail.some(n => (n.attrs.text || '').includes('期望时间不代表')))
  const paymentSource = fs.readFileSync(path.join(ROOT, 'AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/PaymentServiceImpl.java'), 'utf8')
  const offlineNote = paymentSource.match(/DEPOSIT_OFFLINE_RISK_NOTE\s*=([\s\S]*?);\n/)[1]
  assert(offlineNote.includes('现金押金由水站当面收取并登记，请保留收据。'))
  assert(!/纠纷|自行协商|系统无法核实/.test(offlineNote))
})

async function main() {
  const results = [], timer = setTimeout(() => { console.error('PLAIN_COPY_TIMEOUT'); process.exit(1) }, 30000)
  try {
    for (const t of tests) { try { await t.run(); results.push({ name: t.name, pass: true }); console.log('PASS ' + t.name) }
      catch (error) { results.push({ name: t.name, pass: false, error: error.message }); console.log('FAIL ' + t.name + ': ' + error.message) } }
    if (process.env.REVIEW_OUTPUT) fs.writeFileSync(process.env.REVIEW_OUTPUT, JSON.stringify(results, null, 2))
    if (results.some(r => !r.pass)) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + results.length)
  } finally { clearTimeout(timer) }
}
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1 })
