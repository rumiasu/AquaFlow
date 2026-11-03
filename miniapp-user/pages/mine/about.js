const accountHistory = require('../../utils/account-history-entry')
Page({
  ...accountHistory,
  onOpenAccountHistory() { return accountHistory.onOpenAccountHistory.call(this) },
  onRetryAccountHistory() { return accountHistory.onRetryAccountHistory.call(this) },
  data: {
    version: ''
  },

  onShow() { return this.refreshAccountHistory() },
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
