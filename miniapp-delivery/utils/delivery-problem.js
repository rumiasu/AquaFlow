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
 * @param {{onDone?: Function}} [opts] 上报成功后回调（首页用它刷新列表）
 */
function reportDeliveryProblem(orderId, opts) {
  const options = opts || {}
  wx.showActionSheet({
    itemList: DELIVERY_PROBLEM_REASONS.map((r) => r.label),
    success: (res) => {
      const picked = DELIVERY_PROBLEM_REASONS[res.tapIndex]
      if (!picked) return
      wx.showModal({
        title: '上报配送问题',
        // 说清后果：会生成一条待站长处置的异常记录；但**不会**把订单转给别人、也不改状态
        // ——这正是"异常处理"与"转让处理"的分界，配送员得先看懂再点。
        content: '反馈原因：' + picked.label
          + '\n\n会记一条异常，站长那边能看到并处置。\n不会把订单转给别人，也不改订单状态。',
        confirmText: '上报',
        success: async (modalRes) => {
          if (!modalRes.confirm) return
          wx.showLoading({ title: '上报中...' })
          try {
            await reportOrder(orderId, { reason: picked.label, reasonKey: picked.key })
            wx.hideLoading()
            wx.showToast({ title: '已上报，站长会处置', icon: 'success' })
            if (typeof options.onDone === 'function') options.onDone()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: (err && err.message) || '上报失败', icon: 'none' })
          }
        }
      })
    }
  })
}

module.exports = { DELIVERY_PROBLEM_REASONS, reportDeliveryProblem }
