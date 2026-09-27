/**
 * 配送异常上报的**唯一实现**（首页「配送遇到问题」与订单详情页「异常反馈」共用）。
 *
 * 为什么抽出来：这两处原本各写一份（详情页那份见 `pages/order/detail.js#onReport`），
 * 而**原因文案会被后端原样写进订单备注**（`[配送异常] <原因>（上报人ID=…）`，见
 * `DeliveryController#reportOrder`），站长端也是照这行字看的 —— 两处各维护一份，
 * 迟早出现"同一个问题在两个页面里叫法不一样"，站长按名字对不上。
 *
 * 上报后果（写清，免得又有人以为它会生成异常单）：它只落
 * `orders.special_note` + `audit_log`，**不生成 `order_barrel_exception` 异常单** ——
 * 异常单只有"完成配送时少收空桶"与"站长手工发起"两条来源。
 * 配送员上报完，这单**还在他名下**：要继续送、要请同事接手、要退回站长，都是后续动作，
 * 所以这里只负责"留痕 + 告知已提交"，不改订单归属与状态。
 */
const { reportOrder } = require('../api/delivery')

/** 配送现场的常见问题。改这里的文案＝改站长端看到的那行备注，别只改其中一个页面。 */
const DELIVERY_PROBLEM_REASONS = ['客户不接电话', '地址找不到', '客户拒收', '水桶破损', '其他']

/**
 * 弹原因选择 → 二次确认 → 上报。用户取消任一步都直接结束，不产生任何请求。
 *
 * @param {number|string} orderId
 * @param {{onDone?: Function}} [opts] 上报成功后回调（首页用它刷新列表）
 */
function reportDeliveryProblem(orderId, opts) {
  const options = opts || {}
  wx.showActionSheet({
    itemList: DELIVERY_PROBLEM_REASONS,
    success: (res) => {
      const reason = DELIVERY_PROBLEM_REASONS[res.tapIndex]
      if (!reason) return
      wx.showModal({
        title: '上报配送问题',
        content: '反馈原因：' + reason + '\n\n这不会把订单转给别人，也不改订单状态 —— 只是留一条记录让站长知道。',
        confirmText: '上报',
        success: async (modalRes) => {
          if (!modalRes.confirm) return
          wx.showLoading({ title: '上报中...' })
          try {
            await reportOrder(orderId, { reason })
            wx.hideLoading()
            wx.showToast({ title: '已上报，站长看得到', icon: 'success' })
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
