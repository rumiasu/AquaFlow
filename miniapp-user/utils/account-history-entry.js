const api = require('../api/account-data-requests')
const { captureSession: capture, isCurrentSession: current } = require('./token')
const valid = s => !!(s.loggedIn && s.customerId)
// Only low-frequency pages read history; no request or cross-user cache on the mine tab.
module.exports = {
  async refreshAccountHistory() {
    const seq = this._historySeq = (this._historySeq || 0) + 1
    this._historyVisible = true
    const session = this._historySession = capture()
    this.setData({ hasAccountHistory: false, accountHistoryLoading: valid(session), accountHistoryError: '' })
    if (!valid(session)) return
    try {
      const res = await api.mine()
      if (!res || !res.data || !Array.isArray(res.data.items)) throw Error('unavailable')
      if (this._historyVisible && seq === this._historySeq && current(session)) this.setData({ hasAccountHistory: res.data.items.length > 0 })
    } catch (_) {
      if (this._historyVisible && seq === this._historySeq && current(session)) this.setData({ accountHistoryError: '申请记录暂时无法查询，请重试。' })
    } finally {
      if (this._historyVisible && seq === this._historySeq) this.setData({ accountHistoryLoading: false })
    }
  },
  onRetryAccountHistory() { if (!this.data.accountHistoryLoading) return this.refreshAccountHistory() },
  onOpenAccountHistory() {
    if (!this._historyVisible || !current(this._historySession) || !this.data.hasAccountHistory) return
    wx.navigateTo({ url: '/pages/mine/account-data/index?view=records' })
  },
  onHide() { this._historyVisible = false; this._historySeq = (this._historySeq || 0) + 1; this.setData({ hasAccountHistory: false, accountHistoryError: '', accountHistoryLoading: false }) },
  onUnload() { this._historyVisible = false; this._historySeq = (this._historySeq || 0) + 1 }
}
