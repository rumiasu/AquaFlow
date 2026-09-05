// 站长库存查看
const { getInventory } = require('../../../api/station-mgmt')

const STATUS_MAP = { 0: '下架', 1: '上架', 2: '停售' }

Page({
  data: {
    loading: true,
    list: []
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

  async loadData() {
    const app = getApp()
    const stationId = app.globalData.userInfo?.stationId

    this.setData({ loading: true })
    try {
      const res = await getInventory(stationId)
      const list = (res.data || []).map(i => {
        const displayName = i.productName || i.waterTypeName
          || ('商品 #' + (i.productId || i.waterTypeId || i.id || ''))
        return {
          ...i,
          displayName,
          statusText: STATUS_MAP[i.status] || '在售',
          lowStock: (i.quantity || 0) < 20
        }
      })
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})