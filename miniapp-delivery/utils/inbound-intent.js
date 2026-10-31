// 原入库持久保存后再发送；未知恢复仅沿原编号/数量，不能把改过的输入当作原请求。
const { getTransportConfig } = require('../config/api')
const positive = n => typeof n === 'number' && Number.isSafeInteger(n) && n > 0
let serial = 0
function route() {
  const c = getTransportConfig()
  return JSON.stringify(c.mode === 'cloudContainer' ? [c.mode, c.env, c.service] : [c.mode, c.baseUrl])
}
function scope(actor, productId) {
  if (typeof actor !== 'string' || !/^[1-9]\d*:[1-9]\d*$/.test(actor) || !positive(Number(productId))) throw new Error('请重新登录后核对原入库')
  return 'inventory-inbound-intent:' + JSON.stringify([route(), actor, Number(productId)])
}
function itemsOf(items) {
  if (!Array.isArray(items) || items.length !== 1 || !items[0]) throw new Error('请核对原入库商品')
  const { productId, quantity } = items[0]
  if (!positive(productId) || !positive(quantity) || quantity > 2147483647) throw new Error('请填写有效入库数量')
  const raw = items[0].costAmount
  if (raw === null || raw === undefined) return [{ productId, quantity }]
  const text = String(raw).trim()
  if (!/^(0|[1-9]\d{0,8})(\.\d{1,2})?$/.test(text)) throw new Error('进货总成本请填非负金额，最多两位小数')
  return [{ productId, quantity, costAmount: Number(Number(text).toFixed(2)) }]
}
function read(actor, productId) {
  let raw
  try { raw = wx.getStorageSync(scope(actor, productId)) } catch (_) { throw new Error('原入库记录暂时无法读取，请保留记录后核实') }
  if (raw === '' || raw == null) return null
  if (!raw || raw.version !== 1 || raw.actor !== actor || raw.route !== route()
      || raw.stationId !== Number(actor.split(':')[1]) || !/^INB-[A-Za-z0-9_-]{12,76}$/.test(raw.clientRequestId || '')) throw new Error('原入库记录无法识别，请勿重复入库')
  const items = itemsOf(raw.items)
  if (items[0].productId !== Number(productId) || JSON.stringify(items) !== JSON.stringify(raw.items)) throw new Error('原入库数量无法识别，请保留记录核实')
  return { version: 1, route: raw.route, actor, stationId: raw.stationId, clientRequestId: raw.clientRequestId, items }
}
function prepare(actor, items) {
  const normalized = itemsOf(items), productId = normalized[0].productId
  const existing = read(actor, productId)
  if (existing) return existing
  const value = { version: 1, route: route(), actor, stationId: Number(actor.split(':')[1]),
    clientRequestId: 'INB-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2).padEnd(12, '0') + '-' + (++serial).toString(36), items: normalized }
  try { wx.setStorageSync(scope(actor, productId), value) } catch (_) { throw new Error('原入库记录未能保存，请核对后再操作') }
  const saved = read(actor, productId)
  if (JSON.stringify(saved) !== JSON.stringify(value)) throw new Error('原入库记录未能保存，请勿提交')
  return saved
}
function payload(value) { return { clientRequestId: value.clientRequestId, items: itemsOf(value.items) } }
function acceptsReceipt(value, response) {
  const data = response && response.data
  try { return !!(response && response.code === 0 && data && data.status === 'APPLIED'
      && data.clientRequestId === value.clientRequestId && data.stationId === value.stationId
      && JSON.stringify(itemsOf(data.items)) === JSON.stringify(itemsOf(value.items))) }
  catch (_) { return false }
}
function clear(value) {
  const productId = value.items[0].productId, current = read(value.actor, productId)
  if (current && current.clientRequestId !== value.clientRequestId) throw new Error('原入库记录已变化，请核对后再操作')
  if (current) { try { wx.removeStorageSync(scope(value.actor, productId)) } catch (_) { throw new Error('入库已确认，但原记录尚未清理，请勿另开入库') } }
  if (read(value.actor, productId)) throw new Error('入库已确认，但原记录尚未清理，请勿另开入库')
}
module.exports = { read, prepare, payload, acceptsReceipt, clear }
