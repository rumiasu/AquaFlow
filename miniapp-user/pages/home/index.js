const { getQuickOrder } = require('../../api/template')
const { getMyProducts, getStationProducts, getProducts } = require('../../api/product')
const { getOrders, getOrderDetail, getMyLatestStation } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getUnreadNotifications, markAllRead } = require('../../api/notification')
const { getPublicStations } = require('../../api/station')
const { storage, stationStorage } = require('../../utils/storage')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { formatAddress } = require('../../utils/address')

Page({
  data: {
    loading: true,
    submitting: false,
    isLogin: false,
    state: 'guest',
    address: null,
    addressText: '',
    products: [],
    barrelProducts: [],
    cart: {},
    cartCount: 0,
    note: '',
    barrelSummary: { heldBuckets: 0, owedBuckets: 0, deliveryBuckets: 0, depositBalance: 0, depositTotal: 0 },
    hasBarrelModule: false,
    recentOrders: [],
    templateItems: [],
    templateName: '',
    activeOrders: [],
    currentStation: null,
    currentStationId: null,
    showStationList: false,
    stationList: [],
    claimPending: false
  },

  onLoad() {},

  onShow() {
    const app = getApp()
    const isLogin = app.globalData.isLogin
    this.setData({ isLogin })

    if (!isLogin) {
      this.setData({ state: 'guest', products: [], loading: false })
      return
    }

    this.checkStation()

    // 检查是否有未读通知（拒单/临时外派提醒）
    this.checkNotifications()

    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({
        address: selectedAddress,
        addressText: formatAddress(selectedAddress)
      })
      storage.remove('selectedAddress')
    }

    const selectedProductId = storage.get('selectedProductId')
    if (selectedProductId) {
      this._pendingProductId = selectedProductId
      storage.remove('selectedProductId')
    }
  },

  onPullDownRefresh() {
    this.checkStation().then(() => wx.stopPullDownRefresh())
  },

  async checkStation() {
    this.setData({ loading: true })
    let completed = false
    try {
      const app = getApp()

      // 1. 优先使用本地存储的水站
      if (stationStorage.getId()) {
        this.setData({
          currentStationId: stationStorage.getId(),
          currentStation: stationStorage.get()
        })
        await this.loadData()
        completed = true
        return
      }

      // 2. 本地没有水站，尝试从后端获取最近下单的水站（自动恢复）
      try {
        const stationRes = await getMyLatestStation()
        if (stationRes && stationRes.data && stationRes.data.stationId) {
          const s = stationRes.data
          const station = { id: s.stationId, name: s.stationName || '水站' }
          stationStorage.set(station)
          this.setData({
            currentStationId: station.id,
            currentStation: station
          })
          await this.loadData()
          completed = true
          return
        }
      } catch (e) {
        // 无历史订单，继续走选择流程
      }

      // 3. 无历史订单，显示选择列表
      this.setData({ state: 'noStation' })
      await this.loadStationList()
    } catch (e) {
      console.error('检查水站失败:', e)
      this.setData({ state: 'noStation' })
      await this.loadStationList()
    } finally {
      if (!completed) this.setData({ loading: false })
    }
  },

  /** 检查未读通知，逐条弹窗提醒（拒单/临时外派） */
  async checkNotifications() {
    try {
      const res = await getUnreadNotifications().catch(() => null)
      if (res && res.code === 0 && res.data && res.data.length > 0) {
        const notifications = res.data
        // 逐条弹窗提醒
        for (let i = 0; i < notifications.length; i++) {
          const n = notifications[i]
          await new Promise((resolve) => {
            wx.showModal({
              title: n.title || '消息提醒',
              content: n.content || '',
              showCancel: false,
              confirmText: '我知道了',
              success: () => resolve()
            })
          })
        }
        // 全部标记已读
        await markAllRead().catch(() => {})
      }
    } catch (e) {
      console.error('检查通知失败:', e)
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

  onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    this.confirmStation(id)
  },

  async confirmStation(stationId) {
    try {
      const station = this.data.stationList.find(s => s.id === stationId)
      if (!station) {
        wx.showToast({ title: '水站不存在', icon: 'none' })
        return
      }

      // 本地提示：不同水站资产不互通
      const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
      if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== stationId) {
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

      wx.showToast({ title: '已选择水站', icon: 'success' })
      this.setData({ showStationList: false })
      const app = getApp()
      stationStorage.set(station)
      // 不再清空购物车，各站购物车独立保留
      await this.checkStation()
    } catch (e) {
      console.error('选择水站失败:', e)
      wx.showToast({ title: '选择失败', icon: 'none' })
    }
  },

  onOpenStationList() {
    this.setData({ showStationList: true })
    this.loadStationList()
  },

  onCloseStationList() {
    this.setData({ showStationList: false })
  },

async loadData() {
    this.setData({ loading: true })
    try {
      const app = getApp()
      const stationId = this.data.currentStationId

      const [quickRes, ordersRes, addressRes, barrelRes, summaryRes, productsRes] = await Promise.all([
        getQuickOrder(stationId).catch(() => null),
        getOrders({}).catch(() => null),
        getAddresses().catch(() => null),
        getBarrelSummaryByType(stationId).catch(() => null),
        getBarrelSummary(stationId).catch(() => null),
        stationId ? getStationProducts(stationId).catch(() => null) : Promise.resolve(null)
      ])

      let address = null
      if (addressRes && addressRes.data && addressRes.data.length > 0) {
        const list = addressRes.data
        address = list.find(a => a.isDefault) || list[0]
      }
      // 展示串：「区 + 街道门牌」，去掉冗长的省市区前缀（未清洗数据也会兜底拆分）
      const addressText = address ? formatAddress(address) : ''
      this.setData({ address, addressText })

      let barrelByType = []
      if (barrelRes && barrelRes.data) {
        barrelByType = barrelRes.data
      }

      let barrelSummary = { heldBuckets: 0, owedBuckets: 0, deliveryBuckets: 0, depositBalance: 0, depositTotal: 0 }
      if (summaryRes && summaryRes.data) {
        barrelSummary = summaryRes.data
      }

      let products = []
      if (stationId && productsRes && productsRes.data) {
        products = productsRes.data
      }

      const cart = app.getCart(stationId)
      products.forEach(p => {
        if (cart[p.id] === undefined) cart[p.id] = 0
      })

      // 桶资产明细 merge 进商品卡：本站有售可加购，下架/非本站商品仅展示明细
      const formatMoney = (n) => {
        const v = Number(n) || 0
        return (Math.round(v * 100) / 100).toFixed(v % 1 === 0 ? 0 : 2)
      }
      const barrelProducts = []
      ;(barrelByType || []).forEach(b => {
        const pid = b.productId != null ? b.productId : b.waterTypeId
        if (pid == null) return
        const assetQty = b.assetQty != null
          ? (b.assetQty || 0)
          : Math.max(0, (b.holdingQty || 0) - (b.confirmedQty || 0))
        const inTransitQty = b.inTransitQty || 0
        const depositTotal = b.depositTotal != null
          ? (b.depositTotal || 0)
          : (Number(b.deposit) || 0) * assetQty
        if (assetQty <= 0 && inTransitQty <= 0 && depositTotal <= 0) return

        const p = products.find(x => String(x.id) === String(pid))
        if (p) {
          barrelProducts.push({
            ...p,
            barrelQty: assetQty,
            inTransitQty,
            depositTotal,
            depositTotalText: formatMoney(depositTotal),
            hasProduct: true
          })
        } else {
          barrelProducts.push({
            id: pid,
            name: b.productName || '未知商品',
            spec: b.productSpec || '',
            brand: '',
            imageUrl: '',
            price: null,
            deposit: null,
            barrelQty: assetQty,
            inTransitQty,
            depositTotal,
            depositTotalText: formatMoney(depositTotal),
            hasProduct: false
          })
        }
      })

      const hasBarrelModule = barrelProducts.length > 0
        || (barrelSummary.heldBuckets || 0) > 0
        || (barrelSummary.deliveryBuckets || 0) > 0
        || (barrelSummary.owedBuckets || 0) > 0
        || (barrelSummary.depositBalance || 0) > 0

      const cartCount = app.getCartCount(stationId)

      // products 必须 always setData，即使后面因无地址返回 early
      this.setData({ products, barrelProducts, cart, cartCount, barrelSummary, hasBarrelModule })

      if (!address) {
        this.setData({ state: 'noAddress' })
        return
      }

      let activeOrders = []
      if (ordersRes && ordersRes.data) {
        activeOrders = ordersRes.data.filter(o => o.status === 1 || o.status === 2)
      }
      this.setData({ activeOrders })

      let recentOrders = []
      if (ordersRes && ordersRes.data) {
        recentOrders = ordersRes.data.slice(0, 3).map(o => {
          // 水费金额（不含押金）由后端统一计算/兜底下发，前端不再自行做减法
          const nextAmount = o.waterAmount || 0
          let displayDate = o.createTime || ''
          if (displayDate.length >= 10) {
            const parts = displayDate.substring(0, 10).split('-')
            if (parts.length >= 3) displayDate = parseInt(parts[1]) + '/' + parseInt(parts[2])
          }
          return {
            ...o,
            nextAmount: parseFloat(nextAmount) || 0,
            nextAmountText: (parseFloat(nextAmount) || 0).toFixed(2),
            displayDate,
            displayItems: []
          }
        })
        const detailResults = await Promise.all(
          recentOrders.map(o => getOrderDetail(o.id).catch(() => null))
        )
        detailResults.forEach((detail, i) => {
          if (detail && detail.data && detail.data.items && detail.data.items.length > 0) {
            recentOrders[i].displayItems = detail.data.items.map(it => ({
              name: it.productNameSnapshot || '',
              spec: it.specSnapshot || '',
              qty: it.quantity || 0,
              price: it.price || 0
            }))
          } else if (detail && detail.data) {
            const o = detail.data
            recentOrders[i].displayItems = [{
              name: o.productNameSnapshot || o.productName || '桶装水',
              spec: o.specSnapshot || o.productSpec || '',
              qty: o.quantity || 1,
              price: o.price || 0
            }]
          }
        })
      }
      this.setData({ recentOrders })

      if (activeOrders.length > 0) {
        this.setData({ state: 'delivering' })
        return
      }

      const quick = quickRes && quickRes.data
      if (quick && quick.items && quick.items.length > 0) {
        const items = quick.items
        const hasCartData = items.some(i => {
          const pid = i.productId || i.waterTypeId
          return pid && (cart[pid] || 0) > 0
        })
        if (!hasCartData) {
          items.forEach(i => {
            const pid = i.productId || i.waterTypeId
            if (pid && cart[pid] !== undefined) {
              cart[pid] = (cart[pid] || 0) + (i.quantity || 0)
            }
          })
        }
        if (this._pendingProductId) {
          const target = products.find(p => p.id === this._pendingProductId)
          if (target) cart[this._pendingProductId] = (cart[this._pendingProductId] || 0) + 1
          this._pendingProductId = null
        }
        const newCartCount = app.getCartCount(stationId)
        this.setData({
          state: 'hasTemplate',
          templateItems: items,
          templateName: quick.name || '常用订单',
          cart,
          cartCount: newCartCount,
          note: quick.specialNote || ''
        })
        return
      }

      let cartNew = { ...cart }
      if (this._pendingProductId) {
        const target = products.find(p => p.id === this._pendingProductId)
        if (target) cartNew[this._pendingProductId] = 1
        this._pendingProductId = null
      }

      this.setData({
        state: 'noTemplate',
        cart: cartNew,
        cartCount: app.getCartCount(stationId),
        note: ''
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=home' })
  },

  onBannerTap() {
    const { products, cart, address, currentStationId } = this.data
    const items = this.collectCartItems()
    if (!address) {
      wx.showToast({ title: '请先选择配送地址', icon: 'none' })
      return
    }
    if (items.length === 0) {
      if (products && products.length > 0) {
        const p = products[0]
        wx.navigateTo({
          url: `/pages/order/create?productId=${p.id}&stationId=${currentStationId || ''}`
        })
      }
      return
    }
    this.onSubmit()
  },

  onGoOrder() {
    const { products, currentStationId } = this.data
    if (!this.data.address) {
      wx.showToast({ title: '请先选择配送地址', icon: 'none' })
      return
    }
    const items = this.collectCartItems()
    if (items.length === 0) {
      if (products && products.length > 0) {
        const p = products[0]
        wx.navigateTo({
          url: `/pages/order/create?productId=${p.id}&stationId=${currentStationId || ''}`
        })
      } else {
        wx.showToast({ title: '暂无商品可选', icon: 'none' })
      }
      return
    }
    this.onSubmit()
  },

  onGoOrderList() {
    wx.switchTab({ url: '/pages/order/list' })
  },

  // 搜索页此前已实现但没有任何入口，用户根本进不去 —— 这里补上首页搜索框
  onSearchTap() {
    wx.navigateTo({ url: '/pages/home/search' })
  },

  onGoTicket() {
    wx.navigateTo({ url: '/pages/ticket/index' })
  },

  onGoBarrel() {
    wx.navigateTo({ url: '/pages/barrel/index' })
  },

  onTemplateSubmit() {
    if (!this.data.address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }
    this.onSubmit()
  },

  collectCartItems() {
    const app = getApp()
    const stationId = this.data.currentStationId
    const cart = app.getCart(stationId)
    const { products } = this.data
    const items = []
    products.forEach(p => {
      const qty = parseInt(cart[p.id]) || 0
      if (qty > 0) items.push({ productId: p.id, quantity: qty })
    })
    return items
  },

  onCartQtyChange(e) {
    const { id, type } = e.currentTarget.dataset
    const app = getApp()
    const stationId = this.data.currentStationId
    const cart = app.getCart(stationId)
    let qty = parseInt(cart[id]) || 0
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 0) qty--
    cart[id] = qty
    const cartCount = app.getCartCount(stationId)
    this.setData({ cart, cartCount })
  },

  onCartQtyInput(e) {
    const { id } = e.currentTarget.dataset
    const app = getApp()
    const stationId = this.data.currentStationId
    const qty = Math.max(0, parseInt(e.detail.value) || 0)
    const cart = app.getCart(stationId)
    cart[id] = qty
    const cartCount = app.getCartCount(stationId)
    this.setData({ cart, cartCount })
  },

  onNoteInput(e) {
    this.setData({ note: e.detail.value })
  },

  onEditTemplate() {
    wx.navigateTo({ url: '/pages/template/index' })
  },

  onGoShop() {
    wx.navigateTo({ url: '/pages/shop/index' })
  },

  onGoProduct(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/create?productId=${id}&stationId=${this.data.currentStationId || ''}` })
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onViewOrder(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  onReorder(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/create?reorderId=${id}` })
  },

  onGoTemplates() {
    wx.navigateTo({ url: '/pages/template/index' })
  },

  onSubmit() {
    const { address } = this.data
    const items = this.collectCartItems()

    if (!address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }
    if (items.length === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }

    const itemsParam = items.map(it => ({
      productId: it.productId,
      quantity: it.quantity
    }))

    wx.navigateTo({
      url: `/pages/order/create?items=${encodeURIComponent(JSON.stringify(itemsParam))}&addressId=${address.id}&addressDetail=${encodeURIComponent(address.detail || '')}&specialNote=${encodeURIComponent(this.data.note || '')}&source=3&stationId=${this.data.currentStationId || ''}`
    })
  }
})