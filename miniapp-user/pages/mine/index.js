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
      { icon: 'shop', title: '商城', url: '/pages/shop/index' },
      { icon: 'notice', title: '公告', url: '/pages/notice/index' },
      { icon: 'chat', title: '联系客服', url: '/pages/service/index' }
    ],
    loginMenu: [
      { icon: 'order', title: '常用订单', url: '/pages/order/list' },
      { icon: 'notice', title: '公告', url: '/pages/notice/index' },
      { icon: 'bill', title: '账单记录', url: '/pages/payment/records' },
      { icon: 'location', title: '地址管理', url: '/pages/address/list' },
      { icon: 'barrel', title: '我的水桶', url: '/pages/barrel/index' },
      { icon: 'ticket', title: '我的水票', url: '/pages/ticket/index' },
      { icon: 'building', title: '企业资料', url: '/pages/mine/company' },
      { icon: 'shop', title: '商城', url: '/pages/shop/index' },
      { icon: 'chat', title: '客服与反馈', url: '/pages/service/index' }
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
      this.setData({ barrelSummary: res.data || res })
    }).catch(() => {})
  },

  loadCustomerStats() {
    getCustomerStats().then(res => {
      this.setData({ customerStats: res.data || res })
    }).catch(() => {})
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onMenuTap(e) {
    const url = e.currentTarget.dataset.url
    // tabBar 页面必须用 switchTab 跳转，否则会被微信拦截
    const tabPages = ['/pages/home/index', '/pages/order/list', '/pages/mine/index']
    if (tabPages.indexOf(url) >= 0) {
      wx.switchTab({ url })
    } else {
      wx.navigateTo({ url })
    }
  },

  onEditProfile() {
    wx.navigateTo({ url: '/pages/mine/edit' })
  },

  onRecharge() {
    wx.navigateTo({ url: '/pages/ticket/index' })
  },

  onGoOrder() {
    wx.switchTab({ url: '/pages/order/list' })
  },

  onInvite() {
    wx.showToast({ title: '邀请功能即将上线', icon: 'none' })
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
