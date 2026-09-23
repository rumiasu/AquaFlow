const { getPaymentsByCustomer } = require('../../api/payment')
const { formatMoney, formatTime } = require('../../utils/format')

// 支付方式/支付状态文案一律由后端下发（PaymentRecord.getMethodText / getStatusText，
// 真相源为 PayMethod.java / PaymentStatus.java）。前端不再自建 1/2/3 映射表：
// 历史上两端各写一套，后端调整口径后前端不跟随，导致展示与实际状态不符。

Page({
  data: {
    loading: true,
    records: []
  },

  onShow() {
    this.loadRecords()
  },

  onPullDownRefresh() {
    this.loadRecords().then(() => wx.stopPullDownRefresh())
  },

  async loadRecords() {
    this.setData({ loading: true })
    try {
      const res = await getPaymentsByCustomer()
      const list = (res.data || []).map(r => ({
        ...r,
        methodText: r.methodText || '—',
        statusText: r.statusText || '—',
        amountText: formatMoney(r.amount),
        timeText: formatTime(r.createTime)
      }))
      this.setData({ records: list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})