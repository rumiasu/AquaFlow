const { ORDER_STATUS_TEXT, PAYMENT_STATUS_TEXT } = require('../config/constant')

const formatOrderStatus = (status) => {
  return ORDER_STATUS_TEXT[status] || '未知'
}

const formatPaymentStatus = (paymentStatus) => {
  return PAYMENT_STATUS_TEXT[paymentStatus] || '未知'
}

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
  formatOrderStatus,
  formatPaymentStatus,
  formatTime,
  formatMoney
}
