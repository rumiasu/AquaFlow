// 站长数据看板
const { getDashboardToday, getDashboardOverview } = require('../../../api/station-mgmt')

Page({
  data: {
    loading: true,
    stats: {}
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
      const [todayRes, overviewRes] = await Promise.all([
        getDashboardToday(stationId).catch(() => null),
        getDashboardOverview(stationId).catch(() => null)
      ])
      this.setData({
        stats: {
          ...(todayRes ? todayRes.data : {}),
          ...(overviewRes ? overviewRes.data : {})
        }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})