// 2026-10-24：缺值、布尔和非法金额不能被 Number() 变成看似可信的零元收款。
function amountCents(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  if (typeof value === 'number' && !Number.isFinite(value)) return null
  const text = String(value).trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) return null
  const parts = text.split('.')
  const cents = Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0'))
  return Number.isSafeInteger(cents) && cents >= 0 ? cents : null
}

function moneyText(value) {
  const cents = amountCents(value)
  return cents === null ? '' : '¥' + Math.floor(cents / 100) + '.' + String(cents % 100).padStart(2, '0')
}

function collectionPrompt(order) {
  const amount = moneyText(order && order.totalAmount)
  if (!amount) return null
  return '订单 #' + order.id + '，确认实际已收到 ' + amount + '？\n此操作只登记已收款，不会向客户扣款。确认后记为已付款；已送达订单将完成。请勿再次向客户收取这笔钱。'
}

function canConfirmCash(order, user = {}) {
  const same = (a, b) => a != null && b != null && String(a) === String(b)
  const o = order || {}, station = o.deliveryStationId == null ? o.stationId : o.deliveryStationId
  return !!(o.needCollect && o.paymentMethod === 2 && (o.paymentStatus === 0 || o.paymentStatus === 1)
    && (o.status === 2 || o.status === 3) && same(station, user.stationId)
    && (user.role === 'STATION_MANAGER' || user.role === 'manager'
      || ((user.role === 'DELIVERY' || user.role === 'delivery') && same(o.deliveryStaffId, user.staffId))))
}

module.exports = { amountCents, moneyText, collectionPrompt, canConfirmCash }
