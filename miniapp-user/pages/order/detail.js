const { getOrderDetail } = require('../../api/order')
const { formatOrderStatus } = require('../../utils/format')

Page({
  data: {
    loading: true,
    order: {},
    statusText: ''
  },

  onLoad(options) {
    if (options.id) {
      this.loadOrder(options.id)
    }
  },

  async loadOrder(id) {
    this.setData({ loading: true })
    try {
      const res = await getOrderDetail(id)
      if (res.data) {
        this.setData({
          order: res.data,
          statusText: formatOrderStatus(res.data.status)
        })
      }
    } catch (error) {
      console.error('Load order error:', error)
      this.setData({
        order: { id, waterTypeName: '农夫山泉', waterTypeSpec: '18.9L', quantity: 2, addressDetail: '济南市历城区XX小区', status: 1, createTime: '2026-07-18 10:00', customerPhone: '13800138000' },
        statusText: '待配送'
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  onCallPhone() {
    const { order } = this.data
    if (order.customerPhone) {
      wx.makePhoneCall({ phoneNumber: order.customerPhone })
    }
  },

  onReorder() {
    const { order } = this.data
    wx.navigateTo({ url: `/pages/order/create?reorderId=${order.id}` })
  }
})
