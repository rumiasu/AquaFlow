const { logout } = require('../../services/loginService')
const { getBarrelSummary } = require('../../api/barrel')

Page({
  data: {
    isLogin: false,
    userInfo: null,
    barrelSummary: null,
    guestMenu: [
      { id: 'shop', icon: '🛒', title: '商城', url: '/pages/shop/index' },
      { id: 'service', icon: '📞', title: '联系客服', url: '/pages/service/index' }
    ],
    loginMenu: [
      { id: 'template', icon: '⚡', title: '常用订单', url: '/pages/template/index' },
      { id: 'address', icon: '📍', title: '地址管理', url: '/pages/address/list' },
      { id: 'barrel', icon: '🪣', title: '我的水桶', url: '/pages/barrel/index' },
      { id: 'ticket', icon: '🎫', title: '我的水票', url: '/pages/ticket/index' },
      { id: 'shop', icon: '🛒', title: '商城', url: '/pages/shop/index' },
      { id: 'service', icon: '📞', title: '联系客服', url: '/pages/service/index' }
    ]
  },

  onLoad() {},

  onShow() {
    const app = getApp()
    const isLogin = app.globalData.isLogin
    const userInfo = app.globalData.userInfo
    this.setData({ isLogin, userInfo })
    if (isLogin) {
      this.loadBarrelSummary()
    }
  },

  async loadBarrelSummary() {
    try {
      const res = await getBarrelSummary()
      if (res.data) {
        this.setData({ barrelSummary: res.data })
      }
    } catch (error) {
      console.warn('[Mine] loadBarrelSummary失败:', error.message)
    }
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onMenuTap(e) {
    const { url } = e.currentTarget.dataset
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
      title: '确认退出',
      content: '确定要退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          const app = getApp()
          app.clearLoginInfo()
          this.setData({ isLogin: false, userInfo: null, barrelSummary: null })
        }
      }
    })
  }
})
