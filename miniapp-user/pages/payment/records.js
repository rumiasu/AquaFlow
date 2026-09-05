const { getPaymentsByCustomer } = require('../../api/payment')
const { formatMoney, formatTime } = require('../../utils/format')

const PAY_METHOD_TEXT = { 1: '微信', 2: '现金/货到付款', 3: '水票' }
const PAY_STATUS_TEXT = { 1: '待收款', 2: '已付款', 3: '已退款', 4: '已取消' }

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
        methodText: PAY_METHOD_TEXT[r.paymentMethod] || '其他',
        statusText: PAY_STATUS_TEXT[r.status] || '未知',
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