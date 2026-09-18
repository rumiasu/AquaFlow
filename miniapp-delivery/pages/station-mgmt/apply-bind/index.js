const { get, post } = require('../../../utils/request')
const { API, BINDING_STATUS } = require('../../../config/api')
const { STORAGE_KEYS } = require('../../../utils/storage-keys')

// 配送员自己的绑定/解绑申请历史（只读）。
// 路径常量就近写在本页顶部，不进 config/api.js —— 那是**员工端全局路径表**，
// 为一个页面级只读端点去改它，冲突成本高于收益（本仓前端约定：新页面自带局部常量）。
const BIND_APPLICATIONS = '/api/delivery/bind/applications'

// 时间展示：后端下发 ISO-8601，统一 new Date(str)。
// 不要写 .replace(/-/g, '/') —— 那是给非标准格式打补丁，iOS 上反而更脆（AGENTS.md §8.7）。
function formatTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (isNaN(d.getTime())) return ''
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// V1: 配送员申请绑定水站（极简流程，不填任何个人信息）
// - 页面进入即自动拉取所有水站列表
// - 点击任意水站卡片 = 直接提交绑定申请
// - 提交后 bind_status=PENDING, 跳转到 bind-wait 等候站长审批
Page({
  data: {
    loading: false,
    stationList: [],
    applyingId: null, // 正在提交申请的水站ID，防重复点击
    history: []       // 申请历史（只读，见 loadHistory）
  },

  // 不用 onLoad：进页面先向服务器确认真实状态 —— 可能已经在别处完成了绑定
  // （站长在后台直接把人加进本站 / 审批在另一台设备上点了通过），此时应当直接放行，
  // 而不是继续让用户"申请加入"。
  onShow() {
    this.refresh()
  },

  /**
   * 先同步状态并尝试放行，确认确实还需要申请才加载水站列表。
   * refreshIdentityAndRoute 只在「目标页 ≠ 当前页」时才跳，所以不会自我循环。
   */
  async refresh() {
    const app = getApp()
    const left = await app.refreshIdentityAndRoute('pages/station-mgmt/apply-bind/index')
    if (left) return
    // 顺序有意义：申请历史里的水站名要用 stationList 反查，先有列表再有历史
    await this.loadStations()
    this.loadHistory()
  },

  /**
   * 返回上一步：重新选择身份。
   *
   * 用 reLaunch 而不是 navigateBack —— 本页是 redirectTo 进来的，页面栈里根本没有上一页。
   * 之所以允许返回：**选择身份不等于生效**（还没被站长批准绑定），此时改选是合理的；
   * 生效后（stationId 非空）后端会拒绝改选，这里先拦下免得用户白跑一趟。
   */
  onBackToRoleSelect() {
    const app = getApp()
    if (app.isIdentityEffective()) {
      wx.showToast({ title: '身份已生效，如需更换请联系管理员', icon: 'none' })
      return
    }
    wx.reLaunch({ url: '/pages/role-select/index' })
  },

  /**
   * 退出登录。
   * 本页是被 redirectTo 进来的（页面栈里没有上一页），页面本身也没有任何返回入口 ——
   * 不给出口，用户就会一直卡在这里，这正是"注册卡死"的直接成因。
   */
  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '退出后可用其他微信账号登录。',
      confirmText: '退出',
      success: (res) => {
        if (res.confirm) getApp().logout()
      }
    })
  },

  async onPullDownRefresh() {
    await this.loadStations()
    await this.loadHistory()
    wx.stopPullDownRefresh()
  },

  async loadStations() {
    this.setData({ loading: true })
    try {
      const res = await get(API.STATION_SEARCH, {})
      this.setData({
        stationList: res.data || [],
        loading: false
      })
    } catch (err) {
      this.setData({ loading: false, stationList: [] })
      wx.showToast({ title: err.message || '加载水站列表失败', icon: 'none' })
    }
  },

  /**
   * 申请历史（只读）：回答"上次为什么被拒、什么时候被解绑"。
   *
   * 为什么落在本页而不是等待页 bind-wait（2026-09-18）：被拒(REJECTED) / 未绑定(UNBOUND) 时
   * app.js 一律把人路由到本页（app.js:166-172 _targetRoute；role-select/index.js:83-88 同理），
   * 而 bind-wait 只在 PENDING / PENDING_UNBIND 才停留（bind-wait/index.js:66-88 会 switchTab/redirectTo 走）——
   * 把历史挂在等待页上，恰好是最需要看它的两个状态看不到。
   *
   * 文案一律用后端下发的 typeName / statusName（DeliveryBindingController.applicationToMap），
   * 前端不写 type/status → 中文映射；水站名后端只给 stationId，用本页已有的 stationList 反查补齐。
   */
  async loadHistory() {
    try {
      const res = await get(BIND_APPLICATIONS)
      const stationName = {}
      this.data.stationList.forEach(s => { stationName[s.id] = s.name })
      const history = (res.data || []).map(row => ({
        ...row,
        stationLabel: stationName[row.stationId] || `水站 #${row.stationId}`,
        createTimeText: formatTime(row.createTime),
        handleTimeText: formatTime(row.handleTime)
      }))
      this.setData({ history })
    } catch (err) {
      // 历史是附加信息：拉不到就不显示，不弹错误打断"申请绑定"这条主流程
      this.setData({ history: [] })
    }
  },

  async onApplyStation(e) {
    const stationId = Number(e.currentTarget.dataset.id)
    if (!stationId) {
      wx.showToast({ title: '水站信息异常', icon: 'none' })
      return
    }
    if (this.data.applyingId) return // 防重复提交

    const station = this.data.stationList.find(s => s.id === stationId)
    if (!station) {
      wx.showToast({ title: '未找到对应水站', icon: 'none' })
      return
    }

    this.setData({ applyingId: stationId })
    wx.showLoading({ title: '提交申请中...' })
    try {
      const app = getApp()
      const userInfo = (app.globalData && app.globalData.userInfo) || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}

      // V1: 不校验姓名/手机号/身份证，直接提交 stationId
      await post(API.BIND_APPLY, {
        stationId: stationId
      })

      // 成功后：本地更新 bindStatus=PENDING，并记住申请的水站信息
      const updated = {
        ...userInfo,
        role: 'DELIVERY',
        bindStatus: BINDING_STATUS.PENDING,
        applyStationId: stationId,
        applyStationName: station.name || ''
      }
      app.setLoginState({
        accessToken: app.globalData.accessToken,
        refreshToken: app.globalData.refreshToken,
        ...updated
      })

      wx.hideLoading()
      wx.showToast({ title: '申请已提交，等待站长审批', icon: 'success', duration: 2000 })
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/bind-wait/index' })
      }, 1500)
    } catch (err) {
      wx.hideLoading()
      this.setData({ applyingId: null })
      wx.showToast({ title: err.message || '申请提交失败', icon: 'none', duration: 2500 })
    }
  }
})
