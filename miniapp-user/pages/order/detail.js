const { getOrderDetail, cancelOrder } = require('../../api/order')
const { createPayment } = require('../../api/order')
const { getOrderImages } = require('../../api/orderImage')
const { getProductDetail } = require('../../api/product')
const { getCustomerId } = require('../../utils/token')
const { notifyPayResult } = require('../../utils/pay')

Page({
  data: {
    order: null,
    items: [],
    statusText: '',
    payStatusText: '',
    payStatusClass: '',
    canCancel: false,
    canRepay: false,
    repayLabel: '去支付',
    payHint: '',
    images: [],
    bucketInfo: null
  },

  onLoad(options) {
    if (options.id) {
      this.loadOrder(options.id)
      this.loadImages(options.id)
    }
  },

  onPullDownRefresh() {
    if (this.data.order) {
      this.loadOrder(this.data.order.id).then(() => {
        this.loadImages(this.data.order.id)
        wx.stopPullDownRefresh()
      })
    } else {
      wx.stopPullDownRefresh()
    }
  },

  async loadOrder(id) {
    try {
      const res = await getOrderDetail(id)
      const order = res.data || res

      let items = []
      if (order.items && order.items.length > 0) {
        items = order.items.map(it => ({
          productId: it.productId || it.waterTypeId,
          productName: it.productNameSnapshot || it.productName || it.waterTypeName || '',
          productSpec: it.specSnapshot || it.productSpec || it.waterTypeSpec || '',
          quantity: it.quantity || order.quantity || 1,
          price: it.price || it.productPrice || 0,
          imageUrl: ''
        }))
      } else {
        items = [{
          productId: order.productId || order.waterTypeId,
          productName: order.productNameSnapshot || order.productName || order.waterTypeName || '',
          productSpec: order.specSnapshot || order.productSpec || order.waterTypeSpec || '',
          quantity: order.quantity || 1,
          price: order.price || 0,
          imageUrl: ''
        }]
      }

      const bucketInfo = {
        deliveredQty: order.deliveredBuckets || order.deliveredQty || 0,
        returnedQty: order.returnedBuckets || order.returnedQty || 0,
        pendingUnreturned: Math.max(0, (order.deliveredBuckets || order.deliveredQty || 0) - (order.returnedBuckets || order.returnedQty || 0))
      }
      if (order.extraDepositBuckets != null && order.extraDepositBuckets > 0) {
        bucketInfo.extraDepositBuckets = order.extraDepositBuckets
        bucketInfo.extraDepositAmount = order.extraDepositAmount || 0
      }

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
      const canCancel = !!order.canCancel
      const canRepay = !!order.canRepay
      const repayLabel = order.repayLabel || '去支付'
      const payHint = order.payHint || ''

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

      this.setData({ order, items, statusText, payStatusText, payStatusClass, canCancel, canRepay, repayLabel, payHint, bucketInfo, fee })

      this.loadItemImages(items)
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  async loadItemImages(items) {
    const ids = items.map(i => i.productId).filter(Boolean)
    if (ids.length === 0) return
    for (const pid of ids) {
      try {
        const res = await getProductDetail(pid, this.stationIdOfOrder)
        if (res && res.data && res.data.imageUrl) {
          const idx = this.data.items.findIndex(i => i.productId === pid)
          if (idx >= 0) {
            this.setData({ [`items[${idx}].imageUrl`]: res.data.imageUrl })
          }
        }
      } catch (e) { /* ignore */ }
    }
  },

  onCallPhone() {
    if (this.data.order && this.data.order.customerPhone) {
      wx.makePhoneCall({ phoneNumber: this.data.order.customerPhone })
    }
  },

  loadImages(id) {
    return getOrderImages(id).then(res => {
      this.setData({ images: res.data || [] })
    }).catch(() => {
      this.setData({ images: [] })
    })
  },

  onPreviewImage(e) {
    const { url } = e.currentTarget.dataset
    const urls = this.data.images.map(i => i.url)
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
    wx.showModal({
      title: '取消订单',
      content: '确定取消此订单吗？取消后将自动释放库存、退水票、退款。',
      confirmColor: '#f5222d',
      success: (res) => {
        if (res.confirm) {
          cancelOrder(this.data.order.id).then(() => {
            wx.showToast({ title: '订单已取消', icon: 'success' })
            this.loadOrder(this.data.order.id)
          }).catch(err => {
            wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          })
        }
      }
    })
  }
})
