// 2026-10-02：未知押金购买曾按改过的表单换key覆盖原件；完整原body必须可靠保存后才可发款。
const PREFIX = 'barrel-right-intents:'
const LEGACY = 'barrel-right-intent'
const memory = new WeakMap()
const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value))
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const object = value => !!value && typeof value === 'object' && !Array.isArray(value)
const integer = value => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const keyValid = value => typeof value === 'string' && value.length > 0 && value.length <= 64 && value.trim() === value
const absent = value => value === '' || value === undefined

function owner(value) {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) value = Number(value)
  if (!integer(value)) throw new Error('请先登录后核实原购买')
  return value
}
function cents(value) {
  if ((typeof value !== 'number' && typeof value !== 'string') || !/^\d+(?:\.\d{1,2})?$/.test(String(value))) return null
  const amount = Math.round(Number(value) * 100)
  return Number.isSafeInteger(amount) && amount > 0 ? amount : null
}
function validBody(body) {
  return object(body) && Object.keys(body).length === 5 && integer(body.stationId) && integer(body.productId)
    && integer(body.quantity) && body.quantity <= 1000 && [1, 2].includes(body.paymentMethod) && keyValid(body.idempotencyKey)
}
/** 只接受原客户/编号/内容的完整购买凭据；不把列表空结果当成“没有原款”。 */
function validRecord(record, body, customerId) {
  return object(record) && integer(record.id) && integer(record.paymentId) && record.customerId === customerId
    && record.idempotencyKey === body.idempotencyKey && record.stationId === body.stationId
    && record.productId === body.productId && record.quantity === body.quantity
    && ['PENDING', 'PAID', 'CANCELLED'].includes(record.status)
    && typeof record.statusText === 'string' && !!record.statusText.trim()
    && cents(record.amount) !== null && cents(record.unitPrice) !== null
    && cents(record.amount) === cents(record.unitPrice) * body.quantity
}
function validRegistry(value, customerId) {
  if (!object(value) || value.version !== 1 || value.customerId !== customerId || !Array.isArray(value.entries)
    || !Object.prototype.hasOwnProperty.call(value, 'activeKey')) return false
  const keys = new Set()
  for (const entry of value.entries) {
    if (!object(entry) || !validBody(entry.body) || keys.has(entry.body.idempotencyKey)
      || (entry.record !== null && !validRecord(entry.record, entry.body, customerId))) return false
    keys.add(entry.body.idempotencyKey)
  }
  return (value.activeKey === null || keys.has(value.activeKey))
    && !(value.activeKey === null && value.entries.some(entry => entry.record === null))
}
function holds() {
  const app = getApp()
  if (!memory.has(app)) memory.set(app, new Map())
  return memory.get(app)
}
function legacyBody(value) {
  if (!object(value) || typeof value.intent !== 'string' || !keyValid(value.key)) throw new Error('旧购买凭据不完整，请联系原水站核实；原件已保留')
  const parts = value.intent.split(':')
  if (parts.length !== 5 || parts.some(part => !/^[1-9]\d*$/.test(part))) throw new Error('旧购买凭据无法辨认，请联系原水站核实；原件已保留')
  const [customerId, stationId, productId, quantity, paymentMethod] = parts.map(Number)
  const body = { stationId, productId, quantity, paymentMethod, idempotencyKey: value.key }
  if (!integer(customerId) || !validBody(body)) throw new Error('旧购买凭据无法辨认，请联系原水站核实；原件已保留')
  return { customerId, body }
}
/** 已收到的原凭据不因本次未查回而消失；矛盾证据不替客户裁定资金真值。 */
function mergeRecord(previous, incoming, verify = false) {
  if (!incoming) return clone(previous)
  if (!previous) return clone(incoming)
  const identity = ['id', 'paymentId', 'customerId', 'stationId', 'productId', 'quantity', 'idempotencyKey']
  if (identity.some(field => previous[field] !== incoming[field])
    || cents(previous.amount) !== cents(incoming.amount) || cents(previous.unitPrice) !== cents(incoming.unitPrice)) {
    throw new Error('查回的原购买凭据不一致，请联系原水站核实；原件已保留')
  }
  if (previous.status !== 'PENDING') {
    if (previous.status !== incoming.status && (verify || incoming.status !== 'PENDING')) {
      throw new Error('查回的原购买状态与已保存凭据不一致，请联系原水站核实；原件已保留')
    }
    return clone(previous)
  }
  return clone(incoming)
}
function mergeEntry(registry, entry) {
  const found = registry.entries.find(item => item.body.idempotencyKey === entry.body.idempotencyKey)
  if (found) {
    if (!same(found.body, entry.body)) throw new Error('原购买内容冲突，请联系水站核实；原件已保留')
    found.record = mergeRecord(found.record, entry.record)
  } else registry.entries.push(clone(entry))
}
/** 原件不删除；另买只切换当前选择，未解决购买仍保留完整body/key供恢复。 */
function load(customerId) {
  customerId = owner(customerId)
  const raw = clone(wx.getStorageSync(PREFIX + customerId))
  const legacyRaw = clone(wx.getStorageSync(LEGACY))
  let registry = absent(raw) ? { version: 1, customerId, activeKey: null, entries: [] } : clone(raw)
  if (!validRegistry(registry, customerId)) throw new Error('购买凭据损坏，请联系原水站核实；原件已保留，暂不能另买')
  let dirty = false
  if (!absent(legacyRaw)) {
    const old = legacyBody(legacyRaw)
    if (old.customerId === customerId && !registry.entries.some(entry => entry.body.idempotencyKey === old.body.idempotencyKey)) {
      mergeEntry(registry, { body: old.body, record: null }); registry.activeKey = old.body.idempotencyKey; dirty = true
    } else if (old.customerId === customerId) mergeEntry(registry, { body: old.body, record: null })
  }
  const held = holds().get(customerId)
  if (held) {
    if (same(registry, held)) holds().delete(customerId)
    else {
      for (const entry of held.entries) mergeEntry(registry, entry)
      registry.activeKey = registry.activeKey || held.activeKey
      dirty = true
    }
  }
  const unknown = registry.entries.find(entry => !entry.record)
  if (unknown && !registry.activeKey) registry.activeKey = unknown.body.idempotencyKey
  return { customerId, registry, raw, legacyRaw, dirty }
}
/** 比较最新原件后写入，并读回完整内容；写入抛错或无效时按客户保住待恢复意图。 */
function save(state, registry) {
  if (!validRegistry(registry, state.customerId)) throw new Error('原购买内容不完整，暂不能继续')
  const next = clone(registry)
  // 任何候选写入都保住已有凭据；旧快照的再次失败也不能覆盖先前保留在内存的终态。
  for (const prior of state.registry.entries) {
    const entry = next.entries.find(item => item.body.idempotencyKey === prior.body.idempotencyKey)
    if (!entry || !same(entry.body, prior.body)) throw new Error('原购买凭据已变化，请重新核实')
    entry.record = mergeRecord(prior.record, entry.record)
  }
  const held = holds().get(state.customerId)
  if (held) for (const entry of held.entries) mergeEntry(next, entry)
  if (!validRegistry(next, state.customerId)) throw new Error('原购买内容不完整，暂不能继续')
  try {
    if (!same(clone(wx.getStorageSync(PREFIX + state.customerId)), state.raw)
      || !same(clone(wx.getStorageSync(LEGACY)), state.legacyRaw)) throw new Error('购买凭据已变化，请重新核实原购买')
    wx.setStorageSync(PREFIX + state.customerId, next)
    if (!same(clone(wx.getStorageSync(PREFIX + state.customerId)), next)) throw new Error('原购买未能可靠保存，请重试原请求')
    holds().delete(state.customerId)
    return load(state.customerId)
  } catch (error) {
    holds().set(state.customerId, next)
    throw new Error(error.message || '原购买未能可靠保存，请重试原请求')
  }
}
function responseRecord(response, body, customerId) {
  const data = response && response.data
  if ((response && response.code !== undefined && ![0, 200].includes(response.code)) || !object(data)
    || !validRecord(data.purchase, body, customerId) || data.paymentId !== data.purchase.paymentId
    || cents(data.amount) !== cents(data.purchase.amount) || !Number.isInteger(data.status) || ![0, 1, 2, 3, 4].includes(data.status)
    || (data.status === 2 && data.purchase.status !== 'PAID') || (data.status === 4 && data.purchase.status !== 'CANCELLED')
    || ([0, 1].includes(data.status) && data.purchase.status !== 'PENDING')) throw new Error('原购买结果尚未核实，请查询或恢复原请求')
  return clone(data.purchase)
}
function queryRecord(response, body, customerId) {
  if ((response && response.code !== undefined && ![0, 200].includes(response.code)) || !response || !Array.isArray(response.data)) throw new Error('原购买记录暂时无法核实，请稍后重试')
  const matches = response.data.filter(record => object(record) && record.idempotencyKey === body.idempotencyKey)
  if (!matches.length) return null
  if (matches.length !== 1 || !validRecord(matches[0], body, customerId)) throw new Error('查回的原购买内容不一致，请联系水站核实')
  return clone(matches[0])
}
module.exports = { PREFIX, LEGACY, owner, validBody, validRecord, load, save, same, clone, mergeRecord, responseRecord, queryRecord }
