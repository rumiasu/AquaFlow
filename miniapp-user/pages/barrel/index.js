const { getBarrelSummary, getBarrelRecords, requestBarrelReturn } = require('../../api/barrel')

Page({
  data: {
    loading: true,
    summary: {
      deliveryBuckets: 0,
      returnBuckets: 0,
      actualBuckets: 0,
      pendingReturns: 0,
      confirmedReturns: 0,
      depositBalance: 0,
      depositPerBucket: 30
    },
    records: [],
    showReturnModal: false,
    returnForm: {
      quantity: 1,
      depositRefund: 0,
      note: ''
    },
    maxReturnQty: 0,
    submitting: false
  },

  onLoad() {
    this.loadData()
  },

  onShow() {
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const [summaryRes, recordsRes] = await Promise.all([
        getBarrelSummary().catch(e => { console.warn('[Barrel] getBarrelSummary失败:', e.message); return null }),
        getBarrelRecords().catch(e => { console.warn('[Barrel] getBarrelRecords失败:', e.message); return null })
      ])

      if (summaryRes && summaryRes.data) {
        this.setData({ summary: summaryRes.data })
      }
      if (recordsRes && recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }

      const { actualBuckets, pendingReturns } = this.data.summary
      this.setData({ maxReturnQty: Math.max(0, actualBuckets - pendingReturns) })
    } finally {
      this.setData({ loading: false })
    }
  },

  onShowReturnModal() {
    if (this.data.maxReturnQty <= 0) {
      wx.showToast({ title: '暂无可退水桶', icon: 'none' })
      return
    }
    this.setData({
      showReturnModal: true,
      'returnForm.quantity': 1,
      'returnForm.depositRefund': 0,
      'returnForm.note': ''
    })
  },

  onCloseReturnModal() {
    this.setData({ showReturnModal: false })
  },

  onReturnQtyChange(e) {
    const { type } = e.currentTarget.dataset
    let qty = this.data.returnForm.quantity
    if (type === 'add' && qty < this.data.maxReturnQty) {
      qty++
    } else if (type === 'minus' && qty > 1) {
      qty--
    }
    this.setData({ 'returnForm.quantity': qty })
  },

  onReturnQtyInput(e) {
    const qty = parseInt(e.detail.value) || 1
    this.setData({
      'returnForm.quantity': Math.min(this.data.maxReturnQty, Math.max(1, qty))
    })
  },

  onDepositRefundInput(e) {
    const val = parseFloat(e.detail.value) || 0
    const maxRefund = this.data.summary.depositBalance
    this.setData({
      'returnForm.depositRefund': Math.min(maxRefund, Math.max(0, val))
    })
  },

  onFullRefund() {
    this.setData({
      'returnForm.depositRefund': this.data.summary.depositBalance
    })
  },

  onReturnNoteInput(e) {
    this.setData({ 'returnForm.note': e.detail.value })
  },

  async onSubmitReturn() {
    const { returnForm, summary } = this.data
    const { quantity, depositRefund, note } = returnForm

    if (quantity <= 0) {
      wx.showToast({ title: '请输入退桶数量', icon: 'none' })
      return
    }

    if (depositRefund > summary.depositBalance) {
      wx.showToast({ title: '退押金金额超过余额', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await requestBarrelReturn({ quantity, depositRefund, note })
      wx.showToast({ title: '退桶申请已提交', icon: 'success' })
      this.setData({ showReturnModal: false })
      this.loadData()
    } catch (error) {
      console.error('[Barrel] 退桶失败:', error)
      wx.showToast({ title: '提交失败: ' + (error.message || '请检查后端'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ submitting: false })
    }
  },

  getStatusText(status) {
    const map = { 1: '待处理', 2: '已确认', 3: '已退押金', 4: '已驳回' }
    return map[status] || '未知'
  }
})
