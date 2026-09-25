// 支付结果提示（全端统一语义）
//
// 背景：订单详情 / 列表 / 成功页曾各自写 `createPayment(...).then(() => toast('支付成功'))`。
// 但 POST /api/payments 对微信(1)、现金(2) 只创建一条 PENDING 流水，并不改订单付款状态，
// 于是出现"点了去支付 → 提示支付成功 → 订单其实还是待收款"的假支付。
// 现在统一按后端返回的真实 status 决定提示，禁止无条件报成功。
//
// 后端 PaymentRecord.status（PaymentStatus）：0未付 1待收款 2已付 3已退款 4已取消
const PAY_STATUS = {
  UNPAID: 0,
  PENDING: 1,
  PAID: 2,
  REFUNDED: 3,
  CANCELLED: 4
}

/**
 * 按支付记录真实状态给出提示
 * @param {object} record 后端返回的支付记录（res.data）
 * @returns {boolean} 是否已真正支付成功
 */
const notifyPayResult = (record) => {
  const status = record && record.status != null ? Number(record.status) : PAY_STATUS.PENDING

  if (status === PAY_STATUS.PAID) {
    wx.showToast({ title: '支付成功', icon: 'success' })
    return true
  }
  if (status === PAY_STATUS.REFUNDED) {
    wx.showToast({ title: '该笔已退款', icon: 'none' })
    return false
  }
  if (status === PAY_STATUS.CANCELLED) {
    wx.showToast({ title: '该笔已取消', icon: 'none' })
    return false
  }
  // PENDING / UNPAID：款项尚未到账，明确告知客户接下来会发生什么
  wx.showModal({
    title: '等待收款确认',
    content: '付款信息已提交，需水站确认收款后订单才算完成。如长时间未确认请联系水站。',
    showCancel: false,
    confirmText: '知道了'
  })
  return false
}

module.exports = { PAY_STATUS, notifyPayResult }
