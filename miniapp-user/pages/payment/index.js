const { createOrder } = require('../../api/order')
const { getTicketAccounts } = require('../../api/ticket')
const { createPayment } = require('../../api/payment')

Page({
  data: {
    loading: false,
    submitting: false,
    // 订单信息
    waterTypeId: null,
    waterTypeName: '',
    waterTypeSpec: '',
    waterTypePrice: 0,
    quantity: 1,
    addressId: null,
    addressDetail: '',
    specialNote: '',
    source: 3,
    // 桶
    heldBarrels: 0,
    excessBarrels: 0,
    barrelDepositPerUnit: 30,
    // 支付
    selectedMethod: 2,
    ticketBalance: 0,
    ticketNeedQty: 0,
    ticketEnough: true,
    waterCost: 0,
    barrelDeposit: 0,
    totalAmount: 0,
    methods: [
      { id: 2, name: '货到付款', desc: '配送员送达后现金/扫码支付', icon: '💰' },
      { id: 3, name: '水票支付', desc: '使用账户水票抵扣', icon: '🎫' },
      { id: 1, name: '微信支付', desc: '在线支付', icon: '💳' }
    ]
  },

  onLoad(options) {
    const { waterTypeId, waterTypeName, waterTypeSpec, waterTypePrice, quantity, addressId, addressDetail, specialNote, source, heldBarrels } = options
    const price = parseFloat(waterTypePrice) || 0
    const qty = parseInt(quantity) || 1
    const held = parseInt(heldBarrels) || 0
    const excess = Math.max(0, qty - held)
    const waterCost = price * qty
    const barrelDeposit = excess * 30
    this.setData({
      waterTypeId: parseInt(waterTypeId),
      waterTypeName,
      waterTypeSpec,
      waterTypePrice: price,
      quantity: qty,
      addressId: parseInt(addressId),
      addressDetail: decodeURIComponent(addressDetail || ''),
      specialNote: decodeURIComponent(specialNote || ''),
      source: parseInt(source) || 3,
      heldBarrels: held,
      excessBarrels: excess,
      waterCost,
      barrelDeposit,
      totalAmount: waterCost + barrelDeposit,
      ticketNeedQty: qty
    })
    this.loadTicketBalance()
  },

  async loadTicketBalance() {
    try {
      const res = await getTicketAccounts()
      if (res.data) {
        const account = res.data.find(a => a.waterTypeId === this.data.waterTypeId)
        const balance = account ? account.remainQuantity : 0
        this.setData({
          ticketBalance: balance,
          ticketEnough: balance >= this.data.quantity
        })
      }
    } catch (e) {
      console.warn('[Payment] loadTicketBalance error:', e.message)
    }
  },

  onSelectMethod(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ selectedMethod: id })
  },

  async onSubmit() {
    const { selectedMethod, ticketEnough, ticketBalance, quantity } = this.data

    if (selectedMethod === 3 && !ticketEnough) {
      wx.showModal({
        title: '水票不足',
        content: `当前水票余额 ${ticketBalance} 张，还差 ${quantity - ticketBalance} 张`,
        showCancel: true,
        cancelText: '换其他方式',
        confirmText: '去购买',
        success: (res) => {
          if (res.confirm) {
            wx.navigateTo({ url: '/pages/ticket/index' })
          }
        }
      })
      return
    }

    this.setData({ submitting: true })
    try {
      // 1. 创建订单
      const orderRes = await createOrder({
        waterTypeId: this.data.waterTypeId,
        quantity: this.data.quantity,
        addressId: this.data.addressId,
        specialNote: this.data.specialNote,
        source: this.data.source,
        paymentMethod: this.data.selectedMethod
      })

      const orderId = orderRes.data || null

      // 2. 创建支付记录
      if (orderId) {
        await createPayment({
          orderId,
          customerId: wx.getStorageSync('customerId'),
          amount: this.data.totalAmount,
          waterAmount: this.data.waterCost,
          barrelDeposit: this.data.barrelDeposit,
          excessBarrels: this.data.excessBarrels,
          paymentMethod: this.data.selectedMethod,
          ticketWaterTypeId: this.data.selectedMethod === 3 ? this.data.waterTypeId : null,
          ticketQty: this.data.selectedMethod === 3 ? this.data.quantity : null
        })
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
