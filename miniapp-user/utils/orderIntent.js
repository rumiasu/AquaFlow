/**
 * 下单「一次意图 = 一个幂等键」的本地状态机（契约工作包 B / 下单契约 A1）。
 *
 * 为什么单独一个模块：这套规则同时被下单页（submit / 缺货重提 / 支付失败重试）和
 * 结果页（回到原单）用到，写在页面里会变成两套判断 —— 而"重试变成第二张单"正是
 * 这套规则写错的后果。本模块只做**本地**记录与比对，不参与任何金额或业务判断。
 *
 * 恢复规则见 docs/design/04-订单与状态机.md §4.7。
 * 2026-10-13：原来只存五要素和键，写失败被吞、重进又重拼备注，可能重复建单或摘要冲突。
 * 原件必须含完整 body 与阶段；unknown 不能因编辑换键，首次存储没核实则不发请求。
 */

const KEY = 'orderIntent.v1'
const REQUEST_KEY = 'orderRequest.v2:'
const requestsByApp = new WeakMap()

function requestError(message) {
  return Object.assign(new Error(message), { orderRecoveryBlocked: true })
}

function ownedRequests(session) {
  let requests = requestsByApp.get(session.app)
  if (!requests) { requests = new Map(); requestsByApp.set(session.app, requests) }
  return requests
}

function copy(value) { return JSON.parse(JSON.stringify(value)) }
function absent(value) { return value == null || value === '' }

function storedRequest(request) {
  const { customerId, fingerprint, key, body, phase, orderId, at, wechatPay, paymentContext } = request
  return { customerId, fingerprint, key, body, phase, orderId, at, wechatPay, paymentContext }
}

/** 2026-10-13：首次发送前必须核实完整原件；写后抛错或静默未写都不能盲发。 */
function persistRequest(request) {
  const stored = copy(storedRequest(request))
  const expected = JSON.stringify(stored)
  wx.setStorageSync(REQUEST_KEY + request.customerId, stored)
  wx.setStorageSync(KEY, stored)
  if (JSON.stringify(wx.getStorageSync(REQUEST_KEY + request.customerId)) !== expected
      || JSON.stringify(wx.getStorageSync(KEY)) !== expected) throw requestError('原提交记录没有保存完整，尚未发送，请重试')
}

/** 同进程跨页面优先读本人内存原件；读异常不等于没有原单，旧磁盘值不覆盖可信内存。 */
function getRequest(session, knownReceipt) {
  const requests = ownedRequests(session), owner = String(session.customerId)
  if (requests.has(owner)) return requests.get(owner)
  let stored, legacy
  try {
    stored = wx.getStorageSync(REQUEST_KEY + owner)
    if (absent(stored)) legacy = wx.getStorageSync(KEY)
  } catch (error) { throw requestError('没能读取原提交记录，请重试核实，暂不发送订单') }
  if (absent(stored) && !absent(legacy) && (typeof legacy !== 'object' || !legacy.customerId)) {
    throw requestError('原提交记录不完整，请先到我的订单核实')
  }
  if (absent(stored) && legacy && String(legacy.customerId) === owner) {
    if (legacy.body) stored = legacy
    else if (legacy.key && (!knownReceipt || knownReceipt.fingerprint !== legacy.fingerprint || !knownReceipt.orderId)) {
      throw requestError('原提交只保留了编号，请先到我的订单核实，暂不发送新订单')
    }
  }
  if (absent(stored)) return null
  if (String(stored.customerId) !== owner || !stored.body || stored.body.idempotencyKey !== stored.key
      || typeof stored.key !== 'string' || !stored.key.trim()
      || fingerprint({ ...stored.body, customerId: stored.customerId }) !== stored.fingerprint
      || !Number.isFinite(stored.at) || !stored.paymentContext || typeof stored.paymentContext !== 'object'
      || !stored.fingerprint || !['prepared', 'unknown', 'known'].includes(stored.phase)
      || (stored.phase === 'known' && !stored.orderId)) throw requestError('原提交记录不完整，请先到我的订单核实')
  const request = copy(stored)
  request.busy = false
  requests.set(owner, request)
  return request
}

/** 未发送/明确未建单时才可重备请求；unknown 不能被编辑覆盖，known 的新意图用新键。 */
function prepareRequest(intent, body, session, wechatPay, paymentContext, knownReceipt) {
  const requests = ownedRequests(session), owner = String(session.customerId)
  const previous = getRequest(session, knownReceipt)
  if (previous && (previous.busy || previous.phase === 'unknown')) throw requestError('请先核实原提交，再下新订单')
  const fp = fingerprint(intent)
  const key = previous && previous.phase === 'prepared' && previous.fingerprint === fp ? previous.key : newKey()
  const request = { customerId: session.customerId, fingerprint: fp, key,
    body: copy({ ...body, idempotencyKey: key }), phase: 'prepared', orderId: null, at: Date.now(),
    wechatPay: wechatPay || null, paymentContext: copy(paymentContext), busy: false }
  requests.set(owner, request)
  persistRequest(request)
  return request
}

/** 共享在途锁；unknown 重放可信原件，首发则先持久化未知阶段，崩溃后也不会误认未发送。 */
function beginRequest(request) {
  if (request.busy) return false
  if (request.phase !== 'unknown') {
    persistRequest({ ...request, phase: 'unknown' })
    request.phase = 'unknown'
  }
  request.busy = true
  return true
}

/** 服务端明确结果更新本人原件；持久化失败保留内存事实，不把 unknown 猜成没建单。 */
function settleRequest(request, phase, orderId = null) {
  request.phase = phase
  request.orderId = orderId
  request.at = Date.now()
  try { persistRequest(request) } catch (error) { /* 原件留在进程内；旧磁盘 unknown 仍可按原键安全恢复。 */ }
}

function releaseRequest(request) { if (request) request.busy = false }

/** 生成一个新的幂等键（时间 + 随机，够用即可；服务端只当字符串键用）。 */
function newKey() {
  return 'oi-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

/** 把意图五要素归一成一个可比较的字符串（顺序固定，商品按 productId 排序）。 */
function fingerprint(intent) {
  if (!intent) return ''
  const items = (intent.items || [])
    .map(it => `${it.productId}x${it.quantity}`)
    .sort()
    .join(',')
  return [
    intent.customerId == null ? '' : intent.customerId,
    intent.stationId == null ? '' : intent.stationId,
    intent.addressId == null ? '' : intent.addressId,
    intent.paymentMethod == null ? '' : intent.paymentMethod,
    items
  ].join('|')
}

/**
 * 取回「本次意图」的幂等键：意图一致就复用，不一致（或没有记录）就新建并记下来。
 *
 * @param {object} intent {customerId, stationId, addressId, paymentMethod, items:[{productId,quantity}]}
 * @returns {string} 幂等键
 */
function keyFor(intent) {
  const fp = fingerprint(intent)
  let saved = null
  try {
    saved = wx.getStorageSync(KEY) || null
  } catch (e) {
    throw requestError('没能读取原提交编号，请先重试核实')
  }
  if (saved && saved.fingerprint === fp && saved.key) {
    return saved.key
  }
  const key = newKey()
  save(intent, key)
  return key
}

/** 记录当前意图与键（keyFor 内部用；也允许调用方在拿到响应后重写）。 */
function save(intent, key) {
  try {
    wx.setStorageSync(KEY, {
      fingerprint: fingerprint(intent),
      key: key,
      customerId: intent && intent.customerId != null ? intent.customerId : null,
      savedAt: Date.now()
    })
  } catch (e) {
    // 存储本身不能保证重试复用；下单页用当前登录周期与意图绑定的原请求快照保住内存原键。
  }
}

/** 服务端按这个键返回了一个**真实订单** ⇒ 本次意图已了结，下次提交是新的一单。 */
function clear() {
  try {
    wx.removeStorageSync(KEY)
  } catch (e) {
    // 忽略：清不掉最多是下一次提交复用旧键（服务端会按幂等返回原单，不会重复建单）
  }
}

/** 只读当前记录（排障/回传用，不参与判断）。 */
function peek() {
  try {
    return wx.getStorageSync(KEY) || null
  } catch (e) {
    return null
  }
}

module.exports = { keyFor, save, clear, peek, fingerprint, newKey,
  getRequest, prepareRequest, beginRequest, settleRequest, releaseRequest }
