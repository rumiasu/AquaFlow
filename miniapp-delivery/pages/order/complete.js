const { completeOrder, getOrderDetail } = require('../../api/delivery')
const { get } = require('../../utils/request')

const REASON_OPTIONS = [
  { key: 'customer_kept', label: '客户留存' },
  { key: 'lost', label: '路上丢失' },
  { key: 'damaged', label: '破损' },
  { key: 'wrong', label: '送错' },
  { key: 'other', label: '其他' }
]

Page({
  data: {
    orderId: null,
    from: 'detail',
    orderInfo: null,
    orderLoaded: false,
    items: [],
    noteText: '',
    photos: [],
    uploading: false,
    isCashOnDelivery: false,
    collected: false,
    showReasonPicker: false,
    currentReasonItemIdx: -1,
    reasonOptions: REASON_OPTIONS
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ orderId: options.id, from: options.from || 'detail' })
      this.loadOrder(options.id)
    }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
  },

  async loadOrder(id) {
    try {
      const res = await getOrderDetail(id)
      const order = res.data
      if (!order) return

      const isFirstBarrelOrder = order.firstBarrelOrder === true
      const orderItems = order.items || []
      const items = orderItems.map(item => ({
        id: item.id,
        productName: item.productNameSnapshot || item.productName || '未知商品',
        brand: item.brandSnapshot || '',
        spec: item.specSnapshot || '',
        expected: isFirstBarrelOrder ? 0 : (item.quantity || 0),
        actual: isFirstBarrelOrder ? 0 : (item.quantity || 0),
        discrepancy: 0,
        reasons: []
      }))

      const pm = Number(order.paymentMethod)
      const ps = Number(order.paymentStatus)
      const isCashOnDelivery = pm !== 1 && ps !== 2

      this.setData({
        orderInfo: order,
        orderLoaded: true,
        isFirstBarrelOrder,
        items,
        isCashOnDelivery,
        collected: !isCashOnDelivery
      })
    } catch (err) {
      this.setData({ orderLoaded: false })
      wx.showToast({ title: '加载订单失败', icon: 'none' })
    }
  },

  onActualChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const val = Math.max(0, parseInt(e.detail.value) || 0)
    this._updateItemActual(idx, val)
  },

  onActualDecrease(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const item = this.data.items[idx]
    this._updateItemActual(idx, Math.max(0, item.actual - 1))
  },

  onActualIncrease(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const item = this.data.items[idx]
    this._updateItemActual(idx, item.actual + 1)
  },

  _updateItemActual(idx, val) {
    const items = [...this.data.items]
    items[idx].actual = val
    items[idx].discrepancy = items[idx].expected - val
    this.setData({ items })
  },

  _updateReasonOptions() {
    const idx = this.data.currentReasonItemIdx
    if (idx < 0) return
    const item = this.data.items[idx]
    const missing = item.expected - item.actual
    const reasonOptions = REASON_OPTIONS.map(r => ({
      ...r,
      checked: item.reasons.some(reason => reason.key === r.key),
      disabled: !item.reasons.some(reason => reason.key === r.key) && item.reasons.length >= missing
    }))
    this.setData({ reasonOptions })
  },

  onOpenReasonPicker(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    this.setData({ showReasonPicker: true, currentReasonItemIdx: idx }, () => {
      this._updateReasonOptions()
    })
  },

  onCloseReasonPicker() {
    this.setData({ showReasonPicker: false, currentReasonItemIdx: -1 })
  },

  onSelectReason(e) {
    const reasonKey = e.currentTarget.dataset.key
    const idx = this.data.currentReasonItemIdx
    if (idx < 0) return

    const items = [...this.data.items]
    const item = items[idx]
    const missing = item.expected - item.actual

    const existingIdx = item.reasons.findIndex(r => r.key === reasonKey)
    if (existingIdx >= 0) {
      item.reasons.splice(existingIdx, 1)
    } else {
      if (item.reasons.length < missing) {
        item.reasons.push({ key: reasonKey, qty: 1 })
      }
    }
    this.setData({ items }, () => {
      this._updateReasonOptions()
    })
  },

  onReasonQtyChange(e) {
    const { idx, ridx } = e.currentTarget.dataset
    const val = Math.max(0, parseInt(e.detail.value) || 0)
    const items = [...this.data.items]
    items[idx].reasons[ridx].qty = val
    this.setData({ items })
  },

  onRemoveReason(e) {
    const { idx, ridx } = e.currentTarget.dataset
    const items = [...this.data.items]
    items[idx].reasons.splice(ridx, 1)
    this.setData({ items })
  },

  onSelectCollected(e) {
    this.setData({ collected: e.currentTarget.dataset.value === 'true' })
  },

  onNoteInput(e) {
    this.setData({ noteText: e.detail.value })
  },

  onAddPhoto() {
    if (this.data.photos.length >= 3 || this.data.uploading) return
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3 - this.data.photos.length,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ uploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          formData: { orderId: this.data.orderId, type: 1 }
        }).then(r => r.data))
        try {
          const urls = await Promise.all(uploads)
          this.setData({ photos: this.data.photos.concat(urls.filter(Boolean)) })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ uploading: false })
        }
      }
    })
  },

  onPreviewPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.photos[index], urls: this.data.photos })
  },

  onRemovePhoto(e) {
    const { index } = e.currentTarget.dataset
    this.setData({ photos: this.data.photos.filter((_, i) => i !== index) })
  },

  _validate() {
    for (let i = 0; i < this.data.items.length; i++) {
      const item = this.data.items[i]
      const missing = item.expected - item.actual
      if (missing > 0) {
        const totalReasonQty = item.reasons.reduce((s, r) => s + (r.qty || 0), 0)
        if (totalReasonQty !== missing) {
          wx.showToast({ title: `${item.productName} 缺少 ${missing} 桶，请填写异常明细`, icon: 'none' })
          return false
        }
      }
    }
    return true
  },

  async onConfirmComplete() {
    if (!this.data.orderLoaded || !this.data.orderId || !this.data.orderInfo) {
      wx.showToast({ title: '订单未加载完成', icon: 'none' })
      return
    }
    if (!this._validate()) return

    const { items, isCashOnDelivery, collected } = this.data
    const hasAnyReturn = items.some(it => it.actual > 0)

    if (!hasAnyReturn && items.length > 0) {
      wx.showModal({
        title: '确认回桶数',
        content: '所有商品回桶数均为 0，是否确认无误？',
        confirmText: '确认无误',
        success: (res) => {
          if (res.confirm) this._doSubmit()
        }
      })
      return
    }

    if (isCashOnDelivery && !collected) {
      wx.showModal({
        title: '确认未收款',
        content: '此订单为货到付款，确认未收款？完成后将进入待收款列表。',
        confirmText: '确认未收款',
        success: (res) => {
          if (res.confirm) this._doSubmit()
        }
      })
      return
    }

    this._showConfirmDialog()
  },

  _showConfirmDialog() {
    const { items, isCashOnDelivery, collected } = this.data
    let s = ''
    items.forEach(it => {
      s += `${it.productName}：回桶 ${it.actual}/${it.expected}`
      if (it.discrepancy !== 0) {
        s += `（少${Math.abs(it.discrepancy)}）`
      }
      s += '\n'
    })
    if (isCashOnDelivery) {
      s += collected ? '✓ 已收款' : '⚠ 未收款'
    }

    wx.showModal({
      title: '确认完成配送',
      content: s.trim(),
      confirmText: '确认完成',
      confirmColor: '#34C759',
      success: async (res) => {
        if (!res.confirm) return
        this._doSubmit()
      }
    })
  },

  async _doSubmit() {
    const { orderId, items, noteText, collected, isCashOnDelivery } = this.data

    const itemReturns = items.map(it => ({
      orderItemId: it.id,
      productName: it.productName,
      expected: it.expected,
      actual: it.actual,
      reasons: it.reasons.map(r => ({ key: r.key, qty: r.qty }))
    }))

    wx.showLoading({ title: '提交中...' })
    try {
      await completeOrder(orderId, {
        itemReturns,
        note: noteText,
        collected: isCashOnDelivery ? collected : true
      })
      wx.hideLoading()
      wx.showToast({ title: '配送完成！', icon: 'success' })
      setTimeout(() => {
        if (this.data.from === 'home') {
          wx.switchTab({ url: '/pages/home/index' })
        } else {
          wx.navigateBack({ delta: 2 })
        }
      }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '提交失败', icon: 'none' })
    }
  }
})
