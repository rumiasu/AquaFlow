const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn, previewBarrelReturn } = require('../../api/barrel')
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
      note: ''
    },
    // 退桶试算结果（后端按押金条批次 FIFO 算出，前端不自行计算金额）
    preview: null,
    previewing: false,
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
    // 默认选中第一个有权益的商品，省得顾客忘了选还要等报错
    const first = (this.data.customerBarrelAsset || []).find(i => (i.assetQty || 0) > 0)
    this.setData({
      showReturnModal: true,
      'returnForm.productId': first ? first.productId : null,
      'returnForm.quantity': 1,
      'returnForm.note': '',
      preview: null
    })
    if (first) this.refreshPreview()
  },

  onCloseReturnModal() {
    this.setData({ showReturnModal: false })
  },

  /**
   * 退桶试算：调后端 /return/preview。
   * 退款金额只认后端按押金条批次算出来的值 —— 前端自己用「数量 × 押金单价」估是错的：
   * 顾客当年买桶的价和现在不一定一样，那是柜台吵架的经典导火索。
   */
  async refreshPreview() {
    const { productId, quantity } = this.data.returnForm
    if (!productId || !quantity || quantity <= 0) {
      this.setData({ preview: null })
      return
    }
    this.setData({ previewing: true })
    try {
      const res = await previewBarrelReturn(productId, quantity, stationStorage.getId())
      this.setData({ preview: (res && res.data) || null })
    } catch (e) {
      console.warn('[Barrel] 退桶试算失败:', e.message)
      this.setData({ preview: null })
    } finally {
      this.setData({ previewing: false })
    }
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
    this.refreshPreview()
  },

  onReturnQtyInput(e) {
    const qty = parseInt(e.detail.value) || 1
    this.setData({
      'returnForm.quantity': Math.min(this.data.maxReturnQty, Math.max(1, qty))
    })
    this.refreshPreview()
  },

  onReturnNoteInput(e) {
    this.setData({ 'returnForm.note': e.detail.value })
  },

  onSelectProduct(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'returnForm.productId': parseInt(id) || null })
    this.refreshPreview()
  },

  async onSubmitReturn() {
    const { returnForm } = this.data
    const { productId, quantity, note } = returnForm

    if (!productId) {
      wx.showToast({ title: '请选择要退的商品', icon: 'none' })
      return
    }
    if (quantity <= 0) {
      wx.showToast({ title: '请输入退桶数量', icon: 'none' })
      return
    }

    // 试算已经把「权益不足 / 有欠桶」拦在前面了，这里只是最后一道提示
    if (this.data.preview && this.data.preview.blocked) {
      wx.showToast({ title: this.data.preview.blockedReason || '当前无法退桶', icon: 'none', duration: 3000 })
      return
    }

    this.setData({ submitting: true })
    try {
      // 不传 depositRefund：金额由服务端按押金条批次核销决定，顾客填多少都不算数
      await requestBarrelReturn({ productId, waterTypeId: productId, quantity, note })
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