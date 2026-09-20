const { getTodayStats } = require('../../api/delivery')
const { get, post } = require('../../utils/request')
const { API, BINDING_STATUS } = require('../../config/api')

Page({
  data: {
    isLogin: false,
    userInfo: null,
    todayStats: {},
    // 站长三格（[2026-09-19] 按角色分叉，见 loadManagerStats）：
    // 今日净利与单数（净利为 null 时要显示「算不出」）、桶异常 / 欠桶客户数
    todayProfit: null,
    risk: { barrelExceptionCount: 0, owedCustomerCount: 0 },
    roleLabel: '配送员',
    isManager: false,
    isDelivery: false,
    bindStatusText: '未绑定水站',
    bindStatus: null,
    stationInfo: null,
    staffList: [],
    bindApplications: [],
    mgmtActiveTab: 0,
    canAccessBusiness: false
  },

  _initialized: false,

  onLoad() {
    this._initialized = true
    this.checkLogin()
  },

  onShow() {
    if (!this._initialized) {
      this.checkLogin()
      if (this.data.isLogin) {
        this.loadStats(this.data.isManager)
        this.loadRoleData()
      }
    }
    this._initialized = false
  },

  checkLogin() {
    const app = getApp()
    const isLogin = !!(app.globalData && app.globalData.isLogin)
    const canAccessBusiness = isLogin && app.canAccessStationBusiness()
    const userInfo = (app.globalData && app.globalData.userInfo) || null
    const role = userInfo ? userInfo.role : null
    const bindStatus = userInfo ? userInfo.bindStatus : null

    const roleMap = {
      DELIVERY: '配送员', delivery: '配送员',
      STATION_MANAGER: '站长', manager: '站长'
    }
    const roleLabel = isLogin ? (roleMap[role] || '配送员') : '配送员'
    const isManager = isLogin && (role === 'STATION_MANAGER' || role === 'manager')
    const isDelivery = isLogin && (role === 'DELIVERY' || role === 'delivery')

    let bindStatusText = '未绑定水站'
    if (isManager) {
      bindStatusText = userInfo && userInfo.stationId ? '已创建水站' : '未创建水站'
    } else if (isDelivery) {
      switch (bindStatus) {
        case BINDING_STATUS.BOUND: bindStatusText = '已绑定水站'; break
        case BINDING_STATUS.PENDING: bindStatusText = '绑定审批中'; break
        case BINDING_STATUS.REJECTED: bindStatusText = '绑定被拒绝'; break
        case BINDING_STATUS.PENDING_UNBIND: bindStatusText = '解绑审批中'; break
        case BINDING_STATUS.UNBOUND: bindStatusText = '未绑定水站'; break
        default: bindStatusText = '未绑定水站'
      }
    }

    this.setData({
      isLogin,
      userInfo,
      roleLabel,
      isManager,
      isDelivery,
      bindStatus,
      bindStatusText,
      canAccessBusiness
    })

    if (isLogin) {
      // ⚠️ 角色**显式传参**，不要让 loadStats 回头读 this.data.isManager：
      // 依赖"setData 已同步写回 data"这种时序，一旦不成立就会静默走错分支
      // （站长看到配送员的三格数，或反过来去调站长专属端点拿一个权限错误）。
      if (canAccessBusiness) this.loadStats(isManager)
      if (isManager) this.loadRoleData()
    }
  },

  /**
   * 今日统计 —— **按角色取两个不同来源**（[2026-09-19]）：
   *   · 配送员：`/api/delivery/stats/today`（backend 按**人**统计完成/配送中/回桶）；
   *   · 站长：净利走毛利端点（按**站**、按下单时间），异常/欠桶走另外两个站长只读端点。
   * ⚠️ 站长分支**不能**去请求毛利端点以外的东西来凑数：毛利端点带 @RequireRole("STATION_MANAGER")，
   * 配送员调用只会拿到一个权限错误（不是"显示 0"）。
   */
  async loadStats(isManager) {
    if (isManager) {
      await this.loadManagerStats()
      return
    }
    try {
      const res = await getTodayStats()
      this.setData({ todayStats: res.data || {} })
    } catch (err) {
      // 静默失败会让看板把「没加载出来」显示成 0，误导站长。至少留痕 + 提示。
      console.error('[Mine] 今日统计加载失败:', err)
      wx.showToast({ title: '今日统计加载失败', icon: 'none' })
    }
  },

  /** 站长三格：今日净利 / 今日单数 / 桶异常·欠桶。三个请求互不依赖，任一失败只影响自己那一格。 */
  async loadManagerStats() {
    const today = this._todayStr()
    const [profitRes, pendingRes, owedRes] = await Promise.allSettled([
      get(API.MANAGER_GROSS_PROFIT + '?from=' + today + '&to=' + today),
      get(API.MANAGER_PENDING_SUMMARY),
      get(API.MANAGER_OWED_BARRELS)
    ])
    const next = {}

    if (profitRes.status === 'fulfilled' && profitRes.value && profitRes.value.code === 0) {
      next.todayProfit = this._decorateProfit(profitRes.value.data || {})
    } else {
      // 取不到就给"—"而不是 0：0 会被读成"今天一分没赚"
      console.warn('[Mine] 今日净利加载失败:', profitRes.reason && profitRes.reason.message)
      next.todayProfit = { netProfitText: '—', netProfitWarn: false, orderCount: '—' }
    }

    const risk = { barrelExceptionCount: 0, owedCustomerCount: 0 }
    if (pendingRes.status === 'fulfilled' && pendingRes.value && pendingRes.value.code === 0) {
      const items = ((pendingRes.value.data || {}).items) || []
      const hit = items.find(i => i.key === 'barrelException')
      risk.barrelExceptionCount = hit ? Number(hit.count) || 0 : 0
    } else {
      console.warn('[Mine] 桶异常数加载失败:', pendingRes.reason && pendingRes.reason.message)
    }
    if (owedRes.status === 'fulfilled' && owedRes.value && owedRes.value.code === 0) {
      // 欠桶端点是"客户列表"（没有专门的计数），这里数的是**欠桶客户数**，不是欠桶个数
      risk.owedCustomerCount = ((owedRes.value.data) || []).length
    } else {
      console.warn('[Mine] 欠桶客户加载失败:', owedRes.reason && owedRes.reason.message)
    }
    next.risk = risk

    this.setData(next)
  },

  /**
   * 净利 → 展示模型。
   *
   * ⚠️ `netProfit` 为 null = 有商品没填进货成本，**必须显示「算不出」**：
   * 把它当 0 相减会让站长以为自己净赚了整整一个售价（同毛利页的判据）。
   * 金额格式化放在 js 里做（wxml 不能调方法）。
   */
  _decorateProfit(d) {
    const isNull = d.netProfit === null || d.netProfit === undefined
    return {
      netProfitText: isNull ? '算不出' : '¥' + Number(d.netProfit).toFixed(2),
      netProfitWarn: isNull,
      orderCount: Number(d.orderCount) || 0
    }
  },

  /** 今天的 YYYY-MM-DD（本地时区）。净利端点的 from/to 都传今天 = 只看今天。 */
  _todayStr() {
    const d = new Date()
    return d.getFullYear() + '-' +
      String(d.getMonth() + 1).padStart(2, '0') + '-' +
      String(d.getDate()).padStart(2, '0')
  },

  /** 点「今日净利 / 今日单数」→ 毛利页，并把期间锁定为今天（页面据 range=today 设 from/to）。 */
  onGoTodayProfit() {
    wx.navigateTo({ url: '/pages/station-mgmt/gross-profit/index?range=today' })
  },

  /** 第三格主数字 → 「异常订单」页（页签 1 就是桶异常单，故不带 ?tab=）。 */
  onGoBarrelException() {
    wx.navigateTo({ url: '/pages/station-mgmt/exceptions/index' })
  },

  onGoOwedBarrels() {
    wx.navigateTo({ url: '/pages/station-mgmt/owed-barrels/index' })
  },

  async loadRoleData() {
    if (this.data.isManager) {
      try {
        const s = await get(API.STATION_GET)
        this.setData({ stationInfo: s.data || null })
      } catch (e) {
        console.error('[Mine] 水站信息加载失败:', e)
      }
      this.loadStaffList()
      this.loadBindApplications()
    }
  },

  async loadStaffList() {
    try {
      const r = await get(API.MANAGER_STAFF)
      const firstChar = (str) => str ? str.charAt(0) : ''
      this.setData({
        staffList: (r.data || []).map(s => ({
          ...s,
          _loading: false,
          _firstChar: firstChar(s.name || s.nickname || s.nickName || '配')
        }))
      })
      this.syncStaffUnbindFlags()
    } catch (e) {
      // 静默失败会让站长看到「本站没有配送员」，可能误以为需要重新添加员工。
      console.error('[Mine] 员工列表加载失败:', e)
      wx.showToast({ title: '员工列表加载失败', icon: 'none' })
    }
  },

  // [2026-09-16 修复] GET /api/manager/staff 返回的是 Staff 实体，没有 bindStatus 字段，
  // 前端原来判 s.bindStatus 恒为 undefined → 永远显示「已绑定」，有解绑申请的配送员看不出来。
  // 改用本页已加载的待审批申请（type=2 解绑）推导，两个接口的数据都齐了才准。
  syncStaffUnbindFlags() {
    const pendingUnbind = new Set(
      (this.data.bindApplications || [])
        .filter(a => Number(a.type) === 2)
        .map(a => a.staffId)
    )
    const staffList = (this.data.staffList || []).map(s => ({
      ...s,
      _unbindPending: pendingUnbind.has(s.id)
    }))
    this.setData({ staffList })
  },

  async loadBindApplications() {
    try {
      const r = await get(API.MANAGER_BIND_APPLICATIONS)
      const firstChar = (str) => str ? str.charAt(0) : ''
      // [2026-09-16 修复] 后端 applicationToMap 下发的是 staffName / staffPhone /
      // createTime / applyNote，此前前端读 name / phone / applyTime / remark，
      // 字段全不匹配 → 所有申请人都显示成「申请人 · 暂无电话 · 今天」，无法分辨。
      // 这里统一归一到视图字段名（真实字段优先，兼容旧写法）。
      this.setData({
        bindApplications: (r.data || []).map(a => ({
          ...a,
          name: a.staffName || a.nickname || a.name || '',
          phone: a.staffPhone || a.phone || '',
          applyTime: a.createTime || a.applyTime || '',
          applyNote: a.applyNote || a.remark || a.skill || '',
          _loading: false,
          _firstChar: firstChar(a.staffName || a.nickname || a.name || a.nickName || '申')
        }))
      })
      this.syncStaffUnbindFlags()
    } catch (e) {
      console.error('[Mine] 绑定申请加载失败:', e)
      wx.showToast({ title: '绑定申请加载失败', icon: 'none' })
    }
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onGoLogin() {
    // 唯一登录入口：login 页面（包含微信登录 + 开发模式调试入口）
    wx.redirectTo({ url: '/pages/login/index' })
  },

  onGoCoordination() {
    wx.switchTab({ url: '/pages/coordination/index' })
  },

  onGoStationMgmt() {
    wx.navigateTo({ url: '/pages/station-mgmt/index' })
  },

  /** 员工（列表/画像/绑定申请审核）已整块搬到站长端员工页，这里直跳，不再走宫格中转 */
  onGoStaff() {
    wx.navigateTo({ url: '/pages/station-mgmt/staff/index' })
  },

  /** 水站资料（站名/电话/地址/坐标）——2026-09-19 起有真页面可改，不再只是只读展示 */
  onGoStationInfo() {
    wx.navigateTo({ url: '/pages/station-mgmt/station-info/index' })
  },

  onBarrelRecords() {
    wx.navigateTo({ url: '/pages/barrel-records/index' })
  },

  /** 我的工资：员工自助只读页（配送员与自己也在送水的站长都能看，身份由服务端从登录态取） */
  onMyEarnings() {
    wx.navigateTo({ url: '/pages/my-earnings/index' })
  },

  onEditProfile() {
    wx.navigateTo({ url: '/pages/mine/edit' })
  },

  onHistory() {
    wx.navigateTo({ url: '/pages/history/index' })
  },

  onTransfer() {
    wx.navigateTo({ url: '/pages/transfer/index' })
  },

  onReport() {
    wx.navigateTo({ url: '/pages/report/index' })
  },

  onSettings() {
    wx.navigateTo({ url: '/pages/settings/index' })
  },

  // 配送员申请解绑(需站长审批通过后 station_id=NULL)
  onApplyUnbind() {
    wx.showModal({
      title: '申请解绑',
      content: '确认向站长提交解绑申请？站长确认前仍可正常配送；确认后将进入未绑定状态。',
      confirmText: '提交申请',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await post(API.BIND_UNBIND_REQUEST)
          wx.showToast({ title: '已提交申请', icon: 'success' })
          const app = getApp()
          const userInfo = (app.globalData && app.globalData.userInfo) || {}
          app.setLoginState({
            accessToken: app.globalData.accessToken,
            refreshToken: app.globalData.refreshToken,
            ...userInfo,
            bindStatus: BINDING_STATUS.PENDING_UNBIND
          })
          this.checkLogin()
        } catch (e) {
          wx.showToast({ title: e.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  // 配送员撤回解绑申请: V1 不提供撤回入口，等待站长确认即可。
  onWithdrawUnbind() {
    wx.showToast({ title: '请联系站长处理', icon: 'none' })
  },

  // 站长切换标签
  onMgmtTabSwitch(e) {
    this.setData({ mgmtActiveTab: Number(e.currentTarget.dataset.tab || 0) })
  },

  // 站长单方解除配送员
  async onRemoveStaff(e) {
    const id = e.currentTarget.dataset.id
    const list = this.data.staffList
    wx.showModal({
      title: '解除配送员',
      content: '确定解除该配送员？该操作不可撤销。',
      confirmColor: '#FF3B30',
      confirmText: '解除',
      success: async (res) => {
        if (!res.confirm) return
        const idx = list.findIndex(x => x.id === id || x.staffId === id)
        if (idx >= 0) {
          list[idx]._loading = true
          this.setData({ staffList: [...list] })
        }
        try {
          await post(API.MANAGER_BIND_RELEASE, { staffId: id })
          wx.showToast({ title: '已解除', icon: 'success' })
          this.loadStaffList()
        } catch (e) {
          if (idx >= 0) {
            list[idx]._loading = false
            this.setData({ staffList: [...list] })
          }
          wx.showToast({ title: e.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  // 站长同意申请
  // [2026-09-16 修复] 绑定申请(type=1)与解绑申请(type=2)共用同一张待审批列表，
  // 但后端是两个端点：/approve 对 type!=1 直接报「这不是绑定申请」，解绑会永远悬挂。
  async onApproveBind(e) {
    const id = e.currentTarget.dataset.id
    const apps = this.data.bindApplications
    const idx = apps.findIndex(a => a.id === id || a.applyId === id)
    if (idx >= 0) { apps[idx]._loading = true; this.setData({ bindApplications: [...apps] }) }
    const isUnbind = idx >= 0 && Number(apps[idx].type) === 2
    try {
      await post(isUnbind ? API.MANAGER_BIND_UNBIND_CONFIRM : API.MANAGER_BIND_APPROVE, { applicationId: id })
      wx.showToast({ title: isUnbind ? '已同意解绑' : '已同意绑定', icon: 'success' })
      this.loadBindApplications()
      this.loadStaffList()
    } catch (e) {
      if (idx >= 0) { apps[idx]._loading = false; this.setData({ bindApplications: [...apps] }) }
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    }
  },

  // 站长拒绝申请（同 onApproveBind，解绑申请须走 /unbind-reject）
  async onRejectBind(e) {
    const id = e.currentTarget.dataset.id
    const apps = this.data.bindApplications
    const idx = apps.findIndex(a => a.id === id || a.applyId === id)
    if (idx >= 0) { apps[idx]._loading = true; this.setData({ bindApplications: [...apps] }) }
    const isUnbind = idx >= 0 && Number(apps[idx].type) === 2
    try {
      await post(isUnbind ? API.MANAGER_BIND_UNBIND_REJECT : API.MANAGER_BIND_REJECT, { applicationId: id })
      wx.showToast({ title: isUnbind ? '已拒绝解绑' : '已拒绝绑定', icon: 'success' })
      this.loadBindApplications()
    } catch (e) {
      if (idx >= 0) { apps[idx]._loading = false; this.setData({ bindApplications: [...apps] }) }
      wx.showToast({ title: e.message || '操作失败', icon: 'none' })
    }
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          const app = getApp()
          app.logout()
          this.setData({
            isLogin: false,
            userInfo: null,
            todayStats: {},
            todayProfit: null,
            risk: { barrelExceptionCount: 0, owedCustomerCount: 0 },
            stationInfo: null,
            staffList: [],
            bindApplications: []
          })
          wx.showToast({ title: '已退出登录', icon: 'success' })
        }
      }
    })
  }
})
