const assert = require('node:assert/strict')
const { test, after } = require('node:test')
const { loadPage, createWx, createApp } = require('./harness')

after(() => console.log('AQUAFLOW_SUITE_OK 7'))

const original = { opensAt: '08:00', closesAt: '17:00', openMinutes: 60, closedMinutes: 120 }
const edit = (page, field, value) => page.onHoursChange({ currentTarget: { dataset: { field } }, detail: { value } })

function setup(write, read = async () => ({
  data: { operatingStatus: 1, statusText: '正常运营', deliveryHours: original }
})) {
  const app = createApp({ canAccessStationBusiness: () => true })
  const page = loadPage('miniapp-delivery/pages/station-mgmt/station-status/index.js', {
    app, wx: createWx(), stubs: { 'api/station-mgmt': {
      getStationStatus: read,
      updateStationDeliveryHours: write,
      getNotices: async () => ({ data: [] })
    } }
  })
  return { page, app }
}

test('hours are loaded from the station and save separately from the status draft', async () => {
  const { page } = setup(async data => ({ data }))
  await page.loadStatus()
  page.onNoteInput({ detail: { value: '休息公告草稿' } })
  edit(page, 'openMinutes', '45')
  edit(page, 'closesAt', '01:00')
  assert.equal(page.data.hoursSaved.openMinutes, 60)
  assert.equal(page.hoursDraftDirty(), true)
  await page.onSaveHours()
  assert.deepEqual(page.data.hoursSaved, { ...original, openMinutes: 45, closesAt: '01:00' })
  assert.equal(page.hoursDraftDirty(), false)
  assert.equal(page.data.noteInput, '休息公告草稿')
})

test('failed save keeps the draft and the last confirmed settings', async () => {
  const { page } = setup(async () => { throw new Error('暂时无法连接') })
  await page.loadStatus()
  edit(page, 'closedMinutes', '95')
  await page.onSaveHours()
  assert.equal(page.data.hoursError, '暂时无法连接')
  assert.equal(page.data.hoursDraft.closedMinutes, 95)
  assert.equal(page.data.hoursSaved.closedMinutes, 120)
  assert.equal(page.data.hoursSaving, false)
})

test('late save readback from another login cannot update this page', async () => {
  let resolve
  const { page, app } = setup(() => new Promise(done => { resolve = done }))
  await page.loadStatus()
  edit(page, 'closedMinutes', '180')
  const saving = page.onSaveHours()
  app._loginGeneration = 1
  resolve({ data: { ...original, closedMinutes: 180 } })
  await saving
  assert.equal(page.data.hoursSaved.closedMinutes, 120)
})

test('changing the unit preserves exact minutes and editing hours saves minute precision', async () => {
  const { page } = setup(async data => ({ data }))
  const changeUnit = value => page.onHoursUnitChange({ currentTarget: { dataset: { field: 'closedMinutes' } }, detail: { value } })
  await page.loadStatus()
  changeUnit(1)
  assert.equal(page.data.hoursInput.closedMinutes, '2')
  assert.equal(page.data.hoursDirty, false)
  edit(page, 'closedMinutes', '1.25')
  changeUnit(0)
  assert.equal(page.data.hoursInput.closedMinutes, '75')
  edit(page, 'closedMinutes', '95')
  changeUnit(1)
  changeUnit(0)
  assert.equal(page.data.hoursDraft.closedMinutes, 95)
  await page.onSaveHours()
  assert.equal(page.data.hoursSaved.closedMinutes, 95)
})

test('invalid minutes and unverified save receipts preserve the last confirmed schedule', async () => {
  let writes = 0
  const { page } = setup(async data => { writes++; return { data: { ...data, openMinutes: 61 } } })
  await page.loadStatus()
  for (const value of ['0', '1441', '1.2', 'unknown']) {
    edit(page, 'openMinutes', value)
    await page.onSaveHours()
    assert.equal(writes, 0)
    assert.equal(page.data.hoursSaved.openMinutes, 60)
    assert(page.data.hoursError)
  }
  edit(page, 'openMinutes', '45')
  await page.onSaveHours()
  assert.equal(writes, 1)
  assert.equal(page.data.hoursDraft.openMinutes, 45)
  assert.equal(page.data.hoursSaved.openMinutes, 60)
  assert(page.data.hoursError)
})

test('repeated save clicks share one in-flight hours request', async () => {
  let finish, writes = 0
  const { page } = setup(data => { writes++; return new Promise(resolve => { finish = () => resolve({ data }) }) })
  await page.loadStatus()
  edit(page, 'openMinutes', '45')
  const saving = page.onSaveHours()
  await page.onSaveHours()
  assert.equal(writes, 1)
  finish(); await saving
  assert.equal(page.data.hoursSaved.openMinutes, 45)
})

test('three order views preserve the service date and hide missing delivery commitments', () => {
  const fs = require('fs'), path = require('path')
  const { ROOT } = require('./harness')
  const { parseWxml, renderElements } = require('./wxml-tree')
  for (const file of ['miniapp-user/components/OrderCard/index.wxml',
    'miniapp-user/pages/order/detail.wxml', 'miniapp-delivery/pages/order/detail.wxml']) {
    const source = fs.readFileSync(path.join(ROOT, file), 'utf8')
    const pattern = file.includes('OrderCard')
      ? /<text class="time" wx:if="{{\(order.status === 1[\s\S]*?<\/text>/
      : /<view class="info-row" wx:if="{{order.deliveryDeadlineText}}">[\s\S]*?<\/view>/
    const fragment = source.match(pattern)
    assert(fragment, file)
    const tree = parseWxml(fragment[0])
    for (const value of ['11月20日 01:30', '11月21日 23:59', '']) {
      const rows = renderElements(tree, { order: { status: 1, deliveryDeadlineText: value } }, { includeText: true })
      const text = rows.map(row => row.text || '').join(' ')
      if (value) assert(text.includes(value), file)
      else assert(!text.includes('最迟送达'), file)
    }
  }
})
