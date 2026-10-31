Page({
  data: {
    version: ''
  },

  onLoad() {
    try { const info = wx.getAccountInfoSync && wx.getAccountInfoSync(); this.setData({ version: info && info.miniProgram && info.miniProgram.version || '' }) } catch (_) { this.setData({ version: '' }) }
  },
  onOpenAgreement(e) {
    const type = e.currentTarget.dataset.type === 'privacy' ? 'privacy' : 'user'
    wx.navigateTo({ url: '/pages/mine/agreement/index?type=' + type })
  },

  onOpenService() {
    wx.navigateTo({ url: '/pages/service/index' })
  }
})
