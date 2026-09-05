// 站长订单管理
const { getOrders } = require('../../../api/station-mgmt')

const STATUS_MAP = {
  1: { text: '待接单', cls: 'pending' },
  2: { text: '配送中', cls: 'delivering' },
  3: { text: '已完成', cls: 'completed' },
  5: { text: '已取消', cls: 'cancelled' },
  6: { text: '待收款', cls: 'warning' },
  7: { text: '已拒单', cls: 'cancelled' }
}

Page({
  data: {
    loading: true,
    list: [],
    tabs: [
      { id: 0, name: '全部' },
      { id: 1, name: '待配送' },
      { id: 2, name: '已完成' }
    ],
    currentTab: 0
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  onTabChange(e) {
    this.setData({ currentTab: e.currentTarget.dataset.id })
    this.loadData()
  },

  async loadData() {
    const app = getApp()
    const stationId = app.globalData.userInfo?.stationId
    const statusMap = { 0: null, 1: 1, 2: 3 }
    const status = statusMap[this.data.currentTab]

    this.setData({ loading: true })
    try {
      const res = await getOrders({ stationId, status })
      const list = (res.data || []).map(o => ({
        ...o,
        statusText: (STATUS_MAP[o.status] || {}).text || '未知',
        statusCls: (STATUS_MAP[o.status] || {}).cls || 'default'
      }))
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onOrderTap(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})