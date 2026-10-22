const { getStationProducts } = require('../../api/product')
const { getPublicStations, getStationStatus } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken, captureSession, isCurrentSession } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

Page({
  data: {
    loading: true, isLogin: false, allProducts: [], visibleProducts: [], keywords: '',
    stationListLoading: false,
    stationStatusHint: '',
    currentStationId: null,
    currentStation: null,
    stationList: [],
    showStationPicker: false,
    cartCount: 0,
    // 由首页「搜索 ›」入口带参进入时自动聚焦搜索框（/pages/shop/index?focus=1）
    focusSearch: false,
    // ===== 失败标记（[2026-09-20 真机联调]，空串 = 一切正常）=====
    // 为什么需要：原来商品与水站两个请求都是 `.catch(() => null)`，失败时页面渲染成
    // 「暂无商品」/「暂无可用水站」—— 把"没查到"说成"平台上确实没有"（AGENTS §8.22），
    // 顾客会以为这个站/这个平台没货可买。
    loadError: '',
    stationListError: ''
  },
  onLoad(options) {
    if (options && (options.focus === '1' || options.focus === 'true')) {
      this.setData({ focusSearch: true })
    }
  },
  onShow() {
    this._shopHidden = false
    const app = getApp()
    this.setData({ isLogin: app.globalData.isLogin, showStationPicker: false })
    this.updateCartCount()
    return this.loadData()
  },
  onHide() {
    this._shopHidden = true
    this._shopLoadSeq = (this._shopLoadSeq || 0) + 1
  },
  onUnload() {
    this._shopDestroyed = true
    this._shopLoadSeq = (this._shopLoadSeq || 0) + 1
  },
  /** Match the existing login epoch and authoritative order-station preference, never a stale card. */
  _captureShopContext(stationId) {
    return { session: captureSession(), stationId: String(stationId || ''), sequence: this._shopLoadSeq || 0 }
  },
  _isShopContextCurrent(context) {
    return !!context && !this._shopHidden && !this._shopDestroyed
      && context.sequence === (this._shopLoadSeq || 0) && isCurrentSession(context.session)
      && context.stationId === String(stationStorage.getId() || '')
  },
  /** A cart may still be reviewed after a catalog failure; product taps require a current successful list. */
  _canUseShopContext(needsProducts = true, productId) {
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return false
    }
    const context = needsProducts ? this._shopProductsContext : this._shopViewContext
    if (!this._isShopContextCurrent(context)
        || context.stationId !== String(this.data.currentStationId || '')
        || (needsProducts && (this.data.loading || !this.data.allProducts.some(item => item && String(item.id) === String(productId))))) {
      wx.showToast({ title: '商品信息已变化，请刷新后重试', icon: 'none' })
      return false
    }
    return true
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
    if (this._shopHidden || this._shopDestroyed) return
    const oldContext = this._shopReadScope || this._shopProductsContext
    const keep = oldContext && isCurrentSession(oldContext.session) && oldContext.stationId === String(stationStorage.getId() || '')
    const previousProducts = keep ? this.data.allProducts : []
    this._shopLoadSeq = (this._shopLoadSeq || 0) + 1
    const context = this._captureShopContext(stationStorage.getId())
    this._shopViewContext = context
    this._shopProductsContext = null
    this.setData({ loading: true })
    try {
      let allProducts = previousProducts
      let currentStationId = null
      let currentStation = null

      // 优先读取本地存储的水站
      currentStationId = stationStorage.getId()
      if (currentStationId) {
        const station = stationStorage.get()
        if (station) currentStation = station
      }
      // 2026-10-21: A's late products previously replaced B's view while cart writes used B.
      // Cross-station/session cards are cleared; same-scope refresh failures are labelled as previous reads.
      this.setData({ currentStationId, currentStation, allProducts: previousProducts, loadError: '', stationStatusHint: '' })
      this.updateCartCount()

      // 如果本地没有，不再调用后端接口，等待用户选择
      if (currentStationId) {
        // 2026-09-16：sale-by-station 现在下发的是**本站有效价**（effectivePrice/effectiveDeposit），
        // 即"站级覆盖 → 通用库参考价"，与结算价同口径（原实现只给平台参考价）。
        // [2026-09-20 真机联调] 原来这里是 `.catch(() => null)`：失败时 allProducts 为空数组，
        // 页面渲染成「暂无商品」—— 与"这个水站确实没上架商品"完全无法区分（AGENTS §8.22），
        // 顾客会以为整站没货。商品是商城页的全部内容，失败必须出声。
        try {
          const productsRes = await getStationProducts(currentStationId)
          if (!this._isShopContextCurrent(context)) return
          // 业务失败仍是 HTTP 200，一律判 body.code（AGENTS §8.1）；code!=0 也不能当"没有商品"
          if (productsRes && productsRes.code === 0 && Array.isArray(productsRes.data)) {
            allProducts = productsRes.data
            this._shopProductsContext = context
            this._shopReadScope = context
            this.setData({ loadError: '' })
          } else {
            const msg = viewError(productsRes, '返回异常，请稍后重试')
            console.warn('[Shop] 商品列表返回非成功响应:', msg)
            this.setData({ loadError: '商品没加载出来（' + msg + '），下面可能是空的，请下拉刷新' })
            wx.showToast({ title: '商品没加载出来，请下拉刷新', icon: 'none' })
          }
        } catch (e) {
          if (!this._isShopContextCurrent(context)) return
          console.error('[Shop] 加载商品失败:', e)
          this.setData({ loadError: '商品没加载出来（' + viewError(e, '网络异常') + '），下面可能是空的，请下拉刷新' })
          wx.showToast({ title: '商品没加载出来，请下拉刷新', icon: 'none' })
        }
      }

      // 「已有商品」标签原先靠 GET /api/products/my，而那个接口恒返回空数组（永远点不亮）。
      // 2026-09-16 已按设计删除该端点，标签一并去掉；要恢复得先有一个真实的"客户已购商品"接口。
      if (!this._isShopContextCurrent(context)) return
      this.setData({ allProducts, currentStationId, currentStation })
      if (this.data.loadError && previousProducts.length) this.setData({ loadError: '刷新失败，以下为上次读取的商品与价格，重新加载后才能选购。' })
      this.updateVisibleProducts()
      this.loadStationStatus(currentStationId)
    } finally {
      if (this._isShopContextCurrent(context)) this.setData({ loading: false })
    }
  },
  /**
   * 水站营业状态（软状态）：商城顶部提示一句，**不隐藏商品、不拦截下单** ——
   * 产品口径是"不阻断，只提示"。拿不到就静默（不能因为提示失败让人买不了水）。
   */
  async loadStationStatus(stationId) {
    const context = this._captureShopContext(stationId)
    if (!this._isShopContextCurrent(context)) return
    const sequence = this._shopStatusSeq = (this._shopStatusSeq || 0) + 1
    const current = () => sequence === this._shopStatusSeq && this._isShopContextCurrent(context)
    if (!stationId) {
      this.setData({ stationStatusHint: '' })
      return
    }
    try {
      const res = await getStationStatus(stationId)
      if (!current()) return
      const hint = res && res.code === 0 && res.data ? res.data.customerHint : ''
      this.setData({ stationStatusHint: hint || '' })
    } catch (e) {
      if (current()) this.setData({ stationStatusHint: '' })
    }
  },

  async loadStationList() {
    const context = this._captureShopContext(stationStorage.getId())
    if (!this._isShopContextCurrent(context)) return
    const sequence = this._shopListSeq = (this._shopListSeq || 0) + 1
    const current = () => sequence === this._shopListSeq && this._isShopContextCurrent(context)
    this.setData({ stationListLoading: true, stationListError: '' })
    try {
      // [2026-09-20 真机联调] 原来是 `.catch(() => null)` + 外层只 console.error：失败时列表为空，
      // 弹窗渲染成「暂无可用水站」—— 把"没查到"说成"平台真的没有水站"。
      const res = await getPublicStations()
      if (!current()) return
      if (res && res.code === 0 && Array.isArray(res.data)) {
        const activeStations = res.data.filter(s => s.status === 1)
        this.setData({ stationList: activeStations, stationListError: '' })
      } else {
        const msg = viewError(res, '返回异常，请稍后重试')
        console.warn('[Shop] 水站列表返回非成功响应:', msg)
        this.setData({ stationList: [], stationListError: msg })
      }
    } catch (e) {
      if (!current()) return
      console.error('[Shop] 加载水站列表失败:', e)
      this.setData({ stationList: [], stationListError: viewError(e, '网络异常') })
      wx.showToast({ title: '水站列表没加载出来，请重试', icon: 'none' })
    } finally { if (current()) this.setData({ stationListLoading: false }) }
  },
  onOpenStationPicker() {
    this.setData({ showStationPicker: true })
    this.loadStationList()
  },
  onCloseStationPicker() {
    this._shopSelectSeq = (this._shopSelectSeq || 0) + 1
    this._shopListSeq = (this._shopListSeq || 0) + 1
    this.setData({ showStationPicker: false, stationListLoading: false })
  },
  async onSelectStation(e) {
    const context = this._captureShopContext(stationStorage.getId())
    const sequence = this._shopSelectSeq = (this._shopSelectSeq || 0) + 1
    if (!this._isShopContextCurrent(context)) return
    const station = this.data.stationList.find(s => String(s.id) === String(e.currentTarget.dataset.id))
    if (!station) { wx.showToast({ title: '水站不存在，请刷新后重试', icon: 'none' }); return }
    const id = station.id
    const listSequence = this._shopListSeq || 0
    const current = () => sequence === this._shopSelectSeq && this._isShopContextCurrent(context)
      && listSequence === (this._shopListSeq || 0)
      && this.data.stationList.some(item => String(item.id) === String(id))
    if (id === this.data.currentStationId) {
      this.setData({ showStationPicker: false })
      return
    }

    // 本地提示：不同水站资产不互通
    const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
    if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== id) {
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '切换水站提醒',
          content: '不同水站的水票、桶及押金等资产不互通，请确认后再切换。',
          confirmText: '继续',
          cancelText: '取消',
          showCancel: true,
          success: (r) => resolve(r.confirm)
        })
      })
      if (!current() || !confirm) {
        return
      }
      // 记住用户选择
      const dontShow = await new Promise(resolve => {
        wx.showModal({
          title: '提示',
          content: '下次不再提示？',
          confirmText: '不再提示',
          cancelText: '继续提示',
          success: (r) => resolve(r.confirm)
        })
      })
      if (!current()) return
      if (dontShow) {
        stationStorage.setSwitchNoticeDisabled(true)
      }
    }

    const app = getApp()
    if (!current()) return
    stationStorage.set(station)
    this._shopLoadSeq = (this._shopLoadSeq || 0) + 1
    const productContext = this._captureShopContext(id)
    this._shopViewContext = productContext
    this._shopProductsContext = null
    // 不再清空购物车，各站购物车独立保留
    this.setData({
      currentStationId: id,
      currentStation: station,
      showStationPicker: false,
      loading: true,
      // 2026-10-22：切站必须清实际渲染列表，不能在新站名下保留旧站价格。
      allProducts: [], visibleProducts: [], loadError: '', stationStatusHint: ''
    })
    this.updateCartCount()
    try {
      // [2026-09-20 真机联调] 原来是 `.catch(() => null)`：切站后商品拉不到时 allProducts 为空，
      // 页面渲染成「暂无商品」—— 顾客会以为**刚切的这个站**没货（与 loadData 里同一处坑）。
      const productsRes = await getStationProducts(id)
      if (!this._isShopContextCurrent(productContext)) return
      let allProducts = []
      if (productsRes && productsRes.code === 0 && Array.isArray(productsRes.data)) {
        allProducts = productsRes.data
        this._shopProductsContext = productContext
        this._shopReadScope = productContext
        this.setData({ loadError: '' })
      } else {
        const msg = viewError(productsRes, '返回异常，请稍后重试')
        console.warn('[Shop] 切站后商品返回非成功响应:', msg)
        this.setData({ loadError: '商品没加载出来（' + msg + '），下面可能是空的，请下拉刷新' })
        wx.showToast({ title: '商品没加载出来，请下拉刷新', icon: 'none' })
      }
      this.setData({ allProducts })
      this.updateVisibleProducts()
      this.loadStationStatus(id)
    } catch (e) {
      if (!this._isShopContextCurrent(productContext)) return
      console.error('[Shop] 切站后加载商品失败:', e)
      this.setData({ loadError: '商品没加载出来（' + viewError(e, '网络异常') + '），下面可能是空的，请下拉刷新' })
      wx.showToast({ title: '商品没加载出来，请下拉刷新', icon: 'none' })
    } finally {
      if (this._isShopContextCurrent(productContext)) this.setData({ loading: false })
    }
  },
  updateVisibleProducts() {
    const term = this.data.keywords.trim()
    this.setData({ visibleProducts: this.data.allProducts.filter(p => p && (!term || [p.name, p.brand, p.spec].some(v => typeof v === 'string' && v.includes(term)))) })
  },
  onRetryProducts() { return this.loadData() },
  onSearch(e) { this.setData({ keywords: e.detail.value }); this.updateVisibleProducts() },
  onClearSearch() { this.setData({ keywords: '' }); this.updateVisibleProducts() },
  onProductImageError(e) {
    const { id, url } = e.currentTarget.dataset
    this.setData({ allProducts: this.data.allProducts.map(p => String(p.id) === String(id) && p.imageUrl === url ? { ...p, imageUrl: '' } : p) })
    this.updateVisibleProducts()
  },
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
    if (!this._canUseShopContext(true, id)) return
    app.addToCart(stationId, id, 1)
    this.updateCartCount()
    wx.showToast({ title: '已加入订水清单', icon: 'success' })
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
    if (!this._canUseShopContext(true, id)) return
    wx.navigateTo({ url: `/pages/order/create?productId=${id}&stationId=${stationId}` })
  },
  onGoCheckout() {
    const stationId = this.data.currentStationId
    if (!stationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    if (!this._canUseShopContext(false)) return
    wx.navigateTo({ url: '/pages/order/create?source=cart&stationId=' + stationId })
  },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) }
})
