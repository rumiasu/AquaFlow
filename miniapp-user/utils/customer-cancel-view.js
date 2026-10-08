// Permission comes from the server; status only selects the action wording.
function cancelView(order) {
  const row = order || {}, request = row.status === 2
  return {
    canCancel: row.canCancel === true,
    label: request ? '申请取消' : '取消订单',
    title: request ? '申请取消订单' : '取消订单',
    confirmText: request ? '提交申请' : '确认取消',
    content: request
      ? '订单正在配送中，将向水站提交取消申请。提交申请不代表订单已取消，需等待水站处理；退款进度请与水站确认。'
      : '确定取消此订单吗？取消成功表示订单已取消，不代表退款已到账。水票会按原路径退回；现金或微信款项请与水站确认退款进度。'
  }
}

// customer-cancel returns no outcome: only a fresh order can confirm cancellation.
function cancelResultText(order) {
  if (order && order.status === 5) return '订单已取消'
  if (order && order.status === 2) return '取消申请已提交'
  return '取消操作已提交，请刷新确认结果'
}

module.exports = { cancelView, cancelResultText }
