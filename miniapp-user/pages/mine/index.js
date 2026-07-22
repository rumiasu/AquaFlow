const { getBarrelSummary } = require('../../api/barrel')
const { getCustomerStats } = require('../../api/customer')
const app = getApp()

Page({
  data: {
    isLogin: false,
    userInfo: null,
    barrelSummary: null,
    customerStats: null,
    guestMenu: [
      { icon: '🛒', title: '商城', url: '/pages/shop/index' },
      { icon: '💬', title: '联系客服', url: '/pages/service/index' }
    ],
    loginMenu: [
      { icon: '📋', title: '常用订单', url: '/pages/order/list' },
      { icon: '📍', title: '地址管理', url: '/pages/address/list' },
      { icon: '🪣', title: '我的水桶', url: '/pages/barrel/index' },
      { icon: '🎫', title: '我的水票', url: '/pages/ticket/index' },
      { icon: '🛒', title: '商城', url: '/pages/shop/index' },
      { icon: '💬', title: '联系客服', url: '/pages/service/index' }
    ]
  },

  onShow() {
    const { isLogin, userInfo } = app.globalData
    this.setData({ isLogin, userInfo })
    if (isLogin) {
      this.loadBarrelSummary()
      this.loadCustomerStats()
    }
  },

  loadBarrelSummary() {
    getBarrelSummary().then(res => {
      this.setData({ barrelSummary: res })
    }).catch(() => {})
  },

  loadCustomerStats() {
    getCustomerStats().then(res => {
      this.setData({ customerStats: res })
    }).catch(() => {})
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onMenuTap(e) {
    const url = e.currentTarget.dataset.url
    wx.navigateTo({ url })
  },

  onEditProfile() {
    wx.navigateTo({ url: '/pages/mine/edit' })
  },

  onBarrelTap() {
    wx.navigateTo({ url: '/pages/barrel/index' })
  },

  onLogout() {
    wx.showModal({
      title: '提示',
      content: '确定退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          app.clearLoginInfo()
          this.setData({ isLogin: false, userInfo: null, barrelSummary: null, customerStats: null })
          wx.showToast({ title: '已退出', icon: 'success' })
        }
      }
    })
  }
})
