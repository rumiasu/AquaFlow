const { getPaymentsByCustomer } = require('../../api/payment')
const { formatMoney, formatTime } = require('../../utils/format')
const { captureSession, isCurrentSession } = require('../../utils/token')

// 2026-10-07：失败曾只弹 toast，空数组被说成“暂无账单”；只用成功读取确认空态。
// 用途/方式/状态文案读服务端，尤其不能把无订单的独立押金误标成买水票。
Page({
  data: {
    loading: true,
    loaded: false,
    error: '',
    records: []
  },

  onShow() {
    this._hidden = false
    return this.loadRecords()
  },

  onHide() {
    this._hidden = true
    this.invalidatePullRefresh()
    this._recordsSeq = (this._recordsSeq || 0) + 1
    this.setData({ loading: false })
  },

  onUnload() {
    this._destroyed = true
    this.invalidatePullRefresh()
    this._recordsSeq = (this._recordsSeq || 0) + 1
  },

  async onPullDownRefresh() {
    if (this._destroyed || this._hidden) return
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullActive = true
    try { await this.loadRecords() } finally {
      // 旧请求结束不能停止后来一次下拉的动画；离页时由生命周期主动收尾。
      if (version === this._pullVersion) {
        this._pullActive = false
        wx.stopPullDownRefresh()
      }
    }
  },

  invalidatePullRefresh() {
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullActive) wx.stopPullDownRefresh()
    this._pullActive = false
  },

  onRetry() {
    return this.loadRecords()
  },

  canApplyRecords(context) {
    if (this._destroyed || this._hidden || context.seq !== this._recordsSeq) return false
    if (isCurrentSession(context.session)) return true
    this._recordsSeq++
    this._recordsSession = null
    this.setData({ records: [], loaded: false, loading: false, error: '登录身份已变化，请重新加载账单' })
    return false
  },

  async loadRecords() {
    if (this._destroyed || this._hidden) return
    const session = captureSession()
    // 同一客户退出重登也不能沿用上一登录周期的钱款记录；正常续期仍属于原周期。
    if (!this._recordsSession || !isCurrentSession(this._recordsSession)) {
      this.setData({ records: [], loaded: false, error: '' })
    }
    this._recordsSession = session
    const context = { session, seq: this._recordsSeq = (this._recordsSeq || 0) + 1 }
    if (!session.loggedIn || !session.customerId) {
      this.setData({ records: [], loaded: false, loading: false, error: '请先登录后查看账单记录' })
      return
    }
    this.setData({ loading: true, error: '' })
    try {
      const res = await getPaymentsByCustomer()
      if (!this.canApplyRecords(context)) return
      if (!res || (res.code !== 0 && res.code !== 200) || !Array.isArray(res.data)) {
        throw new Error((res && res.message) || '账单信息暂不可用，请重试')
      }
      const list = res.data.map(r => {
        const amount = r && r.amount
        if (!r || typeof r !== 'object' || Array.isArray(r) || !r.id
          || !['string', 'number'].includes(typeof amount) || String(amount).trim() === ''
          || !Number.isFinite(Number(amount))) throw new Error('账单信息不完整，请重试')
        if (r.createTime && !Number.isFinite(new Date(r.createTime).getTime())) {
          throw new Error('账单时间信息暂不可用，请重试')
        }
        return {
          ...r,
          purposeText: (typeof r.purposeText === 'string' && r.purposeText.trim()) || '支付记录',
          methodText: r.methodText || '—',
          statusText: r.statusText || '—',
          amountText: formatMoney(amount),
          timeText: formatTime(r.createTime)
        }
      })
      this.setData({ records: list, loaded: true, error: '' })
    } catch (err) {
      if (!this.canApplyRecords(context)) return
      const message = (err && err.message) || '加载失败，请重试'
      this.setData({ error: this.data.loaded ? '刷新失败，当前为上次成功查询的记录。' + message : message })
    } finally {
      if (this.canApplyRecords(context)) this.setData({ loading: false })
    }
  }
})
