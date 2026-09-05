const { getOrders } = require('../../api/order')
const app = getApp()

Page({
  data: {
    currentTab: 0,
    tabs: [
      { name: '全部', status: 0 },
      { name: '待配送', status: 1 },
      { name: '配送中', status: 3 },
      { name: '已送达', status: 4 },
      { name: '已完成', status: 5 },
      // P6: 抢单池tab已冻结 — 客户选站后不再有"待认领"状态
      // { name: '待认领', status: 7 },
      { name: '已取消', status: 6 }
    ],
    orders: []
  },

  onLoad() {
    if (!app.globalData.isLogin) {
      // #41: tabBar页面用reLaunch而非redirectTo
      wx.reLaunch({ url: '/pages/login/index' })
      return
    }
    this.loadOrders()
  },
  onShow() { this.loadOrders() },

  onPullDownRefresh() {
    this.loadOrders().then(() => wx.stopPullDownRefresh())
  },

  loadOrders() {
    const { currentTab, tabs } = this.data
    const params = {}
    if (currentTab > 0) params.status = tabs[currentTab].status
    return getOrders(params).then(res => {
      const orders = Array.isArray(res) ? res : (res.data || [])
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
