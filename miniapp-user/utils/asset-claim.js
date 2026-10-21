// 两端小程序隔离打包；本文件与员工端同名工具保持同一契约，不跨包 require。
const object = x => !!x && typeof x === 'object' && !Array.isArray(x)
const clone = x => JSON.parse(JSON.stringify(x))
const fail = message => { throw Object.assign(Error(message), { localClaimError: true }) }
function id(value) {
  const text = String(value == null ? '' : value)
  return /^[1-9]\d*$/.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : null
}
function quantity(value) {
  const text = String(value == null ? '' : value).trim()
  if (!/^(0|[1-9]\d*)$/.test(text) || Number(text) > 2147483647) fail('桶数和水票须填写完整的非负整数')
  return Number(text)
}
function cents(value) {
  const text = String(value == null ? '' : value).trim()
  if (!/^(0|[1-9]\d*)(\.\d{1,2})?$/.test(text)) fail('金额须填写完整数值，最多两位小数')
  const parts = text.split('.'), amount = Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0'))
  if (!Number.isSafeInteger(amount) || amount > 9999999999) fail('金额超出可保存范围')
  return amount
}
function money(value) { const n = cents(value); return Math.floor(n / 100) + '.' + String(n % 100).padStart(2, '0') }
function body(raw) {
  if (!object(raw) || !id(raw.stationId) || raw.confirmed !== true || typeof raw.clientToken !== 'string'
    || !raw.clientToken.trim() || raw.clientToken.length > 64 || typeof raw.note !== 'string'
    || !raw.note.trim() || raw.note.trim().length > 500 || !Array.isArray(raw.items) || raw.items.length > 100) fail('请完整核对认领水站、历史资产和说明')
  const seen = new Set(), depositBalance = money(raw.depositBalance)
  let nonempty = cents(depositBalance) > 0
  const items = raw.items.map(row => {
    if (!object(row) || !id(row.productId) || seen.has(id(row.productId))) fail('同一商品只能填写一行，请重新核对')
    const productId = id(row.productId); seen.add(productId)
    const rights = quantity(row.rights), held = quantity(row.held), tickets = quantity(row.tickets)
    const unitPrice = row.unitPrice == null || row.unitPrice === '' ? null : money(row.unitPrice)
    if (unitPrice !== null && (cents(unitPrice) === 0 || cents(unitPrice) * rights > 9999999999)) fail('历史单价须大于零，权益金额不能超出可保存范围')
    nonempty = nonempty || rights > 0 || held > 0 || tickets > 0
    return { productId, rights, held, tickets, unitPrice }
  }).sort((a, b) => a.productId - b.productId)
  if (!nonempty) fail('请填写需要认领的历史资产')
  return { stationId: id(raw.stationId), clientToken: raw.clientToken, confirmed: true, depositBalance, note: raw.note.trim(), items }
}
function reviewBody(raw) {
  if (!object(raw) || typeof raw.approve !== 'boolean' || (raw.note != null && typeof raw.note !== 'string')) fail('请重新核对办理决定')
  const note = raw.note == null ? null : raw.note.trim()
  if ((note && note.length > 500) || (!raw.approve && !note)) fail('拒绝时请填写核对差异（最多500字）')
  return { approve: raw.approve, note: note || null }
}
function data(response) {
  if (!object(response) || ![0, 200].includes(response.code)) fail('认领结果暂时无法核实，请重试')
  return response.data
}
function catalog(response) {
  const rows = data(response), seen = new Set()
  if (!Array.isArray(rows)) fail('认领商品暂时无法加载，请重试')
  return rows.map(row => {
    if (!object(row) || !id(row.id) || seen.has(id(row.id)) || !Number.isInteger(row.category) || row.category < 1) fail('认领商品信息不完整，请联系水站核实')
    seen.add(id(row.id))
    return { id: id(row.id), name: typeof row.name === 'string' && row.name.trim() ? row.name : '商品 #' + row.id,
      spec: typeof row.spec === 'string' ? row.spec : '', category: row.category }
  })
}
function record(raw, expected = {}, products = []) {
  if (!object(raw) || !id(raw.id) || !id(raw.customerId) || !id(raw.stationId)
    || (expected.id && id(raw.id) !== expected.id) || (expected.customerId && id(raw.customerId) !== expected.customerId)
    || (expected.stationId && id(raw.stationId) !== expected.stationId)
    || !['PENDING', 'APPROVED', 'REJECTED'].includes(raw.status) || typeof raw.statusText !== 'string' || !raw.statusText.trim()) fail('认领记录暂时无法核实，请重试或联系水站')
  let snapshot = raw.requestJson
  if (typeof snapshot === 'string') { try { snapshot = JSON.parse(snapshot) } catch (e) { fail('原认领清单无法读取，请联系水站') } }
  const normalized = body(snapshot)
  if (normalized.stationId !== id(raw.stationId) || normalized.clientToken !== raw.clientToken
    || (expected.body && JSON.stringify(normalized) !== JSON.stringify(body(expected.body)))) fail('原申请与查回清单不一致，请保留记录并联系水站')
  if (raw.status !== 'PENDING' && (!id(raw.reviewerId) || typeof raw.reviewTime !== 'string' || !raw.reviewTime)) fail('核对结果不完整，请查询原申请')
  if (raw.status === 'REJECTED' && (typeof raw.reviewNote !== 'string' || !raw.reviewNote.trim())) fail('拒绝反馈尚未核实，请联系水站')
  const names = new Map(products.map(row => [row.id, row]))
  return { id: id(raw.id), customerId: id(raw.customerId), stationId: id(raw.stationId), clientToken: raw.clientToken,
    status: raw.status, statusText: raw.statusText, depositText: normalized.depositBalance, note: normalized.note,
    reviewNote: typeof raw.reviewNote === 'string' ? raw.reviewNote : '', createTime: String(raw.createTime || '').replace('T', ' '),
    body: normalized, items: normalized.items.map(row => ({ ...row, name: (names.get(row.productId) || {}).name || '历史商品',
      spec: (names.get(row.productId) || {}).spec || '', over: row.held - row.rights,
      unitPriceText: row.unitPrice == null ? '未填写，按补录规则推断' : row.unitPrice })) }
}
function records(response, expected, products) {
  const rows = data(response), seen = new Set()
  if (!Array.isArray(rows)) fail('认领记录暂时无法加载，请重试')
  return rows.map(row => { const r = record(row, expected, products); if (seen.has(r.id)) fail('申请记录重复，请重试'); seen.add(r.id); return r })
}
function key(actor, target) {
  if (!/^(customer:[1-9]\d*|manager:[1-9]\d*:[1-9]\d*)$/.test(actor) || !/^(station|reviews):[1-9]\d*$/.test(target)) fail('请重新登录后查看认领')
  return 'asset-claim-intent:' + actor + ':' + target
}
function read(actor, target) {
  let value
  try { value = wx.getStorageSync(key(actor, target)) } catch (e) { fail('原认领记录无法读取，请保留记录并重试') }
  if (value === undefined || value === null || value === '') return null
  if (!object(value) || value.version !== 1 || value.actor !== actor || value.target !== target || !['SUBMIT', 'REVIEW'].includes(value.operation)) fail('原认领记录不完整，请保留记录并联系水站')
  const normalized = value.operation === 'SUBMIT' ? body(value.body) : reviewBody(value.body)
  if (value.operation === 'SUBMIT' && ('station:' + normalized.stationId !== target || !actor.startsWith('customer:'))) fail('原认领身份不一致，请联系水站')
  if (value.operation === 'REVIEW' && (!actor.startsWith('manager:') || target !== 'reviews:' + actor.split(':')[2] || !id(value.objectId))) fail('原核对身份不一致，请联系水站')
  return Object.assign({ version: 1, actor, target, operation: value.operation, body: normalized }, value.operation === 'REVIEW' ? { objectId: id(value.objectId) } : {})
}
function prepare(actor, target, operation, input, objectId) {
  const normalized = operation === 'SUBMIT' ? body(input) : reviewBody(input), old = read(actor, target)
  if (old) {
    if (old.operation !== operation || (operation === 'REVIEW' && old.objectId !== objectId) || JSON.stringify(old.body) !== JSON.stringify(normalized)) fail('上次结果尚未核实，请先恢复原申请')
    return old
  }
  const value = Object.assign({ version: 1, actor, target, operation, body: normalized }, operation === 'REVIEW' ? { objectId } : {})
  try { wx.setStorageSync(key(actor, target), clone(value)) } catch (e) { fail('原请求记录保存失败，尚未提交，请重试') }
  const saved = read(actor, target)
  if (!saved || JSON.stringify(saved) !== JSON.stringify(value)) fail('原请求记录未能保存，尚未提交，请重试')
  return value
}
function clear(value) {
  const current = read(value.actor, value.target)
  if (current && JSON.stringify(current) !== JSON.stringify(value)) fail('原请求记录已经变化，请重新查询')
  try { if (current) wx.removeStorageSync(key(value.actor, value.target)) } catch (e) { fail('原请求记录尚未清理，请恢复原申请后重试') }
  if (read(value.actor, value.target)) fail('原请求记录尚未清理，请恢复原申请后重试')
}
function errorText(error, fallback) {
  const text = error && error.message || ''
  if (error && error.sessionChanged) return '登录身份已变化，请重新进入后查看原申请'
  if (/接口不存在|HTTP\s*404|参数格式不正确[:：]\s*id\b|不支持/.test(text) || error && (error.backendCode === 404 || error.statusCode === 404)) return '当前服务暂不能办理历史资产认领，请稍后重试或联系水站'
  if (/权限不足|仅客户|仅站长|无权访问/.test(text)) return '当前身份无法办理认领，请重新登录后查看'
  return error && (error.localClaimError || error.businessRejected) && !/接口|数据库|SQL|端点|schema|后端/.test(text) ? text : fallback
}
module.exports = { id, quantity, money, body, reviewBody, data, catalog, record, records, read, prepare, clear, errorText }
