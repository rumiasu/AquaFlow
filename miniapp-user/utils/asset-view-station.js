// 资产查看范围：仅本登录周期内共享；绝不写首页/下单的 selectedStation。
const { stationStorage } = require('./storage')
const { captureSession, isCurrentSession } = require('./token')
const { getAssetStation } = require('../api/asset-stations')

let remembered = null
let metadataEpoch = null
let metadata = {}
const positiveId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : null

function selection(page) {
  const session = captureSession()
  if (metadataEpoch !== session.epoch) { metadataEpoch = session.epoch; metadata = {} }
  if (remembered && !isCurrentSession(remembered.session)) remembered = null
  if (remembered) return remembered.station
  const station = stationStorage.get()
  return station && positiveId(station.id) ? metadata[station.id] || { id: positiveId(station.id), name: station.name || '' } : null
}

function sync(page) {
  const station = selection(page)
  const orderStation = stationStorage.get()
  page.setData({ viewStationId: station ? station.id : null,
    stationName: station ? station.name : '', orderStationName: orderStation && orderStation.name || '',
    viewStationStatusText: station && station.statusText || '' })
  return station
}

function init(page, options = {}) {
  const id = positiveId(options.stationId)
  if (id) remembered = { session: captureSession(), station: { id, name: '' } }
  sync(page)
}

function activate(page) {
  page._assetHidden = false
  page._assetLife = (page._assetLife || 0) + 1
  const station = sync(page)
  // 直接链接到历史站时，只经本人关系接口取得站名；不信任 URL 的站名。
  if (station && !station.name) {
    const context = beginRead(page, 'stationName')
    getAssetStation(station.id).then(res => {
      if (!current(page, context) || !res || res.code !== 0 || !res.data
        || positiveId(res.data.id) !== station.id) return
      metadata[station.id] = res.data
      if (remembered && remembered.station.id === station.id) remembered = { session: context.session, station: res.data }
      sync(page)
    }).catch(() => { /* 范围仍显示站号；资产读取和列表重试不依赖名称成功。 */ })
  }
}

function suspend(page) {
  page._assetHidden = true
  page._assetLife = (page._assetLife || 0) + 1
}

function beginRead(page, slot = 'assets') {
  const station = sync(page)
  page._assetSequences = page._assetSequences || {}
  const sequence = page._assetSequences[slot] = (page._assetSequences[slot] || 0) + 1
  return { stationId: station && station.id, session: captureSession(), life: page._assetLife || 0, slot, sequence }
}

function current(page, context) {
  const station = selection(page)
  return !page._assetHidden && isCurrentSession(context.session)
    && (page._assetLife || 0) === context.life
    && (station && station.id) === context.stationId
    && page._assetSequences[context.slot] === context.sequence
}

function select(page, station) {
  const id = positiveId(station && station.id)
  if (!id || page.data.submitting || page.data.recoveringPurchase || page._returnConfirmFlight || page._arrangementFlight) return false
  const chosen = { id, name: station.name || '', status: station.status, statusText: station.statusText || '' }
  remembered = { session: captureSession(), station: chosen }
  page._assetLife = (page._assetLife || 0) + 1
  page._arrangementLife = (page._arrangementLife || 0) + 1
  sync(page)
  return true
}

function id(page) { const station = selection(page); return station && station.id }
function url(page, path) { const stationId = id(page); return stationId ? path + (path.includes('?') ? '&' : '?') + 'stationId=' + stationId : path }

module.exports = { init, activate, suspend, beginRead, current, select, id, url, sync }
