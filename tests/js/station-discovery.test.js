const assert = require('assert')
const fs = require('fs'), path = require('path'), vm = require('vm')
const { loadPage, createWx, createApp, armWatchdog, ROOT } = require('./harness')
const { parseWxml, renderElements, validateConditionalSiblings } = require('./wxml-tree')
const discovery = require('../../miniapp-user/utils/station-discovery')
const done = armWatchdog(), tests = [], test = (name, run) => tests.push({ name, run })
const plain = value => JSON.parse(JSON.stringify(value))
const city = { province: '山东省', city: '济南市' }, otherCity = { province: '山东省', city: '青岛市' }
const point = { lat: 36.6, lng: 117.1 }, both = { ...point, ...city }
const result = id => ({ code: 0, data: id ? [{ id, name: '合成水站', status: 1 }] : [] })
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }

function componentFixture(relative = 'StationDiscoveryScope', read = async () => result(), options = {}) {
  let config, epoch = 1
  const wx = createWx(), maps = [], events = []
  wx.chooseLocation = options => maps.push(options)
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'miniapp-user/components', relative, 'index.js'), 'utf8'), {
    Component: value => { config = value }, wx, console,
    require: id => {
      if (id.endsWith('/utils/token')) return {
        captureSession: () => ({ epoch, loggedIn: !!options.addresses, customerId: options.addresses ? 7 : null }),
        isCurrentSession: value => value && value.epoch === epoch
      }
      if (id.endsWith('/api/address')) return { getAddresses: options.addresses || (async () => ({ code: 0, data: [] })) }
      if (id.endsWith('/utils/storage')) return { deliveryAddressStorage: { getId: () => options.activeId == null ? null : options.activeId } }
      if (id.endsWith('/api/station')) return { getDiscoverStations: read, getPublicStations: () => { throw Error('unscoped request') } }
      return require(path.resolve(ROOT, 'miniapp-user/components', relative, id))
    }
  })
  const c = { data: plain(config.data), properties: { active: true, scope: null },
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push({ name, detail: detail && plain(detail) }); if (name === 'scopechange') this.properties.scope = detail }
  }
  Object.entries(config.methods).forEach(([name, method]) => { c[name] = method.bind(c) })
  config.lifetimes.attached.call(c)
  return { c, maps, events, wx, changeSession() { epoch++ },
    hide() { config.pageLifetimes.hide.call(c) }, show() { return config.pageLifetimes.show.call(c) },
    detach() { config.lifetimes.detached.call(c) },
    close() { c.properties.active = false; config.properties.active.observer.call(c, false) },
    reopen() { c.properties.active = true; return config.properties.active.observer.call(c, true) },
    changes() { return events.filter(event => event.name === 'scopechange').map(event => event.detail) }
  }
}
const chooseCity = (f, region) => f.c.onCityChange({ detail: { value: region } })
const mapSuccess = (f, data, index = 0) => { f.maps[index].success(data); f.maps[index].complete() }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function pageFixture(folder, read = async () => result()) {
  const wx = createWx(), app = createApp({ getCurrentStationId: () => null, getCartCount: () => 0 }), reads = []
  const page = loadPage('miniapp-user/pages/' + folder + '/index.js', { wx, app, stubs: {
    'api/station': { getDiscoverStations: scope => { reads.push(plain(scope)); return read(scope) }, getPublicStations: () => { throw Error('unscoped request') }, getStationStatus: async () => ({ code: 0, data: {} }) },
    'api/order': { getMyLatestStation: async () => ({ code: 0, data: null }) }
  } })
  return { page, reads, wx, app }
}

test('absent and invalid location never normalize into an unrestricted or false-positive request', () => {
  for (const value of [null, {}, { province: '山东省' }, { city: ' ' }, { lat: null, lng: null }, { lat: '', lng: '' },
    { lat: '36', lng: '117' }, { lat: 36 }, { lng: 117 }, { lat: NaN, lng: 117 }, { lat: 91, lng: 117 },
    { lat: 36, lng: 181 }, { ...city, lat: null, lng: null }]) assert.equal(discovery.discoveryScope(value), null)
  assert.deepStrictEqual(discovery.discoveryScope({ lat: 0, lng: 0 }), { lat: 0, lng: 0 })
  assert.deepStrictEqual(discovery.discoveryScope({ province: ' 山东省 ', city: ' 济南市 ' }), city)
  assert.deepStrictEqual(discovery.discoveryScope(both), { ...point, city: city.city, province: city.province })
})
test('API sends exact discovery scope while keeping the historical public directory unchanged', async () => {
  const calls = [], module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'miniapp-user/api/station.js'), 'utf8'), {
    module, require: id => id.endsWith('/utils/request') ? { get: (url, params) => { calls.push({ url, params }); return Promise.resolve(result()) } }
      : id.endsWith('/config/api') ? { API: { STATIONS: '/api/stations', STATIONS_PUBLIC: '/api/stations/public' } } : discovery
  })
  await assert.rejects(module.exports.getDiscoverStations({}), /选择城市/)
  await assert.rejects(module.exports.getDiscoverStations({ ...city, lat: null }), /选择城市/)
  assert.equal(calls.length, 0)
  await module.exports.getDiscoverStations(city); await module.exports.getDiscoverStations(point); await module.exports.getDiscoverStations(both)
  await module.exports.getPublicStations()
  assert.deepStrictEqual(plain(calls), [
    { url: '/api/stations/discover', params: city }, { url: '/api/stations/discover', params: point },
    { url: '/api/stations/discover', params: { ...point, city: city.city, province: city.province } }, { url: '/api/stations/public' }
  ])
})
test('city selection needs no map permission and does not infer a location', () => {
  const f = componentFixture(); chooseCity(f, ['山东省', '济南市'])
  assert.deepStrictEqual(f.changes(), [city]); assert.equal(f.maps.length, 0)
})
test('complete map address sends coordinates and city, but never the prior city', () => {
  const f = componentFixture(); f.c.properties.scope = otherCity; f.c.onChooseLocation()
  mapSuccess(f, { latitude: point.lat, longitude: point.lng, address: '山东省济南市历下区测试路' })
  assert.deepStrictEqual(f.changes(), [{ ...point, city: city.city, province: city.province }])
})
for (const [address, province, cityName] of [
  ['吉林省延边朝鲜族自治州延吉市测试路', '吉林省', '延边朝鲜族自治州'],
  ['吉林省延边州延吉市测试路', '吉林省', '延边朝鲜族自治州'],
  ['黑龙江省大兴安岭地区漠河市测试路', '黑龙江省', '大兴安岭地区'],
  ['内蒙古自治区兴安盟阿尔山市测试路', '内蒙古自治区', '兴安盟'],
  ['广西壮族自治区南宁市青秀区测试路', '广西壮族自治区', '南宁市'],
  ['新疆维吾尔自治区伊犁哈萨克自治州伊宁市测试路', '新疆维吾尔自治区', '伊犁哈萨克自治州']
]) test('ambiguous map prefecture is never inferred; confirmed city retains coordinates: ' + cityName, () => {
  const f = componentFixture(); f.c.properties.scope = otherCity; f.c.onChooseLocation()
  mapSuccess(f, { latitude: point.lat, longitude: point.lng, address })
  assert.deepStrictEqual(f.changes(), [point]); assert.match(f.c.data.message, /城市暂未识别/)
  assert.deepStrictEqual(plain(f.c.data.region), [])
  chooseCity(f, [province, cityName])
  assert.deepStrictEqual(f.changes()[1], { province, city: cityName, ...point })
  assert.match(f.c.data.message, /已确认地图所在城市/)
})
for (const [address, province, cityName] of [
  ['浙江省杭州市西湖区测试路', '浙江省', '杭州市'],
  ['重庆市渝中区测试路', '重庆市', '重庆市']
]) test('ordinary city and municipality remain eligible for map prefill: ' + cityName, () => {
  const f = componentFixture(); f.c.onChooseLocation()
  mapSuccess(f, { latitude: point.lat, longitude: point.lng, address })
  assert.deepStrictEqual(f.changes(), [{ ...point, city: cityName, province }])
})
test('unknown map city clears prior city and explicit confirmation can add map city', () => {
  const f = componentFixture(); f.c.properties.scope = otherCity; f.c.onChooseLocation()
  mapSuccess(f, { latitude: point.lat, longitude: point.lng, address: '测试街道' })
  assert.deepStrictEqual(f.changes(), [point]); assert.match(f.c.data.message, /城市暂未识别/)
  chooseCity(f, ['山东省', '济南市'])
  assert.deepStrictEqual(f.changes()[1], { ...city, ...point })
})
test('choosing a different city clears old map coordinates and discloses that reset', () => {
  const f = componentFixture(); f.c.properties.scope = both
  chooseCity(f, ['山东省', '青岛市'])
  assert.deepStrictEqual(f.changes(), [otherCity]); assert.match(f.c.data.message, /原地图位置已清除/)
})
test('switching city without a prior map does not claim a map position was cleared', () => {
  const f = componentFixture(); f.c.properties.scope = city; chooseCity(f, ['山东省', '青岛市'])
  assert.deepStrictEqual(f.changes(), [otherCity]); assert.doesNotMatch(f.c.data.message, /原地图位置已清除/)
})
test('confirming the same map city preserves both conditions', () => {
  const f = componentFixture(); f.c.properties.scope = both; chooseCity(f, ['山东省', '济南市'])
  assert.deepStrictEqual(f.changes(), [{ ...city, ...point }])
})
test('map results without complete numeric coordinates never become a selected location', () => {
  for (const coordinates of [{ latitude: null, longitude: null }, { latitude: point.lat }, { latitude: '', longitude: '' }, { latitude: 91, longitude: 117 }]) {
    const f = componentFixture(); f.c.properties.scope = city; f.c.onChooseLocation(); mapSuccess(f, coordinates)
    assert.equal(f.changes().length, 0); assert.deepStrictEqual(f.c.properties.scope, city); assert.match(f.c.data.message, /位置不完整/)
  }
})
test('denied, canceled, failed and unavailable map flows preserve prior scope and offer city selection', () => {
  for (const message of ['chooseLocation:fail auth deny', 'chooseLocation:fail cancel', 'chooseLocation:fail unavailable']) {
    const f = componentFixture(); f.c.properties.scope = city; f.c.onChooseLocation(); f.maps[0].fail({ errMsg: message }); f.maps[0].complete()
    assert.equal(f.changes().length, 0); assert.deepStrictEqual(f.c.properties.scope, city); assert.equal(f.c.data.locating, false); assert.ok(f.c.data.message)
  }
  const f = componentFixture(); f.wx.chooseLocation = undefined; f.c.onChooseLocation(); assert.match(f.c.data.message, /选择城市/)
})
test('map selection is single flight and hidden native result waits for return', () => {
  const f = componentFixture(); f.c.onChooseLocation(); f.c.onChooseLocation(); assert.equal(f.maps.length, 1)
  f.hide(); mapSuccess(f, { latitude: point.lat, longitude: point.lng }); assert.equal(f.changes().length, 0)
  f.show(); assert.deepStrictEqual(f.changes(), [point]); assert.equal(f.c.data.locating, false)
})
for (const interruption of ['close', 'detach', 'changeSession']) test('late map callback cannot change scope after ' + interruption, () => {
  const f = componentFixture(); f.c.onChooseLocation(); f[interruption](); mapSuccess(f, { latitude: point.lat, longitude: point.lng })
  assert.equal(f.changes().length, 0)
})
test('new city choice wins over an older native map callback', () => {
  const f = componentFixture(); f.c.onChooseLocation(); chooseCity(f, ['山东省', '青岛市'])
  mapSuccess(f, { latitude: point.lat, longitude: point.lng }); assert.deepStrictEqual(f.changes(), [otherCity])
})

for (const folder of ['home', 'shop']) {
  test(folder + ' never reads without scope, including partial coordinates and stale cached candidates', async () => {
    const f = pageFixture(folder); f.page.setData({ stationList: [{ id: 99 }], stationListError: 'old' })
    await f.page.loadStationList(); assert.equal(f.reads.length, 0); assert.equal(f.page.data.stationDiscoveryReady, false)
    assert.equal(f.page.data.stationList.length, 0); assert.equal(f.page.data.stationListError, '')
    await f.page.onStationDiscoveryChange({ detail: { ...city, lat: null, lng: null } }); assert.equal(f.reads.length, 0)
  })
  test(folder + ' reads selected city or both conditions and does not change the saved order station', async () => {
    const f = pageFixture(folder); f.wx.setStorageSync('selectedStation', { id: 88, name: '历史下单站' })
    await f.page.onStationDiscoveryChange({ detail: city }); await f.page.onStationDiscoveryChange({ detail: both })
    assert.deepStrictEqual(f.reads, [city, { ...point, city: city.city, province: city.province }])
    assert.equal(f.wx.getStorageSync('selectedStation').id, 88)
  })
  test(folder + ' rejects old scope results while exposing successful empty and failed reads distinctly', async () => {
    const old = deferred(); let call = 0
    const f = pageFixture(folder, () => ++call === 1 ? old.promise : Promise.resolve(result(2)))
    const pending = f.page.onStationDiscoveryChange({ detail: city })
    assert.equal(f.page.data.stationList.length, 0); assert.equal(f.page.data.stationListLoading, true)
    await f.page.onStationDiscoveryChange({ detail: otherCity }); old.resolve(result(1)); await pending
    assert.equal(f.page.data.stationList[0].id, 2)
    const empty = pageFixture(folder); await empty.page.onStationDiscoveryChange({ detail: city })
    assert.equal(empty.page.data.stationDiscoveryReady, true); assert.equal(empty.page.data.stationListError, ''); assert.equal(empty.page.data.stationList.length, 0)
    const fail = pageFixture(folder, async () => ({ code: 1, message: '暂时不可用', data: [] }))
    await fail.page.onStationDiscoveryChange({ detail: city }); assert.ok(fail.page.data.stationListError)
  })
  test(folder + ' selector close invalidates its pending discovery response', async () => {
    const old = deferred(), f = pageFixture(folder, () => old.promise)
    const pending = f.page.onStationDiscoveryChange({ detail: city })
    f.page[folder === 'home' ? 'onCloseStationList' : 'onCloseStationPicker']()
    old.resolve(result(1)); await pending; assert.equal(f.page.data.stationList.length, 0)
  })
  test(folder + ' native map hide preserves selector but a normal hide does not reuse pending list', async () => {
    const old = deferred(), f = pageFixture(folder, () => old.promise)
    const key = folder === 'home' ? 'showStationList' : 'showStationPicker'; f.page.setData({ [key]: true })
    f.page.onStationLocationStart(); const pending = f.page.onStationDiscoveryChange({ detail: city }); f.page.onHide()
    assert.equal(folder === 'home' ? f.page.data.showStationList : f.page._resumeStationPicker, true)
    old.resolve(result(1)); await pending; assert.equal(f.page.data.stationList.length, 0)
    f.page.onStationLocationEnd(); f.page.onHide()
    assert.equal(folder === 'home' ? f.page.data.showStationList : f.page._resumeStationPicker, false)
  })
}
test('shop native map navigation does not dismiss the selector through page-container leave', () => {
  const f = pageFixture('shop'); f.page.setData({ showStationPicker: true }); f.page.onStationLocationStart()
  f.page.onStationPickerBeforeLeave(); assert.equal(f.page.data.showStationPicker, true)
  f.page.onStationLocationEnd(); f.page.onStationPickerBeforeLeave(); assert.equal(f.page.data.showStationPicker, false)
})
test('guest has no initial global read and receives only explicitly scoped results', async () => {
  const reads = [], f = componentFixture('GuestStationGuide', async scope => { reads.push(plain(scope)); return result(1) })
  await f.c.open(); assert.equal(reads.length, 0); assert.equal(f.c.data.scopeReady, false)
  await f.c.onScopeChange({ detail: both }); assert.deepStrictEqual(reads, [{ ...point, city: city.city, province: city.province }])
  assert.equal(f.c.data.stations.length, 1)
})
test('guest map hide preserves open selector and clears interrupted loading for return', async () => {
  const old = deferred(), f = componentFixture('GuestStationGuide', () => old.promise)
  await f.c.open(); const pending = f.c.onScopeChange({ detail: city }); f.c.onLocationStart(); f.hide()
  assert.equal(f.c.data.open, true); assert.equal(f.c.data.loading, false)
  old.resolve(result(1)); await pending; assert.equal(f.c.data.stations.length, 0)
  f.c.onLocationEnd(); f.hide(); assert.equal(f.c.data.open, false)
})
test('guest canceling native map while a prior list is pending reloads on actual return', async () => {
  const old = deferred(); let reads = 0
  const f = componentFixture('GuestStationGuide', () => ++reads === 1 ? old.promise : Promise.resolve(result(2)))
  await f.c.open(); const pending = f.c.onScopeChange({ detail: city }); f.c.onLocationStart(); f.hide()
  f.c.onLocationEnd(); await f.show()
  assert.equal(reads, 2); assert.equal(f.c.data.loading, false); assert.equal(f.c.data.stations[0].id, 2)
  old.resolve(result(1)); await pending; assert.equal(f.c.data.stations[0].id, 2)
})
test('guest newer city and close reject previous scope responses', async () => {
  const old = deferred(); let call = 0
  const f = componentFixture('GuestStationGuide', () => ++call === 1 ? old.promise : Promise.resolve(result(2)))
  await f.c.open(); const pending = f.c.onScopeChange({ detail: city }); await f.c.onScopeChange({ detail: otherCity })
  old.resolve(result(1)); await pending; assert.equal(f.c.data.stations[0].id, 2)
  f.c.close(); assert.equal(f.c.data.stations.length, 0)
})
test('scope markup leads with location, concise source and honest fallback guidance', () => {
  const scopeTree = parseWxml(fs.readFileSync(path.join(ROOT, 'miniapp-user/components/StationDiscoveryScope/index.wxml'), 'utf8'))
  validateConditionalSiblings(scopeTree)
  const nodes = scope => renderElements(scopeTree, { scope, active: true, locating: false, addressLoading: false, source: '', message: '', region: [] }, { includeText: true })
  const texts = scope => nodes(scope).map(n => n.text || '').join('\n')
  assert.match(texts(null), /可在地图选点/); assert.match(texts(city), /济南市/)
  assert.match(texts(both), /按距离排序/)
  const markup = fs.readFileSync(path.join(ROOT, 'miniapp-user/components/StationDiscoveryScope/index.wxml'), 'utf8')
  assert(markup.indexOf('bindtap="onChooseLocation"') < markup.indexOf('<picker'))
  for (const folder of ['home', 'shop']) {
    const markup = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages', folder, 'index.wxml'), 'utf8')
    validateConditionalSiblings(parseWxml(markup)); assert.match(markup, /<station-discovery-scope/)
    assert.match(markup, /stationDiscoveryReady.*stationList\.length === 0/)
  }
})
test('address discovery prefers active address, then default, without inventing coordinates', () => {
  const defaultAddress = { id: 1, isDefault: 1, ...city, ...point }
  const activeAddress = { id: 2, ...otherCity, lat: 36.1, lng: 120.3 }
  assert.deepStrictEqual(discovery.addressDiscovery([defaultAddress, activeAddress], 2), {
    scope: { lat: 36.1, lng: 120.3, city: otherCity.city, province: otherCity.province }, source: 'active', coordinates: true
  })
  assert.equal(discovery.addressDiscovery([defaultAddress, activeAddress], 999).source, 'default')
  assert.deepStrictEqual(discovery.addressDiscovery([defaultAddress, { ...activeAddress, lat: null }], 2), {
    scope: otherCity, source: 'active', coordinates: false
  })
  assert.equal(discovery.addressDiscovery([{ id: 3, ...point }], null), null)
  assert.equal(discovery.addressDiscovery([{ id: 3, isDefault: 1, lat: null, lng: null }], null), null)
  assert.deepStrictEqual(discovery.addressDiscovery([{ id: 3, isDefault: true, lat: 0, lng: 0 }], null).scope, { lat: 0, lng: 0 })
  assert.equal(discovery.addressDiscovery([{ id: 3, isDefault: 1, lat: '36', lng: '117' }], null), null)
})
for (const activeId of [null, 2]) test('selector automatically reads ' + (activeId ? 'active' : 'default') + ' address before manual city choice', async () => {
  const options = { activeId, addresses: async () => ({ code: 0, data: [
    { id: 1, isDefault: 1, ...city, ...point }, { id: 2, ...otherCity, lat: 36.1, lng: 120.3 }
  ] }) }
  const f = componentFixture('StationDiscoveryScope', undefined, options)
  assert.equal(f.c.data.addressLoading, true); await flush()
  assert.deepStrictEqual(f.changes(), [activeId ? { lat: 36.1, lng: 120.3, city: otherCity.city, province: otherCity.province } : { ...point, city: city.city, province: city.province }])
  assert.equal(f.maps.length, 0); assert.equal(f.c.data.addressLoading, false)
  assert.match(f.c.data.source, activeId ? /正在使用/ : /默认/)
})
test('active address without coordinates uses only its own city, with an explicit map fallback', async () => {
  const f = componentFixture('StationDiscoveryScope', undefined, { activeId: 2, addresses: async () => ({ code: 0, data: [
    { id: 1, isDefault: 1, ...city, ...point }, { id: 2, ...otherCity, lat: null, lng: null }
  ] }) })
  await flush(); assert.deepStrictEqual(f.changes(), [otherCity]); assert.match(f.c.data.message, /地址无坐标/)
})
for (const response of [{ code: 1, data: [{ id: 1, isDefault: 1, ...point }] }, { code: 0, data: [] }, { code: 0, data: [{ id: 1, isDefault: 1, lat: null, lng: null }] }]) {
  test('unavailable address never becomes an unscoped discovery read: ' + JSON.stringify(response), async () => {
    const f = componentFixture('StationDiscoveryScope', undefined, { addresses: async () => response })
    await flush(); assert.equal(f.changes().length, 0); assert.ok(f.c.data.message); assert.equal(f.maps.length, 0)
  })
}
for (const interruption of ['close', 'detach', 'hide', 'changeSession']) test('automatic address callback is rejected after ' + interruption, async () => {
  const pending = deferred(), f = componentFixture('StationDiscoveryScope', undefined, { addresses: () => pending.promise })
  f[interruption](); pending.resolve({ code: 0, data: [{ id: 1, isDefault: 1, ...point }] }); await flush()
  assert.equal(f.changes().length, 0)
})
test('explicit city selection wins over an older automatic address read', async () => {
  const pending = deferred(), f = componentFixture('StationDiscoveryScope', undefined, { addresses: () => pending.promise })
  chooseCity(f, ['山东省', '青岛市']); pending.resolve({ code: 0, data: [{ id: 1, isDefault: 1, ...point }] }); await flush()
  assert.deepStrictEqual(f.changes(), [otherCity])
})
test('concurrent home address resolution uses the newest active ID instead of leaving discovery empty', async () => {
  const pending = deferred(), options = { addresses: () => pending.promise, activeId: null }
  const f = componentFixture('StationDiscoveryScope', undefined, options)
  options.activeId = 2
  pending.resolve({ code: 0, data: [{ id: 1, isDefault: 1, ...city, ...point }, { id: 2, ...otherCity, lat: 36.1, lng: 120.3 }] })
  await flush(); assert.deepStrictEqual(f.changes(), [{ lat: 36.1, lng: 120.3, city: otherCity.city, province: otherCity.province }])
  assert.equal(f.c.data.addressLoading, false)
})
test('reopening rechecks address, while canceled manual map retains current address scope', async () => {
  let lat = point.lat
  const f = componentFixture('StationDiscoveryScope', undefined, { addresses: async () => ({ code: 0, data: [{ id: 1, isDefault: 1, lat, lng: point.lng }] }) })
  await flush(); f.c.onChooseLocation(); f.maps[0].fail({ errMsg: 'chooseLocation:fail cancel' }); f.maps[0].complete()
  assert.deepStrictEqual(f.c.properties.scope, point)
  f.close(); lat = 36.8; f.reopen(); await flush()
  assert.deepStrictEqual(f.c.properties.scope, { lat: 36.8, lng: point.lng })
})
for (const folder of ['home', 'shop']) test(folder + ' account switch clears earlier address scope before another discovery request', async () => {
  const f = pageFixture(folder); await f.page.onStationDiscoveryChange({ detail: both })
  f.app.globalData.customerId = 8
  await f.page.loadStationList()
  assert.equal(f.reads.length, 1); assert.equal(f.page.data.stationDiscoveryScope, null); assert.equal(f.page.data.stationList.length, 0)
})
test('historical assets and readiness probe retain their existing public directory paths', () => {
  const claims = fs.readFileSync(path.join(ROOT, 'miniapp-user/pages/mine/asset-claims/index.js'), 'utf8')
  assert.match(claims, /getPublicStations\(\)/); assert.doesNotMatch(claims, /getDiscoverStations/)
  const health = fs.readFileSync(path.join(ROOT, 'miniapp-user/utils/backendCheck.js'), 'utf8')
  assert.match(health, /\/api\/stations\/public/)
})
;(async () => {
  let failures = 0
  for (const t of tests) { try { await t.run(); console.log('PASS ' + t.name) } catch (error) { failures++; console.error('FAIL ' + t.name, error) } }
  if (failures) process.exitCode = 1
  else console.log('AQUAFLOW_SUITE_OK ' + tests.length)
})().finally(done)
