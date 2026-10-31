/**
 * 配送异常上报的**唯一实现**（首页「配送遇到问题」与订单详情页「异常反馈」共用）。
 *
 * 为什么抽出来：这两处原本各写一份（详情页那份见 `pages/order/detail.js#onReport`），
 * 而**原因文案会被后端原样写进订单备注**（`[配送异常] <原因>（上报人ID=…）`，见
 * `DeliveryTaskController#reportOrder`），站长端也是照这行字看的 —— 两处各维护一份，
 * 迟早出现"同一个问题在两个页面里叫法不一样"，站长按名字对不上。
 *
 * 上报后果（[2026-09-27] 改了，别再照旧注释理解）：
 *   · 落 `orders.special_note` + `audit_log`；
 *   · **并生成一条异常单**（`order_barrel_exception`，`STAFF_RECORDED`）进站长「待处置」——
 *     产品口径原话：「配送遇到问题，不应该是异常单处理吗」。在这之前只留痕不建单，
 *     站长端「异常订单」里永远只有"少回桶/多回桶"，现场问题只能靠站长事后手工补录。
 *   · **不改订单归属与状态**：这单还在配送员名下，他接着送、请同事接手、退回站长都是后续动作。
 */
const { reportOrder } = require('../api/delivery')
const recovery = require('./order-write-recovery')
const { showBusinessModal } = require('./business-dialog')
const dialogsByApp = new WeakMap()
function reportSession() {
  const app = getApp(), data = app.globalData || {}, user = data.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    identity: JSON.stringify([!!data.isLogin, user.staffId, user.stationId, user.role, user.bindStatus]) }
}

/**
 * 现场问题清单。`key` 是**契约**：后端 `ExceptionCategory.ReportReason` 按它映射异常类别
 * （`customer_refuse` → 拒收、`barrel_damaged` → 损坏，其余落"其他"），
 * `label` 只用于给配送员看与写进订单备注。**加一项要同时改后端那份映射**，否则上报会被拒。
 */
const DELIVERY_PROBLEM_REASONS = [
  { key: 'customer_unreachable', label: '客户不接电话' },
  { key: 'address_not_found', label: '地址找不到' },
  { key: 'customer_refuse', label: '客户拒收' },
  { key: 'barrel_damaged', label: '水桶破损' },
  { key: 'other', label: '其他' }
]

/**
 * 弹原因选择 → 二次确认 → 上报（会生成异常单）。用户取消任一步都直接结束，不产生任何请求。
 *
 * @param {number|string} orderId
 * @param {{order?: Object, isCurrent?: Function, dialog?: Function, setLoading?: Function,
 *   onStateChange?: Function, onDone?: Function}} [opts] 只在原反馈回执匹配后调用 onDone。
 */
function reportDeliveryProblem(orderId, opts) {
  const options = opts || {}
  const session = reportSession()
  const current = () => {
    const now = reportSession()
    return session.app === now.app && session.generation === now.generation && session.identity === now.identity
      && now.app.canAccessStationBusiness() && (typeof options.isCurrent !== 'function' || options.isCurrent())
  }
  const dialog = options.dialog || ((config, sheet) => new Promise(resolve => {
    // 2026-10-28: the default dispatcher bypassed the shared confirmation; keep only the picker native.
    const show = sheet ? value => wx.showActionSheet(value) : showBusinessModal
    show({ ...(sheet ? config : { confirmColor: '#1264D6', cancelColor: '#637083', ...config }), success: resolve, fail: () => resolve({ confirm: false }) })
  }))
  return (async () => {
    if (!current() || !Number.isSafeInteger(Number(orderId)) || Number(orderId) <= 0) return
    if (!dialogsByApp.has(session.app)) dialogsByApp.set(session.app, new Set())
    const dialogs = dialogsByApp.get(session.app), lockKey = session.identity + ':' + orderId
    if (dialogs.has(lockKey)) return
    dialogs.add(lockKey)
    let token, failure, receipt, retrying = false
    const changed = () => { if (current() && typeof options.onStateChange === 'function') options.onStateChange() }
    const setLoading = value => {
      if (typeof options.setLoading === 'function') options.setLoading(value)
      else if (value) wx.showLoading({ title: '上报中...', mask: true })
      else wx.hideLoading()
    }
    try {
      const old = recovery.read(orderId), payload = recovery.reportPayload(old)
      if (old && (old.phase === 'pending' || !payload)) {
        wx.showToast({ title: old.phase === 'pending' ? '原操作仍在处理中，请稍候' : '原记录待核实，请联系站长，勿重复上报', icon: 'none' }); return
      }
      retrying = !!payload
      let picked
      if (!retrying) {
        const chosen = await dialog({ itemList: DELIVERY_PROBLEM_REASONS.map(r => r.label) }, true)
        picked = DELIVERY_PROBLEM_REASONS[chosen.tapIndex]
        if (!picked || !current()) return
      }
      const answer = await dialog(retrying ? {
        title: '重试原反馈', content: '原反馈原因：' + payload.reason + '\n\n将按原记录核实并重试，不改原因、不另建一条反馈。订单仍按当前安排配送。', confirmText: '重试反馈'
      } : {
        title: '上报配送问题',
        content: '反馈原因：' + picked.label + '\n\n会记一条异常，站长那边能看到并处置。\n不会把订单转给别人，也不改订单状态。', confirmText: '上报'
      })
      if (!answer.confirm || !current()) return
      token = retrying ? recovery.retryReport(orderId) : recovery.beginReport(orderId, options.order || {}, picked)
      changed(); setLoading(true)
      // 2026-10-24：原意图先保存再发送。超时重试沿用同键同文；后端回原异常单，不能靠订单状态猜成功。
      const body = recovery.reportPayload(token.value)
      const response = await reportOrder(orderId, body), data = response && response.data
      if (!data || !['number', 'string'].includes(typeof data.id) || !Number.isSafeInteger(Number(data.id)) || Number(data.id) <= 0
        || String(data.orderId) !== String(orderId) || data.clientRequestId !== body.clientRequestId
        || (response.code != null && response.code !== 0 && response.code !== 200)) {
        throw Object.assign(new Error('反馈结果尚未核实，请重试原反馈'), { resultUnknown: true })
      }
      receipt = data
    } catch (err) {
      // 原反馈已经未知，后来一次确定拒绝也不能反证第一次没成功；保持原键供继续核实。
      failure = token && retrying ? Object.assign(new Error((err && err.message) || '原反馈仍待核实'), { resultUnknown: true }) : err
      if (current()) wx.showToast({ title: recovery.uncertain(failure) && token ? '反馈结果待核实，请重试原反馈' : ((err && err.message) || '上报未完成'), icon: 'none' })
    } finally {
      if (token) { recovery.finish(token, failure); setLoading(false) }
      dialogs.delete(lockKey)
      changed()
    }
    if (receipt && current()) {
      wx.showToast({ title: '已记录反馈 #' + receipt.id, icon: 'none' })
      if (typeof options.onDone === 'function') await options.onDone(receipt)
      return receipt
    }
  })()
}

module.exports = { DELIVERY_PROBLEM_REASONS, reportDeliveryProblem }
