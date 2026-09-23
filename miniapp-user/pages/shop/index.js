const { getStationProducts } = require('../../api/product')
const { getPublicStations, getStationStatus } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true, isLogin: false, allProducts: [], keywords: '',
    stationStatusHint: '',
    currentStationId: null,
    currentStation: null,
    stationList: [],
    showStationPicker: false,
    cartCount: 0,
    // 由首页「搜索 ›」入口带参进入时自动聚焦搜索框（/pages/shop/index?focus=1）
    focusSearch: false
  },
  onLoad(options) {
    if (options && (options.focus === '1' || options.focus === 'true')) {
      this.setData({ focusSearch: true })
    }
  },
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
        // 2026-09-16：sale-by-station 现在下发的是**本站有效价**（effectivePrice/effectiveDeposit），
        // 即"站级覆盖 → 通用库参考价"，与结算价同口径（原实现只给平台参考价）。
        const productsRes = await getStationProducts(currentStationId).catch(() => null)
        if (productsRes && productsRes.data) {
          allProducts = productsRes.data
        }
      }

      // 「已有商品」标签原先靠 GET /api/products/my，而那个接口恒返回空数组（永远点不亮）。
      // 2026-09-16 已按设计删除该端点，标签一并去掉；要恢复得先有一个真实的"客户已购商品"接口。
      this.setData({ allProducts, currentStationId, currentStation })
      this.loadStationStatus(currentStationId)
    } finally {
      this.setData({ loading: false })
    }
  },
  /**
   * 水站营业状态（软状态）：商城顶部提示一句，**不隐藏商品、不拦截下单** ——
   * 产品口径是"不阻断，只提示"。拿不到就静默（不能因为提示失败让人买不了水）。
   */
  async loadStationStatus(stationId) {
    if (!stationId) {
      this.setData({ stationStatusHint: '' })
      return
    }
    try {
      const res = await getStationStatus(stationId)
      const hint = res && res.data ? res.data.customerHint : ''
      this.setData({ stationStatusHint: hint || '' })
    } catch (e) {
      this.setData({ stationStatusHint: '' })
    }
  },

  async loadStationList() {    try {
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
      this.setData({ allProducts })
      this.loadStationStatus(id)
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