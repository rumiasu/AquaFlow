/**
 * 下单「一次意图 = 一个幂等键」的本地状态机（契约工作包 B / 下单契约 A1）。
 *
 * 为什么单独一个模块：这套规则同时被下单页（submit / 缺货重提 / 支付失败重试）和
 * 结果页（回到原单）用到，写在页面里会变成两套判断 —— 而"重试变成第二张单"正是
 * 这套规则写错的后果。本模块只做**本地**记录与比对，不参与任何金额或业务判断。
 *
 * 规则（判据）：
 *   1. 一次**下单意图**由「客户 + 水站 + 地址 + 商品明细 + 支付方式」唯一确定；
 *      其中任何一项变了 ⇒ 意图变了 ⇒ 必须换新键（否则"改完数量再提交"会被服务端
 *      当成重复请求，拿回上一张金额不同的旧单）。
 *   2. 意图没变时，**任何失败都要复用同一个键**：缺货未确认、超时、点击重试、
 *      服务端已建单但响应丢失（此时服务端按幂等键返回原单，正是我们要的恢复路径）。
 *   3. 只有"这一单确实建出来了"（拿到合法 orderId）才清掉本次意图 —— 下一次提交是新的一单。
 *   4. 记录**按客户隔离**：换账号登录不得继承上一位客户的待确认请求。
 *      （key 里带 customerId，读的时候也比对；不匹配就当没有。）
 */

const KEY = 'orderIntent.v1'

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
    saved = null
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
    // 存不下就只能保证"本页内不换键"，不阻断下单
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

module.exports = { keyFor, save, clear, peek, fingerprint, newKey }
