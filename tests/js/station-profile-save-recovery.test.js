'use strict'
const assert = require('node:assert/strict')
const { loadPage, createWx, createApp } = require('./harness')
const tick = () => new Promise(setImmediate)
function fixture(opts = {}) {
  const wx = createWx(), writes = [], app = createApp({ _loginGeneration: 1, globalData: { isLogin: true, userInfo: { staffId: 7, stationId: 3, role: 'STATION_MANAGER', bindStatus: 'BOUND' } } })
  let station = { id: 3, name: '原水站', phone: '123', address: '原地址', lat: 30, lng: 120 }, reads = 0
  const page = loadPage('miniapp-delivery/pages/station-mgmt/station-info/index.js', { wx, app, stubs: {
    'config/api': { getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }) },
    'utils/setup-progress': { initialModuleData: () => ({}), refreshModuleTasks: () => {} },
    'api/station-mgmt': {
      getMyStation: async () => { reads++; if (reads > 1 && opts.readWait) await opts.readWait; if (reads > 1 && opts.readFail) throw Error('offline'); return { code: 0, data: { ...station } } },
      updateStationProfile: async (id, body) => {
        writes.push({ id, body: { ...body } })
        if (!opts.fail || opts.applied) {
          station = { ...station, ...body, lat: body.coordinateMode === 'SET' ? Number(body.lat.toFixed(6)) : body.coordinateMode === 'CLEAR' ? null : station.lat, lng: body.coordinateMode === 'SET' ? Number(body.lng.toFixed(6)) : body.coordinateMode === 'CLEAR' ? null : station.lng }
          if (opts.afterApply) station = opts.afterApply(station)
        }
        if (opts.fail) throw opts.fail
        return opts.result ? opts.result(station) : { code: 0, data: { ...station } }
      }
    }
  } })
  page.setData = (patch, cb) => { for (const [key, value] of Object.entries(patch)) { const keys = key.split('.'); let node = page.data; for (const k of keys.slice(0, -1)) node = node[k]; node[keys.at(-1)] = value } if (cb) cb() }
  return { page, wx, writes, app, get reads() { return reads } }
}
const cases = []
function test(name, run) { cases.push({ name, run }) }
const edit = t => t.page.onNameInput({ detail: { value: '新水站' } })
const toast = t => t.wx.__calls.toast.at(-1)
test('confirmed name/phone edit sends one KEEP write, clears dirty, and shows success', async () => {
 const t = fixture(); await t.page.onShow(); edit(t); t.page.onPhoneInput({ detail: { value: '456' } }); await t.page.onSave()
 assert.deepEqual(t.writes[0].body, { address: '原地址', coordinateMode: 'KEEP', name: '新水站', phone: '456' }); assert.equal(t.reads, 1); assert.equal(t.page.data.infoDirty, false); assert.equal(t.page.data.origin.name, '新水站'); assert.equal(toast(t).title, '保存成功')
})
test('editing after a confirmed save clears the stale success message', async () => {
 const t = fixture(); await t.page.onShow(); edit(t); await t.page.onSave(); assert.match(t.page.data.saveMessage, /已保存/); t.page.onNameInput({ detail: { value: '再次修改' } }); assert.equal(t.page.data.saveMessage, ''); assert.equal(t.page.data.infoDirty, true)
})
for (const error of [Object.assign(Error('not found'), { statusCode: 404 }), Object.assign(Error('not found'), { statusCode: 200, backendCode: 404 }), Object.assign(Error('method'), { statusCode: 405 })]) test('unsupported profile is explicit for HTTP or body status ' + (error.backendCode || error.statusCode), async () => {
 const t = fixture({ fail: error }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 1); assert.equal(t.writes.length, 1); assert.equal(t.page.data.infoDirty, true); assert.match(t.page.data.saveMessage, /尚不支持/); assert.equal(toast(t).title, '服务需更新')
})
test('business rejection preserves form and does not imply unknown commit', async () => {
 const t = fixture({ fail: Object.assign(Error('没有权限'), { businessRejected: true }) }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 1); assert.equal(t.page.data.infoDirty, true); assert.match(t.page.data.saveMessage, /没有权限/); assert.equal(toast(t).title, '保存未完成')
})
test('pre-dispatch failure is explicit and does not read back', async () => {
 const t = fixture({ fail: Object.assign(Error('network'), { requestMayHaveOccurred: false }) }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 1); assert.equal(t.page.data.infoDirty, true); assert.equal(toast(t).title, '连接未成功')
})
test('timeout after write reads the matching station once without replaying PUT', async () => {
 const t = fixture({ fail: Error('timeout'), applied: true }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.reads, 2); assert.equal(t.page.data.infoDirty, false); assert.match(t.page.data.saveMessage, /当前资料与提交内容一致/); assert.equal(toast(t).title, '资料已核对')
})
test('timeout before write preserves draft when readback differs', async () => {
 const t = fixture({ fail: Error('timeout') }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.reads, 2); assert.equal(t.page.data.infoDirty, true); assert.equal(t.page.data.form.name, '新水站'); assert.match(t.page.data.saveMessage, /未确认/); assert.equal(toast(t).title, '请核对资料')
})
test('unavailable readback leaves result unknown and input intact', async () => {
 const t = fixture({ fail: Error('timeout'), readFail: true }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 2); assert.equal(t.page.data.infoDirty, true); assert.equal(t.page.data.form.name, '新水站'); assert.match(t.page.data.saveMessage, /未确认/)
})
test('readback with changed KEEP coordinates cannot falsely confirm the old station point', async () => {
 const t = fixture({ fail: Error('timeout'), applied: true, afterApply: s => ({ ...s, lat: 31 }) }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 2); assert.equal(t.page.data.infoDirty, true); assert.match(t.page.data.saveMessage, /未确认/)
})
test('a success response with wrong requested name/phone is not accepted', async () => {
 const t = fixture({ afterApply: s => ({ ...s, name: '其他名称' }) }); await t.page.onShow(); edit(t); await t.page.onSave(); assert.equal(t.reads, 2); assert.equal(t.page.data.infoDirty, true); assert.match(t.page.data.saveMessage, /未确认/)
})
test('a receipt and readback with the wrong requested phone preserve the edit', async () => {
 const t = fixture({ afterApply: s => ({ ...s, phone: '999' }) }); await t.page.onShow(); t.page.onPhoneInput({ detail: { value: '456' } }); await t.page.onSave(); assert.equal(t.reads, 2); assert.equal(t.writes.length, 1); assert.equal(t.page.data.form.phone, '456'); assert.equal(t.page.data.infoDirty, true); assert.match(t.page.data.saveMessage, /未确认/)
})
test('uncertain SET readback checks rounded coordinates and confirms only one write', async () => {
 const t = fixture({ fail: Error('timeout'), applied: true }); await t.page.onShow(); t.page.setData({ coordinateMode: 'SET', coord: { lat: 31.1234567, lng: 121.1234567 } }); t.page.markInfoDraft(); await t.page.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.page.data.infoDirty, false); assert.equal(t.page.data.coord.lat, 31.123457)
})
test('uncertain CLEAR readback only confirms when coordinates are cleared', async () => {
 const t = fixture({ fail: Error('timeout'), applied: true }); await t.page.onShow(); t.page.onAddressInput({ detail: { value: '新地址' } }); await t.page.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.page.data.infoDirty, false); assert.equal(t.page.data.coord.lat, null); assert.equal(t.page.data.coord.lng, null)
})
test('repeat save during uncertain readback does not dispatch another write', async () => {
 let release; const wait = new Promise(resolve => { release = resolve }), t = fixture({ fail: Error('timeout'), applied: true, readWait: wait }); await t.page.onShow(); edit(t); const first = t.page.onSave(); await tick(); const second = t.page.onSave(); assert.equal(t.writes.length, 1); assert.equal(t.page.data.saving, true); release(); await Promise.all([first, second]); assert.equal(t.writes.length, 1); assert.equal(t.page.data.saving, false)
})
test('a changed identity before save gives clear feedback without dispatching', async () => {
 const t = fixture(); await t.page.onShow(); edit(t); t.app.globalData.userInfo.role = 'DELIVERY'; await t.page.onSave(); assert.equal(t.writes.length, 0); assert.match(t.page.data.saveMessage, /登录状态已变化/); assert.equal(toast(t).title, '登录状态已变化')
})
test('a changed identity during readback cannot apply another account result or toast', async () => {
 let release; const wait = new Promise(resolve => { release = resolve }), t = fixture({ fail: Error('timeout'), applied: true, readWait: wait }); await t.page.onShow(); edit(t); const first = t.page.onSave(); await tick(); t.app.globalData.userInfo.stationId = 4; release(); await first; assert.equal(t.writes.length, 1); assert.equal(t.page.data.infoDirty, true); assert.equal(t.wx.__calls.toast.length, 0)
})
;(async () => { let failed = 0; for (const c of cases) { try { await c.run(); console.log('PASS ' + c.name) } catch (e) { failed++; console.error('FAIL ' + c.name, e) } } if (failed) process.exitCode = 1; else console.log('AQUAFLOW_SUITE_OK ' + cases.length) })()
