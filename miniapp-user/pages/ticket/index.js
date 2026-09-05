const { getTicketAccounts, getTicketRecords, purchaseTicket } = require('../../api/ticket')
const { getOnSaleProducts, getStationProducts } = require('../../api/product')
const { getPublicStations } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    accounts: [],
    records: [],
    currentTab: 0,
    totalTickets: 0,
    totalValue: 0,
    tabs: [
      { id: 0, name: '水票明细' },
      { id: 1, name: '消费记录' }
    ],
    showPurchase: false,
    buyProducts: [],
    buyForm: {
      productId: null,
      productName: '',
      faceValue: 0,
      quantity: 1,
      totalPrice: 0,
      paymentMethod: 1
    },
    buyMethods: [
      { id: 1, name: '微信支付', desc: '在线支付（待确认）' }
    ],
    submitting: false,
    currentStationId: null,
    currentStation: null,
    showStationPicker: false,
    stationList: []
  },

  onShow() {
    this.loadData()
  },

  async loadData() {
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()
    let station = stationStorage.get()

    this.setData({ currentStationId: stationId, currentStation: station })

    this.setData({ loading: true })
    try {
      let productsRes = null
      if (stationId) {
        productsRes = await getStationProducts(stationId).catch(() => null)
      }
      if (!productsRes || !productsRes.data) {
        productsRes = await getOnSaleProducts().catch(() => null)
      }

      const [accountsRes, recordsRes] = await Promise.all([
        getTicketAccounts(stationId),
        getTicketRecords(stationId)
      ])
      if (accountsRes.data) {
        const accounts = accountsRes.data
        const totalTickets = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0), 0)
        const totalValue = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0) * (a.faceValue || a.price || 0), 0)
        this.setData({ accounts, totalTickets, totalValue })
      }
      if (recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }
      if (productsRes && productsRes.data) {
        this.setData({ buyProducts: productsRes.data, currentStationId: stationId })
      }
    } catch (error) {
      console.error('Load ticket data error:', error)
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

    stationStorage.set(station)
    this.setData({ showStationPicker: false })
    await this.loadData()
  },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
  },

  onShowPurchase() {
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    this.setData({ showPurchase: true })
  },

  onClosePurchase() {
    this.setData({ showPurchase: false, buyForm: { productId: null, productName: '', faceValue: 0, quantity: 1, totalPrice: 0, paymentMethod: 1 } })
  },

  onBuyProductSelect(e) {
    const { id } = e.currentTarget.dataset
    const product = this.data.buyProducts.find(p => p.id === id)
    if (!product) return
    this.setData({
      'buyForm.productId': id,
      'buyForm.productName': product.name,
      'buyForm.faceValue': parseFloat(product.price) || 0,
      'buyForm.totalPrice': (parseFloat(product.price) || 0) * (this.data.buyForm.quantity || 1)
    })
  },

  onBuyQuantityChange(e) {
    const qty = parseInt(e.detail.value) || 1
    this.setData({
      'buyForm.quantity': qty,
      'buyForm.totalPrice': (this.data.buyForm.faceValue || 0) * qty
    })
  },

  onBuyPaymentMethodChange(e) {
    this.setData({ 'buyForm.paymentMethod': parseInt(e.detail.value) })
  },

  async onBuySubmit() {
    const { productId, quantity, faceValue, paymentMethod } = this.data.buyForm
    if (!productId) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!quantity || quantity <= 0) {
      wx.showToast({ title: '请输入正确数量', icon: 'none' })
      return
    }
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await purchaseTicket({
        productId: productId,
        waterTypeId: productId, // 兼容旧字段
        quantity: quantity,
        paymentMethod: paymentMethod,
        stationId: this.data.currentStationId
      })
      wx.showToast({ title: '购买成功', icon: 'success' })
      this.onClosePurchase()
      await this.loadData()
    } catch (error) {
      console.error('Purchase ticket error:', error)
      wx.showToast({ title: error.message || '购买失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})