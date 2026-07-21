const { getQuickOrder } = require('../../api/template')
const { getMyWaterTypes, getWaterTypes } = require('../../api/product')
const { getOrders } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummaryByType } = require('../../api/barrel')
const { storage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    submitting: false,
    isLogin: false,
    // 状态: guest / noAddress / noTemplate / hasTemplate / delivering
    state: 'guest',
    address: null,
    products: [],
    selectedProduct: null,
    quantity: 1,
    note: '',
    barrelByType: [],
    recentOrders: [],
    templateItems: [],
    templateName: '',
    activeOrders: [],
    // 手动选择模式
    showManualPicker: false
  },

  onLoad() {},

  onShow() {
    const app = getApp()
    const isLogin = app.globalData.isLogin
    this.setData({ isLogin })

    if (isLogin) {
      this.loadData()
    } else {
      this.setData({ state: 'guest', loading: false })
    }

    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({ address: selectedAddress })
      storage.remove('selectedAddress')
    }

    // 从商城跳转过来的选中水类型
    const selectedWaterTypeId = storage.get('selectedWaterTypeId')
    if (selectedWaterTypeId) {
      this._pendingWaterTypeId = selectedWaterTypeId
      storage.remove('selectedWaterTypeId')
    }
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const [quickRes, ordersRes, addressRes, barrelRes, productsRes] = await Promise.all([
        getQuickOrder().catch(() => null),
        getOrders({}).catch(() => null),
        getAddresses().catch(() => null),
        getBarrelSummaryByType().catch(() => null),
        getMyWaterTypes().catch(() => null)
      ])

      // 地址
      let address = null
      if (addressRes && addressRes.data && addressRes.data.length > 0) {
        const list = addressRes.data
        address = list.find(a => a.isDefault) || list[0]
      }
      this.setData({ address })

      // 桶明细
      let barrelByType = []
      if (barrelRes && barrelRes.data) {
        barrelByType = barrelRes.data
      }
      this.setData({ barrelByType })

      // 产品列表
      let products = []
      if (productsRes && productsRes.data) {
        products = productsRes.data
      } else {
        const allRes = await getWaterTypes().catch(() => null)
        products = (allRes && allRes.data) ? allRes.data : []
      }
      this.setData({ products })

      // 活跃订单（配送中/待配送）
      let activeOrders = []
      if (ordersRes && ordersRes.data) {
        activeOrders = ordersRes.data.filter(o => o.status === 1 || o.status === 2)
      }
      this.setData({ activeOrders })

      // 最近订单
      let recentOrders = []
      if (ordersRes && ordersRes.data) {
        recentOrders = ordersRes.data.slice(0, 3)
      }
      this.setData({ recentOrders })

      // 判断状态
      if (!address) {
        this.setData({ state: 'noAddress' })
        return
      }

      if (activeOrders.length > 0) {
        this.setData({ state: 'delivering' })
        return
      }

      // 模板 → 预填表单
      const quick = quickRes && quickRes.data
      if (quick && quick.items && quick.items.length > 0) {
        const item = quick.items[0]
        let selected = products.find(p => p.id === item.waterTypeId) || products[0] || null

        // 从商城跳转过来，覆盖模板选择
        if (this._pendingWaterTypeId) {
          const target = products.find(p => p.id === this._pendingWaterTypeId)
          if (target) selected = target
          this._pendingWaterTypeId = null
        }

        this.setData({
          state: 'hasTemplate',
          templateItems: quick.items,
          templateName: quick.name || '常用订单',
          selectedProduct: selected,
          quantity: item.quantity || 1,
          note: item.specialNote || ''
        })
        return
      }

      // 手动选择模式
      let selectedProduct = products[0] || null

      // 从商城跳转过来，自动选中指定水类型
      if (this._pendingWaterTypeId) {
        const target = products.find(p => p.id === this._pendingWaterTypeId)
        if (target) selectedProduct = target
        this._pendingWaterTypeId = null
      }

      this.setData({
        state: 'noTemplate',
        selectedProduct,
        quantity: 1,
        note: ''
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=home' })
  },

  onViewBarrel() {
    wx.navigateTo({ url: '/pages/barrel/index' })
  },

  // 模板下单 - 直接提交
  onTemplateSubmit() {
    const { templateItems, address } = this.data
    if (!templateItems || templateItems.length === 0) return
    this.doSubmit()
  },

  // 手动选择
  onSelectProduct(e) {
    const { id } = e.currentTarget.dataset
    const product = this.data.products.find(p => p.id === id)
    if (product) this.setData({ selectedProduct: product })
  },

  onQuantityChange(e) {
    const { type } = e.currentTarget.dataset
    let { quantity } = this.data
    if (type === 'add') quantity++
    else if (type === 'minus' && quantity > 1) quantity--
    this.setData({ quantity })
  },

  onQuantityInput(e) {
    const quantity = parseInt(e.detail.value) || 1
    this.setData({ quantity: Math.max(1, quantity) })
  },

  onNoteInput(e) {
    this.setData({ note: e.detail.value })
  },

  onEditTemplate() {
    wx.navigateTo({ url: '/pages/template/index' })
  },

  onGoShop() {
    wx.switchTab({ url: '/pages/shop/index' })
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onViewOrder(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  onSubmit() {
    const { selectedProduct, quantity, address, barrelByType } = this.data
    if (!address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }

    if (!selectedProduct) {
      wx.showToast({ title: '请选择水类型', icon: 'none' })
      return
    }

    // 计算持有桶数
    const held = barrelByType.find(b => b.waterTypeId === selectedProduct.id)
    const heldBarrels = held ? held.holdingQty : 0

    wx.navigateTo({
      url: `/pages/payment/index?waterTypeId=${selectedProduct.id}&waterTypeName=${encodeURIComponent(selectedProduct.name || '')}&waterTypeSpec=${encodeURIComponent(selectedProduct.spec || '')}&waterTypePrice=${selectedProduct.price || 0}&quantity=${quantity}&addressId=${address.id}&addressDetail=${encodeURIComponent(address.detail || '')}&specialNote=${encodeURIComponent(this.data.note || '')}&source=3&heldBarrels=${heldBarrels}`
    })
  }
})
