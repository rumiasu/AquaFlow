const { getWaterTypeDetail } = require('../../api/product')
const { getAddresses } = require('../../api/address')
const { createOrder } = require('../../api/order')
const { getBarrelSummary } = require('../../api/barrel')
const { storage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    submitting: false,
    product: {},
    quantity: 1,
    address: null,
    note: '',
    barrelSummary: null,
    showExceedModal: false,
    exceedInfo: {
      exceedQty: 0,
      extraWaterCost: 0,
      extraDepositCost: 0,
      extraTotal: 0
    }
  },

  onLoad(options) {
    if (options.productId) {
      this.loadProduct(options.productId)
    }
    if (options.quantity) {
      this.setData({ quantity: parseInt(options.quantity) })
    }
    this.loadAddress()
    this.loadBarrelSummary()
  },

  onShow() {
    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({ address: selectedAddress })
      storage.remove('selectedAddress')
    }
  },

  async loadProduct(id) {
    try {
      const res = await getWaterTypeDetail(id)
      if (res.data) {
        this.setData({ product: res.data })
      }
    } catch (error) {
      console.error('Load product error:', error)
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadAddress() {
    try {
      const res = await getAddresses()
      if (res.data && res.data.length > 0) {
        const defaultAddr = res.data.find(a => a.isDefault) || res.data[0]
        this.setData({ address: defaultAddr })
      }
    } catch (error) {
      console.error('Load address error:', error)
    }
  },

  async loadBarrelSummary() {
    try {
      const res = await getBarrelSummary()
      if (res.data) {
        this.setData({ barrelSummary: res.data })
      }
    } catch (error) {
      console.error('Load barrel summary error:', error)
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

  onNoteInput(e) {
    this.setData({ note: e.detail.value })
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=order' })
  },

  onSubmit() {
    const { product, quantity, address, note, barrelSummary } = this.data

    if (!address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }

    const actualBuckets = barrelSummary ? barrelSummary.actualBuckets : 0
    const exceedQty = quantity - actualBuckets

    if (exceedQty > 0) {
      const waterPrice = product.price || 0
      const depositPerBucket = barrelSummary ? barrelSummary.depositPerBucket : 30
      const extraWaterCost = exceedQty * waterPrice
      const extraDepositCost = exceedQty * depositPerBucket

      this.setData({
        showExceedModal: true,
        exceedInfo: {
          exceedQty,
          extraWaterCost,
          extraDepositCost,
          extraTotal: extraWaterCost + extraDepositCost
        }
      })
      return
    }

    this.doSubmit()
  },

  onConfirmExceed() {
    this.setData({ showExceedModal: false })
    this.doSubmit()
  },

  onCloseExceedModal() {
    this.setData({ showExceedModal: false })
  },

  async doSubmit() {
    const { product, quantity, address, note } = this.data
    this.setData({ submitting: true })
    try {
      const orderData = {
        waterTypeId: product.id,
        quantity,
        addressId: address.id,
        specialNote: note,
        source: 3
      }

      const res = await createOrder(orderData)
      if (res.data) {
        wx.redirectTo({ url: `/pages/order/success?id=${res.data.id}` })
      } else {
        wx.redirectTo({ url: '/pages/order/success?id=mock' })
      }
    } catch (error) {
      console.error('Create order error:', error)
      wx.redirectTo({ url: '/pages/order/success?id=mock' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
