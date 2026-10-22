Page({
  data: {
    version: '1.0.0'
  },

  onOpenAgreement(e) {
    const type = e.currentTarget.dataset.type === 'privacy' ? 'privacy' : 'user'
    wx.navigateTo({ url: '/pages/mine/agreement/index?type=' + type })
  },

  onOpenService() {
    wx.navigateTo({ url: '/pages/service/index' })
  }
})
