const { get, post } = require('../../../utils/request')
const { API, BINDING_STATUS } = require('../../../config/api')
const { STORAGE_KEYS } = require('../../../utils/storage-keys')

// V1: 配送员申请绑定水站（极简流程，不填任何个人信息）
// - 页面进入即自动拉取所有水站列表
// - 点击任意水站卡片 = 直接提交绑定申请
// - 提交后 bind_status=PENDING, 跳转到 bind-wait 等候站长审批
Page({
  data: {
    loading: false,
    stationList: [],
    applyingId: null // 正在提交申请的水站ID，防重复点击
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
    this.loadStations()
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

  onPullDownRefresh() {
    this.loadStations().then(() => {
      wx.stopPullDownRefresh()
    })
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
