const { createOrder } = require('../../api/order')
const { getTicketAccounts } = require('../../api/ticket')
const { createPayment, getQuote } = require('../../api/payment')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')

Page({
  data: {
    loading: false,
    submitting: false,
    items: [],
    addressId: null,
    addressDetail: '',
    specialNote: '',
    source: 3,
    stationId: null,
    selectedMethod: 2,
    ticketAccounts: [],
    filteredTickets: [],
    ticketBalanceByProduct: {},
    waterCost: 0,
    barrelDeposit: 0,
    totalAsset: 0,
    totalNeeded: 0,
    extraDepositBuckets: 0,
    extraDepositAmount: 0,
    extraDepositConfirmed: false,
    totalAmount: 0,
    quoteData: null,
    showOfflineConfirm: false,
    methods: [
      { id: 2, name: '货到付款', desc: '配送员送达后现金/扫码支付', icon: 'wallet' },
      { id: 3, name: '水票支付', desc: '使用账户水票抵扣', icon: 'ticket' },
      { id: 1, name: '微信支付', desc: '在线支付', icon: 'bill' }
    ]
  },

  onLoad(options) {
    let items = []
    if (options.items) {
      try { items = JSON.parse(decodeURIComponent(options.items)) || [] } catch (e) { items = [] }
    }

    if (!items || items.length === 0) {
      if (options.waterTypeId || options.productId) {
        const pid = options.productId || options.waterTypeId
        items = [{
          productId: parseInt(pid),
          productName: decodeURIComponent(options.waterTypeName || options.productName || ''),
          productSpec: decodeURIComponent(options.waterTypeSpec || options.productSpec || ''),
          productPrice: parseFloat(options.waterTypePrice || options.productPrice || 0),
          productDeposit: parseFloat(options.waterTypeDeposit || options.productDeposit || 0),
          quantity: parseInt(options.quantity) || 1
        }]
      }
    }

    this.setData({
      items,
      addressId: options.addressId ? parseInt(options.addressId) : null,
      addressDetail: decodeURIComponent(options.addressDetail || ''),
      specialNote: decodeURIComponent(options.specialNote || ''),
      source: parseInt(options.source) || 3,
      stationId: options.stationId ? parseInt(options.stationId) : null
    })

    this.loadTicketBalance()
    this.refreshQuote()
  },

  async refreshQuote() {
    try {
      const { items, selectedMethod, stationId } = this.data
      const quoteItems = items.map(it => ({
        productId: it.productId,
        quantity: it.quantity
      }))
      const res = await getQuote({
        items: quoteItems,
        paymentMethod: selectedMethod,
        stationId: stationId
      })
      if (res.data) {
        const d = res.data
        this.setData({
          quoteData: d,
          totalAsset: d.totalAsset != null ? d.totalAsset : (d.usableHeld || 0),
          totalNeeded: d.totalNeeded != null ? d.totalNeeded : items.reduce((s, i) => s + (i.quantity || 0), 0),
          extraDepositBuckets: d.extraDepositBuckets || 0,
          extraDepositAmount: d.extraDeposit || 0,
          waterCost: d.waterAmount != null ? d.waterAmount : 0,
          barrelDeposit: d.barrelDeposit != null ? d.barrelDeposit : 0,
          totalAmount: d.totalAmount || 0
        })
      }
    } catch (e) {
      console.warn('[Payment] refreshQuote error:', e.message)
    }
  },

  async loadTicketBalance() {
    try {
      const { items, stationId } = this.data
      const productIds = items.map(i => i.productId).filter(Boolean)

      const res = await getTicketAccounts(stationId).catch(() => null)
      let accounts = []
      if (res && res.data) accounts = res.data

      const filtered = []
      const balanceMap = {}
      accounts.forEach(a => {
        const pid = a.productId || a.waterTypeId
        const stId = a.stationId
        const matchStation = !stationId || !stId || String(stId) === String(stationId)
        const matchProduct = !productIds.length || productIds.indexOf(pid) >= 0
        if (matchStation && matchProduct) {
          filtered.push(a)
          balanceMap[pid] = (balanceMap[pid] || 0) + (a.remainQuantity || 0)
        }
      })

      this.setData({
        ticketAccounts: accounts,
        filteredTickets: filtered,
        ticketBalanceByProduct: balanceMap
      })
    } catch (e) {
      console.warn('[Payment] loadTicketBalance error:', e.message)
    }
  },

  onSelectMethod(e) {
    const { id } = e.currentTarget.dataset
    if (id === 2) {
      this.setData({ showOfflineConfirm: true, _pendingMethod: id })
      return
    }
    this.setData({ selectedMethod: id })
    this.refreshQuote()
  },

  onOfflineConfirmOk() {
    this.setData({
      showOfflineConfirm: false,
      selectedMethod: this.data._pendingMethod || 2
    })
    this.refreshQuote()
  },

  onOfflineConfirmCancel() {
    this.setData({ showOfflineConfirm: false, _pendingMethod: null })
  },

  onToggleExtraDepositConfirm(e) {
    this.setData({ extraDepositConfirmed: !!e.detail.value })
  },

  async onSubmit() {
    const { items, selectedMethod, totalAmount, extraDepositAmount, extraDepositBuckets, extraDepositConfirmed } = this.data

    if (!items || items.length === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }

    if (extraDepositBuckets > 0 && extraDepositAmount > 0 && !extraDepositConfirmed) {
      wx.showToast({ title: '请确认额外桶押金', icon: 'none' })
      return
    }

    if (selectedMethod === 3) {
      const { ticketBalanceByProduct } = this.data
      for (const it of items) {
        const bal = ticketBalanceByProduct[it.productId] || 0
        if (bal < (it.quantity || 0)) {
          wx.showModal({
            title: '水票不足',
            content: `${it.productName || '商品'} 水票余额 ${bal} 张，还差 ${(it.quantity || 0) - bal} 张`,
            showCancel: true,
            cancelText: '换其他方式',
            confirmText: '去购买',
            success: (res) => {
              if (res.confirm) wx.navigateTo({ url: '/pages/ticket/index' })
            }
          })
          return
        }
      }
    }

    this.setData({ submitting: true })
    try {
      const orderItems = items.map(it => ({
        productId: it.productId,
        quantity: it.quantity || 1
      }))

      const orderRes = await createOrder({
        items: orderItems,
        addressId: this.data.addressId,
        specialNote: this.data.specialNote,
        source: this.data.source,
        paymentMethod: this.data.selectedMethod,
        stationId: this.data.stationId
      })

      const orderId = orderRes.data || null

      if (orderId) {
        const ticketProductId = (this.data.selectedMethod === 3 && items.length === 1) ? items[0].productId : null
        const ticketQty = (this.data.selectedMethod === 3 && items.length === 1) ? items[0].quantity : null

        try {
          await createPayment({
            orderId,
            customerId: wx.getStorageSync('customerId'),
            amount: this.data.totalAmount,
            waterAmount: this.data.waterCost,
            barrelDeposit: this.data.barrelDeposit,
            extraDepositBuckets: this.data.extraDepositBuckets,
            extraDepositAmount: this.data.extraDepositAmount,
            paymentMethod: this.data.selectedMethod,
            ticketProductId,
            ticketQty
          })
        } catch (paymentError) {
          console.error('[Payment] 支付创建失败:', paymentError)
          // #40: 支付失败时提示用户，不跳转成功页
          wx.showToast({ title: '订单已创建但支付失败: ' + (paymentError.message || ''), icon: 'none', duration: 3000 })
          this.setData({ submitting: false })
          return
        }
      }

      wx.setStorageSync('lastOrderId', orderId)
      wx.redirectTo({ url: `/pages/order/success?id=${orderId || 'mock'}` })
    } catch (error) {
      console.error('[Payment] 下单失败:', error)
      wx.showToast({ title: '下单失败: ' + (error.message || ''), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
