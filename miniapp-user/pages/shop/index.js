const { getWaterTypes, getMyWaterTypes } = require('../../api/product')

Page({
  data: {
    loading: true,
    isLogin: false,
    allProducts: [],
    myProductIds: [],
    keywords: ''
  },

  onLoad() {},

  onShow() {
    const app = getApp()
    this.setData({ isLogin: app.globalData.isLogin })
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const [allRes, myRes] = await Promise.all([
        getWaterTypes().catch(() => null),
        getMyWaterTypes().catch(() => null)
      ])

      let allProducts = []
      if (allRes && allRes.data) {
        allProducts = allRes.data
      }

      let myProductIds = []
      if (myRes && myRes.data) {
        myProductIds = myRes.data.map(item => item.id)
      }

      this.setData({ allProducts, myProductIds })
    } finally {
      this.setData({ loading: false })
    }
  },

  onSearch(e) {
    this.setData({ keywords: e.detail.value })
  },

  onClearSearch() {
    this.setData({ keywords: '' })
  },

  onBuyNow(e) {
    if (!this.data.isLogin) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const { id } = e.currentTarget.dataset
    wx.setStorageSync('selectedWaterTypeId', id)
    wx.switchTab({ url: '/pages/home/index' })
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  getFilteredProducts() {
    const { allProducts, keywords } = this.data
    if (!keywords) return allProducts
    return allProducts.filter(p =>
      p.name.includes(keywords) ||
      (p.spec && p.spec.includes(keywords)) ||
      (p.note && p.note.includes(keywords))
    )
  }
})
