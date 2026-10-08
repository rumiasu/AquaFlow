const { withItemUnits } = require('../../utils/order-item-view')
const { cancelView, cancelResultText } = require('../../utils/customer-cancel-view')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { cancelOrder, getOrderDetail } = require('../../api/order')

Component({
  properties: {
    order: { type: Object, value: {} }
  },

  data: {
    displayItems: [],
    statusText: '',
    statusClass: '',
    payStatusText: '',
    payStatusClass: '',
    canRepay: false,
    canCancel: false,
    cancelLabel: '',
    repayLabel: '去支付'
  },

  lifetimes: {
    detached() { this._detached = true; this._cancelFlight = null }
  },

  observers: {
    'order': function (order) {
      if (!order) {
        this.setData({ canCancel: false, cancelLabel: '', canRepay: false, displayItems: [] })
        return
      }
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
      const cancel = cancelView(order)
      this.setData({
        displayItems: withItemUnits(order.items),
        statusText: order.statusText || '',
        statusClass,
        payStatusText: order.payStateText || '',
        payStatusClass: payClassMap[order.payState] || 'other',
        canRepay: !!order.canRepay,
        canCancel: cancel.canCancel,
        cancelLabel: cancel.label,
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
      const order = this.data.order && { ...this.data.order }, view = cancelView(order)
      if (!order || !order.id || !view.canCancel || this._cancelFlight || this._detached) return
      const session = captureSession(), flight = {}
      if (!session.loggedIn || !session.customerId) return
      this._cancelFlight = flight
      const current = () => this._cancelFlight === flight && !this._detached && isCurrentSession(session)
        && this.data.order && String(this.data.order.id) === String(order.id)
        && this.data.order.status === order.status && this.data.order.canCancel === true
      const release = () => { if (this._cancelFlight === flight) this._cancelFlight = null }
      wx.showModal({
        title: view.title,
        content: view.content,
        confirmText: view.confirmText,
        fail: release,
        success: async (res) => {
          if (!res.confirm || !current()) { release(); return }
          try {
            await cancelOrder(order.id)
            if (!current()) return
            let fresh = null
            try {
              const result = await getOrderDetail(order.id)
              const row = result && result.data
              if (row && String(row.id) === String(order.id)) fresh = row
            } catch (_) { /* Mutation succeeded; its outcome still needs confirmation. */ }
            if (!current()) return
            wx.showToast({ title: cancelResultText(fresh), icon: 'none' })
            this.triggerEvent('cancel', { order })
          } catch (err) {
            if (current()) wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          } finally {
            release()
          }
        }
      })
    }
  }
})
