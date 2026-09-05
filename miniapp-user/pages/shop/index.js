const { getOnSaleProducts, getMyProducts, getStationProducts } = require('../../api/product')
const { getPublicStations } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true, isLogin: false, allProducts: [], myProductIds: [], keywords: '',
    currentStationId: null,
    currentStation: null,
    stationList: [],
    showStationPicker: false,
    cartCount: 0
  },
  onLoad() {},
  onShow() {
    const app = getApp()
    this.setData({ isLogin: app.globalData.isLogin })
    this.updateCartCount()
    this.loadData()
  },
  updateCartCount() {
    const app = getApp()
    const stationId = app.getCurrentStationId()
    const cartCount = stationId ? app.getCartCount(stationId) : 0
    this.setData({ cartCount })
  },
  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },
  async loadData() {
    this.setData({ loading: true })
    try {
      let allProducts = []
      let currentStationId = null
      let currentStation = null

      // 优先读取本地存储的水站
      currentStationId = stationStorage.getId()
      if (currentStationId) {
        const station = stationStorage.get()
        if (station) currentStation = station
      }

      // 如果本地没有，不再调用后端接口，等待用户选择
      if (currentStationId) {
        const productsRes = await getStationProducts(currentStationId).catch(() => null)
        if (productsRes && productsRes.data) {
          allProducts = productsRes.data
        }
      }

      const myRes = await getMyProducts().catch(() => null)
      let myProductIds = []
      if (myRes && myRes.data) { myProductIds = myRes.data.map(item => item.id) }
      this.setData({ allProducts, myProductIds, currentStationId, currentStation })
    } finally {
      this.setData({ loading: false })
    }
  },
  async loadStationList() {
    try {
      const res = await getPublicStations().catch(() => null)
      if (res && res.code === 0 && res.data) {
        const activeStations = res.data.filter(s => s.status === 1)
        this.setData({ stationList: activeStations })
      }
    } catch (e) {
      console.error('加载水站列表失败:', e)
    }
  },
  onOpenStationPicker() {
    this.setData({ showStationPicker: true })
    this.loadStationList()
  },
  onCloseStationPicker() {
    this.setData({ showStationPicker: false })
  },
  async onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    if (id === this.data.currentStationId) {
      this.setData({ showStationPicker: false })
      return
    }
    const station = this.data.stationList.find(s => s.id === id)

    // 本地提示：不同水站资产不互通
    const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
    if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== id) {
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '切换水站提醒',
          content: '不同水站的水票、桶及押金等资产不互通，请确认后再切换。',
          confirmText: '知道了，继续',
          cancelText: '取消',
          showCancel: true,
          success: (r) => resolve(r.confirm)
        })
      })
      if (!confirm) {
        return
      }
      // 记住用户选择
      const dontShow = await new Promise(resolve => {
        wx.showModal({
          title: '提示',
          content: '下次不再提示？',
          confirmText: '不再提示',
          cancelText: '每次都提示',
          success: (r) => resolve(r.confirm)
        })
      })
      if (dontShow) {
        stationStorage.setSwitchNoticeDisabled(true)
      }
    }

    const app = getApp()
    stationStorage.set(station)
    // 不再清空购物车，各站购物车独立保留
    this.setData({
      currentStationId: id,
      currentStation: station,
      showStationPicker: false,
      loading: true,
      allProducts: []
    })
    try {
      const productsRes = await getStationProducts(id).catch(() => null)
      let allProducts = []
      if (productsRes && productsRes.data) {
        allProducts = productsRes.data
      }
      const myRes = await getMyProducts().catch(() => null)
      let myProductIds = []
      if (myRes && myRes.data) { myProductIds = myRes.data.map(item => item.id) }
      this.setData({ allProducts, myProductIds })
    } finally {
      this.setData({ loading: false })
    }
  },
  onSearch(e) { this.setData({ keywords: e.detail.value }) },
  onClearSearch() { this.setData({ keywords: '' }) },
  onAddToCart(e) {
    if (!this.data.isLogin) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const { id } = e.currentTarget.dataset
    const app = getApp()
    const stationId = app.getCurrentStationId()
    if (!stationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    app.addToCart(stationId, id, 1)
    this.updateCartCount()
    wx.showToast({ title: '已加入购物车', icon: 'success' })
  },
  onBuyNow(e) {
    if (!this.data.isLogin) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const { id } = e.currentTarget.dataset
    const stationId = this.data.currentStationId
    if (!stationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/order/create?productId=${id}&stationId=${stationId}` })
  },
  onGoCheckout() {
    const stationId = this.data.currentStationId
    if (!stationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/order/create?source=cart&stationId=' + stationId })
  },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) }
})