const { setFromOrder, getQuickOrder } = require('../../api/template')

Page({
  data: {
    orderId: '',
    isDefaultSet: false,
    hasTemplate: false
  },

  onLoad(options) {
    const orderId = options.id || wx.getStorageSync('lastOrderId') || ''
    if (orderId && orderId !== 'mock') {
      this.setData({ orderId: parseInt(orderId) || orderId })
      wx.setStorageSync('lastOrderId', orderId)
      this.checkTemplateStatus()
    }
  },

  async checkTemplateStatus() {
    try {
      const res = await getQuickOrder()
      if (res.data && res.data.items && res.data.items.length > 0) {
        this.setData({ hasTemplate: true })
      }
    } catch (error) {
      // 无模板，显示保存入口
    }
  },

  async onSaveTemplate() {
    const { orderId } = this.data
    if (!orderId) return
    try {
      await setFromOrder(orderId)
      this.setData({ isDefaultSet: true })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (error) {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    }
  },

  onBackHome() {
    wx.switchTab({ url: '/pages/home/index' })
  },

  onViewOrders() {
    wx.switchTab({ url: '/pages/order/list' })
  }
})
