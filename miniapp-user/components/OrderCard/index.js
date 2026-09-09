Component({
  properties: {
    order: { type: Object, value: {} }
  },

  data: {
    statusText: '',
    statusClass: '',
    payStatusText: '',
    payStatusClass: '',
    canRepay: false,
    repayLabel: '去支付'
  },

  observers: {
    'order': function (order) {
      if (!order) return
      // 订单状态与支付态文案、支付入口均由后端计算下发（Orders 派生字段 statusText /
      // payStateText / canRepay / repayLabel）。前端只按状态编码选配色，
      // 不再维护 status -> 文案映射，避免两端各写一套导致漂移。
      const statusClassMap = {
        1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'cancelled'
      }
      const statusClass = statusClassMap[order.status] || 'default'
      const payClassMap = {
        UNPAID: 'other',
        PENDING: 'warning',
        PAID: 'paid',
        REFUNDED: 'other',
        CANCELLED: 'other'
      }
      this.setData({
        statusText: order.statusText || '',
        statusClass,
        payStatusText: order.payStateText || '',
        payStatusClass: payClassMap[order.payState] || 'other',
        canRepay: !!order.canRepay,
        repayLabel: order.repayLabel || '去支付'
      })
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
