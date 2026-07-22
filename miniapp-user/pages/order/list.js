const { getOrders } = require('../../api/order')
const app = getApp()

Page({
  data: {
    currentTab: 0,
    tabs: [
      { name: '全部', status: 0 },
      { name: '待配送', status: 1 },
      { name: '配送中', status: 2 },
      { name: '已完成', status: 3 }
    ],
    orders: []
  },

  onLoad() { this.loadOrders() },
  onShow() { this.loadOrders() },

  onPullDownRefresh() {
    this.loadOrders().then(() => wx.stopPullDownRefresh())
  },

  loadOrders() {
    const { currentTab, tabs } = this.data
    const params = {}
    if (currentTab > 0) params.status = tabs[currentTab].status
    return getOrders(params).then(orders => {
      this.setData({ orders })
    }).catch(err => {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    })
  },

  onTabChange(e) {
    const idx = e.currentTarget.dataset.index
    this.setData({ currentTab: idx })
    this.loadOrders()
  },

  onReorder(e) {
    const { order } = e.detail
    wx.navigateTo({ url: `/pages/order/create?reorderId=${order.id}` })
  },

  onCancel(e) {
    // OrderCard 已处理取消逻辑，这里刷新列表
    this.loadOrders()
  }
})
