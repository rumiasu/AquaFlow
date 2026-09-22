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
    // 后端口径见 Orders.getCanRepay()/getRepayLabel()/getPayHint()：水票(下单即付)、
    // 现金(货到付款)、微信(渠道未接入) 三种当前 `canRepay` 都为 false —— 即"当前没有
    // 客户自助在线支付入口"。前端自造条件会造出一个点了没用的按钮（2026-09-20 实测）。
    canRepay: false,
    repayLabel: '去支付',
    payHint: ''
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

  async loadOrderStatus(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      if (res.data) {
        this.setData({
          paymentStatus: res.data.paymentStatus,
          orderAmount: res.data.totalAmount || res.data.amount || 0,
          orderPaymentMethod: res.data.paymentMethod,
          canRepay: res.data.canRepay === true,
          repayLabel: res.data.repayLabel || '去支付',
          payHint: res.data.payHint || ''
        })
      }
    } catch (e) {
      console.warn('loadOrderStatus error:', e)
    }
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
