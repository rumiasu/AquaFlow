const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')

const pending = { operatingStatus: 3, statusText: '待上线', note: '',
  currentEffect: '顾客看不到这家站', options: [{ value: 1, text: '正常运营', desc: '', effect: '顾客可以选择水站' }] }
const normal = { operatingStatus: 1, statusText: '正常运营', note: 'SYNTH', currentEffect: '顾客可以选择水站' }
function setup(read = async () => ({ code: 0, data: pending }), save = async () => ({ code: 0, data: normal })) {
  const writes = [], wx = createWx()
  const page = loadPage('miniapp-delivery/pages/station-mgmt/station-status/index.js', {
    wx, app: createApp(), stubs: { 'api/station-mgmt': {
      getStationStatus: read,
      updateStationStatus: async (...args) => { writes.push(args); return save(...args) }
    } }
  })
  return { page, wx, writes }
}
function unknown(page) {
  assert.strictEqual(page.data.statusLoaded, false)
  assert.strictEqual(page.data.current.operatingStatus, null)
  assert.strictEqual(page.data.current.statusText, '')
  assert.strictEqual(page.data.picked, null)
}
let passed = 0
async function test(name, run) { await run(); passed++; console.log('PASS ' + name) }

;(async () => {
  const done = armWatchdog()
  try {
    await test('before any successful read, status is unknown and save cannot issue a write', async () => {
      const { page, writes } = setup()
      unknown(page)
      await page.onSaveStatus()
      assert.strictEqual(writes.length, 0)
    })
    await test('failed initial read does not display normal or permit a default normal write', async () => {
      const { page, writes } = setup(async () => { throw new Error('SYNTH offline failure') })
      await page.loadStatus()
      unknown(page)
      assert.strictEqual(page.data.loading, false)
      page.onPickStatus({ currentTarget: { dataset: { value: 1 } } })
      await page.onSaveStatus()
      assert.strictEqual(writes.length, 0)
    })
    await test('empty, invalid or incomplete readbacks remain unknown', async () => {
      for (const data of [null, {}, [], { operatingStatus: '3', statusText: '待上线' }, { operatingStatus: 3 }, { operatingStatus: 3, statusText: '' }]) {
        const { page, writes } = setup(async () => ({ code: 0, data }))
        await page.loadStatus()
        unknown(page)
        await page.onSaveStatus()
        assert.strictEqual(writes.length, 0)
      }
    })
    await test('pending status and its customer visibility effect come from the backend', async () => {
      const { page } = setup()
      await page.loadStatus()
      assert.strictEqual(page.data.statusLoaded, true)
      assert.strictEqual(page.data.current.operatingStatus, 3)
      assert.strictEqual(page.data.current.statusText, '待上线')
      assert.strictEqual(page.data.effectText, pending.currentEffect)
      assert.strictEqual(page.data.effectLabel, '当前状态下顾客端显示：')
      page.onPickStatus({ currentTarget: { dataset: { value: 1 } } })
      assert.strictEqual(page.data.effectLabel, '选中后顾客端显示：')
      assert.ok(!page.data.statusOptions.some(x => x.value === 3))
    })
    await test('required-setup rejection keeps the known pending state and does not report success', async () => {
      const rejection = Object.assign(new Error('SYNTH 2 required setup items pending'), { businessRejected: true })
      const { page, wx, writes } = setup(undefined, async () => { throw rejection })
      await page.loadStatus()
      page.onPickStatus({ currentTarget: { dataset: { value: 1 } } })
      await page.onSaveStatus()
      assert.strictEqual(writes.length, 1)
      assert.strictEqual(page.data.current.operatingStatus, 3)
      assert.strictEqual(page.data.statusLoaded, true)
      assert.ok(!wx.__calls.toast.some(x => x.title === '已保存'))
    })
    await test('a successful save uses the returned authoritative status and effect', async () => {
      const { page, writes } = setup()
      await page.loadStatus()
      page.onPickStatus({ currentTarget: { dataset: { value: 1 } } })
      await page.onSaveStatus()
      assert.strictEqual(writes.length, 1)
      assert.strictEqual(writes[0][0], 1)
      assert.strictEqual(page.data.current.operatingStatus, 1)
      assert.strictEqual(page.data.current.statusText, normal.statusText)
      assert.strictEqual(page.data.effectText, normal.currentEffect)
    })
    await test('uncertain save or malformed success requires readback before another write', async () => {
      for (const save of [async () => { throw new Error('SYNTH timeout') }, async () => ({ code: 0, data: {} })]) {
        const { page, writes, wx } = setup(undefined, save)
        await page.loadStatus()
        page.onPickStatus({ currentTarget: { dataset: { value: 1 } } })
        await page.onSaveStatus()
        unknown(page)
        await page.onSaveStatus()
        assert.strictEqual(writes.length, 1)
        assert.ok(!wx.__calls.toast.some(x => x.title === '已保存'))
      }
    })
    await test('failed refresh discards a formerly normal status instead of leaving a false current label', async () => {
      let fail = false
      const { page } = setup(async () => { if (fail) throw new Error('SYNTH refresh failure'); return { code: 0, data: normal } })
      await page.loadStatus()
      assert.strictEqual(page.data.current.operatingStatus, 1)
      fail = true
      await page.loadStatus()
      unknown(page)
    })
    await test('in-flight read cannot be used to submit a stale selected status', async () => {
      let resolve
      const { page, writes } = setup(() => new Promise(r => { resolve = r }))
      const flight = page.loadStatus()
      await page.onSaveStatus()
      assert.strictEqual(writes.length, 0)
      resolve({ code: 0, data: pending })
      await flight
      assert.strictEqual(page.data.current.operatingStatus, 3)
    })
    await test('existing save button is disabled until status is loaded', async () => {
      const view = fs.readFileSync(path.join(ROOT, 'miniapp-delivery/pages/station-mgmt/station-status/index.wxml'), 'utf8')
      assert.ok(/bindtap="onSaveStatus"[^>]*disabled="{{saving \|\| loading \|\| !statusLoaded}}"/.test(view))
      assert.ok(view.includes('营业状态加载失败'))
    })
    console.log('AQUAFLOW_SUITE_OK ' + passed)
  } finally { done() }
})().catch(error => { console.error(error); process.exitCode = 1 })
