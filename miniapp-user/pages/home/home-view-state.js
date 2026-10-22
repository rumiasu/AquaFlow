// 首页专用：导航完整避开系统胶囊；无设备数据时仍保留状态栏和导航栏。
const finite = (value, low, high) => typeof value === 'number' && Number.isFinite(value) && value >= low && value <= high

function homeNavLayout(platform) {
  let info = {}
  try { info = platform.getWindowInfo ? platform.getWindowInfo() : platform.getSystemInfoSync() } catch (_) {
    try { info = platform.getSystemInfoSync() } catch (_) {}
  }
  if (!info || typeof info !== 'object') info = {}
  const width = finite(info.windowWidth, 240, 2000) ? info.windowWidth : 375
  const status = finite(info.statusBarHeight, 0, 80) ? info.statusBarHeight
    : (info.safeArea && finite(info.safeArea.top, 0, 80) ? info.safeArea.top : 44)
  let menu
  try { menu = platform.getMenuButtonBoundingClientRect && platform.getMenuButtonBoundingClientRect() } catch (_) {}
  const bounds = menu && finite(menu.top, 0, status + 64) && finite(menu.height, 24, 48)
  const validLeft = bounds && finite(menu.left, width * 0.45, width - 20)
  return {
    statusBarHeight: status,
    navBarHeight: bounds ? menu.height + 2 * Math.max(0, menu.top - status) : 44,
    navContentWidth: validLeft ? Math.max(64, menu.left - 24) : Math.max(64, width - 140)
  }
}

function catalogState(response) {
  if (!response || response.code !== 0 || !Array.isArray(response.data)) return 'error'
  return response.data.length ? 'ready' : 'empty'
}

module.exports = { homeNavLayout, catalogState }
