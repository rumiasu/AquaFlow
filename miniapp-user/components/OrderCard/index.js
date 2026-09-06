Component({
  properties: {
    order: { type: Object, value: {} }
  },

  data: {
    statusText: '',
    statusClass: '',
    payStatusText: '',
    payStatusClass: ''
  },

  observers: {
    'order.status': function (status) {
      const map = {
        1: { text: '待配送', class: 'warning' },
        2: { text: '配送中', class: 'primary' },
        3: { text: '已送达', class: 'success' },
        4: { text: '已完成', class: 'success' },
        5: { text: '已取消', class: 'cancelled' }
      }
      const info = map[status] || { text: '未知', class: 'default' }
      this.setData({ statusText: info.text, statusClass: info.class })
    },
    'order.paymentStatus': function (ps) {
      const map = {
        0: { text: '未付款', class: 'other' },
        1: { text: '待收款', class: 'warning' },
        2: { text: '已付款', class: 'paid' },
        3: { text: '已退款', class: 'other' },
        4: { text: '已取消', class: 'other' }
      }
      const info = map[ps] || { text: '未知', class: 'other' }
      this.setData({ payStatusText: info.text, payStatusClass: info.class })
    }
  },

  methods: {
    onOrderTap() {
      wx.navigateTo({ url: `/pages/order/detail?id=${this.data.order.id}` })
    },
    onReorder() {
      this.triggerEvent('reorder', { order: this.data.order })
    },
    onPayNow() {
      this.triggerEvent('pay', { order: this.data.order })
    },
    onCancel() {
      wx.showModal({
        title: '取消订单',
        content: '确定取消此订单吗？取消后将自动退款。',
        success: (res) => {
          if (res.confirm) {
            const { cancelOrder } = require('../../api/order')
            cancelOrder(this.data.order.id).then(() => {
              wx.showToast({ title: '已取消', icon: 'success' })
              this.triggerEvent('cancel', { order: this.data.order })
            }).catch(err => {
              wx.showToast({ title: err.message || '取消失败', icon: 'none' })
            })
          }
        }
      })
    }
  }
})
