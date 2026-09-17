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
    // 先落地 stationId 再拉商品：详情接口要用它判定"这个自定义商品是不是你的站的"
    if (options.stationId) {
      this.setData({ stationId: parseInt(options.stationId) })
    }
    if (options.id) {
      this.loadProduct(options.id)
    }
  },

  async loadProduct(id) {
    this.setData({ loading: true })
    try {
      // 带上 stationId：本站自定义商品只有该站能读（后端按 owner_station_id 过滤）；
      // 同时用它拿到**本站有效价**（站级覆盖 → 通用库参考价），与列表/结算同口径。
      const res = await getProductDetail(id, this.data.stationId)
      if (res.data) {
        const d = res.data
        this.setData({
          product: {
            ...d,
            price: d.effectivePrice != null ? d.effectivePrice : d.price,
            deposit: d.effectiveDeposit != null ? d.effectiveDeposit : d.deposit
          }
        })
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
