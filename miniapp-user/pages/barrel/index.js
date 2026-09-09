const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn } = require('../../api/barrel')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    summary: {
      heldBuckets: 0,
      owedBuckets: 0,
      deliveryBuckets: 0,
      returnBuckets: 0,
      actualBuckets: 0,
      pendingReturns: 0,
      confirmedReturns: 0,
      depositBalance: 0,
      depositPerBucket: 0
    },
    records: [],
    customerBarrelAsset: [],
    showReturnModal: false,
    returnForm: {
      productId: null,
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
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()

    this.setData({ loading: true })
    try {
      const [summaryRes, recordsRes, holdingsRes] = await Promise.all([
        getBarrelSummary(stationId).catch(e => { console.warn('[Barrel] getBarrelSummary失败:', e.message); return null }),
        getBarrelRecords(stationId).catch(e => { console.warn('[Barrel] getBarrelRecords失败:', e.message); return null }),
        getBarrelSummaryByType(stationId).catch(e => { console.warn('[Barrel] getBarrelSummaryByType失败:', e.message); return null })
      ])

      if (summaryRes && summaryRes.data) {
        this.setData({ summary: summaryRes.data })
      }
      if (recordsRes && recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }

      let holdings = []
      if (holdingsRes && holdingsRes.data) {
        holdings = holdingsRes.data
      }

      const customerBarrelAsset = []
      const map = {}
      ;(holdings || []).forEach(h => {
        const pid = h.productId || h.waterTypeId
        if (!pid) return
        if (!map[pid]) {
          map[pid] = {
            productId: pid,
            productName: h.productName || h.waterTypeName || '',
            productSpec: h.productSpec || h.waterTypeSpec || '',
            assetQty: 0,
            owedQty: 0,
            deposit: h.deposit || 0
          }
        }
        map[pid].assetQty += (h.assetQty != null ? h.assetQty : ((h.holdingQty || 0) - (h.confirmedQty || 0)))
        map[pid].owedQty += (h.owedQty || 0)
      })
      Object.keys(map).forEach(k => customerBarrelAsset.push(map[k]))

      this.setData({ customerBarrelAsset })

      const { heldBuckets, actualBuckets, pendingReturns } = this.data.summary
      const held = heldBuckets !== undefined && heldBuckets !== null ? heldBuckets : actualBuckets
      this.setData({ maxReturnQty: Math.max(0, (held || 0) - (pendingReturns || 0)) })
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
      'returnForm.productId': null,
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
    // #44: 防止undefined导致NaN
    const maxRefund = this.data.summary.depositBalance || 0
    this.setData({
      'returnForm.depositRefund': Math.min(maxRefund, Math.max(0, val))
    })
  },

  onFullRefund() {
    this.setData({
      'returnForm.depositRefund': this.data.summary.depositBalance || 0
    })
  },

  onReturnNoteInput(e) {
    this.setData({ 'returnForm.note': e.detail.value })
  },

  onSelectProduct(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'returnForm.productId': parseInt(id) || null })
  },

  async onSubmitReturn() {
    const { returnForm, summary } = this.data
    const { productId, quantity, depositRefund, note } = returnForm

    if (quantity <= 0) {
      wx.showToast({ title: '请输入退桶数量', icon: 'none' })
      return
    }

    if (depositRefund > summary.depositBalance) {
      wx.showToast({ title: '退押金金额超过余额', icon: 'none' })
      return
    }

    // 欠桶提醒：客户有欠桶时弹窗提示，但不硬阻拦提交（站长审批时会拦截）
    if (summary.owedBuckets > 0) {
      const confirm = await new Promise((resolve) => {
        wx.showModal({
          title: '存在欠桶提醒',
          content: `您当前欠 ${summary.owedBuckets} 个空桶未归还。存在欠桶时退桶申请可能被站长驳回，建议先归还欠桶后再申请退桶。是否继续提交？`,
          confirmText: '继续提交',
          cancelText: '取消',
          success: (res) => resolve(res.confirm)
        })
      })
      if (!confirm) return
    }

    this.setData({ submitting: true })
    try {
      await requestBarrelReturn({ productId, waterTypeId: productId, quantity, depositRefund, note })
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

  onEcoRuleTap() {
    wx.showModal({
      title: '水桶回收规则',
      content: '1. 水桶需保持完好，无严重破损\n2. 退桶时请联系配送员或到站点办理\n3. 押金将在确认后退还至您的账户\n4. 请勿将水桶用于非饮用水用途',
      showCancel: false,
      confirmText: '我知道了'
    })
  },

})