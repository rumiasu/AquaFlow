const app = getApp()

Page({
  data: {
    userInfo: null,
    stationName: '',
    stationPhone: ''
  },

  onShow() {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    this.setData({ 
      userInfo: app.globalData.userInfo,
      stationName: app.globalData.tempStation?.name || '未选择水站'
    })
  },

  onStationSwitch() {
    wx.navigateTo({ url: '/pages/home/index' })
  },

  onLogout() {
    wx.showModal({
      title: '确认退出登录',
      content: '退出后需要重新登录',
      confirmText: '退出',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) {
          app.logout()
          wx.redirectTo({ url: '/pages/login/index' })
        }
      }
    })
  },

  onAboutTap() {
    wx.showModal({
      title: '关于',
      content: 'AquaFlow 站长端\n版本: 1.0.0\n技术支持: AquaFlow Team',
      showCancel: false
    })
  }
})