const { getOrders } = require('../../api/order')

Page({
  data: {
    loading: true,
    orders: [],
    currentTab: 0,
    tabs: [
      { id: 0, name: '全部' },
      { id: 1, name: '待配送' },
      { id: 2, name: '配送中' },
      { id: 3, name: '已完成' }
    ]
  },

  onLoad() {
    this.loadOrders()
  },

  onShow() {
    this.loadOrders()
  },

  onPullDownRefresh() {
    this.loadOrders().then(() => {
      wx.stopPullDownRefresh()
    })
  },

  async loadOrders() {
    this.setData({ loading: true })
    try {
      const params = {}
      if (this.data.currentTab > 0) {
        params.status = this.data.currentTab
      }
      const res = await getOrders(params)
      if (res.data) {
        this.setData({ orders: res.data })
      }
    } catch (error) {
      console.error('Load orders error:', error)
      this.setData({
        orders: [
          { id: 1001, waterTypeName: '农夫山泉', waterTypeSpec: '18.9L', quantity: 2, addressDetail: '济南市历城区XX小区', status: 1, createTime: '2026-07-18 10:00' },
          { id: 1002, waterTypeName: '娃哈哈', waterTypeSpec: '18.9L', quantity: 1, addressDetail: '济南市历城区YY大厦', status: 2, createTime: '2026-07-17 14:30' },
          { id: 1003, waterTypeName: '怡宝', waterTypeSpec: '11.3L', quantity: 3, addressDetail: '济南市历城区ZZ路', status: 3, createTime: '2026-07-16 09:15' }
        ]
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
    this.loadOrders()
  },

  onOrderTap(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  onReorder(e) {
    const { order } = e.detail
    wx.navigateTo({ url: `/pages/order/create?reorderId=${order.id}` })
  }
})
