Component({
  properties: {
    order: {
      type: Object,
      value: {}
    }
  },

  data: {
    statusText: '',
    statusClass: ''
  },

  observers: {
    'order.status': function(status) {
      const statusMap = {
        1: { text: '待配送', class: 'warning' },
        2: { text: '配送中', class: 'primary' },
        3: { text: '已完成', class: 'success' },
        4: { text: '待配送', class: 'warning' }
      }
      const statusInfo = statusMap[status] || { text: '未知', class: 'default' }
      this.setData({
        statusText: statusInfo.text,
        statusClass: statusInfo.class
      })
    }
  },

  methods: {
    onOrderTap() {
      const { order } = this.data
      wx.navigateTo({
        url: `/pages/order/detail?id=${order.id}`
      })
    },

    onReorder() {
      const { order } = this.data
      this.triggerEvent('reorder', { order })
    }
  }
})
