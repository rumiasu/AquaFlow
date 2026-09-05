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

  onLoad() {
    this.loadStations()
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
