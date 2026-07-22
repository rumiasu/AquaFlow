const { getOrderDetail, cancelOrder } = require('../../api/order')
const { formatOrderStatus, formatPaymentStatus } = require('../../utils/format')

Page({
  data: {
    order: null,
    statusText: '',
    payStatusText: '',
    canCancel: false
  },

  onLoad(options) {
    if (options.id) {
      this.loadOrder(options.id)
    }
  },

  onPullDownRefresh() {
    if (this.data.order) {
      this.loadOrder(this.data.order.id).then(() => wx.stopPullDownRefresh())
    } else {
      wx.stopPullDownRefresh()
    }
  },

  loadOrder(id) {
    return getOrderDetail(id).then(order => {
      const statusText = formatOrderStatus(order.status)
      const payStatusText = formatPaymentStatus(order.paymentStatus)
      const canCancel = order.status === 1 || order.status === 4
      this.setData({ order, statusText, payStatusText, canCancel })
    }).catch(err => {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    })
  },

  onCallPhone() {
    if (this.data.order && this.data.order.customerPhone) {
      wx.makePhoneCall({ phoneNumber: this.data.order.customerPhone })
    }
  },

  onReorder() {
    wx.navigateTo({ url: `/pages/order/create?reorderId=${this.data.order.id}` })
  },

  onCancel() {
    wx.showModal({
      title: '取消订单',
      content: '确定取消此订单吗？取消后将自动释放库存、退水票、退款。',
      confirmColor: '#f5222d',
      success: (res) => {
        if (res.confirm) {
          cancelOrder(this.data.order.id).then(() => {
            wx.showToast({ title: '订单已取消', icon: 'success' })
            this.loadOrder(this.data.order.id)
          }).catch(err => {
            wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          })
        }
      }
    })
  }
})
