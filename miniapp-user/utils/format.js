// 注：原 formatOrderStatus / formatPaymentStatus 及其依赖的 config/constant.js 已删除。
// 展示文案一律由后端下发（Orders.getStatusText / getPayMethodText、PaymentRecord.getMethodText / getStatusText），
// 前端禁止自建「状态码 → 文案」映射表 —— 历史上两端各写一套，后端调整口径后前端不跟随，
// 导致展示与实际状态不符（新客下单 100% 失败的同类成因）。

const formatTime = (dateStr) => {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const formatMoney = (amount) => {
  if (amount === null || amount === undefined) return '0.00'
  return Number(amount).toFixed(2)
}

module.exports = {
  formatTime,
  formatMoney
}
