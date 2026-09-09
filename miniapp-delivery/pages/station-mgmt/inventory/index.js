// 站长库存查看
const { getInventory } = require('../../../api/station-mgmt')

// 商品状态文案与低库存判定均由后端下发（Inventory.statusText / lowStock），
// 前端不再本地映射状态、也不再硬编码低库存阈值。

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
          statusText: i.statusText || '在售',
          lowStock: !!i.lowStock
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