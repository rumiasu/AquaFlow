const { setFromOrder, getQuickOrder } = require('../../api/template')
const { createPayment } = require('../../api/order')
const { getOrderDetail } = require('../../api/order')
const { stationStorage } = require('../../utils/storage')
const { getCustomerId } = require('../../utils/token')
const { notifyPayResult } = require('../../utils/pay')

Page({
  data: {
    orderId: '',
    stationId: null,
    isDefaultSet: false,
    hasTemplate: false,
    paymentStatus: null,
    orderAmount: 0,
    orderPaymentMethod: null,
    // 能否在线支付 / 按钮文案 / 付款说明：**一律用后端下发的**，绝不用 paymentStatus 自己判断。
    // 后端投影见 Orders.getCanRepay() + PaymentService.canSelfPay()：
    // 只有"当前渠道真的能付掉"才为 true（现金单永远 false；微信单取决于模拟渠道是否开启）。
    canRepay: false,
    repayLabel: '去支付',
    payHint: '',
    // ===== 结果区按**服务端事实**渲染（契约 A3）=====
    // loadState: 'loading' | 'ok' | 'failed' —— failed 时**不许**落到"下单成功 / 尽快配送"
    loadState: 'loading',
    loadErrorText: '',
    heroTitle: '订单已提交',
    heroDesc: '',
    heroTone: 'ok',            // 'ok' | 'warn'
    statusText: ''
  },

  onLoad(options) {
    const orderId = options.id || wx.getStorageSync('lastOrderId') || ''
    const app = getApp()
    const stationId = options.stationId || stationStorage.getId() || app.globalData.tempStationId || null
    if (orderId && orderId !== 'mock') {
      this.setData({ orderId: parseInt(orderId) || orderId, stationId })
      wx.setStorageSync('lastOrderId', orderId)
      this.checkTemplateStatus(stationId)
      this.loadOrderStatus(orderId)
    }
    if (stationId) {
      app.clearCart(stationId)
    }
  },

  /** 重试查一次（契约 A3：加载失败要有能做的下一步，而不是显示一个假成功） */
  onReload() {
    if (!this.data.orderId) return
    this.setData({ loadState: 'loading' })
    this.loadOrderStatus(this.data.orderId)
  },

  async loadOrderStatus(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      const order = res && res.data
      // 必须真的拿到订单才算"查到了"：原来 data 为空只 console.warn，
      // 页面继续渲染"我们会尽快为您配送"——而订单可能根本不存在（AGENTS §8.22 的同一类坑）。
      if (!order) {
        this.setData({
          loadState: 'failed',
          loadErrorText: '没有取到这张订单的信息，可能是订单号不对或网络问题。可以先到「我的订单」核对。'
        })
        return
      }
      this.applyOrderFacts(order)
    } catch (e) {
      console.warn('loadOrderStatus error:', e)
      this.setData({
        loadState: 'failed',
        loadErrorText: (e && e.message ? e.message + '。' : '')
          + '没能读到这张订单，请重试；一直读不到就点「查看订单」核对，或联系水站。'
      })
    }
  },

  /**
   * 把订单事实映射成结果页文案（契约 A3 的「响应 → 页面」映射）：
   *   订单是否存在（拿到了 order）/ 支付是否确定 / 下一步动作。
   * 判据只用**服务端下发的字段**（status / paymentStatus / payMethodText / payHint / canRepay），
   * 不在前端另写一套状态含义。
   */
  applyOrderFacts(order) {
    const status = order.status == null ? null : Number(order.status)
    const payStatus = order.paymentStatus == null ? null : Number(order.paymentStatus)
    const payMethod = order.paymentMethod == null ? null : Number(order.paymentMethod)
    const amount = order.totalAmount || order.amount || 0

    let heroTitle = '订单已提交'
    let heroDesc = ''
    let heroTone = 'ok'

    if (status === 5) {
      // 已取消：绝不能显示"尽快配送"
      heroTitle = '订单已取消'
      heroDesc = '这张订单已经取消，不需要付款。'
      heroTone = 'warn'
    } else if (payStatus === 3) {
      heroTitle = '已退款'
      heroDesc = '这张订单的钱已经退回。'
      heroTone = 'warn'
    } else if (payStatus === 2) {
      heroTitle = '下单成功'
      heroDesc = '付款已完成，水站会按顺序安排配送。'
    } else if (status === 3 || status === 4) {
      heroTitle = status === 4 ? '订单已完成' : '订单已送达'
      heroDesc = payStatus === 2 ? '本次订单已完成。' : '货物已送达，款项按订单说明结清。'
    } else if (payMethod === 2 && payStatus === 1) {
      // 现金单：说清"送到再付"，不给在线支付入口
      heroTitle = '下单成功'
      heroDesc = '订单已提交，送到再付 ¥' + amount
    } else if (payStatus === 0 || payStatus === 4) {
      heroTitle = '订单已提交'
      heroDesc = '还没付款' + (amount ? '：应付 ¥' + amount : '')
      heroTone = 'warn'
    } else {
      heroTitle = '订单已提交'
      heroDesc = '正在核实这张订单的付款情况。'
    }

    this.setData({
      loadState: 'ok',
      paymentStatus: payStatus,
      orderAmount: amount,
      orderPaymentMethod: payMethod,
      canRepay: order.canRepay === true,
      repayLabel: order.repayLabel || '去支付',
      payHint: order.payHint || '',
      statusText: order.statusText || '',
      heroTitle,
      heroDesc,
      heroTone
    })
  },

  async checkTemplateStatus(stationId) {
    try {
      const res = await getQuickOrder(stationId)
      if (res.data && res.data.items && res.data.items.length > 0) {
        this.setData({ hasTemplate: true })
      }
    } catch (error) {
      // 无模板，显示保存入口
    }
  },

  async onSaveTemplate() {
    const { orderId, stationId } = this.data
    if (!orderId) return
    try {
      await setFromOrder(orderId, stationId)
      this.setData({ isDefaultSet: true })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (error) {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    }
  },

  async onPayNow() {
    const { orderId, orderAmount, orderPaymentMethod } = this.data
    if (!orderId) return
    try {
      const res = await createPayment({
        orderId,
        customerId: getCustomerId(),
        amount: orderAmount,
        paymentMethod: orderPaymentMethod || 1,
        waterAmount: 0,
        barrelDeposit: 0,
        extraDepositBuckets: 0,
        extraDepositAmount: 0,
        ticketProductId: null,
        ticketQty: null
      })
      // 按真实支付状态提示，不再无条件报"支付成功"
      notifyPayResult(res && res.data)
      this.loadOrderStatus(orderId)
    } catch (e) {
      wx.showToast({ title: e.message || '支付失败', icon: 'none' })
    }
  },

  onBackHome() {
    wx.switchTab({ url: '/pages/home/index' })
  },

  onViewOrders() {
    wx.switchTab({ url: '/pages/order/list' })
  }
})
