// 设置页
const app = getApp()
const { STORAGE_KEYS } = require('../../utils/storage-keys')

Page({
  data: {
    staffId: '',
    stationName: '',
    notifyNewOrder: true
  },

  onLoad() {
    this.loadSettings()
  },

  onShow() {
    this.loadSettings()
  },

  loadSettings() {
    const userInfo = app.globalData.userInfo || {}
    this.setData({
      staffId: userInfo.staffId || wx.getStorageSync(STORAGE_KEYS.STAFF_ID) || '',
      stationName: userInfo.stationName || wx.getStorageSync('stationName') || '未分配',
      notifyNewOrder: wx.getStorageSync('notifyNewOrder') !== false
    })
  },

  onToggleNotify(e) {
    const type = e.currentTarget.dataset.type
    const value = e.detail.value

    if (type === 'newOrder') {
      this.setData({ notifyNewOrder: value })
      wx.setStorageSync('notifyNewOrder', value)
      wx.showToast({
        title: value ? '已开启新订单提醒' : '已关闭新订单提醒',
        icon: 'none'
      })
    }
  },

  onClearCache() {
    wx.showModal({
      title: '清除缓存',
      content: '清除本地缓存，不影响登录状态。确定继续吗？',
      confirmColor: '#FF9500',
      success: (res) => {
        if (res.confirm) {
          const accessToken = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
          const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
          const userInfo = wx.getStorageSync(STORAGE_KEYS.USER_INFO)
          wx.clearStorageSync()
          if (accessToken) wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
          if (refreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
          if (userInfo) wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)
          wx.showToast({ title: '缓存已清除', icon: 'success' })
        }
      }
    })
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出登录吗？',
      confirmColor: '#FF3B30',
      success: (res) => {
        if (res.confirm) {
          app.logout()
        }
      }
    })
  }
})
