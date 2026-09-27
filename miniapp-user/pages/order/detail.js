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
    // 可拨号码（没有 = 不显示「联系配送员」按钮）。
    // [2026-09-26] 见下面 onCallPhone 的核实结论：后端顾客端订单详情不下发配送员电话，
    // 而原来的实现会去拨 order.customerPhone —— **下单人自己**的号码，是个假入口。
    // 这里只把"到底有没有号可拨"变成显式判据，不给它编一个号码、也不新增请求。
    callPhone: '',
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

      this.setData({ order, items, statusText, payStatusText, payStatusClass, canCancel, canRepay, repayLabel, payHint, bucketInfo, fee, callPhone: this.resolveCallablePhone(order) })

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

  /**
   * 「联系配送员」按钮**到底拨谁** —— [2026-09-26 核实结论]
   *
   * <p>核实过程（只读，没动后端）：顾客端订单详情是 {@code GET /api/orders/{id}}
   * → {@code OrderController.getById} → {@code OrderServiceImpl.getById}
   * → {@code OrderMapper.getById}，SQL 只 join {@code customer} 与 {@code address}，
   * **没有任何 staff / 电话列**；Orders 实体里与配送员有关的只有
   * {@code deliveryStaffId} 与 {@code deliveryStaffName}（后者**全仓没有一处查询给它赋值**），
   * 而 {@code customerPhone} / {@code addressPhone} 都是**客户自己**的号码。
   * 结论：<b>后端目前不下发配送员电话</b>，不加请求就取不到。</p>
   *
   * <p>所以这里**不假装**：只有真拿到配送员电话才返回（→ 按钮显示且拨的是配送员）；
   * 拿不到就返回空串，wxml 据此**不渲染**该按钮 —— 原来无条件常显、点了拨的是客户自己的号，
   * 属于"看起来能联系配送员、实际打给自己"的假入口（本轮按卡要求改成不发假的形态）。</p>
   *
   * <p>⚠️ 缺口（留给后端，本轮不改）：要让客户真的能联系配送员，需要顾客端订单详情投影一个
   * <b>配送员电话</b>字段（并确认跨站/抢单池场景下允许对客户下发谁的联系方式）；
   * 或者把入口改成"拨打水站电话"（水站电话在报价接口里已有，见 create.js 的 stationPhone）。
   * 字段定了再改这里的判据，**不要**新加请求去取号码。</p>
   */
  resolveCallablePhone(order) {
    if (!order) return ''
    // 配送员电话：后端将来下发才有值，现在恒为空（不是忘写，是没有来源）。
    return order.deliveryStaffPhone || order.deliveryPhone || ''
  },

  onCallPhone() {
    const phone = this.data.callPhone || ''
    if (!phone) {
      // 双保险：按钮已按 wx:if 隐藏，这里再挡一次，避免将来有人把按钮改回常显时又拨错号。
      console.warn('没有可拨打的配送员电话，已跳过拨号')
      return
    }
    wx.makePhoneCall({ phoneNumber: phone })
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
