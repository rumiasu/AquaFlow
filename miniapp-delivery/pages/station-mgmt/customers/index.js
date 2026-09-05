// 站长客户查询
const { getCustomers } = require('../../../api/station-mgmt')

Page({
  data: {
    loading: true,
    list: [],
    keyword: ''
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

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.loadData()
  },

  async loadData() {
    const app = getApp()
    const stationId = app.globalData.userInfo?.stationId

    this.setData({ loading: true })
    try {
      const res = await getCustomers(stationId)
      const keyword = this.data.keyword.trim()
      let list = res.data || []
      if (keyword) {
        list = list.filter(c =>
          (c.name || '').includes(keyword) || (c.phone || '').includes(keyword)
        )
      }
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  }
})