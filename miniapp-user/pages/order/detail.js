const { getOrderDetail, cancelOrder } = require('../../api/order')
const { createPayment } = require('../../api/order')
const { getOrderImages } = require('../../api/orderImage')
const { getProductDetail } = require('../../api/product')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { notifyPayResult } = require('../../utils/pay')
const { getStationPublicPhone } = require('../../api/station')
const { itemUnit } = require('../../utils/order-item-view')
const { cancelView, cancelResultText } = require('../../utils/customer-cancel-view')

Page({
  data: {
    detailState: 'loading',
    loadError: '',
    order: null,
    items: [],
    statusText: '',
    payStatusText: '',
    payStatusClass: '',
    canCancel: false,
    cancelLabel: '',
    canRepay: false,
    repayLabel: '去支付',
    payHint: '',
    deliveredAwaitingCollection: false,
    // 只拨本单服务水站公开电话；不暴露员工通讯录或客户档案。
    callPhone: '',
    images: [],
    imagesState: 'idle',
    imagesError: '',
    bucketInfo: null
  },

  onLoad(options = {}) {
    this._destroyed = false
    this._orderId = options.id || null
    if (this._orderId) return this.loadOrder(this._orderId)
    this.clearDetail('notfound', '未提供订单编号')
  },

  onShow() {
    if (this._detailContext && !isCurrentSession(this._detailContext.session)) {
      this._detailSeq = (this._detailSeq || 0) + 1
      this.clearDetail('error', '登录状态已变化，请重新加载')
    }
  },

  onUnload() {
    this._destroyed = true
    this._detailSeq = (this._detailSeq || 0) + 1
  },

  async onPullDownRefresh() {
    try {
      if (this._orderId) await this.loadOrder(this._orderId)
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  onRetry() {
    if (this._orderId) return this.loadOrder(this._orderId)
  },

  clearDetail(detailState, loadError = '') {
    this.setData({ detailState, loadError, order: null, items: [], images: [],
      imagesState: 'idle', imagesError: '', callPhone: '', bucketInfo: null, fee: null,
      canCancel: false, cancelLabel: '', canRepay: false, statusText: '', payStatusText: '', payHint: '',
      deliveredAwaitingCollection: false, legacyNoteUnavailable: false })
  },

  isDetailCurrent(context) {
    return !!context && !this._destroyed && context.seq === this._detailSeq
      && String(context.id) === String(this._orderId) && isCurrentSession(context.session)
  },

  async loadOrder(id) {
    if (this._destroyed) return
    this._orderId = id
    const context = { id, seq: this._detailSeq = (this._detailSeq || 0) + 1, session: captureSession() }
    this._detailContext = context
    this.clearDetail('loading')
    try {
      const res = await getOrderDetail(id)
      if (!this.isDetailCurrent(context)) return
      const order = res && Object.prototype.hasOwnProperty.call(res, 'data') ? res.data : res
      if (!order || !order.id) {
        this.clearDetail('notfound', '订单不存在')
        return
      }
      if (String(order.id) !== String(id)) throw new Error('订单信息不匹配，请重新加载')

      let items = []
      if (order.items && order.items.length > 0) {
        items = order.items.map(it => ({
          productId: it.productId || it.waterTypeId,
          productName: it.productNameSnapshot || it.productName || it.waterTypeName || '',
          productSpec: it.specSnapshot || it.productSpec || it.waterTypeSpec || '',
          quantity: it.quantity || order.quantity || 1,
          quantityUnit: itemUnit(it),
          price: it.price || it.productPrice || 0,
          imageUrl: ''
        }))
      } else {
        items = [{
          productId: order.productId || order.waterTypeId,
          productName: order.productNameSnapshot || order.productName || order.waterTypeName || '',
          productSpec: order.specSnapshot || order.productSpec || order.waterTypeSpec || '',
          quantity: order.quantity || 1,
          quantityUnit: itemUnit(order),
          price: order.price || 0,
          imageUrl: ''
        }]
      }

      // 只展示 type=8 配送流水的真实数据；绝不以送出-收回推欠桶（新押金桶不属于回收义务）。
      const bucketInfo = order.bucketDeliveryRecorded ? {
        deliveredQty: Number(order.deliveredBarrelQty || 0),
        returnedQty: Number(order.returnedBarrelQty || 0)
      } : null

      // 订单归属站：补商品图/详情时带上它，才能读到本站自定义商品（后端按 owner_station_id 过滤）
      this.stationIdOfOrder = order.stationId || order.ownerStationId || null

      // 状态/支付文案、能否取消、能否重新支付：全部由后端计算下发（Orders 派生字段），
      // 前端只负责渲染与选配色，不再自行推导业务规则（此前 canCancel/canRepay 各端各写一套）。
      const statusText = order.statusText || ''
      const payStatusText = order.payStateText || ''
      const payClassMap = {
        UNPAID: 'default', PENDING: 'warning', PAID: 'success',
        REFUNDED: 'default', CANCELLED: 'default'
      }
      const payStatusClass = payClassMap[order.payState] || 'default'
      const cancel = cancelView(order)
      const canCancel = cancel.canCancel
      const cancelLabel = cancel.label
      const canRepay = !!order.canRepay
      const repayLabel = order.repayLabel || '去支付'
      // 仅调整已送达现金单的展示；后端支付终态与按钮权限仍照原值使用。
      const deliveredAwaitingCollection = order.status === 3 && order.needCollect === true
        && (order.payState === 'PENDING' || order.payState === 'UNPAID')
      const payHint = deliveredAwaitingCollection
        ? '本单已送达，货款待确认收款。请与配送员或水站核对收款情况。'
        : order.payHint || ''

      // 费用明细：全部用后端下发的金额字段，前端口径只做格式化
      // ⚠️ 配送费/楼层费是 v34/v35 加的列，**必须在明细里逐项出现** —— 只把它们并进合计
      //    而不显示，客户会以为算错了钱（下单页那句同样的注释已经写过一次，这里是同一个坑）。
      const yuan = v => Number(v || 0).toFixed(2)
      const fee = {
        waterText: yuan(order.waterAmount),
        depositText: yuan(order.depositAmount),
        extraDepositText: yuan(order.extraDepositAmount),
        deliveryFeeText: yuan(order.deliveryFee),
        floorFeeText: yuan(order.floorFee),
        totalText: yuan(order.totalAmount),
        hasDeposit: Number(order.depositAmount || 0) > 0 || Number(order.extraDepositAmount || 0) > 0,
        hasDeliveryFee: Number(order.deliveryFee || 0) > 0,
        hasFloorFee: Number(order.floorFee || 0) > 0
      }

      this.setData({ detailState: 'ready', loadError: '', order, items, statusText, payStatusText, payStatusClass, canCancel, cancelLabel, canRepay, repayLabel, payHint, deliveredAwaitingCollection, bucketInfo, fee, callPhone: '', legacyNoteUnavailable: !order.customerNote && !!order.specialNote })
      await Promise.allSettled([
        this.loadCallableStationPhone(order, context),
        this.loadItemImages(items, context, this.stationIdOfOrder),
        this.loadImages(id, context)
      ])
    } catch (err) {
      if (!this.isDetailCurrent(context)) return
      const message = err.message || '订单暂时无法加载，请重试'
      // OrderController.getById 对不存在的订单返回这一精确业务错误；权限错误另行显示。
      this.clearDetail(message === '订单不存在' ? 'notfound' : 'error', message)
    } finally {
      if (!this._destroyed && context.seq === this._detailSeq && !isCurrentSession(context.session)) {
        this.clearDetail('error', '登录状态已变化，请重新加载')
      }
    }
  },

  async loadItemImages(items, context = this._detailContext, stationId = this.stationIdOfOrder) {
    const ids = items.map(i => i.productId).filter(Boolean)
    if (ids.length === 0) return
    for (const pid of ids) {
      if (!this.isDetailCurrent(context)) return
      try {
        const res = await getProductDetail(pid, stationId)
        if (!this.isDetailCurrent(context)) return
        if (res && res.data && res.data.imageUrl) {
          const idx = this.data.items.findIndex(i => i.productId === pid)
          if (idx >= 0) {
            this.setData({ items: this.data.items.map((item, index) => index === idx ? { ...item, imageUrl: res.data.imageUrl } : item) })
          }
        }
      } catch (e) { /* ignore */ }
    }
  },

  async loadCallableStationPhone(order, context = this._detailContext) {
    // 已接单后联系履约站；未接单时联系归属站。使用公开电话端点，不读取员工电话。
    const stationId = order && (order.deliveryStationId || order.stationId)
    if (!stationId) return
    try {
      const res = await getStationPublicPhone(stationId)
      const data = res && (res.data || res)
      if (this.isDetailCurrent(context)) {
        this.setData({ callPhone: data && data.phone ? String(data.phone) : '' })
      }
    } catch (_) {
      // 联系入口不可用时不显示假按钮；订单本身仍可正常查看。
    }
  },

  onCallPhone() {
    const phone = this.data.callPhone || ''
    if (!phone) {
      // 双保险：按钮已按 wx:if 隐藏，这里再挡一次，避免将来有人把按钮改回常显时又拨错号。
      console.warn('没有可拨打的水站公开电话，已跳过拨号')
      return
    }
    wx.makePhoneCall({ phoneNumber: phone })
  },

  async loadImages(id, context = this._detailContext) {
    if (!this.isDetailCurrent(context)) return
    const seq = this._imagesSeq = (this._imagesSeq || 0) + 1
    const current = () => this.isDetailCurrent(context) && seq === this._imagesSeq
    this.setData({ imagesState: 'loading', imagesError: '' })
    try {
      const res = await getOrderImages(id)
      if (!current()) return
      if (!res || !Array.isArray(res.data)) throw new Error('配送凭证暂时无法加载')
      this.setData({ images: res.data, imagesState: 'ready', imagesError: '' })
    } catch (_) {
      if (current()) this.setData({ imagesState: 'error', imagesError: '配送凭证暂时无法加载，请重试' })
    }
  },

  onRetryImages() {
    if (this.data.detailState === 'ready') return this.loadImages(this._orderId)
  },

  onItemImageError(e) {
    if (!this.isDetailCurrent(this._detailContext)) return
    const { index, url } = e.currentTarget.dataset
    const item = this.data.items[index]
    if (!item || item.imageUrl !== url) return
    this.setData({ items: this.data.items.map((row, i) => i === Number(index) ? { ...row, imageUrl: '' } : row) })
  },

  onProofImageError(e) {
    if (!this.isDetailCurrent(this._detailContext)) return
    const { url } = e.currentTarget.dataset
    this.setData({ images: this.data.images.map(row => row.url === url ? { ...row, failed: true } : row),
      imagesState: 'error', imagesError: '部分配送凭证未能显示，请重试' })
  },

  onPreviewImage(e) {
    const { url } = e.currentTarget.dataset
    const urls = this.data.images.filter(i => !i.failed).map(i => i.url)
    if (!urls.includes(url)) return
    wx.previewImage({ current: url, urls })
  },

  onReorder() {
    wx.navigateTo({ url: `/pages/order/create?reorderId=${this.data.order.id}` })
  },

  onPayNow() {
    const { order } = this.data
    if (!order) return
    createPayment({
      orderId: order.id,
      customerId: getCustomerId(),
      amount: order.totalAmount || order.amount,
      paymentMethod: order.paymentMethod || 1,
      waterAmount: 0,
      barrelDeposit: 0,
      extraDepositBuckets: 0,
      extraDepositAmount: 0,
      ticketProductId: null,
      ticketQty: null
    }).then(res => {
      // 按后端返回的真实支付状态提示，不再无条件报"支付成功"
      notifyPayResult(res && res.data)
      this.loadOrder(order.id)
    }).catch(e => {
      wx.showToast({ title: e.message || '支付失败', icon: 'none' })
    })
  },

  onCancel() {
    const order = this.data.order && { ...this.data.order }, view = cancelView(order), context = this._detailContext
    if (!order || !view.canCancel || !this.isDetailCurrent(context) || this._cancelFlight) return
    const flight = {}
    this._cancelFlight = flight
    const current = () => this._cancelFlight === flight && this.isDetailCurrent(context)
      && this.data.order && String(this.data.order.id) === String(order.id)
      && this.data.order.status === order.status && this.data.order.canCancel === true
    const release = () => { if (this._cancelFlight === flight) this._cancelFlight = null }
    wx.showModal({
      title: view.title,
      content: view.content,
      confirmText: view.confirmText,
      confirmColor: '#f5222d',
      fail: release,
      success: async (res) => {
        if (!res.confirm || !current()) { release(); return }
        try {
          await cancelOrder(order.id)
          if (!current()) return
          const reload = this.loadOrder(order.id), reloadContext = this._detailContext
          await reload
          if (this._cancelFlight !== flight || !this.isDetailCurrent(reloadContext)
            || !isCurrentSession(context.session)) return
          wx.showToast({ title: cancelResultText(this.data.order), icon: 'none' })
        } catch (err) {
          if (current()) wx.showToast({ title: err.message || '取消失败', icon: 'none' })
        } finally {
          release()
        }
      }
    })
  }
})
