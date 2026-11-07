'use strict'
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const root = path.resolve(__dirname, '../..')
const script = fs.readFileSync(path.join(root, 'miniapp-user/components/GuestStationGuide/index.js'), 'utf8')
const { stationName, stationText } = require('../../miniapp-user/utils/station-view')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function fixture(read) {
  let config, epoch = 1
  vm.runInNewContext(script, {
    Component: c => { config = c }, console,
    require: id => {
      if (id === '../../api/station') return { getDiscoverStations: read }
      if (id === '../../utils/token') return { captureSession: () => epoch, isCurrentSession: s => s === epoch }
      if (id === '../../utils/station-view') return { stationName, stationText }
      if (id === '../../utils/station-discovery') return require('../../miniapp-user/utils/station-discovery')
      throw Error('unexpected dependency ' + id)
    }
  })
  const events = []
  const component = { data: JSON.parse(JSON.stringify(config.data)), setData(v) { Object.assign(this.data, v) },
    triggerEvent(name) { events.push(name) }, ...config.methods }
  component.data.scope = { province: '山东省', city: '济南市' }
  component.data.scopeReady = true
  config.lifetimes.attached.call(component)
  return { component, events, changeSession: () => { epoch++ },
    hide: () => config.pageLifetimes.hide.call(component), detach: () => config.lifetimes.detached.call(component) }
}
;(async () => {
  let reads = 0
  const f = fixture(async () => { reads++; return { code: 0, data: [
    { id: 1, name: 'SYNTH A', address: 'A road', status: 1, secret: 'not displayed' },
    { id: 2, name: 'SYNTH B', status: 2 }
  ] } })
  assert.equal(reads, 0)
  await f.component.open()
  assert.equal(reads, 1); assert.equal(f.component.data.stations.length, 1)
  assert.equal(JSON.stringify(f.component.data.stations), '[{"id":1,"name":"SYNTH A","address":"A road"}]')
  f.component.login(); assert.equal(f.events[0], 'login')
  f.component.close(); assert.equal(f.component.data.open, false); assert.equal(f.component.data.stations.length, 0)
  let response = { code: 1, data: [] }
  const e = fixture(async () => response)
  await e.component.open(); assert.equal(e.component.data.error, true)
  response = { code: 0, data: [{ name: 'invalid' }] }; await e.component.retry()
  assert.equal(e.component.data.error, true)
  response = { code: 0, data: [] }; await e.component.retry()
  assert.equal(e.component.data.error, false); assert.equal(e.component.data.stations.length, 0)
  const pending = deferred(), late = fixture(() => pending.promise)
  const old = late.component.open(); late.component.close()
  pending.resolve({ code: 0, data: [{ id: 3, name: 'old', status: 1 }] }); await old
  assert.equal(late.component.data.open, false); assert.equal(late.component.data.stations.length, 0)
  const pending2 = deferred(), changed = fixture(() => pending2.promise)
  const old2 = changed.component.open(); changed.changeSession()
  pending2.resolve({ code: 0, data: [{ id: 4, name: 'old session', status: 1 }] }); await old2
  assert.equal(changed.component.data.stations.length, 0)
  changed.hide(); assert.equal(changed.component.data.open, false)
  const pending3 = deferred(), removed = fixture(() => pending3.promise)
  const old3 = removed.component.open(); removed.detach()
  pending3.resolve({ code: 0, data: [{ id: 5, name: 'detached', status: 1 }] }); await old3
  assert.equal(removed.component.data.stations.length, 0)
  const homeConfig = JSON.parse(fs.readFileSync(path.join(root, 'miniapp-user/pages/home/index.json'), 'utf8'))
  const homeMarkup = fs.readFileSync(path.join(root, 'miniapp-user/pages/home/index.wxml'), 'utf8')
  assert.equal(homeConfig.usingComponents['guest-station-guide'], '../../components/GuestStationGuide/index')
  assert.match(homeMarkup, /<guest-station-guide\s+bind:login="onLogin"\s*\/>/)
  assert.match(homeMarkup, /wx:if="{{!isLogin}}"/)
  console.log('AQUAFLOW_SUITE_OK 1 guest station guide')
})().catch(error => { console.error(error); process.exitCode = 1 })
