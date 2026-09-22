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
  /** 弹窗内容区吞掉点击（wxml 用 catchtap 绑定，此处为空实现，避免未定义方法告警） */
  stopPropagation() {},

  data: {
    orderId: null,
    from: 'detail',
    orderInfo: null,
    orderLoaded: false,
    items: [],
    noteText: '',
    photos: [],
    uploading: false,
    // v43：楼层数（选填）+ 楼层凭证照片（不强制，和客户对峙时用）
    reportedFloor: '',
    floorPhotos: [],
    floorUploading: false,
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
        collected: !isCashOnDelivery,
        // 楼层数**默认带出地址里的楼层**（客户填过就省得配送员再输一遍）；
        // 地址没填就留空 —— 有楼层才填，没有就不填（空 = 沿用地址，两边都没有就不补）。
        reportedFloor: order.addressFloor === null || order.addressFloor === undefined
          ? '' : String(order.addressFloor)
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

  /* ==================== 楼层数（选填）+ 楼层凭证（v43）====================
   * 为什么要有这两样：楼层补贴是给配送员的钱，只有他知道自己爬了几层 ——
   *   ① 楼层数**选填**：有楼层就填、没有就不填；不填时后端沿用客户地址里的楼层；
   *   ② 照片**不强制**（产品决定），但拍一张站得住脚 —— 与客户扯皮时（"你不是说 6 楼吗"）
   *      这是唯一的凭证，站长也可以事后补传。
   * ⚠️ 它不影响向客户收的楼层费 —— 那笔钱在下单时就按地址快照了。
   */
  onFloorInput(e) {
    this.setData({ reportedFloor: e.detail.value })
  },

  onAddFloorPhoto() {
    if (this.data.floorPhotos.length >= 3 || this.data.floorUploading) return
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3 - this.data.floorPhotos.length,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ floorUploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          // 3 = 楼层凭证（1 正常送达 / 2 异常），后端 order_image.type 的注释里有
          formData: { orderId: this.data.orderId, type: 3 }
        }).then(r => r.data))
        try {
          const urls = await Promise.all(uploads)
          this.setData({ floorPhotos: this.data.floorPhotos.concat(urls.filter(Boolean)) })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ floorUploading: false })
        }
      }
    })
  },

  onPreviewFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.floorPhotos[index], urls: this.data.floorPhotos })
  },

  onRemoveFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    this.setData({ floorPhotos: this.data.floorPhotos.filter((_, i) => i !== index) })
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
    // [2026-09-20 预防层] 只列明细还不够：配送员要知道「点下去会发生什么、能不能撤」。
    // 「完成配送」是**不可逆**动作 —— 订单立刻闭环、计件工钱同时产生，事后没有系统内的回退通道
    // （订单类误操作只能线下联系客户协商，见 AGENTS §0.6 / design/20 §5.2）。
    s += '\n订单将立即结算为「已完成」，计件工钱同时产生，且不能撤回。'

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
    const { orderId, items, noteText, collected, isCashOnDelivery, reportedFloor } = this.data

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
        collected: isCashOnDelivery ? collected : true,
        // v43：楼层数选填（有就填、没有不填）。填了才是楼层补贴的依据，
        // 与客户地址里填的不一致时后端会在收益明细里标记出来（防虚报）。
        reportedFloor: reportedFloor === '' || reportedFloor === null ? null : Number(reportedFloor)
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
