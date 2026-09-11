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
    orderPaymentMethod: null
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
          orderPaymentMethod: res.data.paymentMethod
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
