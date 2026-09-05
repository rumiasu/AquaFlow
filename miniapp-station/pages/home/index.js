const app = getApp()

Page({
  data: {
    stationName: '',
    todayStats: {
      orderCount: 0,
      deliverCount: 0,
      returnBarrelCount: 0
    },
    pendingExceptions: 0
  },

  onLoad() {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
  },

  onShow() {
    if (app.globalData.isLogin) {
      this.loadStationInfo()
      this.loadTodayStats()
    }
  },

  async loadStationInfo() {
    const app = getApp()
    if (app.globalData.tempStation) {
      this.setData({ stationName: app.globalData.tempStation.name })
    }
  },

  async loadTodayStats() {
    try {
      const { get } = require('../../utils/request')
      const { API } = require('../../config/api')
      
      const res = await get(`/api/delivery/stats/today`)
      if (res.data && res.data.code === 0) {
        this.setData({ todayStats: res.data.data })
      }
    } catch (error) {
      console.error('加载今日统计失败:', error)
    }
  },

  onProductTap() {
    wx.switchTab({ url: '/pages/product/list' })
  },

  onExceptionTap() {
    wx.switchTab({ url: '/pages/exception/list' })
  },

  onOrderTap() {
    wx.navigateTo({ url: '/pages/order/list' })
  }
})