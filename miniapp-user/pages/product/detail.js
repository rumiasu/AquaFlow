const { getProductDetail } = require('../../api/product')
const { getBaseUrl, API } = require('../../config/api')

Page({
  data: {
    loading: true,
    product: {},
    quantity: 1,
    stationId: null
  },

  onLoad(options) {
    if (options.id) {
      this.loadProduct(options.id)
    }
    if (options.stationId) {
      this.setData({ stationId: parseInt(options.stationId) })
    }
  },

  async loadProduct(id) {
    this.setData({ loading: true })
    try {
      const res = await getProductDetail(id)
      if (res.data) {
        this.setData({ product: res.data })
      }
    } catch (error) {
      console.error('Load product error:', error)
      wx.showToast({ title: '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onQuantityChange(e) {
    const { type } = e.currentTarget.dataset
    let { quantity } = this.data
    if (type === 'add') {
      quantity++
    } else if (type === 'minus' && quantity > 1) {
      quantity--
    }
    this.setData({ quantity })
  },

  onQuantityInput(e) {
    const quantity = parseInt(e.detail.value) || 1
    this.setData({ quantity: Math.max(1, quantity) })
  },

  onBuyNow() {
    const app = getApp()
    if (!app.globalData.isLogin) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const { product, quantity, stationId } = this.data
    wx.navigateTo({
      url: `/pages/order/create?productId=${product.id}&quantity=${quantity}&stationId=${stationId || ''}`
    })
  }
})
