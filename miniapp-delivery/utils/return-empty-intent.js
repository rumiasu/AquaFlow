// 2026-10-23：还空桶每次点击换键曾绕过后端去重；先保存原意图，未知结果只重试同键。
const positive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0
const keyOf = (actor, customerId) => 'return-empty-intent:' + actor + ':' + customerId
function itemsOf(items) {
  if (!Array.isArray(items) || !items.length || items.length > 200) throw new Error('请填写还桶数量')
  const ids = new Set()
  return items.map(item => {
    const productId = Number(item.productId), qty = Number(item.qty)
    if (!positive(productId) || !positive(qty) || ids.has(productId)) throw new Error('还桶商品或数量未能核对')
    ids.add(productId)
    return { productId, qty }
  }).sort((a, b) => a.productId - b.productId)
}
function read(actor, customerId) {
  if (!/^[1-9]\d*:[1-9]\d*$/.test(actor) || !positive(customerId)) throw new Error('请重新登录后核对原还桶登记')
  let raw
  try { raw = wx.getStorageSync(keyOf(actor, customerId)) }
  catch (_) { throw new Error('原还桶记录暂时无法读取，请重新核对，勿再次交桶') }
  if (raw === '' || raw == null) return null
  if (!raw || raw.version !== 1 || raw.actor !== actor || raw.customerId !== Number(customerId)
      || typeof raw.clientToken !== 'string' || !/^RE-[\w-]+$/.test(raw.clientToken)
      || raw.note !== '站长代客还空桶') throw new Error('原还桶记录无法识别，请保留记录并联系负责人核实')
  const items = itemsOf(raw.items)
  if (JSON.stringify(items) !== JSON.stringify(raw.items)) throw new Error('原还桶数量无法识别，请保留记录并联系负责人核实')
  return { version: 1, actor, customerId: Number(customerId), clientToken: raw.clientToken, items, note: raw.note }
}
function save(value) {
  try { wx.setStorageSync(keyOf(value.actor, value.customerId), value) }
  catch (_) { throw new Error('原还桶记录未能保存，请重新核对后再办理') }
  const saved = read(value.actor, value.customerId)
  if (JSON.stringify(saved) !== JSON.stringify(value)) throw new Error('原还桶记录未能保存，请勿重复交桶，重新核对后再办理')
  return saved
}
function prepare(actor, customerId, items) {
  const old = read(actor, customerId)
  if (old) return old
  return save({ version: 1, actor, customerId: Number(customerId),
    clientToken: 'RE-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2),
    items: itemsOf(items), note: '站长代客还空桶' })
}
function clear(value) {
  const old = read(value.actor, value.customerId)
  if (old && old.clientToken !== value.clientToken) throw new Error('原还桶记录已变化，请重新核对')
  if (old) {
    try { wx.removeStorageSync(keyOf(value.actor, value.customerId)) }
    catch (_) { throw new Error('登记已确认，但本地记录未能清理，请核对原登记，勿再次交桶') }
  }
  if (read(value.actor, value.customerId)) throw new Error('登记已确认，但本地记录未能清理，请核对原登记，勿再次交桶')
}
module.exports = { read, save, prepare, clear }
