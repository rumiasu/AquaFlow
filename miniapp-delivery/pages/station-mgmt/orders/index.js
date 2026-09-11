// 站长订单管理
const { getOrders } = require('../../../api/station-mgmt')

const STATUS_MAP = {
  1: { text: '待配送', cls: 'pending' },
  2: { text: '配送中', cls: 'delivering' },
  3: { text: '已送达', cls: 'delivered' },
  4: { text: '已完成', cls: 'completed' },
  5: { text: '已取消', cls: 'cancelled' }
}

// 由 STATUS_MAP 派生状态→样式类映射（避免重复手写另一份枚举）
const STATUS_CLASS_MAP = {}
Object.keys(STATUS_MAP).forEach(k => { STATUS_CLASS_MAP[k] = STATUS_MAP[k].cls })

Page({
  data: {
    loading: true,
    list: [],
    tabs: [
      { id: 0, name: '全部' },
      { id: 1, name: '待配送' },
      { id: 2, name: '配送中' }
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
    // tab → 订单状态码：0=全部(null) 1=待配送 2=配送中。
    // 旧实现把 tab=2(配送中) 错映射成 status=3(已送达)，导致「配送中」标签筛出的是已送达订单。
    const statusMap = { 0: null, 1: 1, 2: 2 }
    const status = statusMap[this.data.currentTab]

    this.setData({ loading: true })
    try {
      const res = await getOrders({ stationId, status })
      const list = (res.data || []).map(o => ({
        ...o,
        statusText: o.statusText || '未知',
        statusCls: STATUS_CLASS_MAP[o.status] || 'default'
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