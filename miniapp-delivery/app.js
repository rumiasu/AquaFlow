// AquaFlow V1 配送端小程序
const { getBaseUrl, API, BINDING_STATUS } = require('./config/api')
const { STORAGE_KEYS } = require('./utils/storage-keys')
// 仅用于 syncIdentity()。utils/request 只依赖 config/api 与 utils/storage-keys，
// 不会回引 app.js，故此处顶层 require 不会形成循环。
const { get } = require('./utils/request')

const ROLE_UNSELECTED = 'UNSELECTED'
const ROLE_STATION_MANAGER = 'STATION_MANAGER'
const ROLE_DELIVERY = 'DELIVERY'

// ==================== V1 身份/绑定状态判定 ====================
// 角色: UNSELECTED / STATION_MANAGER / DELIVERY
// bindStatus: UNBOUND / PENDING / BOUND / REJECTED / PENDING_UNBIND
// stationId: null(未绑定) / 数字(已绑定水站)
//
// 规则:
 //  - UNSELECTED: 跳转到 role-select
//  - STATION_MANAGER + stationId=null: 跳转到 create-station
//  - STATION_MANAGER + stationId!=null: 正常进入业务
//  - DELIVERY + bindStatus in {PENDING, REJECTED, PENDING_UNBIND} or stationId=null and bindStatus=UNBOUND:
//      PENDING → bind-wait (审批中)
//      REJECTED/UNBOUND → apply-bind (申请绑定)
//      PENDING_UNBIND → bind-wait (解绑审批中)
//  - DELIVERY + bindStatus=BOUND + stationId!=null: 正常业务
const WHITE_LIST_ROUTES = [
  'pages/login/index',
  'pages/role-select/index',
  'pages/bind-wait/index',
  'pages/station-mgmt/create-station/index',
  'pages/station-mgmt/apply-bind/index'
]

function normalizeStationId(v) {
  // 后端 JWT 对 null stationId 存为 0,前端统一当 null
  if (v === 0 || v === '0' || v === undefined) return null
  return v
}

App({
  globalData: {
    accessToken: null,
    refreshToken: null,
    userInfo: null, // { staffId, nickname, phone, role(STATION_MANAGER/DELIVERY/UNSELECTED), stationId, bindStatus, applyStationId, _pendingOpenid }
    isLogin: false
  },

  onLaunch() {
    const accessToken = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
    const userInfo = wx.getStorageSync(STORAGE_KEYS.USER_INFO)

    if (accessToken && userInfo) {
      this.globalData.accessToken = accessToken
      this.globalData.refreshToken = refreshToken
      this.globalData.userInfo = this._normalizeUserInfo(userInfo)
      this.globalData.isLogin = true
    }
  },

  onShow() {
    this.checkLoginState()
    this.refreshPendingReminder()
  },

  /**
   * 刷新「待办」红点（站长专属，应用内提醒）。
   *
   * [2026-09-19 新建] 小程序**在前台无法真推送** —— 所以红点只能在"打开小程序时"刷新，
   * 这里是那个唯一的时机点（app.onShow 每次从后台切回前台都会走）。
   * 配送员不请求：那个端点是 `@RequireRole("STATION_MANAGER")`，问了只会拿到一条权限错误。
   *
   * ⚠️ 不 await、不抛错：它挂在应用启动路径上，一次网络抖动不该影响任何页面。
   * 失败时 quiet 降级为"不亮红点"（utils/pending-reminder.js 里会 console.warn 留痕）。
   */
  refreshPendingReminder() {
    const u = this.globalData.userInfo || {}
    const role = this._normalizeRole(u)
    if (!this.globalData.isLogin || role !== ROLE_STATION_MANAGER || !u.stationId) {
      // 不是站长 / 没绑定水站：确保不留上一次登录遗留的红点
      wx.hideTabBarRedDot({ index: 0, fail: () => {} })
      return
    }
    require('./utils/pending-reminder').syncPendingReminder()
  },

  _normalizeUserInfo(u) {
    if (!u) return u
    const role = this._normalizeRole(u)

    // V1 严格：旧绑定状态映射到新枚举
    let bindStatus = u.bindingStatus || u.bindStatus || BINDING_STATUS.UNBOUND
    if (bindStatus === 'APPROVED' || bindStatus === 'approved') bindStatus = BINDING_STATUS.BOUND
    if (bindStatus === 'UNBIND_PENDING' || bindStatus === 'unbind_pending') bindStatus = BINDING_STATUS.PENDING_UNBIND
    // 未知旧值一律归一化到 UNBOUND，强制走流程
    const LEGAL_BIND = [BINDING_STATUS.UNBOUND, BINDING_STATUS.PENDING, BINDING_STATUS.BOUND, BINDING_STATUS.REJECTED, BINDING_STATUS.PENDING_UNBIND]
    if (LEGAL_BIND.indexOf(bindStatus) < 0) bindStatus = BINDING_STATUS.UNBOUND

    // V1 核心：只有 BOUND 状态 stationId 才有意义，其余状态一律视为 stationId=NULL
    //   避免旧数据（如 stationId=1 默认水站、UNBOUND 时数据库残留）误判为已绑定
    //   注意：站长角色不走绑定流程，stationId 直接使用原始值
    let stationId = normalizeStationId(u.stationId)
    if (role === ROLE_DELIVERY && bindStatus !== BINDING_STATUS.BOUND) {
      stationId = null
    }

    return {
      staffId: u.staffId || null,
      nickname: u.nickname || u.name || '',
      phone: u.phone || '',
      role,
      stationId,
      bindStatus,
      applyStationId: u.applyStationId || null,
      // applyStationName 必须一起透出：申请绑定后 bind-wait 页要用它显示"已申请绑定 XX 水站"。
      // 旧实现只白名单了 applyStationId，申请页写进本地的水站名在归一化时被丢掉，
      // 结果是待审批页永远显示空水站名。
      applyStationName: u.applyStationName || null,
      needSelectRole: !!u.needSelectRole || role === ROLE_UNSELECTED,
      _pendingOpenid: u._pendingOpenid || null
    }
  },

  _normalizeRole(u) {
    const r = (u.staffRole || u.role || '').toUpperCase()
    if (r === 'MANAGER') return ROLE_STATION_MANAGER
    if (r === 'DELIVERY' || r === 'STATION_MANAGER' || r === ROLE_UNSELECTED) return r
    return ROLE_UNSELECTED
  },

  setLoginState(data) {
    const userInfo = this._normalizeUserInfo(data)
    const { accessToken, refreshToken } = data
    this.globalData.accessToken = accessToken || null
    this.globalData.refreshToken = refreshToken || null
    this.globalData.userInfo = userInfo
    this.globalData.isLogin = !!(accessToken && userInfo)
    if (accessToken) wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
    if (refreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
    if (userInfo) wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)
    return userInfo
  },

  clearLoginState() {
    this.globalData.accessToken = null
    this.globalData.refreshToken = null
    this.globalData.userInfo = null
    this.globalData.isLogin = false
    wx.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    wx.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
    wx.removeStorageSync(STORAGE_KEYS.USER_INFO)
  },

  checkLoginState() {
    const token = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    const pages = getCurrentPages()
    const currentPage = pages[pages.length - 1]
    const currentRoute = currentPage ? currentPage.route : ''
    const isWhite = WHITE_LIST_ROUTES.indexOf(currentRoute) >= 0

    if (!token && currentRoute !== 'pages/login/index') {
      wx.redirectTo({ url: '/pages/login/index' })
      return false
    }
    if (!token) return true

    if (!isWhite) {
      // 非白名单页面，按身份/绑定状态做一次路由守卫
      this.routeByRole(true)
    }
    return true
  },

  /**
   * 纯决策：按当前身份/绑定状态算出「应该在哪一页」。
   * 返回 null 表示状态已满足，可以正常进入业务页。
   * 抽出来是为了让 routeByRole 与 refreshIdentityAndRoute 共用同一套判定，
   * 避免两处各写一份、日后逐渐走样。
   */
  _targetRoute(u) {
    const user = u || {}
    if (!user.role || user.role === ROLE_UNSELECTED || user.needSelectRole) {
      return '/pages/role-select/index'
    }
    if (user.role === ROLE_STATION_MANAGER) {
      return user.stationId ? null : '/pages/station-mgmt/create-station/index'
    }
    if (user.role === ROLE_DELIVERY) {
      if (user.bindStatus === BINDING_STATUS.BOUND && user.stationId) return null
      if (user.bindStatus === BINDING_STATUS.PENDING
          || user.bindStatus === BINDING_STATUS.PENDING_UNBIND) {
        return '/pages/bind-wait/index'
      }
      return '/pages/station-mgmt/apply-bind/index'
    }
    return '/pages/role-select/index'
  },

  /**
   * V1 严格路由守卫
   * @param {boolean} silent  已在业务页时，仅在不符合权限时重定向，正常不操作
   */
  routeByRole(silent) {
    const u = this._normalizeUserInfo(
      this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {})
    const target = this._targetRoute(u)
    console.log('[routeByRole] silent=' + silent + ', role=' + u.role
      + ', bindStatus=' + u.bindStatus + ', stationId=' + u.stationId
      + ' → ' + (target || '业务页'))

    if (target === null) {
      if (!silent) wx.reLaunch({ url: '/pages/home/index' })
      return
    }
    if (silent) wx.redirectTo({ url: target })
    else wx.reLaunch({ url: target })
  },

  /**
   * 向服务器同步当前员工的真实身份/绑定状态，并写回 globalData 与本地存储。
   *
   * 为什么必须有这个方法（「注册卡死」的根因之一）：
   * 本地 userInfo 只是「登录/申请那一刻」的快照，之后**再也不会自动更新**。
   * 于是「在别的地方完成了注册」——站长在后台直接把你加进本站、审批在另一台设备上点了通过、
   * 或者你在另一部手机上走完了建站流程——本机永远不知道，界面还按旧状态把人按在注册流程里，
   * 且注册页本身没有任何出口，就表现为"卡死"。
   *
   * 注意：UNSELECTED 是「还没建员工记录」的虚拟会话（后端查不到 staff 行），
   * 此时调该接口只会得到「员工不存在」，故直接跳过。
   */
  async syncIdentity() {
    const token = this.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    if (!token) return null
    const current = this._normalizeUserInfo(
      this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {})
    if (!current || !current.role
        || current.role === ROLE_UNSELECTED || current.needSelectRole) {
      return null
    }
    try {
      // 该端点对 DELIVERY / STATION_MANAGER 都开放，返回 role + stationId + bindingStatus，
      // 足够支撑一次完整的路由判定。
      const res = await get(API.BIND_STATUS)
      const d = (res && res.data) || null
      if (!d) return null
      const normalized = this._normalizeUserInfo({
        ...current,
        role: d.role || current.role,
        stationId: d.stationId,
        bindStatus: d.bindingStatus || current.bindStatus
      })
      this.globalData.userInfo = normalized
      wx.setStorageSync(STORAGE_KEYS.USER_INFO, normalized)
      return normalized
    } catch (e) {
      // 同步失败绝不能把人卡在流程里：保持旧状态，让页面按原逻辑继续走
      console.warn('[syncIdentity] 同步身份状态失败:', e && e.message)
      return null
    }
  },

  /**
   * 注册/绑定流程页专用：先同步服务器状态，再按最新状态决定去留。
   *
   * @param {string} currentRoute 当前页面路由（不带前导 /）
   * @returns {Promise<boolean>} true = 已跳走（调用方不要再执行自己的加载逻辑）
   *
   * 只在**目标页与当前页不同**时才跳 —— 否则 reLaunch 到当前页会重新触发 onShow，
   * 形成自我循环（这是"进页面就重定向"这类写法最容易踩的坑）。
   */
  async refreshIdentityAndRoute(currentRoute) {
    await this.syncIdentity()
    const u = this._normalizeUserInfo(this.globalData.userInfo || {})
    const target = this._targetRoute(u)
    // target === null 表示「状态已满足」→ 该进业务首页；否则去 target 指定的流程页。
    // **这一支必须跳**：用户报的"在别的地方注册了也没用"，就是因为在别处获批后
    // 本地状态更新了、却没有人把他从注册页放出去。
    // 两种情况下都只在「目标 ≠ 当前页」时跳，避免 reLaunch 到当前页触发的 onShow 自我循环。
    const dest = target || '/pages/home/index'
    if (dest.replace(/^\//, '') !== currentRoute) {
      wx.reLaunch({ url: dest })
      return true
    }
    return false
  },

  /**
   * 身份是否已「生效」：已经挂到某个水站上。
   *   配送员生效 = 站长批准了绑定；站长生效 = 已建好水站 —— 两者在数据上都表现为 stationId 非空。
   *
   * 与后端 LoginController#selectRole 的判据**必须保持一致**：
   * 未生效时允许返回重选身份，生效后由后端拒绝自行更改（前端据此隐藏入口，别让用户白点）。
   */
  isIdentityEffective() {
    const u = this._normalizeUserInfo(
      this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {})
    return !!u.role && u.role !== ROLE_UNSELECTED && !!u.stationId
  },

  /** 判断是否具有水站业务数据访问权限(业务页 onShow 自保护用) */
  canAccessStationBusiness() {
    const u = this._normalizeUserInfo(this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {})
    if (!u || !u.role) return false
    if (u.role === ROLE_UNSELECTED) return false
    if (u.role === ROLE_STATION_MANAGER) return !!u.stationId
    if (u.role === ROLE_DELIVERY) return u.bindStatus === BINDING_STATUS.BOUND && !!u.stationId
    return false
  },

  /** 当前是否站长 */
  isStationManager() {
    const u = this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
    return this._normalizeRole(u) === ROLE_STATION_MANAGER
  },

  /** 当前是否已绑定水站的配送员 */
  isBoundDelivery() {
    const u = this._normalizeUserInfo(this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {})
    return u.role === ROLE_DELIVERY && u.bindStatus === BINDING_STATUS.BOUND && !!u.stationId
  },

  logout() {
    const token = this.globalData.accessToken
    const header = {}
    if (token) header['Authorization'] = 'Bearer ' + token
    wx.request({
      url: getBaseUrl() + API.LOGOUT,
      method: 'POST',
      header,
      complete: () => {
        this.clearLoginState()
        wx.reLaunch({ url: '/pages/login/index' })
      }
    })
  }
})

module.exports = {
  ROLE_UNSELECTED,
  ROLE_STATION_MANAGER,
  ROLE_DELIVERY,
  BINDING_STATUS,
  WHITE_LIST_ROUTES,
  normalizeStationId
}
