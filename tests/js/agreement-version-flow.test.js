const assert = require('assert'), path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const done = armWatchdog(15000), tests = [], test = (name, run) => tests.push({ name, run })
const tick = () => new Promise(resolve => setImmediate(resolve))
function published(root, revision = 'a') {
  const audience = root === 'miniapp-user' ? 'CUSTOMER' : 'STAFF'
  return { enabled: true, notice: '仅用于合成测试的登录告知', documents: ['user', 'privacy'].map(type => ({
    audience, type, contentSha256: revision.repeat(64), versionId: audience.toLowerCase() + '-' + type + '-' + revision.repeat(64),
    active: true, status: 'APPROVED', navTitle: type, docTitle: '合成测试正文', updatedAt: '合成版本', notice: '', sections: [{ heading: '测试', body: '测试正文' }]
  })) }
}
function login(root, current) {
  const wx = createWx(), calls = [], app = createApp({ globalData: { isLogin: false }, setLoginState: x => x, routeByRole() {} })
  let wxCallback
  wx.login = o => { wxCallback = o }
  const page = loadPage(root + '/pages/login/index.js', { wx, app, stubs: {
    'api/agreements': { current }, 'config/api': { isReleaseEnv: () => true },
    'api/auth': { wxLogin: async (...args) => { calls.push(args); return { code: 1, data: null } },
      wxLoginStaff: async (...args) => { calls.push(args); return { code: 1, data: null } },
      devLogin: async (...args) => { calls.push(['dev', ...args]); return { code: 1, data: null } } }
  } })
  return { page, wx, calls, complete: () => wxCallback.success({ code: 'synthetic-code' }) }
}
for (const root of ['miniapp-user', 'miniapp-delivery']) {
  test(root + ': reading has no acceptance and click pins both versions before wx.login completes', async () => {
    const catalog = published(root), t = login(root, async () => ({ data: catalog }))
    await t.page.loadAgreements()
    t.page.onOpenAgreement({ currentTarget: { dataset: { type: 'privacy' } } })
    assert.equal(t.calls.length, 0); assert.ok(t.wx.__calls.nav[0].url.includes(encodeURIComponent(catalog.documents[1].versionId)))
    t.page.onHide(); t.page.onShow(); await tick()
    const request = t.page.onWxLogin(); t.page.setData({ agreementCatalog: published(root, 'b') }); t.complete(); await request
    assert.deepStrictEqual(t.calls, [['synthetic-code', { termsVersionId: catalog.documents[0].versionId, privacyVersionId: catalog.documents[1].versionId }]])
    assert.equal(t.page.data.loading, false)
  })
  test(root + ': drafts and developer login never fabricate acceptance', async () => {
    const t = login(root, async () => { throw Error('offline') }); await t.page.loadAgreements()
    assert.equal(t.page.data.agreementCatalog.enabled, false)
    const request = t.page.onWxLogin(); t.complete(); await request
    assert.deepStrictEqual(t.calls[0], ['synthetic-code', undefined]); t.page.onDevLogin(); await tick()
    assert.equal(t.calls[1][0], 'dev'); assert.equal(t.calls[1].length, 2)
  })
  test(root + ': malformed catalog cannot retain earlier active versions', async () => {
    let value = published(root); const t = login(root, async () => ({ data: value })); await t.page.loadAgreements()
    value = { enabled: true, documents: [null, null] }; await t.page.loadAgreements(); assert.equal(t.page.data.agreementCatalog.enabled, false)
    const helper = require(path.join(ROOT, root, 'utils/agreements')); assert.equal(helper.loginEvidence(value), undefined)
  })
  test(root + ': pinned document never silently falls back to other text; retries and unload are safe', async () => {
    const catalog = published(root), wx = createWx(); wx.setNavigationBarTitle = () => {}
    let request, reads = 0, accepts = 0
    const page = loadPage(root + '/pages/mine/agreement/index.js', { wx, stubs: { 'api/agreements': {
      document: version => { assert.equal(version, catalog.documents[0].versionId); reads++; return new Promise(resolve => { request = resolve }) },
      current: () => { throw Error('pinned reading must not query latest') }, acknowledge: () => accepts++
    } } })
    page.onLoad({ type: 'user', versionId: catalog.documents[0].versionId }); assert.equal(page.data.doc, null)
    request({ data: published(root, 'b').documents[0] }); await tick(); assert.equal(page.data.doc, null); assert.ok(page.data.error)
    const retry = page.onRetry(); request({ data: catalog.documents[0] }); await retry; assert.equal(page.data.doc.versionId, catalog.documents[0].versionId)
    const late = page.onRetry(); page.onUnload(); request({ data: published(root, 'b').documents[0] }); await late
    assert.equal(page.data.doc.versionId, catalog.documents[0].versionId); assert.equal(reads, 3); assert.equal(accepts, 0)
  })
}
;(async () => { let failures = 0; for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (e) { failures++; console.error('FAIL ' + t.name, e) } }
  done(); if (failures) process.exitCode = 1; else { console.log('全部通过：' + tests.length + ' 项协议版本流程'); console.log('AQUAFLOW_SUITE_OK ' + tests.length) } })()
