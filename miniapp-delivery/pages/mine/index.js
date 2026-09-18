const { getTodayStats } = require('../../api/delivery')
const { get, post, put, del } = require('../../utils/request')
const { API, BINDING_STATUS } = require('../../config/api')

Page({
  data: {
    isLogin: false,
    userInfo: null,
    todayStats: {},
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
        this.loadStats()
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
      if (canAccessBusiness) this.loadStats()
      if (isManager) this.loadRoleData()
    }
  },

  async loadStats() {
    try {
      const res = await getTodayStats()
      this.setData({ todayStats: res.data || {} })
    } catch (err) {
      // 静默失败会让看板把「没加载出来」显示成 0，误导站长。至少留痕 + 提示。
      console.error('[Mine] 今日统计加载失败:', err)
      wx.showToast({ title: '今日统计加载失败', icon: 'none' })
    }
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
