// AquaFlow V1 配送端小程序
const { getBaseUrl, API, BINDING_STATUS } = require('./config/api')
const { STORAGE_KEYS } = require('./utils/storage-keys')

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
   * V1 严格路由守卫
   * @param {boolean} silent  已在业务页时，仅在不符合权限时重定向，正常不操作
   */
  routeByRole(silent) {
    const userInfo = this.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
    const u = this._normalizeUserInfo(userInfo)
    const role = u.role
    const bindStatus = u.bindStatus
    const stationId = u.stationId

    console.log('[routeByRole] silent=' + silent + ', role=' + role + ', bindStatus=' + bindStatus + ', stationId=' + stationId + ', needSelectRole=' + u.needSelectRole)

    // 1) 未选角色
    if (role === ROLE_UNSELECTED || !role || u.needSelectRole) {
      const targetUrl = '/pages/role-select/index'
      console.log('[routeByRole] 跳转角色选择页:', targetUrl, 'silent=' + silent)
      if (silent) wx.redirectTo({ url: targetUrl })
      else wx.reLaunch({ url: targetUrl })
      return
    }

    // 2) 站长：没有 stationId → 创建水站
    if (role === ROLE_STATION_MANAGER) {
      if (!stationId) {
        const targetUrl = '/pages/station-mgmt/create-station/index'
        console.log('[routeByRole] 站长无水站, 跳转:', targetUrl)
        if (silent) wx.redirectTo({ url: targetUrl })
        else wx.reLaunch({ url: targetUrl })
        return
      }
      console.log('[routeByRole] 站长已绑定, 进入首页')
      if (!silent) wx.reLaunch({ url: '/pages/home/index' })
      return
    }

    // 3) 配送员：按 bindStatus
    if (role === ROLE_DELIVERY) {
      if (bindStatus === BINDING_STATUS.BOUND && stationId) {
        console.log('[routeByRole] 配送员已绑定, 进入首页')
        if (!silent) wx.reLaunch({ url: '/pages/home/index' })
        return
      }

      if (bindStatus === BINDING_STATUS.PENDING || bindStatus === BINDING_STATUS.PENDING_UNBIND) {
        const targetUrl = '/pages/bind-wait/index'
        console.log('[routeByRole] 配送员审批中, 跳转:', targetUrl)
        if (silent) wx.redirectTo({ url: targetUrl })
        else wx.reLaunch({ url: targetUrl })
        return
      }

      const targetUrl = '/pages/station-mgmt/apply-bind/index'
      console.log('[routeByRole] 配送员未绑定, 跳转:', targetUrl)
      if (silent) wx.redirectTo({ url: targetUrl })
      else wx.reLaunch({ url: targetUrl })
      return
    }

    // 未知角色 → 角色选择
    const fallbackUrl = '/pages/role-select/index'
    console.log('[routeByRole] 未知角色, 跳转:', fallbackUrl)
    if (silent) wx.redirectTo({ url: fallbackUrl })
    else wx.reLaunch({ url: fallbackUrl })
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
