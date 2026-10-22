// Whitespace formatting is kept consistent across the two independent miniapp packages.
/** 2026-10-22：只预览独立客户留言；specialNote 混有处理记录，不能回退或按标记猜测。 */
function customerNotePreview(order) {
  const note = order && order.customerNote
  return typeof note === 'string' ? note.replace(/\s+/g, ' ').trim() : ''
}

const positiveId = value => (typeof value === 'number' && Number.isSafeInteger(value) && value > 0)
  || (typeof value === 'string' && /^[1-9][0-9]*$/.test(value))
const sameId = (a, b) => positiveId(a) && positiveId(b) && String(a) === String(b)
const codeIs = (value, code) => value === code || value === String(code)

/**
 * 2026-10-22：转让/历史/归属站列表比详情权限宽，列表有订单不能证明可打开详情。
 * 只保守镜像 OrderTaskAccess.requireEmployeeDetail；缺身份/履约范围/状态证据就不预览，
 * 不扩大接口权限，也不逐单请求详情。后端守卫变化时须同步核对本展示限制。
 */
function employeeCustomerNotePreview(order) {
  const app = typeof getApp === 'function' ? getApp() : null
  const data = app && app.globalData, user = data && data.userInfo
  if (!data || data.isLogin !== true || !user || !order || !positiveId(order.id)
      || !positiveId(user.staffId) || !positiveId(user.stationId) || user.needSelectRole) return ''
  const role = typeof user.role === 'string' ? user.role.toUpperCase() : ''
  const manager = role === 'STATION_MANAGER' || role === 'MANAGER'
  const delivery = role === 'DELIVERY'
  if (!manager && !delivery) return ''
  // StationUtil.deliveryStation 只在履约站为 null 时回退归属站；无效非空值不能回退。
  const station = order.deliveryStationId == null ? order.stationId : order.deliveryStationId
  if (!sameId(station, user.stationId)) return ''
  if (delivery && (user.bindStatus !== 'BOUND' || !sameId(order.deliveryStaffId, user.staffId))) return ''
  if (![1, 2, 3, 4, 5].some(code => codeIs(order.status, code))) return ''
  const visible = codeIs(order.paymentStatus, 2) || codeIs(order.paymentMethod, 2)
    || [3, 4, 5].some(code => codeIs(order.status, code))
  return visible ? customerNotePreview(order) : ''
}

module.exports = { customerNotePreview, employeeCustomerNotePreview }
